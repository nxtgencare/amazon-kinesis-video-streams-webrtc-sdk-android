package com.amazonaws.kinesisvideo.service.webrtc.model;

import android.util.Log;

import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;

/**
 * Listener for Peer connection events. Prints event info to the logs at debug level.
 */
public class KinesisVideoPeerConnection implements PeerConnection.Observer {
    private final String tag;

    public KinesisVideoPeerConnection(String tag) {
        this.tag = tag;
    }

    /**
     * Triggered when the SignalingState changes.
     */
    @Override
    public void onSignalingChange(final PeerConnection.SignalingState signalingState) {
        Log.d(getTag(), "onSignalingChange(): signalingState = [" + signalingState + "]");
    }

    /**
     * Triggered when the IceConnectionState changes.
     */
    @Override
    public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
        Log.d(getTag(), "onIceConnectionChange(): iceConnectionState = [" + iceConnectionState + "]");
    }

    /**
     * Triggered when the ICE connection receiving status changes.
     */
    @Override
    public void onIceConnectionReceivingChange(final boolean connectionChange) {
        Log.d(getTag(), "onIceConnectionReceivingChange(): connectionChange = [" + connectionChange + "]");
    }

    /**
     * Triggered when the IceGatheringState changes.
     */
    @Override
    public void onIceGatheringChange(final PeerConnection.IceGatheringState iceGatheringState) {
        Log.d(getTag(), "onIceGatheringChange(): iceGatheringState = [" + iceGatheringState + "]");
    }

    /**
     * Triggered when a new ICE candidate has been found.
     */
    @Override
    public void onIceCandidate(final IceCandidate iceCandidate) {
        Log.d(getTag(), "onIceCandidate(): iceCandidate = [" + iceCandidate + "]");
    }

    /**
     * Triggered when some ICE candidates have been removed.
     */
    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] iceCandidates) {
        Log.d(getTag(), "onIceCandidatesRemoved(): iceCandidates Length = [" + iceCandidates.length + "]");
    }

    /**
     * Triggered when the ICE candidate pair is changed.
     */
    @Override
    public void onSelectedCandidatePairChanged(final CandidatePairChangeEvent event) {
        final String eventString = "{" +
            String.join(", ",
                "reason: " + event.reason,
                "remote: " + event.remote,
                "local: " + event.local,
                "lastReceivedMs: " + event.lastDataReceivedMs) +
            "}";
        Log.d(getTag(), "onSelectedCandidatePairChanged(): event = " + eventString);
    }

    /**
     * Triggered when media is received on a new stream from remote peer.
     */
    @Override
    public void onAddStream(final MediaStream mediaStream) {
        Log.d(getTag(), "onAddStream(): mediaStream = [" + mediaStream + "]");
    }

    /**
     * Triggered when a remote peer close a stream.
     */
    @Override
    public void onRemoveStream(final MediaStream mediaStream) {
        Log.d(getTag(), "onRemoveStream(): mediaStream = [" + mediaStream + "]");
    }

    /**
     * Triggered when a remote peer opens a DataChannel.
     */
    @Override
    public void onDataChannel(final DataChannel dataChannel) {
        Log.d(getTag(), "onDataChannel(): dataChannel = [" + dataChannel + "]");
    }

    /**
     * Triggered when renegotiation is necessary.
     */
    @Override
    public void onRenegotiationNeeded() {
        Log.d(getTag(), "onRenegotiationNeeded():");
    }

    /**
     * Triggered when a new track is signaled by the remote peer, as a result of setRemoteDescription.
     */
    @Override
    public void onAddTrack(final RtpReceiver rtpReceiver, final MediaStream[] mediaStreams) {
        Log.d(
                getTag(),
            "onAddTrack(): rtpReceiver = [" + rtpReceiver + "], " + "mediaStreams Length = [" + mediaStreams.length + "]"
        );
    }

    private String getTag() {
        return tag;
    }
}
