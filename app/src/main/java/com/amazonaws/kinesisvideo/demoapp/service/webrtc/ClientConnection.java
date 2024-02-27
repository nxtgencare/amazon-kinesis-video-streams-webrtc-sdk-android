package com.amazonaws.kinesisvideo.demoapp.service.webrtc;

import android.util.Base64;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;
import com.google.gson.Gson;

import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.websocket.MessageHandler;

public abstract class ClientConnection implements MessageHandler.Whole<String> {

    private final Gson gson = new Gson();

    protected PeerConnectionFactory peerConnectionFactory;
    protected static volatile SignalingServiceWebSocketClient client;
    protected final ChannelDetails channelDetails;
    protected final Consumer<ServiceStateChange> stateChangeCallback;

    /**
     * Mapping of established peer connections to the peer's sender id. In other words, if an SDP
     * offer/answer for a peer connection has been received and sent, the PeerConnection is added
     * to this map.
     */
    protected final Map<String, PeerConnection> peerConnectionFoundMap = new ConcurrentHashMap<>();

    /**
     * Only used when we are master. Mapping of the peer's sender id to its received ICE candidates.
     * Since we can receive ICE Candidates before we have sent the answer, we hold ICE candidates in
     * this queue until after we send the answer and the peer connection is established.
     */
    private final Map<String, Queue<IceCandidate>> pendingIceCandidatesMap = new ConcurrentHashMap<>();

    public ClientConnection(PeerConnectionFactory peerConnectionFactory, ChannelDetails channelDetails, Consumer<ServiceStateChange> stateChangeCallback) {
        this.peerConnectionFactory = peerConnectionFactory;
        this.channelDetails = channelDetails;
        this.stateChangeCallback = stateChangeCallback;
    }

    public boolean isValidClient() {
        return client != null && client.isOpen();
    }

    public void initWsConnection(AWSCredentials creds) throws Exception {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
        final String endpoint = buildEndPointUri();
        final URI signedUri = WebRtcService.getSignedUri(channelDetails, creds, endpoint);
        final String wsHost = signedUri.toString();

        // Step 11. Create SignalingServiceWebSocketClient.
        //          This is the actual client that is used to send messages over the signaling channel.
        //          SignalingServiceWebSocketClient will attempt to open the connection in its constructor.
        try {
            client = new SignalingServiceWebSocketClient(getTag(), wsHost, this, Executors.newFixedThreadPool(10));
            Log.d(getTag(), "Client connection " + (client.isOpen() ? "Successful" : "Failed"));
        } catch (final Exception e) {
            Log.e(getTag(), "Exception with websocket client: " + e);
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }

        if (isValidClient()) {
            Log.d(getTag(), "Client connected to Signaling service " + client.isOpen());
            onValidClient();
        } else {
            Log.e(getTag(), "Error in connecting to signaling service");
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }
    }

    public abstract void handleSdpOffer(Event offerEvent);

    protected abstract void onValidClient();

    protected abstract String buildEndPointUri();

    /**
     * Called once the peer connection is established. Checks the pending ICE candidate queue to see
     * if we have received any before we finished sending the SDP answer. If so, add those ICE
     * candidates to the peer connection belonging to this clientId.
     *
     * @param clientId The sender client id of the peer whose peer connection was just established.
     * @see #pendingIceCandidatesMap
     */
    public void handlePendingIceCandidates(final String clientId, final PeerConnection peerConnection) {
        // Add any pending ICE candidates from the queue for the client ID
        Log.d(getTag(), "Pending ice candidates found? " + pendingIceCandidatesMap.get(clientId));
        final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(clientId);
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            final IceCandidate iceCandidate = pendingIceCandidatesQueueByClientId.peek();
            final boolean addIce = peerConnection.addIceCandidate(iceCandidate);
            Log.d(getTag(), "Added ice candidate after SDP exchange " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
            pendingIceCandidatesQueueByClientId.remove();
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId);
    }

    public void checkAndAddIceCandidate(final Event message, final IceCandidate iceCandidate) {
        String peerConnectionKey = getPeerConnectionKey(message);
        Optional<PeerConnection> maybePeerConnection = Optional.ofNullable(peerConnectionFoundMap.get(peerConnectionKey));
        maybePeerConnection.ifPresent(
            peerConnection -> {
                // This is the case where peer connection is established and ICE candidates are received for the established
                // connection
                Log.d(getTag(), "Peer connection found already");
                // Remote sent us ICE candidates, add to local peer connection
                final boolean addIce = peerConnection.addIceCandidate(iceCandidate);
                Log.d(getTag(), "Added ice candidate " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
            }
        );

        if (!maybePeerConnection.isPresent()) {
            // If answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
            // Once the peer connection is found, add them directly instead of adding it to the queue.
            Log.d(getTag(), "SDP exchange is not complete. Ice candidate " + iceCandidate + " + added to pending queue");

            // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
            if (pendingIceCandidatesMap.containsKey(peerConnectionKey)) {
                final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(peerConnectionKey);
                pendingIceCandidatesQueueByClientId.add(iceCandidate);
                pendingIceCandidatesMap.put(peerConnectionKey, pendingIceCandidatesQueueByClientId);
            } else {
                // If the first ICE candidate before peer connection is received, add entry to map and ICE candidate to a queue
                final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = new LinkedList<>();
                pendingIceCandidatesQueueByClientId.add(iceCandidate);
                pendingIceCandidatesMap.put(peerConnectionKey, pendingIceCandidatesQueueByClientId);
            }
        }
    }

    @Override
    public void onMessage(String message) {
        if (message.isEmpty()) {
            return;
        }

        Log.d(getTag(), "Received objMessage: " + message);

        if (!message.contains("messagePayload")) {
            return;
        }

        final Event evt = gson.fromJson(message, Event.class);

        if (evt == null || evt.getMessageType() == null || evt.getMessagePayload().isEmpty()) {
            return;
        }

        String peerConnectionKey = getPeerConnectionKey(evt);

        switch (evt.getMessageType().toUpperCase()) {
            case "SDP_OFFER":
                Log.d(getTag(), "Offer received: SenderClientId=" + peerConnectionKey);
                Log.d(getTag(), new String(Base64.decode(evt.getMessagePayload(), 0)));
                handleSdpOffer(evt);
                break;
            case "SDP_ANSWER":
                Log.d(getTag(), "Answer received: SenderClientId=" + peerConnectionKey);
                Log.d(getTag(), "SDP answer received from signaling");
                final String sdp = Event.parseSdpEvent(evt);
                final SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);

                Optional<PeerConnection> maybePeerConnection = Optional.ofNullable(
                    peerConnectionFoundMap.get(peerConnectionKey)
                );

                maybePeerConnection.ifPresent(peerConnection -> {
                    peerConnection.setRemoteDescription(new KinesisVideoSdpObserver() {
                        @Override
                        public void onCreateFailure(final String error) {
                            super.onCreateFailure(error);
                        }
                    }, sdpAnswer);

                    Log.d(getTag(), "Answer Client ID: " + peerConnectionKey);
                    // Check if ICE candidates are available in the queue and add the candidate
                    handlePendingIceCandidates(peerConnectionKey, peerConnection);
                });
                break;
            case "ICE_CANDIDATE":
                Log.d(getTag(), "Ice Candidate received: SenderClientId=" + peerConnectionKey);
                Log.d(getTag(), new String(Base64.decode(evt.getMessagePayload(), 0)));
                Log.d(getTag(), "Received ICE candidate from remote");
                final IceCandidate iceCandidate = Event.parseIceCandidate(evt);
                if (iceCandidate != null) {
                    checkAndAddIceCandidate(evt, iceCandidate);
                } else {
                    Log.e(getTag(), "Invalid ICE candidate: " + evt);
                }
                break;
            default:
                break;
        }
    }

    private String getPeerConnectionKey(Event message) {
        // The only peer we care about in viewer is ourselves so it's okay to us our own client ID as our peer connection key.
        return channelDetails.getRole() == ChannelRole.MASTER ? message.getSenderClientId() : getClientId();
    }

    public PeerConnection createLocalPeerConnection(String recipientClientId) {
        final PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(channelDetails.getPeerIceServers());

        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;

        // TODO: Decide if we want to keep these after testing. -- GATHER_CONTINUALLY overrides default.
        // rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.disableIPv6OnWifi = true;

        // Step 8. Create RTCPeerConnection.
        //  The RTCPeerConnection is the primary interface for WebRTC communications in the Web.
        //  We also configure the Add Peer Connection Event Listeners here.
        return peerConnectionFactory.createPeerConnection(rtcConfig, new KinesisVideoPeerConnection(getTag()) {
            @Override
            public void onIceCandidate(final IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                final Message message = createIceCandidateMessage(recipientClientId, iceCandidate);
                Log.d(getTag(), "Sending IceCandidate to remote peer " + iceCandidate);
                client.sendIceCandidate(message);  /* Send to Peer */
            }

            @Override
            public void onAddStream(final MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                Log.d(getTag(), "Adding remote audio stream to the view");
                addRemoteStreamToView(mediaStream);
            }

            @Override
            public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
                super.onIceConnectionChange(iceConnectionState);
                stateChangeCallback.accept(ServiceStateChange.iceConnectionStateChange(channelDetails, iceConnectionState));
            }
        });
    }

    private Message createIceCandidateMessage(final String recipientClientId, final IceCandidate iceCandidate) {
        final String sdpMid = iceCandidate.sdpMid;
        final int sdpMLineIndex = iceCandidate.sdpMLineIndex;
        final String sdp = iceCandidate.sdp;

        final String messagePayload =
            "{\"candidate\":\""
                + sdp
                + "\",\"sdpMid\":\""
                + sdpMid
                + "\",\"sdpMLineIndex\":"
                + sdpMLineIndex
                + "}";

        return new Message("ICE_CANDIDATE", recipientClientId, getClientId(),
            new String(Base64.encode(messagePayload.getBytes(),
                    Base64.URL_SAFE | Base64.NO_WRAP)));
    }

    public abstract String getClientId();

    private void addRemoteStreamToView(MediaStream stream) {
        AudioTrack remoteAudioTrack = stream.audioTracks != null && stream.audioTracks.size() > 0 ? stream.audioTracks.get(0) : null;

        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(true);
            Log.d(getTag(), "remoteAudioTrack received: State=" + remoteAudioTrack.state().name());
        }
    }

    public void onDestroy() {
        peerConnectionFoundMap.values().forEach(PeerConnection::close);

        if (client != null) {
            client.disconnect();
            client = null;
        }
        peerConnectionFoundMap.clear();
        pendingIceCandidatesMap.clear();
    }

    public void onException(Exception e) {
        stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, e));
    }

    public abstract String getTag();

    public abstract List<PeerManager> getPeerStatus();

    protected void removePeer(String peerConnectionKey) {
        peerConnectionFoundMap.remove(peerConnectionKey);
        stateChangeCallback.accept(ServiceStateChange.remove(channelDetails));
    }
}
