package com.amazonaws.kinesisvideo.demoapp.service;

import android.util.Log;

import org.webrtc.PeerConnection;

public class PeerManager {

    private static final String TAG = "PeerManager";
    private final String name;
    private final PeerConnection peerConnection;
    private final Runnable cleanup;

    public PeerManager(String name, PeerConnection peerConnection, Runnable cleanup) {
        this.name = name;
        this.peerConnection = peerConnection;
        this.cleanup = cleanup;
    }

    public String getName() { return name; }
    public PeerConnection.PeerConnectionState getState() {
        return peerConnection == null ?
            PeerConnection.PeerConnectionState.FAILED :
            peerConnection.connectionState();
    }
    public void endPeerConnection() {
        if (peerConnection.iceConnectionState() == PeerConnection.IceConnectionState.CLOSED) {
            cleanup.run();
        } else {
            peerConnection.close();
        }
    }

}
