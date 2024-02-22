package com.amazonaws.kinesisvideo.demoapp.service;

public class WebRtcChannelDetailsException extends Exception {
    private final Exception innerException;

    public WebRtcChannelDetailsException(Exception e) {
        super(e);
        this.innerException = e;
    }

    @Override
    public String toString() {
        return "Problem getting channel details: " + innerException.toString();
    }
}
