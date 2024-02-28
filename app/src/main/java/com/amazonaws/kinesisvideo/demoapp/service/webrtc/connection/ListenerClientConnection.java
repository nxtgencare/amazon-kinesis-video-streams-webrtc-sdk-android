package com.amazonaws.kinesisvideo.demoapp.service.webrtc.connection;

import android.util.Log;

import com.amazonaws.kinesisvideo.demoapp.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.demoapp.service.webrtc.ServiceStateChange;
import com.amazonaws.kinesisvideo.demoapp.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ListenerClientConnection extends ClientConnection {
    private static final String TAG = "ListenerClientConnection";
    protected String clientId;

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
                    // TODO: Better exception
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, new Exception("SDP Client invalid")));
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
        PeerConnection peerConnection = getPeerConnectionWithSdpOffer();
        peerConnectionFoundMap.put(clientId, peerConnection);
        stateChangeCallback.accept(
            ServiceStateChange.iceConnectionStateChange(
                channelDetails,
                peerConnection.iceConnectionState()
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
    public String getTag() {
        return TAG;
    }

    @Override
    public List<PeerManager> getPeerStatus() {
        return peerConnectionFoundMap
            .values()
            .stream()
            .map(p -> new PeerManager(
                channelDetails.getChannelName(), p, () -> removePeer(clientId))
            )
            .collect(Collectors.toList());
    }
}
