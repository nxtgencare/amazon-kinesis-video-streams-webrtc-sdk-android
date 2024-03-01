package com.amazonaws.kinesisvideo.service.webrtc;

import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.PeerConnection;

import java.util.Optional;

public class PeerManager {

    private final String name;
    private final ChannelRole localRole;
    private final Optional<PeerConnection> peerConnection;

    public PeerManager(String name, ChannelRole localRole, PeerConnection peerConnection) {
        this.name = name;
        this.localRole = localRole;
        this.peerConnection = Optional.ofNullable(peerConnection);
    }

    public String getName() { return name; }
    public ChannelRole getLocalRole() { return localRole; }
    public Optional<PeerConnection> getPeerConnection() { return peerConnection; }

    public PeerConnection.PeerConnectionState getState() {
        return peerConnection
            .map(PeerConnection::connectionState)
            .orElse(PeerConnection.PeerConnectionState.FAILED);
    }
}
