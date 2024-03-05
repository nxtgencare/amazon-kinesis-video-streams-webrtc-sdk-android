package com.amazonaws.kinesisvideo.service.webrtc.connection;

import android.util.Log;

import com.amazonaws.kinesisvideo.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.service.webrtc.exception.InvalidSdpClientExcception;
import com.amazonaws.kinesisvideo.service.webrtc.model.ServiceStateChange;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ListenerClientConnection extends AbstractClientConnection {
    private static final String TAG = "ListenerClientConnection";
    protected String clientId;
    private Optional<PeerManager> peerManager = Optional.empty();

    public ListenerClientConnection(PeerConnectionFactory peerConnectionFactory, ChannelDetails channelDetails, String clientId, Consumer<ServiceStateChange> stateChangeCallback) {
        super(peerConnectionFactory, channelDetails, stateChangeCallback);
        this.clientId = clientId;
    }

    private PeerConnection getPeerConnectionWithSdpOffer() {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        PeerConnection peerConnection = createLocalPeerConnection("");

        peerConnection.createOffer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message sdpOfferMessage = Message.createOfferMessage(sessionDescription, clientId);
                if (isValidClient()) {
                    client.sendSdpOffer(sdpOfferMessage);
                } else {
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails.getChannelDescription(), new InvalidSdpClientExcception()));
                }
            }
        }, sdpMediaConstraints);

        return peerConnection;
    }

    @Override
    public void handleSdpOffer(Event offerEvent) {
        Log.d(getTag(), "Viewer should not be receiving SDP Offer");
    }

    @Override
    protected void onValidClient() {
        Log.d(getTag(), "Signaling service is connected: Sending offer as viewer to remote peer");
        peerManager = Optional
            .of(getPeerConnectionWithSdpOffer())
            .map(c -> new PeerManager(channelDetails.getChannelDescription(), c));

        stateChangeCallback.accept(
            ServiceStateChange.iceConnectionStateChange(
                channelDetails.getChannelDescription(),
                peerManager.flatMap(PeerManager::getPeerConnection)
                    .map(PeerConnection::iceConnectionState)
                    .orElse(PeerConnection.IceConnectionState.FAILED)
            )
        );
    }

    @Override
    protected String buildEndPointUri() {
        return String.format(
            "%s?%s=%s&%s=%s",
            channelDetails.getWssEndpoint(),
            Constants.CHANNEL_ARN_QUERY_PARAM,
            channelDetails.getChannelArn(),
            Constants.CLIENT_ID_QUERY_PARAM,
            clientId
        );
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    protected void handleSdpAnswer(Event evt, String peerConnectionKey) {
        Log.d(getTag(), "Answer received: SenderClientId=" + peerConnectionKey);
        Log.d(getTag(), "SDP answer received from signaling");
        final String sdp = Event.parseSdpEvent(evt);
        final SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        Optional<PeerConnection> maybePeerConnection = getPeerConnection(peerConnectionKey);

        maybePeerConnection.ifPresent(peerConnection -> {
            peerConnection.setRemoteDescription(new KinesisVideoSdpObserver() {
                @Override
                public void onCreateFailure(final String error) {
                    super.onCreateFailure(error);
                }
            }, sdpAnswer);
        });
    }

    @Override
    protected void addCandidateToPending(String peerConnectionKey, IceCandidate iceCandidate) {
        // Listener doesn't queue pending connections.
    }

    @Override
    protected void handlePendingIceCandidates(String peerConnectionKey, PeerConnection peerConnection) {
        // Listener doesn't queue pending connections
    }

    @Override
    public Optional<PeerManager> getPeerManager(String peerConnectionKey) {
        return peerManager;
    }

    @Override
    public Optional<PeerConnection> getPeerConnection(String peerConnectionKey) {
        return peerManager.flatMap(PeerManager::getPeerConnection);
    }

    @Override
    protected void cleanupPeerConnections() {
        peerManager.flatMap(PeerManager::getPeerConnection).ifPresent(PeerConnection::close);
        peerManager = Optional.empty();
    }

    @Override
    public String getTag() {
        return TAG;
    }

    @Override
    public void resetPeer(String key) {
        cleanupPeerConnections();
    }

    @Override
    public List<PeerManager> getPeerManagers() {
        return peerManager.map(Arrays::asList).orElse(new ArrayList<>());
    }
}
