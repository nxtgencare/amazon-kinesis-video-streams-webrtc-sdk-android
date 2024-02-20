package com.amazonaws.kinesisvideo.signaling;


import android.util.Base64;
import android.util.Log;

import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.google.gson.Gson;

import javax.websocket.MessageHandler;

public abstract class SignalingListener implements Signaling {

    private final static String TAG = "CustomMessageHandler";

    private final Gson gson = new Gson();

    private final MessageHandler messageHandler = (MessageHandler.Whole<Object>) objMessage -> {
        String strMessage = "";
        if (objMessage instanceof String) {
            strMessage = (String) objMessage;
        } else if (objMessage instanceof Boolean) {
            strMessage = ((Boolean) objMessage).toString();
        }

        if (strMessage.isEmpty()) {
            return;
        }

        Log.d(TAG, "Received objMessage: " + objMessage);

        if (!strMessage.contains("messagePayload")) {
            return;
        }

        final Event evt = gson.fromJson(strMessage, Event.class);

        if (evt == null || evt.getMessageType() == null || evt.getMessagePayload().isEmpty()) {
            return;
        }

        switch (evt.getMessageType().toUpperCase()) {
            case "SDP_OFFER":
                Log.d(TAG, "Offer received: SenderClientId=" + evt.getSenderClientId());
                Log.d(TAG, new String(Base64.decode(evt.getMessagePayload(), 0)));

                onSdpOffer(evt);
                break;
            case "SDP_ANSWER":
                Log.d(TAG, "Answer received: SenderClientId=" + evt.getSenderClientId());

                onSdpAnswer(evt);
                break;
            case "ICE_CANDIDATE":
                Log.d(TAG, "Ice Candidate received: SenderClientId=" + evt.getSenderClientId());
                Log.d(TAG, new String(Base64.decode(evt.getMessagePayload(), 0)));

                onIceCandidate(evt);
                break;
            default:
                break;
        }
    };

    public MessageHandler getMessageHandler() {
        return messageHandler;
    }
}
