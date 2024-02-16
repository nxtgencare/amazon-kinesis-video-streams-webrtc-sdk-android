package com.amazonaws.kinesisvideo.demoapp.activity;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;

import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ViewerWebRtc extends WebRtc {
    private static final String TAG = "ViewerWebRtc";
    protected String mClientId;

    public ViewerWebRtc(
        Context context,
        String mClientId,
        String mChannelArn,
        String mWssEndpoint,
        ArrayList<String> mUserNames,
        ArrayList<String> mPasswords,
        ArrayList<List<String>> mUrisList,
        String mRegion,
        AudioManager audioManager
    ) {
        super(context, mChannelArn, mWssEndpoint, mUserNames, mPasswords, mUrisList, mRegion, audioManager);
        this.mClientId = mClientId;
    }

    // when mobile sdk is viewer
    private void createSdpOffer(Consumer<Exception> signallingListeningExceptionHandler, Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        if (localPeer == null) {
            createLocalPeerConnection(iceConnectionStateChangedHandler);
        }

        localPeer.createOffer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message sdpOfferMessage = Message.createOfferMessage(sessionDescription, mClientId);
                if (isValidClient()) {
                    client.sendSdpOffer(sdpOfferMessage);
                } else {
                    // TODO: Better exception
                    signallingListeningExceptionHandler.accept(new Exception("SDP Client invalid"));
                }
            }
        }, sdpMediaConstraints);
    }

    @Override
    protected void handleSdpOffer(Event offerEvent, Consumer<Exception> signallingListeningExceptionHandler) {
        Log.d(getTag(), "Viewer should not be receiving SDP Offer");
    }

    @Override
    protected void onValidClient(Consumer<Exception> signallingListeningExceptionHandler, Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        Log.d(getTag(), "Signaling service is connected: Sending offer as viewer to remote peer"); // Viewer
        createSdpOffer(signallingListeningExceptionHandler, iceConnectionStateChangedHandler);
    }

    @Override
    protected String buildEndPointUri() {
        return mWssEndpoint + "?" + Constants.CHANNEL_ARN_QUERY_PARAM + "=" + mChannelArn + "&" + Constants.CLIENT_ID_QUERY_PARAM + "=" + mClientId;
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
