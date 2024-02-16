package com.amazonaws.kinesisvideo.demoapp.activity;

import android.content.Context;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.kinesisvideo.signaling.SignalingListener;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class WebRtc {
    private static final String TAG = "WebRtc";
    private static final String AudioTrackID = "KvsAudioTrack";
    private static final String LOCAL_MEDIA_STREAM_LABEL = "KvsLocalMediaStream";

    private static volatile SignalingServiceWebSocketClient client;
    private PeerConnectionFactory peerConnectionFactory;

    private String mChannelArn;

    private String mWssEndpoint;
    private String mRegion;

    private AudioManager audioManager;
    private int originalAudioMode;
    private boolean originalSpeakerphoneOn;

    private AudioTrack localAudioTrack;

    private PeerConnection localPeer;

    private EglBase rootEglBase;

    private final List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();

    private String recipientClientId;

    /**
     * Mapping of established peer connections to the peer's sender id. In other words, if an SDP
     * offer/answer for a peer connection has been received and sent, the PeerConnection is added
     * to this map.
     */
    private final HashMap<String, PeerConnection> peerConnectionFoundMap = new HashMap<>();

    /**
     * Only used when we are master. Mapping of the peer's sender id to its received ICE candidates.
     * Since we can receive ICE Candidates before we have sent the answer, we hold ICE candidates in
     * this queue until after we send the answer and the peer connection is established.
     */
    private final HashMap<String, Queue<IceCandidate>> pendingIceCandidatesMap = new HashMap<>();

    public boolean isValidClient() {
        return client != null && client.isOpen();
    }

    public WebRtc(
        Context context,
        String mChannelArn,
        String mWssEndpoint,
        ArrayList<String> mUserNames,
        ArrayList<String> mPasswords,
        ArrayList<List<String>> mUrisList,
        String mRegion,
        AudioManager audioManager
    ) {
        this.mChannelArn = mChannelArn;
        this.mWssEndpoint = mWssEndpoint;
        this.mRegion = "ca-central-1";
        this.rootEglBase = EglBase.create();

        final PeerConnection.IceServer stun = PeerConnection.IceServer
            .builder(String.format("stun:stun.kinesisvideo.%s.amazonaws.com:443", mRegion))
            .createIceServer();

        peerIceServers.add(stun);

        if (mUrisList != null) {
            for (int i = 0; i < mUrisList.size(); i++) {
                final String turnServer = mUrisList.get(i).toString();
                final PeerConnection.IceServer iceServer = PeerConnection.IceServer.builder(turnServer.replace("[", "").replace("]", ""))
                    .setUsername(mUserNames.get(i))
                    .setPassword(mPasswords.get(i))
                    .createIceServer();

                Log.d(TAG, "IceServer details (TURN) = " + iceServer.toString());
                peerIceServers.add(iceServer);
            }
        }

        PeerConnectionFactory.initialize(PeerConnectionFactory
            .InitializationOptions
            .builder(context)
            .createInitializationOptions());

        // codecs are mandatory even if we aren't using them.
        final VideoDecoderFactory vdf = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        final VideoEncoderFactory vef = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(vdf)
            .setVideoEncoderFactory(vef)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory();

        // Enable Google WebRTC debug logs
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack(AudioTrackID, audioSource);
        localAudioTrack.setEnabled(true);

        this.audioManager = audioManager;
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
    }

    public void initWsConnection(
        AWSCredentials mCreds,
        Consumer<Exception> signallingListeningExceptionHandler,
        Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler
    ) throws Exception {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-2.html
        final String masterEndpoint = mWssEndpoint + "?" + Constants.CHANNEL_ARN_QUERY_PARAM + "=" + mChannelArn;
        final URI signedUri = getSignedUri(mCreds, masterEndpoint);

        createLocalPeerConnection(iceConnectionStateChangedHandler);
        final String wsHost = signedUri.toString();

        // Step 10. Create Signaling Client Event Listeners.
        //          When we receive messages, we need to take the appropriate action.
        final SignalingListener signalingListener = new SignalingListener() {
            @Override
            public void onSdpOffer(final Event offerEvent) {
                Log.d(TAG, "Received SDP Offer: Setting Remote Description ");

                final String sdp = Event.parseOfferEvent(offerEvent);

                localPeer.setRemoteDescription(new KinesisVideoSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdp));
                recipientClientId = offerEvent.getSenderClientId();
                Log.d(TAG, "Received SDP offer for client ID: " + recipientClientId + ". Creating answer");
                createSdpAnswer(signallingListeningExceptionHandler);
            }

            @Override
            public void onSdpAnswer(final Event answerEvent) {
                Log.d(TAG, "SDP answer received from signaling");
                final String sdp = Event.parseSdpEvent(answerEvent);
                final SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);

                localPeer.setRemoteDescription(new KinesisVideoSdpObserver() {
                    @Override
                    public void onCreateFailure(final String error) {
                        super.onCreateFailure(error);
                    }
                }, sdpAnswer);
                Log.d(TAG, "Answer Client ID: " + answerEvent.getSenderClientId());
                peerConnectionFoundMap.put(answerEvent.getSenderClientId(), localPeer);
                // Check if ICE candidates are available in the queue and add the candidate
                handlePendingIceCandidates(answerEvent.getSenderClientId());

            }

            @Override
            public void onIceCandidate(final Event message) {
                Log.d(TAG, "Received ICE candidate from remote");
                final IceCandidate iceCandidate = Event.parseIceCandidate(message);
                if (iceCandidate != null) {
                    checkAndAddIceCandidate(message, iceCandidate);
                } else {
                    Log.e(TAG, "Invalid ICE candidate: " + message);
                }
            }

            @Override
            public void onError(final Event errorMessage) {
                Log.e(TAG, "Received error message: " + errorMessage);
            }

            @Override
            public void onException(final Exception e) {
                Log.e(TAG, "Signaling client returned exception: " + e.getMessage());
                signallingListeningExceptionHandler.accept(e);
            }
        };

        // Step 11. Create SignalingServiceWebSocketClient.
        //          This is the actual client that is used to send messages over the signaling channel.
        //          SignalingServiceWebSocketClient will attempt to open the connection in its constructor.
        try {
            client = new SignalingServiceWebSocketClient(wsHost, signalingListener, Executors.newFixedThreadPool(10));
            Log.d(TAG, "Client connection " + (client.isOpen() ? "Successful" : "Failed"));
        } catch (final Exception e) {
            Log.e(TAG, "Exception with websocket client: " + e);
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }

        if (isValidClient()) {
            Log.d(TAG, "Client connected to Signaling service " + client.isOpen());
        } else {
            Log.e(TAG, "Error in connecting to signaling service");
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }
    }

    /**
     * Called once the peer connection is established. Checks the pending ICE candidate queue to see
     * if we have received any before we finished sending the SDP answer. If so, add those ICE
     * candidates to the peer connection belonging to this clientId.
     *
     * @param clientId The sender client id of the peer whose peer connection was just established.
     * @see #pendingIceCandidatesMap
     */
    private void handlePendingIceCandidates(final String clientId) {
        // Add any pending ICE candidates from the queue for the client ID
        Log.d(TAG, "Pending ice candidates found? " + pendingIceCandidatesMap.get(clientId));
        final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(clientId);
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            final IceCandidate iceCandidate = pendingIceCandidatesQueueByClientId.peek();
            final PeerConnection peer = peerConnectionFoundMap.get(clientId);
            final boolean addIce = peer.addIceCandidate(iceCandidate);
            Log.d(TAG, "Added ice candidate after SDP exchange " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
            pendingIceCandidatesQueueByClientId.remove();
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId);
    }

    private void checkAndAddIceCandidate(final Event message, final IceCandidate iceCandidate) {
        // If answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        // Once the peer connection is found, add them directly instead of adding it to the queue.
        if (!peerConnectionFoundMap.containsKey(message.getSenderClientId())) {
            Log.d(TAG, "SDP exchange is not complete. Ice candidate " + iceCandidate + " + added to pending queue");

            // If the entry for the client ID already exists (in case of subsequent ICE candidates), update the queue
            if (pendingIceCandidatesMap.containsKey(message.getSenderClientId())) {
                final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(message.getSenderClientId());
                pendingIceCandidatesQueueByClientId.add(iceCandidate);
                pendingIceCandidatesMap.put(message.getSenderClientId(), pendingIceCandidatesQueueByClientId);
            } else {
                // If the first ICE candidate before peer connection is received, add entry to map and ICE candidate to a queue
                final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = new LinkedList<>();
                pendingIceCandidatesQueueByClientId.add(iceCandidate);
                pendingIceCandidatesMap.put(message.getSenderClientId(), pendingIceCandidatesQueueByClientId);
            }
        } else {
            // This is the case where peer connection is established and ICE candidates are received for the established
            // connection
            Log.d(TAG, "Peer connection found already");
            // Remote sent us ICE candidates, add to local peer connection
            final PeerConnection peer = peerConnectionFoundMap.get(message.getSenderClientId());
            final boolean addIce = peer.addIceCandidate(iceCandidate);

            Log.d(TAG, "Added ice candidate " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
        }
    }

    private void createLocalPeerConnection(Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
        final PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(peerIceServers);

        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;

        // Step 8. Create RTCPeerConnection.
        //  The RTCPeerConnection is the primary interface for WebRTC communications in the Web.
        //  We also configure the Add Peer Connection Event Listeners here.
        localPeer = peerConnectionFactory.createPeerConnection(rtcConfig, new KinesisVideoPeerConnection() {
            @Override
            public void onIceCandidate(final IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                final Message message = createIceCandidateMessage(iceCandidate);
                Log.d(TAG, "Sending IceCandidate to remote peer " + iceCandidate);
                client.sendIceCandidate(message);  /* Send to Peer */
            }

            @Override
            public void onAddStream(final MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                Log.d(TAG, "Adding remote audio stream to the view");
                addRemoteStreamToView(mediaStream);
            }

            @Override
            public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
                super.onIceConnectionChange(iceConnectionState);
                iceConnectionStateChangedHandler.accept(iceConnectionState);
            }
        });

        addStreamToLocalPeer();
    }

    private Message createIceCandidateMessage(final IceCandidate iceCandidate) {
        final String sdpMid = iceCandidate.sdpMid;
        final int sdpMLineIndex = iceCandidate.sdpMLineIndex;
        final String sdp = iceCandidate.sdp;

        final String messagePayload =
            "{\"candidate\":\""
                    + sdp
                    + "\",\"sdpMid\":\""
                    + sdpMid
                    + "\",\"sdpMLineIndex\":"
                    + sdpMLineIndex
                    + "}";

        final String senderClientId = "";
        return new Message("ICE_CANDIDATE", recipientClientId, senderClientId,
            new String(Base64.encode(messagePayload.getBytes(),
                    Base64.URL_SAFE | Base64.NO_WRAP)));
    }

    private void addStreamToLocalPeer() {
        final MediaStream stream = peerConnectionFactory.createLocalMediaStream(LOCAL_MEDIA_STREAM_LABEL);
        if (!stream.addTrack(localAudioTrack)) {
            Log.e(TAG, "Add audio track failed");
        }

        if (stream.audioTracks.size() > 0) {
            localPeer.addTrack(stream.audioTracks.get(0), Collections.singletonList(stream.getId()));
            Log.d(TAG, "Sending audio track");
        }
    }

    // when local is set to be the master
    private void createSdpAnswer(Consumer<Exception> sdpAnswerErrorHandler) {
        final MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        localPeer.createAnswer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(final SessionDescription sessionDescription) {
                Log.d(TAG, "Creating answer: success");
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message answer = Message.createAnswerMessage(sessionDescription, recipientClientId);
                client.sendSdpAnswer(answer);

                peerConnectionFoundMap.put(recipientClientId, localPeer);
                handlePendingIceCandidates(recipientClientId);
            }

            @Override
            public void onCreateFailure(final String error) {
                super.onCreateFailure(error);

                // Device is unable to support the requested media format
                if (error.contains("ERROR_CONTENT")) {
                    String codecError = "No supported codec is present in the offer!";
                    Log.e(TAG, codecError);
                    sdpAnswerErrorHandler.accept(new Exception(codecError));
                } else {
                    sdpAnswerErrorHandler.accept(new Exception(error));
                }
            }
        }, sdpMediaConstraints);
    }

    private void addRemoteStreamToView(MediaStream stream) {
        AudioTrack remoteAudioTrack = stream.audioTracks != null && stream.audioTracks.size() > 0 ? stream.audioTracks.get(0) : null;

        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(true);
            Log.d(TAG, "remoteAudioTrack received: State=" + remoteAudioTrack.state().name());
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(true);
        }
    }

    public void onDestroy() {
        audioManager.setMode(originalAudioMode);
        audioManager.setSpeakerphoneOn(originalSpeakerphoneOn);

        if (rootEglBase != null) {
            rootEglBase.release();
            rootEglBase = null;
        }

        if (localPeer != null) {
            localPeer.dispose();
            localPeer = null;
        }

        if (client != null) {
            client.disconnect();
            client = null;
        }
        peerConnectionFoundMap.clear();
        pendingIceCandidatesMap.clear();
    }

    /**
     * Constructs and returns signed URL for the specified endpoint.
     *
     * @param endpoint The websocket endpoint (master or viewer endpoint)
     * @return A signed URL. {@code null} if there was an issue fetching credentials.
     */
    private URI getSignedUri(final AWSCredentials mCreds, final String endpoint) throws Exception {
        final String accessKey = mCreds.getAWSAccessKeyId();
        final String secretKey = mCreds.getAWSSecretKey();
        final String sessionToken = Optional.of(mCreds)
                .filter(creds -> creds instanceof AWSSessionCredentials)
                .map(awsCredentials -> (AWSSessionCredentials) awsCredentials)
                .map(AWSSessionCredentials::getSessionToken)
                .orElse("");

        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            // TODO: Make a custom exception
            throw new Exception("Failed to fetch credentials!");
        }

        return AwsV4Signer.sign(
                URI.create(endpoint),
                accessKey,
                secretKey,
                sessionToken,
                URI.create(mWssEndpoint),
                mRegion,
                new Date().getTime()
        );
    }
}
