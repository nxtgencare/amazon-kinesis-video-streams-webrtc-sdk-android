package com.amazonaws.kinesisvideo.demoapp.activity;

import android.content.Context;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class MasterWebRtc extends WebRtc {
    private static final String TAG = "MasterWebRtc";
    private static final String AudioTrackID = "KvsAudioTrack";
    private static final String LOCAL_MEDIA_STREAM_LABEL = "KvsLocalMediaStream";

    private AudioTrack localAudioTrack;

    private String recipientClientId;

    public MasterWebRtc(
        Context context,
        String mChannelArn,
        String mWssEndpoint,
        ArrayList<String> mUserNames,
        ArrayList<String> mPasswords,
        ArrayList<List<String>> mUrisList,
        String mRegion,
        AudioManager audioManager
    ) {
        super(context, mChannelArn, mWssEndpoint, mUserNames, mPasswords, mUrisList, mRegion, audioManager);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AudioTrackID, audioSource);
        localAudioTrack.setEnabled(true);
    }

    @Override
    protected void handleSdpOffer(Event offerEvent, Consumer<Exception> signallingListeningExceptionHandler) {
        Log.d(TAG, "Received SDP Offer: Setting Remote Description ");

        final String sdp = Event.parseOfferEvent(offerEvent);

        localPeer.setRemoteDescription(new KinesisVideoSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdp));
        recipientClientId = offerEvent.getSenderClientId();
        Log.d(TAG, "Received SDP offer for client ID: " + recipientClientId + ". Creating answer");
        createSdpAnswer(signallingListeningExceptionHandler);
    }

    @Override
    protected void onValidClient(Consumer<Exception> signallingListeningExceptionHandler, Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        // Do nothing
    }

    @Override
    protected String buildEndPointUri() {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
        return mWssEndpoint + "?" + Constants.CHANNEL_ARN_QUERY_PARAM + "=" + mChannelArn;
    }

    protected void createLocalPeerConnection(Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        super.createLocalPeerConnection(iceConnectionStateChangedHandler);
        addStreamToLocalPeer();
    }

    private Message createIceCandidateMessage(final IceCandidate iceCandidate) {
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

        final String senderClientId = "";
        return new Message("ICE_CANDIDATE", recipientClientId, senderClientId,
            new String(Base64.encode(messagePayload.getBytes(),
                    Base64.URL_SAFE | Base64.NO_WRAP)));
    }

    @Override
    protected String getRecipientClientId() {
        return recipientClientId;
    }

    private void addStreamToLocalPeer() {
        final MediaStream stream = peerConnectionFactory.createLocalMediaStream(LOCAL_MEDIA_STREAM_LABEL);
        if (!stream.addTrack(localAudioTrack)) {
            Log.e(TAG, "Add audio track failed");
        }

        if (stream.audioTracks.size() > 0) {
            localPeer.addTrack(stream.audioTracks.get(0), Collections.singletonList(stream.getId()));
            Log.d(TAG, "Sending audio track");
        }
    }

    // when local is set to be the master
    private void createSdpAnswer(Consumer<Exception> sdpAnswerErrorHandler) {
        final MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        localPeer.createAnswer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(final SessionDescription sessionDescription) {
                Log.d(TAG, "Creating answer: success");
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message answer = Message.createAnswerMessage(sessionDescription, recipientClientId);
                client.sendSdpAnswer(answer);

                peerConnectionFoundMap.put(recipientClientId, localPeer);
                handlePendingIceCandidates(recipientClientId);
            }

            @Override
            public void onCreateFailure(final String error) {
                super.onCreateFailure(error);

                // Device is unable to support the requested media format
                if (error.contains("ERROR_CONTENT")) {
                    String codecError = "No supported codec is present in the offer!";
                    Log.e(TAG, codecError);
                    sdpAnswerErrorHandler.accept(new Exception(codecError));
                } else {
                    sdpAnswerErrorHandler.accept(new Exception(error));
                }
            }
        }, sdpMediaConstraints);
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
