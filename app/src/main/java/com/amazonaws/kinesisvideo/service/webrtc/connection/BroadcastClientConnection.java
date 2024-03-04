package com.amazonaws.kinesisvideo.service.webrtc.connection;

import android.util.Log;

import com.amazonaws.kinesisvideo.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.service.webrtc.exception.InvalidCodecException;
import com.amazonaws.kinesisvideo.service.webrtc.exception.SdpAnswerCreationException;
import com.amazonaws.kinesisvideo.service.webrtc.model.ServiceStateChange;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BroadcastClientConnection extends AbstractClientConnection {
    private static final String TAG = "BroadcastClientConnection";
    private static final String LOCAL_MEDIA_STREAM_LABEL = "BroadcastClientMediaStream";
    private final AudioTrack localAudioTrack;

    /**
     * Mapping of established peer connections to the peer's sender id. In other words, if an SDP
     * offer/answer for a peer connection has been received and sent, the PeerConnection is added
     * to this map.
     */
    private final Map<String, PeerConnection> peerConnectionFoundMap = new ConcurrentHashMap<>();

    /**
     * Only used when we are master. Mapping of the peer's sender id to its received ICE candidates.
     * Since we can receive ICE Candidates before we have sent the answer, we hold ICE candidates in
     * this queue until after we send the answer and the peer connection is established.
     */
    private final Map<String, Queue<IceCandidate>> pendingIceCandidatesMap = new ConcurrentHashMap<>();

    public BroadcastClientConnection(
        PeerConnectionFactory peerConnectionFactory,
        ChannelDetails channelDetails,
        AudioTrack localAudioTrack,
        Consumer<ServiceStateChange> stateChangeCallback) {
        super(peerConnectionFactory, channelDetails, stateChangeCallback);
        this.localAudioTrack = localAudioTrack;
    }

    @Override
    public void handleSdpOffer(Event offerEvent) {
        Log.d(TAG, "Received SDP Offer: Setting Remote Description ");

        final String sdp = Event.parseOfferEvent(offerEvent);
        String recipientClientId = offerEvent.getSenderClientId();
        PeerConnection peerConnection = createLocalPeerConnection(recipientClientId);
        peerConnection.setRemoteDescription(new KinesisVideoSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdp));
        Log.d(TAG, "Received SDP offer for client ID: " + recipientClientId + ". Creating answer");
        createSdpAnswer(recipientClientId, peerConnection);
    }

    /**
     * Called once the peer connection is established. Checks the pending ICE candidate queue to see
     * if we have received any before we finished sending the SDP answer. If so, add those ICE
     * candidates to the peer connection belonging to this clientId.
     *
     * @param clientId The sender client id of the peer whose peer connection was just established.
     * @see #pendingIceCandidatesMap
     */
    @Override
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

    @Override
    protected void onValidClient() {
        stateChangeCallback.accept(ServiceStateChange.waitingForConnection(channelDetails));
    }

    @Override
    protected String buildEndPointUri() {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
        return String.format(
            "%s?%s=%s",
            channelDetails.getWssEndpoint(),
            Constants.CHANNEL_ARN_QUERY_PARAM,
            channelDetails.getChannelArn()
        );
    }

    @Override
    protected void addCandidateToPending(String peerConnectionKey, IceCandidate iceCandidate) {
        // If answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        // Once the peer connection is found, add them directly instead of adding it to the queue.
        Log.d(getTag(), "SDP exchange is not complete. Ice candidate " + iceCandidate + " + added to pending queue");

        // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
        if (pendingIceCandidatesMap.containsKey(peerConnectionKey)) {
            final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(peerConnectionKey);

            if (pendingIceCandidatesQueueByClientId != null) {
                pendingIceCandidatesQueueByClientId.add(iceCandidate);
            }

            pendingIceCandidatesMap.put(peerConnectionKey, pendingIceCandidatesQueueByClientId);
        } else {
            // If the first ICE candidate before peer connection is received, add entry to map and ICE candidate to a queue
            final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = new LinkedList<>();
            pendingIceCandidatesQueueByClientId.add(iceCandidate);
            pendingIceCandidatesMap.put(peerConnectionKey, pendingIceCandidatesQueueByClientId);
        }
    }

    @Override
    protected Optional<PeerConnection> getPeerConnection(String peerConnectionKey) {
        return Optional.ofNullable(peerConnectionFoundMap.get(peerConnectionKey));
    }

    @Override
    protected void cleanupPeerConnections() {
        for (PeerConnection peerConnection : peerConnectionFoundMap.values()) {
            peerConnection.close();
        }

        peerConnectionFoundMap.clear();
        pendingIceCandidatesMap.clear();
    }

    public PeerConnection createLocalPeerConnection(String recipientClientId) {
        PeerConnection peerConnection = super.createLocalPeerConnection(recipientClientId);
        addStreamToLocalPeer(peerConnection);
        return peerConnection;
    }

    @Override
    public String getClientId() {
        // Broadcaster has no client id
        return null;
    }

    private void addStreamToLocalPeer(PeerConnection peerConnection) {
        final MediaStream stream = peerConnectionFactory.createLocalMediaStream(LOCAL_MEDIA_STREAM_LABEL);
        if (!stream.addTrack(localAudioTrack)) {
            Log.e(TAG, "Add audio track failed");
        }

        if (stream.audioTracks.size() > 0) {
            peerConnection.addTrack(stream.audioTracks.get(0), Collections.singletonList(stream.getId()));
            Log.d(TAG, "Sending audio track");
        }
    }

    @Override
    protected void handleSdpAnswer(Event evt, String peerConnectionKey) {
        // Listener should handle Sdp Answer?
    }

    private void createSdpAnswer(String recipientClientId, PeerConnection peerConnection) {
        final MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerConnection.createAnswer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(final SessionDescription sessionDescription) {
                Log.d(TAG, "Creating answer: success");
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message answer = Message.createAnswerMessage(sessionDescription, recipientClientId);

                if (client != null) {
                    client.sendSdpAnswer(answer);
                    peerConnectionFoundMap.put(recipientClientId, peerConnection);
                    handlePendingIceCandidates(recipientClientId, peerConnection);
                }
            }

            @Override
            public void onCreateFailure(final String error) {
                super.onCreateFailure(error);

                // Device is unable to support the requested media format
                if (error.contains("ERROR_CONTENT")) {
                    String codecError = "No supported codec is present in the offer!";
                    Log.e(TAG, codecError);
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, new InvalidCodecException()));
                } else {
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, new SdpAnswerCreationException(error)));
                }
            }
        }, sdpMediaConstraints);
    }

    @Override
    public String getTag() {
        return TAG;
    }

    @Override
    public List<PeerManager> getPeerStatus() {
        return peerConnectionFoundMap
            .entrySet()
            .stream()
            .map(e -> new PeerManager(e.getKey(), ChannelRole.MASTER, e.getValue()))
            .collect(Collectors.toList());
    }

}
