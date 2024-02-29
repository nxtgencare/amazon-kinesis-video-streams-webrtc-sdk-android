package com.amazonaws.kinesisvideo.demoapp.service.webrtc.connection;

import android.util.Log;

import com.amazonaws.kinesisvideo.demoapp.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.demoapp.service.webrtc.ServiceStateChange;
import com.amazonaws.kinesisvideo.demoapp.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BroadcastClientConnection extends ClientConnection {
    private static final String TAG = "BroadcastClientConnection";
    private static final String LOCAL_MEDIA_STREAM_LABEL = "BroadcastClientMediaStream";
    private final AudioTrack localAudioTrack;

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

    public PeerConnection createLocalPeerConnection(String recipientClientId) {
        PeerConnection peerConnection = super.createLocalPeerConnection(recipientClientId);
        addStreamToLocalPeer(peerConnection);
        return peerConnection;
    }

    @Override
    public String getClientId() {
        // Master has no client id
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

    // when local is set to be the master
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
                    // TODO: Better exceptions
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, new Exception(codecError)));
                } else {
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDetails, new Exception(error)));
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


    public void removePeerConnection(String name) {
        peerConnectionFoundMap.remove(name);
    }

}
