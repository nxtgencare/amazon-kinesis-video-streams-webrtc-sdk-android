package com.amazonaws.kinesisvideo.signaling.tyrus;

import android.util.Base64;
import android.util.Log;

import com.amazonaws.kinesisvideo.service.webrtc.connection.ClientConnection;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.google.gson.Gson;

import org.glassfish.tyrus.client.ClientManager;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Signaling service client based on websocket.
 */

public class SignalingServiceWebSocketClient {

    private final WebSocketClient websocketClient;

    private final ExecutorService executorService;

    private final Gson gson = new Gson();
    private final String tag;

    public SignalingServiceWebSocketClient(
        final String tag,
        final String uri,
        final ClientConnection signalingListener,
        final ExecutorService executorService
    ) {
        this.tag = tag;
        Log.d(getTag(), "Connecting to URI " + uri + " as " + signalingListener.getChannelDetails().getRole());
        websocketClient = new WebSocketClient(uri, new ClientManager(), signalingListener, executorService);
        this.executorService = executorService;
    }

    public boolean isOpen() {
        return websocketClient.isOpen();
    }

    public void sendSdpOffer(final Message offer) {
        safeSubmit(() -> {
            if (offer.getAction().equalsIgnoreCase("SDP_OFFER")) {
                Log.d(getTag(), "Sending Offer");
                send(offer);
            }
        });
    }

    public void sendSdpAnswer(final Message answer) {
        safeSubmit(() -> {

            if (answer.getAction().equalsIgnoreCase("SDP_ANSWER")) {
                Log.d(getTag(), "Answer sent " + new String(Base64.decode(answer.getMessagePayload().getBytes(),
                        Base64.NO_WRAP | Base64.URL_SAFE)));
                send(answer);
            }
        });
    }

    public void sendIceCandidate(final Message candidate) {
        safeSubmit(() -> {
            if (candidate.getAction().equalsIgnoreCase("ICE_CANDIDATE")) {
                send(candidate);
            }

            Log.d(getTag(), "Sent Ice candidate message");
        });
    }

    public void disconnect() {
        safeSubmit(websocketClient::disconnect);
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(1, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Log.e(getTag(), "Error in disconnect");
        }
    }

    private void send(final Message message) {
        final String jsonMessage = gson.toJson(message);
        final String messageId = UUID.randomUUID().toString();

        if (websocketClient.isOpen()) {
            Log.d(getTag(), String.format("Sending JSON Message (%s)= %s", messageId, jsonMessage));
            websocketClient.send(jsonMessage);
            Log.d(getTag(), String.format("Sent JSON Message (%s)", messageId));
        } else {
            Log.d(getTag(), String.format("Can't send JSON Message (%s)= %s, socket closed", messageId, jsonMessage));
        }

    }

    private void safeSubmit(Runnable r) {
        try {
            executorService.submit(r);
        } catch (Exception e) {
            Log.e(getTag(), "Exception executing client task", e);
        }
    }

    private String getTag() {
        return tag;
    }
}
