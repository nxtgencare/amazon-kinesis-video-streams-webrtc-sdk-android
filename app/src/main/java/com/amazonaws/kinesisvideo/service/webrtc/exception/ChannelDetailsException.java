package com.amazonaws.kinesisvideo.service.webrtc.exception;

public class ChannelDetailsException extends Exception {
    private final Exception innerException;

    public ChannelDetailsException(Exception e) {
        super(e);
        this.innerException = e;
    }

    @Override
    public String toString() {
        return "Problem getting channel details: " + innerException.toString();
    }
}
