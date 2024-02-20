package com.amazonaws.kinesisvideo.demoapp.service;

import android.content.Context;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.signaling.SignalingListener;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.amazonaws.regions.Region;
import com.amazonaws.services.kinesisvideo.AWSKinesisVideoClient;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelResult;
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelResult;
import com.amazonaws.services.kinesisvideo.model.GetSignalingChannelEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetSignalingChannelEndpointResult;
import com.amazonaws.services.kinesisvideo.model.ResourceEndpointListItem;
import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException;
import com.amazonaws.services.kinesisvideo.model.SingleMasterChannelEndpointConfiguration;
import com.amazonaws.services.kinesisvideosignaling.AWSKinesisVideoSignalingClient;
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigRequest;
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigResult;
import com.amazonaws.services.kinesisvideosignaling.model.IceServer;

import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public abstract class WebRtc {
    private static final String TAG = "WebRtc";

    private final List<ResourceEndpointListItem> mEndpointList = new ArrayList<>();
    private final List<IceServer> mIceServerList = new ArrayList<>();

    protected static volatile SignalingServiceWebSocketClient client;
    protected final List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();
    /**
     * Mapping of established peer connections to the peer's sender id. In other words, if an SDP
     * offer/answer for a peer connection has been received and sent, the PeerConnection is added
     * to this map.
     */
    protected final HashMap<String, PeerConnection> peerConnectionFoundMap = new HashMap<>();
    /**
     * Only used when we are master. Mapping of the peer's sender id to its received ICE candidates.
     * Since we can receive ICE Candidates before we have sent the answer, we hold ICE candidates in
     * this queue until after we send the answer and the peer connection is established.
     */
    private final HashMap<String, Queue<IceCandidate>> pendingIceCandidatesMap = new HashMap<>();
    protected PeerConnectionFactory peerConnectionFactory;
    protected String mChannelArn;
    protected String mWssEndpoint;
    protected String mRegion;
    protected AudioManager audioManager;
    protected int originalAudioMode;
    protected boolean originalSpeakerphoneOn;
    protected EglBase rootEglBase;
    protected PeerConnection localPeer;

    public WebRtc(
        Context context,
        String mRegion,
        String channelName,
        ChannelRole role,
        AudioManager audioManager
    ) throws Exception {
        this.mRegion = "ca-central-1";
        this.audioManager = audioManager;
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
        this.rootEglBase = EglBase.create();

        configureChannel(mRegion, channelName, role);

        for (ResourceEndpointListItem endpoint : mEndpointList) {
            if (endpoint.getProtocol().equals("WSS")) {
                this.mWssEndpoint = endpoint.getResourceEndpoint();
            }
        }

        final PeerConnection.IceServer stun = PeerConnection.IceServer
            .builder(String.format("stun:stun.kinesisvideo.%s.amazonaws.com:443", mRegion))
            .createIceServer();

        peerIceServers.add(stun);

        for (IceServer iceServer : mIceServerList) {
            final String turnServer = iceServer.getUris().toString();
            final PeerConnection.IceServer peerIceServer = PeerConnection.IceServer.builder(turnServer.replace("[", "").replace("]", ""))
                .setUsername(iceServer.getUsername())
                .setPassword(iceServer.getPassword())
                .createIceServer();

            Log.d(getTag(), "IceServer details (TURN) = " + peerIceServer.toString());
            peerIceServers.add(peerIceServer);
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

        this.audioManager = audioManager;
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
    }

    public boolean isValidClient() {
        return client != null && client.isOpen();
    }

    public void configureChannel(String region, String channelName, ChannelRole role) throws Exception {
        final AWSKinesisVideoClient awsKinesisVideoClient;
        try {
            awsKinesisVideoClient = getAwsKinesisVideoClient(region);
        } catch (Exception e) {
            // TODO: Better exceptions
            throw new Exception("Create client failed with " + e.getMessage());
        }

        // Step 2. Use the Kinesis Video Client to call DescribeSignalingChannel API.
        //         If that fails with ResourceNotFoundException, the channel does not exist.
        //         If we are connecting as Master, if it doesn't exist, we attempt to create
        //         it by calling CreateSignalingChannel API.
        try {
            final DescribeSignalingChannelResult describeSignalingChannelResult = awsKinesisVideoClient.describeSignalingChannel(
                    new DescribeSignalingChannelRequest()
                            .withChannelName(channelName));

            Log.i(TAG, "Channel ARN is " + describeSignalingChannelResult.getChannelInfo().getChannelARN());
            mChannelArn = describeSignalingChannelResult.getChannelInfo().getChannelARN();
        } catch (final ResourceNotFoundException e) {
            if (role.equals(ChannelRole.MASTER)) {
                try {
                    CreateSignalingChannelResult createSignalingChannelResult = awsKinesisVideoClient.createSignalingChannel(
                            new CreateSignalingChannelRequest()
                                    .withChannelName(channelName));

                    mChannelArn = createSignalingChannelResult.getChannelARN();
                } catch (Exception ex) {
                    throw new Exception("Create Signaling Channel failed with Exception " + ex.getMessage());
                }
            } else {
                throw new Exception("Signaling Channel " + channelName + " doesn't exist!");
            }
        } catch (Exception ex) {
            throw new Exception("Describe Signaling Channel failed with Exception " + ex);
        }

        final String[] protocols = new String[]{"WSS", "HTTPS"};

        // Step 4. Use the Kinesis Video Client to call GetSignalingChannelEndpoint.
        //         Each signaling channel is assigned an HTTPS and WSS endpoint to connect
        //         to for data-plane operations, which we fetch using the GetSignalingChannelEndpoint API,
        //         and a WEBRTC endpoint to for storage data-plane operations.
        //         Attempting to obtain the WEBRTC endpoint if the signaling channel is not configured
        //         will result in an InvalidArgumentException.
        try {
            final GetSignalingChannelEndpointResult getSignalingChannelEndpointResult = awsKinesisVideoClient.getSignalingChannelEndpoint(
                new GetSignalingChannelEndpointRequest()
                    .withChannelARN(mChannelArn)
                    .withSingleMasterChannelEndpointConfiguration(
                        new SingleMasterChannelEndpointConfiguration()
                            .withProtocols(protocols)
                            .withRole(role)));

            Log.i(TAG, "Endpoints " + getSignalingChannelEndpointResult.toString());
            mEndpointList.addAll(getSignalingChannelEndpointResult.getResourceEndpointList());
        } catch (Exception e) {
            throw new Exception("Get Signaling Endpoint failed with Exception " + e.getMessage());
        }

        String dataEndpoint = null;
        for (ResourceEndpointListItem endpoint : mEndpointList) {
            if (endpoint.getProtocol().equals("HTTPS")) {
                dataEndpoint = endpoint.getResourceEndpoint();
            }
        }

        // Step 5. Construct the Kinesis Video Signaling Client. The HTTPS endpoint from the
        //         GetSignalingChannelEndpoint response above is used with this client. This
        //         client is just used for getting ICE servers, not for actual signaling.
        // Step 6. Call GetIceServerConfig in order to obtain TURN ICE server info.
        //         Note: the STUN endpoint will be `stun:stun.kinesisvideo.${region}.amazonaws.com:443`
        try {
            final AWSKinesisVideoSignalingClient awsKinesisVideoSignalingClient = getAwsKinesisVideoSignalingClient(region, dataEndpoint);
            GetIceServerConfigResult getIceServerConfigResult = awsKinesisVideoSignalingClient.getIceServerConfig(
                    new GetIceServerConfigRequest().withChannelARN(mChannelArn).withClientId(role.name()));
            mIceServerList.addAll(getIceServerConfigResult.getIceServerList());
        } catch (Exception e) {
            throw new Exception("Get Ice Server Config failed with Exception " + e.getMessage());
        }
    }

    private AWSKinesisVideoClient getAwsKinesisVideoClient(final String region) {
        final AWSKinesisVideoClient awsKinesisVideoClient = new AWSKinesisVideoClient(
                KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials());
        awsKinesisVideoClient.setRegion(Region.getRegion(region));
        awsKinesisVideoClient.setSignerRegionOverride(region);
        awsKinesisVideoClient.setServiceNameIntern("kinesisvideo");
        return awsKinesisVideoClient;
    }

    private AWSKinesisVideoSignalingClient getAwsKinesisVideoSignalingClient(final String region, final String endpoint) {
        final AWSKinesisVideoSignalingClient client = new AWSKinesisVideoSignalingClient(
                KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials());
        client.setRegion(Region.getRegion(region));
        client.setSignerRegionOverride(region);
        client.setServiceNameIntern("kinesisvideo");
        client.setEndpoint(endpoint);
        return client;
    }

    public void initWsConnection(
        AWSCredentials mCreds,
        Consumer<Exception> signallingListeningExceptionHandler,
        Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler
    ) throws Exception {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
        final String endpoint = buildEndPointUri();
        final URI signedUri = getSignedUri(mCreds, endpoint);

        createLocalPeerConnection(iceConnectionStateChangedHandler);
        final String wsHost = signedUri.toString();

        // Step 10. Create Signaling Client Event Listeners.
        //          When we receive messages, we need to take the appropriate action.
        final SignalingListener signalingListener = new SignalingListener() {
            @Override
            public void onSdpOffer(final Event offerEvent) {
                handleSdpOffer(offerEvent, signallingListeningExceptionHandler);
            }

            @Override
            public void onSdpAnswer(final Event answerEvent) {
                Log.d(getTag(), "SDP answer received from signaling");
                final String sdp = Event.parseSdpEvent(answerEvent);
                final SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);

                localPeer.setRemoteDescription(new KinesisVideoSdpObserver() {
                    @Override
                    public void onCreateFailure(final String error) {
                        super.onCreateFailure(error);
                    }
                }, sdpAnswer);
                Log.d(getTag(), "Answer Client ID: " + answerEvent.getSenderClientId());
                peerConnectionFoundMap.put(answerEvent.getSenderClientId(), localPeer);
                // Check if ICE candidates are available in the queue and add the candidate
                handlePendingIceCandidates(answerEvent.getSenderClientId());
            }

            @Override
            public void onIceCandidate(final Event message) {
                Log.d(getTag(), "Received ICE candidate from remote");
                final IceCandidate iceCandidate = Event.parseIceCandidate(message);
                if (iceCandidate != null) {
                    checkAndAddIceCandidate(message, iceCandidate);
                } else {
                    Log.e(getTag(), "Invalid ICE candidate: " + message);
                }
            }

            @Override
            public void onError(final Event errorMessage) {
                Log.e(getTag(), "Received error message: " + errorMessage);
            }

            @Override
            public void onException(final Exception e) {
                Log.e(getTag(), "Signaling client returned exception: " + e.getMessage());
                signallingListeningExceptionHandler.accept(e);
            }
        };

        // Step 11. Create SignalingServiceWebSocketClient.
        //          This is the actual client that is used to send messages over the signaling channel.
        //          SignalingServiceWebSocketClient will attempt to open the connection in its constructor.
        try {
            client = new SignalingServiceWebSocketClient(wsHost, signalingListener, Executors.newFixedThreadPool(10));
            Log.d(getTag(), "Client connection " + (client.isOpen() ? "Successful" : "Failed"));
        } catch (final Exception e) {
            Log.e(getTag(), "Exception with websocket client: " + e);
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }

        if (isValidClient()) {
            Log.d(getTag(), "Client connected to Signaling service " + client.isOpen());
            onValidClient(signallingListeningExceptionHandler, iceConnectionStateChangedHandler);
        } else {
            Log.e(getTag(), "Error in connecting to signaling service");
            // TODO: Better exceptions
            throw new Exception("Error in connecting to signaling service");
        }
    }

    protected abstract void handleSdpOffer(Event offerEvent, Consumer<Exception> signallingListeningExceptionHandler);

    protected abstract void onValidClient(Consumer<Exception> signallingListeningExceptionHandler, Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler);

    protected abstract String buildEndPointUri();

    /**
     * Called once the peer connection is established. Checks the pending ICE candidate queue to see
     * if we have received any before we finished sending the SDP answer. If so, add those ICE
     * candidates to the peer connection belonging to this clientId.
     *
     * @param clientId The sender client id of the peer whose peer connection was just established.
     * @see #pendingIceCandidatesMap
     */
    protected void handlePendingIceCandidates(final String clientId) {
        // Add any pending ICE candidates from the queue for the client ID
        Log.d(getTag(), "Pending ice candidates found? " + pendingIceCandidatesMap.get(clientId));
        final Queue<IceCandidate> pendingIceCandidatesQueueByClientId = pendingIceCandidatesMap.get(clientId);
        while (pendingIceCandidatesQueueByClientId != null && !pendingIceCandidatesQueueByClientId.isEmpty()) {
            final IceCandidate iceCandidate = pendingIceCandidatesQueueByClientId.peek();
            final PeerConnection peer = peerConnectionFoundMap.get(clientId);
            final boolean addIce = peer.addIceCandidate(iceCandidate);
            Log.d(getTag(), "Added ice candidate after SDP exchange " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
            pendingIceCandidatesQueueByClientId.remove();
        }
        // After sending pending ICE candidates, the client ID's peer connection need not be tracked
        pendingIceCandidatesMap.remove(clientId);
    }

    private void checkAndAddIceCandidate(final Event message, final IceCandidate iceCandidate) {
        // If answer/offer is not received, it means peer connection is not found. Hold the received ICE candidates in the map.
        // Once the peer connection is found, add them directly instead of adding it to the queue.
        if (!peerConnectionFoundMap.containsKey(message.getSenderClientId())) {
            Log.d(getTag(), "SDP exchange is not complete. Ice candidate " + iceCandidate + " + added to pending queue");

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
            Log.d(getTag(), "Peer connection found already");
            // Remote sent us ICE candidates, add to local peer connection
            final PeerConnection peer = peerConnectionFoundMap.get(message.getSenderClientId());
            final boolean addIce = peer.addIceCandidate(iceCandidate);

            Log.d(getTag(), "Added ice candidate " + iceCandidate + " " + (addIce ? "Successfully" : "Failed"));
        }
    }

    protected void createLocalPeerConnection(Consumer<PeerConnection.IceConnectionState> iceConnectionStateChangedHandler) {
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
                Log.d(getTag(), "Sending IceCandidate to remote peer " + iceCandidate);
                client.sendIceCandidate(message);  /* Send to Peer */
            }

            @Override
            public void onAddStream(final MediaStream mediaStream) {
                super.onAddStream(mediaStream);
                Log.d(getTag(), "Adding remote audio stream to the view");
                addRemoteStreamToView(mediaStream);
            }

            @Override
            public void onIceConnectionChange(final PeerConnection.IceConnectionState iceConnectionState) {
                super.onIceConnectionChange(iceConnectionState);
                iceConnectionStateChangedHandler.accept(iceConnectionState);
            }
        });
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
        return new Message("ICE_CANDIDATE", getRecipientClientId(), senderClientId,
                new String(Base64.encode(messagePayload.getBytes(),
                        Base64.URL_SAFE | Base64.NO_WRAP)));
    }

    protected String getRecipientClientId() {
        return null;
    }

    private void addRemoteStreamToView(MediaStream stream) {
        AudioTrack remoteAudioTrack = stream.audioTracks != null && stream.audioTracks.size() > 0 ? stream.audioTracks.get(0) : null;

        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(true);
            Log.d(getTag(), "remoteAudioTrack received: State=" + remoteAudioTrack.state().name());
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
    
    public abstract String getTag();
}
