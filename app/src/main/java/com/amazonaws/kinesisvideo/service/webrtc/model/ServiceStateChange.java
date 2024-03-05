package com.amazonaws.kinesisvideo.service.webrtc.model;

import org.webrtc.PeerConnection;

import java.util.Optional;

public class ServiceStateChange {

    private ChannelDescription channelDescription;
    private PeerConnection.IceConnectionState iceConnectionState;
    private Optional<Exception> exception = Optional.empty();
    private boolean waitingForConnection = false;

    public ServiceStateChange(ChannelDescription channelDetails) {
        this.channelDescription = channelDetails;
    }

    public ServiceStateChange(ChannelDescription channelDescription, Exception e) {
        this(channelDescription);
        this.exception = Optional.of(e);
    }

    public ServiceStateChange(ChannelDescription channelDescription, PeerConnection.IceConnectionState iceConnectionState) {
        this(channelDescription);
        this.iceConnectionState = iceConnectionState;
    }

    public ServiceStateChange(ChannelDescription channelDescription, boolean waitingForConnection) {
        this(channelDescription);
        this.waitingForConnection = waitingForConnection;
    }

    public static ServiceStateChange exception(ChannelDescription channelDescription, Exception e) {
        return new ServiceStateChange(channelDescription, e);
    }

    public static ServiceStateChange iceConnectionStateChange(ChannelDescription channelDescription, PeerConnection.IceConnectionState iceConnectionState) {
        return new ServiceStateChange(channelDescription, iceConnectionState);
    }

    public static ServiceStateChange waitingForConnection(ChannelDescription channelDescription) {
        return new ServiceStateChange(channelDescription, true);
    }

    public static ServiceStateChange close(ChannelDescription channelDescription) {
        return new ServiceStateChange(channelDescription);
    }

    public PeerConnection.IceConnectionState getIceConnectionState() {
        return iceConnectionState == null ? PeerConnection.IceConnectionState.CLOSED : iceConnectionState;
    }
    public ChannelDescription getChannelDescription() { return channelDescription; }

    public Optional<Exception> getException() {
        return exception;
    }

    @Override
    public String toString() {
        return (channelDescription == null ? "" : channelDescription + " ") +
            (iceConnectionState != null ? iceConnectionState.toString() :
            exception
                .map(Throwable::toString)
                .orElse(waitingForConnection ? "Waiting for connection" : "Unknown state change"));
    }


}
