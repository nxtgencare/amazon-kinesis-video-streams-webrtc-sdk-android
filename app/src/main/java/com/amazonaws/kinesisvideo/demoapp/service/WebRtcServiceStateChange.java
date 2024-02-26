package com.amazonaws.kinesisvideo.demoapp.service;

import org.webrtc.PeerConnection;

import java.util.Optional;

public class WebRtcServiceStateChange {

    private ChannelDetails channelDetails;
    private PeerConnection.IceConnectionState iceConnectionState;
    private Optional<Exception> exception = Optional.empty();
    private boolean waitingForConnection = false;

    public WebRtcServiceStateChange(ChannelDetails channelDetails) {
        this.channelDetails = channelDetails;
    }

    public WebRtcServiceStateChange(ChannelDetails channelDetails, Exception e) {
        this(channelDetails);
        this.exception = Optional.of(e);
    }

    public WebRtcServiceStateChange(ChannelDetails channelDetails, PeerConnection.IceConnectionState iceConnectionState) {
        this(channelDetails);
        this.iceConnectionState = iceConnectionState;
    }

    public WebRtcServiceStateChange(ChannelDetails channelDetails, boolean waitingForConnection) {
        this(channelDetails);
        this.waitingForConnection = waitingForConnection;
    }

    public static WebRtcServiceStateChange exception(ChannelDetails channelDetails, Exception e) {
        return new WebRtcServiceStateChange(channelDetails, e);
    }

    public static WebRtcServiceStateChange iceConnectionStateChange(ChannelDetails channelDetails, PeerConnection.IceConnectionState iceConnectionState) {
        return new WebRtcServiceStateChange(channelDetails, iceConnectionState);
    }

    public static WebRtcServiceStateChange waitingForConnection(ChannelDetails channelDetails) {
        return new WebRtcServiceStateChange(channelDetails, true);
    }

    public static WebRtcServiceStateChange close(ChannelDetails channelDetails) {
        return new WebRtcServiceStateChange(channelDetails);
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
