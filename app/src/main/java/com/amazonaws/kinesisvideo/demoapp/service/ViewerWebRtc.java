package com.amazonaws.kinesisvideo.demoapp.service;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.function.Consumer;

public class ViewerWebRtc extends WebRtc {
    private static final String TAG = "ViewerWebRtc";
    protected String mClientId;

    public ViewerWebRtc(
        Context context,
        String mRegion,
        String mChannelName,
        String mClientId,
        AudioManager audioManager,
        Consumer<Exception> signallingListeningExceptionHandler,
        Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler
    ) throws Exception {
        super(context, mRegion, mChannelName, ChannelRole.VIEWER, audioManager, signallingListeningExceptionHandler, iceConnectionStateChangedHandler);
        this.mClientId = mClientId;
    }

    // when mobile sdk is viewer
    private PeerConnection createSdpOffer(Consumer<Exception> signallingListeningExceptionHandler) {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        PeerConnection peerConnection = createLocalPeerConnection();

        peerConnection.createOffer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message sdpOfferMessage = Message.createOfferMessage(sessionDescription, mClientId);
                if (isValidClient()) {
                    client.sendSdpOffer(sdpOfferMessage);
                } else {
                    // TODO: Better exception
                    signallingListeningExceptionHandler.accept(new Exception("SDP Client invalid"));
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
    protected void onValidClient(Consumer<Exception> signallingListeningExceptionHandler, Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        Log.d(getTag(), "Signaling service is connected: Sending offer as viewer to remote peer"); // Viewer
        peerConnectionFoundMap.put(
            mClientId,
            createSdpOffer(signallingListeningExceptionHandler)
        );
    }

    @Override
    protected String buildEndPointUri() {
        return mWssEndpoint + "?" +
            Constants.CHANNEL_ARN_QUERY_PARAM +
            "=" + mChannelArn +
            "&" + Constants.CLIENT_ID_QUERY_PARAM + "=" + mClientId;
    }

    @Override
    public String getRecipientClientId() {
        return mClientId;
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
