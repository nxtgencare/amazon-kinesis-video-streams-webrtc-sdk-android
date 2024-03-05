package com.amazonaws.kinesisvideo.service.webrtc.connection;

import android.util.Base64;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.kinesisvideo.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.service.webrtc.exception.NoAwsCredentialsException;
import com.amazonaws.kinesisvideo.service.webrtc.exception.SignalingServiceWebSocketClientConnectionException;
import com.amazonaws.kinesisvideo.service.webrtc.exception.SignalingServiceWebSocketInvalidClientException;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.service.webrtc.model.ServiceStateChange;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;
import com.google.gson.Gson;

import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.websocket.MessageHandler;

public abstract class AbstractClientConnection implements MessageHandler.Whole<String> {

    private final Gson gson = new Gson();

    protected PeerConnectionFactory peerConnectionFactory;
    protected volatile SignalingServiceWebSocketClient client;
    protected final ChannelDetails channelDetails;
    protected final Consumer<ServiceStateChange> stateChangeCallback;

    public AbstractClientConnection(PeerConnectionFactory peerConnectionFactory, ChannelDetails channelDetails, Consumer<ServiceStateChange> stateChangeCallback) {
        this.peerConnectionFactory = peerConnectionFactory;
        this.channelDetails = channelDetails;
        this.stateChangeCallback = stateChangeCallback;
    }

    public ChannelDetails getChannelDetails() { return channelDetails; }

    public boolean isValidClient() {
        return client != null && client.isOpen();
    }

    public void initWsConnection(AWSCredentials creds) throws
            SignalingServiceWebSocketClientConnectionException,
            SignalingServiceWebSocketInvalidClientException,
            NoAwsCredentialsException {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
        final String endpoint = buildEndPointUri();
        final URI signedUri = getSignedUri(channelDetails, creds, endpoint);
        final String wsHost = signedUri.toString();

        try {
            client = new SignalingServiceWebSocketClient(getTag(), wsHost, this, Executors.newFixedThreadPool(10));
            Log.d(getTag(), "Client connection " + (client.isOpen() ? "Successful" : "Failed"));
        } catch (final Exception e) {
            Log.e(getTag(), "Exception with websocket client: " + e);
            throw new SignalingServiceWebSocketClientConnectionException(e);
        }

        if (isValidClient()) {
            Log.d(getTag(), "Client connected to Signaling service " + client.isOpen());
            onValidClient();
        } else {
            Log.e(getTag(), "Error in connecting to signaling service");
            throw new SignalingServiceWebSocketInvalidClientException();
        }
    }

    public void checkAndAddIceCandidate(final Event message, final IceCandidate iceCandidate) {
        String peerConnectionKey = getPeerConnectionKey(message);
        Optional<PeerConnection> maybePeerConnection = getPeerConnection(peerConnectionKey);
        maybePeerConnection.ifPresent(
            peerConnection -> {
                // This is the case where peer connection is established and ICE candidates are received for the established
                // connection
                Log.d(getTag(), "Peer connection found already");
                // Remote sent us ICE candidates, add to local peer connection
                final boolean addIce = peerConnection.addIceCandidate(iceCandidate);
                Log.d(getTag(), "Added ice candidate " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));

                if (addIce) {
                    Log.d(getTag(), "Answer Client ID: " + peerConnectionKey);
                    // Check if ICE candidates are available in the queue and add the candidate
                    handlePendingIceCandidates(peerConnectionKey, maybePeerConnection.get());
                }
            }
        );

        if (!maybePeerConnection.isPresent()) {
            addCandidateToPending(peerConnectionKey, iceCandidate);
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
                handleSdpAnswer(evt, peerConnectionKey);
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

        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
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
                if (isValidClient()) {
                    client.sendIceCandidate(message);  /* Send to Peer */
                }
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
                stateChangeCallback.accept(ServiceStateChange.iceConnectionStateChange(channelDetails.getChannelDescription(), iceConnectionState));
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
        cleanupPeerConnections();
        cleanupClientConnection();
    }

    public void cleanupClientConnection() {
        if (isValidClient()) {
            client.disconnect();
        }

        client = null;
    }

    public void onException(Exception e) {
        stateChangeCallback.accept(ServiceStateChange.exception(channelDetails.getChannelDescription(), e));
    }

    protected abstract void onValidClient();

    public abstract void handleSdpOffer(Event offerEvent);
    protected abstract void handleSdpAnswer(Event evt, String peerConnectionKey);
    protected abstract void addCandidateToPending(String peerConnectionKey, IceCandidate iceCandidate);
    protected abstract void handlePendingIceCandidates(String peerConnectionKey, PeerConnection peerConnection);

    protected abstract void cleanupPeerConnections();

    public abstract Optional<PeerManager> getPeerManager(String peerConnectionKey);
    public abstract Optional<PeerConnection> getPeerConnection(String peerConnectionKey);

    public abstract List<PeerManager> getPeerManagers();

    protected abstract String buildEndPointUri();
    public abstract String getTag();

    /**
     * Constructs and returns signed URL for the specified endpoint.
     *
     * @param endpoint The websocket endpoint (master or viewer endpoint)
     * @return A signed URL. {@code null} if there was an issue fetching credentials.
     */
    private static URI getSignedUri(ChannelDetails channelDetails, AWSCredentials creds, String endpoint) throws NoAwsCredentialsException {
        final String accessKey = creds.getAWSAccessKeyId();
        final String secretKey = creds.getAWSSecretKey();
        final String sessionToken = Optional.of(creds)
            .filter(c -> c instanceof AWSSessionCredentials)
            .map(awsCredentials -> (AWSSessionCredentials) awsCredentials)
            .map(AWSSessionCredentials::getSessionToken)
            .orElse("");

        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw new NoAwsCredentialsException();
        }

        return AwsV4Signer.sign(
            URI.create(endpoint),
            accessKey,
            secretKey,
            sessionToken,
            URI.create(channelDetails.getWssEndpoint()),
            channelDetails.getRegion(),
            new Date().getTime()
        );
    }

    public abstract void resetPeer(String key);
}
