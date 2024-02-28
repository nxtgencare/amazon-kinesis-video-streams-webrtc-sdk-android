package com.amazonaws.kinesisvideo.demoapp.service.webrtc;

import com.amazonaws.kinesisvideo.demoapp.service.webrtc.model.ChannelDetails;

import org.webrtc.PeerConnection;

import java.util.Optional;

public class ServiceStateChange {

    private ChannelDetails channelDetails;
    private PeerConnection.IceConnectionState iceConnectionState;
    private Optional<Exception> exception = Optional.empty();
    private boolean waitingForConnection = false;

    public ServiceStateChange(ChannelDetails channelDetails) {
        this.channelDetails = channelDetails;
    }

    public ServiceStateChange(ChannelDetails channelDetails, Exception e) {
        this(channelDetails);
        this.exception = Optional.of(e);
    }

    public ServiceStateChange(ChannelDetails channelDetails, PeerConnection.IceConnectionState iceConnectionState) {
        this(channelDetails);
        this.iceConnectionState = iceConnectionState;
    }

    public ServiceStateChange(ChannelDetails channelDetails, boolean waitingForConnection) {
        this(channelDetails);
        this.waitingForConnection = waitingForConnection;
    }

    public static ServiceStateChange exception(ChannelDetails channelDetails, Exception e) {
        return new ServiceStateChange(channelDetails, e);
    }

    public static ServiceStateChange iceConnectionStateChange(ChannelDetails channelDetails, PeerConnection.IceConnectionState iceConnectionState) {
        return new ServiceStateChange(channelDetails, iceConnectionState);
    }

    public static ServiceStateChange waitingForConnection(ChannelDetails channelDetails) {
        return new ServiceStateChange(channelDetails, true);
    }

    public static ServiceStateChange close(ChannelDetails channelDetails) {
        return new ServiceStateChange(channelDetails);
    }

    public static ServiceStateChange remove(ChannelDetails channelDetails) {
        return new ServiceStateChange(channelDetails);
    }

    public ChannelDetails getChannelDetails() {
        return channelDetails;
    }

    public boolean isWaitingForConnection() {
        return waitingForConnection;
    }

    @Override
    public String toString() {
        return (channelDetails == null ? "" : channelDetails + " ") +
            (iceConnectionState != null ? iceConnectionState.toString() :
            exception
                .map(Throwable::toString)
                .orElse(waitingForConnection ? "Waiting for connection" : "Unknown state change"));
    }
}
