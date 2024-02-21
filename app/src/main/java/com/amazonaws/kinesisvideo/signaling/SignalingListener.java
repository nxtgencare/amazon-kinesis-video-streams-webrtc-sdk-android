package com.amazonaws.kinesisvideo.signaling;


import android.util.Base64;
import android.util.Log;

import com.amazonaws.kinesisvideo.demoapp.service.WebRtc;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.google.gson.Gson;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.Optional;

import javax.websocket.MessageHandler;

public class SignalingListener implements MessageHandler.Whole<String> {
    private final Gson gson = new Gson();
    private final WebRtc webRtc;

    public SignalingListener(WebRtc webRtc) {
        this.webRtc = webRtc;
    }

    @Override
    public void onMessage(String message) {
        if (message.isEmpty()) {
            return;
        }

        Log.d(webRtc.getTag(), "Received objMessage: " + message);

        if (!message.contains("messagePayload")) {
            return;
        }

        final Event evt = gson.fromJson(message, Event.class);

        if (evt == null || evt.getMessageType() == null || evt.getMessagePayload().isEmpty()) {
            return;
        }
        String peerConnectionKey = evt.getSenderClientId() == null || evt.getSenderClientId().isEmpty() ?
            webRtc.getRecipientClientId() :
            evt.getSenderClientId();

        switch (evt.getMessageType().toUpperCase()) {
            case "SDP_OFFER":
                Log.d(webRtc.getTag(), "Offer received: SenderClientId=" + peerConnectionKey);
                Log.d(webRtc.getTag(), new String(Base64.decode(evt.getMessagePayload(), 0)));
                webRtc.handleSdpOffer(evt);
                break;
            case "SDP_ANSWER":
                Log.d(webRtc.getTag(), "Answer received: SenderClientId=" + peerConnectionKey);
                Log.d(webRtc.getTag(), "SDP answer received from signaling");
                final String sdp = Event.parseSdpEvent(evt);
                final SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);

                Optional<PeerConnection> maybePeerConnection = Optional.ofNullable(
                    webRtc.peerConnectionFoundMap.get(peerConnectionKey)
                );

                maybePeerConnection.ifPresent(peerConnection -> {
                    peerConnection.setRemoteDescription(new KinesisVideoSdpObserver() {
                        @Override
                        public void onCreateFailure(final String error) {
                            super.onCreateFailure(error);
                        }
                    }, sdpAnswer);

                    Log.d(webRtc.getTag(), "Answer Client ID: " + peerConnectionKey);
                    // Check if ICE candidates are available in the queue and add the candidate
                    webRtc.handlePendingIceCandidates(peerConnectionKey, peerConnection);
                });
                break;
            case "ICE_CANDIDATE":
                Log.d(webRtc.getTag(), "Ice Candidate received: SenderClientId=" + peerConnectionKey);
                Log.d(webRtc.getTag(), new String(Base64.decode(evt.getMessagePayload(), 0)));
                Log.d(webRtc.getTag(), "Received ICE candidate from remote");
                final IceCandidate iceCandidate = Event.parseIceCandidate(evt);
                if (iceCandidate != null) {
                    webRtc.checkAndAddIceCandidate(evt, iceCandidate);
                } else {
                    Log.e(webRtc.getTag(), "Invalid ICE candidate: " + evt);
                }
                break;
            default:
                break;
        }
    }

    public void onException(Exception e) {
        webRtc.signallingListeningExceptionHandler.accept(e);
    }
}
