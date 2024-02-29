package com.amazonaws.kinesisvideo.service.webrtc.factory;

import android.content.Context;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

public class PeerConnectionFactoryBuilder {
    public static PeerConnectionFactory build(Context context, EglBase rootEglBase) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory
                .InitializationOptions
                .builder(context)
                .createInitializationOptions()
        );

        // codecs are mandatory even if we aren't using them.
        final VideoDecoderFactory vdf = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        final VideoEncoderFactory vef = new DefaultVideoEncoderFactory(
            rootEglBase.getEglBaseContext(),
            true,
            true
        );

        return org.webrtc.PeerConnectionFactory.builder()
            .setVideoDecoderFactory(vdf)
            .setVideoEncoderFactory(vef)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory();
    }
}
