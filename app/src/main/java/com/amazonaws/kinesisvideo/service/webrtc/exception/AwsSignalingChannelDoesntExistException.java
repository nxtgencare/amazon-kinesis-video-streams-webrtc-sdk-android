package com.amazonaws.kinesisvideo.service.webrtc.exception;

import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException;

public class AwsSignalingChannelDoesntExistException extends RuntimeException {
    public AwsSignalingChannelDoesntExistException(ResourceNotFoundException e) {
        super(e);
    }
}
