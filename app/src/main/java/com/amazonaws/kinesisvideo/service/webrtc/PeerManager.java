package com.amazonaws.kinesisvideo.service.webrtc;

import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDescription;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.webrtc.PeerConnection;

import java.nio.channels.Channel;
import java.time.LocalDateTime;
import java.util.Optional;

public class PeerManager {

    private static final long OFFLINE_RESTART_THRESHOLD_SECS = 10;
    private final ChannelDescription channelDescription;
    private final Optional<PeerConnection> peerConnection;
    private PeerConnection.IceConnectionState previousState;
    private LocalDateTime offlineSince = LocalDateTime.now();

    public PeerManager(ChannelDescription channelDescription, PeerConnection peerConnection) {
        this.channelDescription = channelDescription;
        this.peerConnection = Optional.ofNullable(peerConnection);
        this.previousState = PeerConnection.IceConnectionState.CLOSED;
    }

    public ChannelDescription getChannelDescription() { return channelDescription; }
    public Optional<PeerConnection> getPeerConnection() { return peerConnection; }

    public PeerConnection.PeerConnectionState getState() {
        return peerConnection
            .map(PeerConnection::connectionState)
            .orElse(PeerConnection.PeerConnectionState.FAILED);
    }

    public void updateIceConnectionHistory(PeerConnection.IceConnectionState iceConnectionState) {
        if (previousState == PeerConnection.IceConnectionState.CONNECTED && iceConnectionState != PeerConnection.IceConnectionState.CONNECTED) {
            offlineSince = LocalDateTime.now();
        }
        this.previousState = iceConnectionState;
    }

    public boolean requiresRestart() {
        if (peerConnection
            .map(PeerConnection::iceConnectionState)
            .orElse(PeerConnection.IceConnectionState.FAILED) != PeerConnection.IceConnectionState.CONNECTED
        ) {
            return offlineSince.plusSeconds(OFFLINE_RESTART_THRESHOLD_SECS).isBefore(LocalDateTime.now());
        }
        return false;
    }

}
