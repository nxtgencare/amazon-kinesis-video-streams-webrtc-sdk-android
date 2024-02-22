package com.amazonaws.kinesisvideo.demoapp.service;

import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.function.Consumer;

public class ViewerWebRtcClientConnection extends WebRtcClientConnection {
    private static final String TAG = "ViewerWebRtc";
    protected String clientId;

    public ViewerWebRtcClientConnection(PeerConnectionFactory peerConnectionFactory, ChannelDetails channelDetails, String clientId, Consumer<WebRtcServiceStateChange> stateChangeCallback) {
        super(peerConnectionFactory, channelDetails, stateChangeCallback);
        this.clientId = clientId;
    }

    // when mobile sdk is viewer
    private PeerConnection getPeerConnectionWithSdpOffer() {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        PeerConnection peerConnection = createLocalPeerConnection();

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
                    // Publish state change with exception
                    // signallingListeningExceptionHandler.accept(new Exception("SDP Client invalid"));
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
        Log.d(getTag(), "Signaling service is connected: Sending offer as viewer to remote peer"); // Viewer
        peerConnectionFoundMap.put(
            clientId,
            getPeerConnectionWithSdpOffer()
        );
    }

    @Override
    protected String buildEndPointUri() {
        return channelDetails.getWssEndpoint() + "?" +
            Constants.CHANNEL_ARN_QUERY_PARAM +
            "=" + channelDetails.getChannelArn() +
            "&" + Constants.CLIENT_ID_QUERY_PARAM + "=" + clientId;
    }

    @Override
    public String getRecipientClientId() {
        return clientId;
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
