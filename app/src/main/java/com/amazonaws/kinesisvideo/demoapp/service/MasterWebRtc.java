package com.amazonaws.kinesisvideo.demoapp.service;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.Collections;
import java.util.function.Consumer;

public class MasterWebRtc extends WebRtc {
    private static final String TAG = "MasterWebRtc";
    private static final String AudioTrackID = "KvsAudioTrack";
    private static final String LOCAL_MEDIA_STREAM_LABEL = "KvsLocalMediaStream";

    private final AudioTrack localAudioTrack;

    private String recipientClientId;

    public MasterWebRtc(
        Context context,
        String mRegion,
        String channelName,
        AudioManager audioManager,
        Consumer<Exception> signallingListeningExceptionHandler,
        Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler
    ) throws Exception {
        super(context, mRegion, channelName, ChannelRole.MASTER, audioManager, signallingListeningExceptionHandler, iceConnectionStateChangedHandler);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AudioTrackID, audioSource);
        localAudioTrack.setEnabled(true);
    }

    @Override
    public void handleSdpOffer(Event offerEvent) {
        Log.d(TAG, "Received SDP Offer: Setting Remote Description ");

        final String sdp = Event.parseOfferEvent(offerEvent);
        PeerConnection peerConnection = createLocalPeerConnection();
        peerConnection.setRemoteDescription(new KinesisVideoSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdp));
        recipientClientId = offerEvent.getSenderClientId();
        Log.d(TAG, "Received SDP offer for client ID: " + recipientClientId + ". Creating answer");
        createSdpAnswer(peerConnection);
    }

    @Override
    protected void onValidClient(
        Consumer<Exception> signallingListeningExceptionHandler,
        Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler
    ) {
        // Do nothing
    }

    @Override
    protected String buildEndPointUri() {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
        return mWssEndpoint + "?" + Constants.CHANNEL_ARN_QUERY_PARAM + "=" + mChannelArn;
    }

    public PeerConnection createLocalPeerConnection() {
        PeerConnection peerConnection = super.createLocalPeerConnection();
        addStreamToLocalPeer(peerConnection);
        return peerConnection;
    }

    @Override
    public String getRecipientClientId() {
        return recipientClientId;
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

    // when local is set to be the master
    private void createSdpAnswer(PeerConnection peerConnection) {
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
                client.sendSdpAnswer(answer);

                peerConnectionFoundMap.put(recipientClientId, peerConnection);
                handlePendingIceCandidates(recipientClientId, peerConnection);
            }

            @Override
            public void onCreateFailure(final String error) {
                super.onCreateFailure(error);

                // Device is unable to support the requested media format
                if (error.contains("ERROR_CONTENT")) {
                    String codecError = "No supported codec is present in the offer!";
                    Log.e(TAG, codecError);
                    signallingListeningExceptionHandler.accept(new Exception(codecError));
                } else {
                    signallingListeningExceptionHandler.accept(new Exception(error));
                }
            }
        }, sdpMediaConstraints);
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
