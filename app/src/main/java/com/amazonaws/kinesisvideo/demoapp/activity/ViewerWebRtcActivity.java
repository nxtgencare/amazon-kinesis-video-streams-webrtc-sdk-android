package com.amazonaws.kinesisvideo.demoapp.activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.signaling.SignalingListener;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
import com.amazonaws.kinesisvideo.utils.Constants;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoPeerConnection;
import com.amazonaws.kinesisvideo.webrtc.KinesisVideoSdpObserver;
import com.google.common.base.Strings;

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
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executors;

import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_CHANNEL_ARN;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_PASSWORD;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_URI;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_USER_NAME;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_WSS_ENDPOINT;

public class ViewerWebRtcActivity extends AppCompatActivity {
    private static final String TAG = "KVSViewerWebRtcActivity";
    private static final String CHANNEL_ID = "WebRtcDataChannel";

    private static volatile SignalingServiceWebSocketClient client;
    private PeerConnectionFactory peerConnectionFactory;

    private AudioManager audioManager;
    private int originalAudioMode;
    private boolean originalSpeakerphoneOn;

    private PeerConnection localPeer;

    private EglBase rootEglBase = null;

    private final List<IceServer> peerIceServers = new ArrayList<>();

    private boolean gotException = false;

    private int mNotificationId = 0;

    private TextView connectedStatusText = null;

    private String mChannelArn;
    private String mClientId;

    private String mWssEndpoint;
    private String mRegion;

    private AWSCredentials mCreds = null;

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

    private void initWsConnection() {
        // See https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/kvswebrtc-websocket-apis-1.html
        final String viewerEndpoint = mWssEndpoint + "?" + Constants.CHANNEL_ARN_QUERY_PARAM + "=" + mChannelArn + "&" + Constants.CLIENT_ID_QUERY_PARAM + "=" + mClientId;

        runOnUiThread(() -> mCreds = KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials());

        final URI signedUri = getSignedUri(viewerEndpoint);

        if (signedUri == null) {
            gotException = true;
            return;
        }

        final String wsHost = signedUri.toString();

        // Step 10. Create Signaling Client Event Listeners.
        //          When we receive messages, we need to take the appropriate action.
        final SignalingListener signalingListener = new SignalingListener() {

            @Override
            public void onSdpOffer(final Event offerEvent) {
                Log.d(TAG, "Viewer should not be receiving SDP Offer");
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
                gotException = true;
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
            gotException = true;
            return;
        }

        if (isValidClient()) {
            Log.d(TAG, "Client connected to Signaling service " + client.isOpen());
            Log.d(TAG, "Signaling service is connected: Sending offer as viewer to remote peer"); // Viewer
            createSdpOffer();
        } else {
            Log.e(TAG, "Error in connecting to signaling service");
            gotException = true;
        }
    }

    private boolean isValidClient() {
        return client != null && client.isOpen();
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

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);

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

        finish();

        super.onDestroy();
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Start websocket after adding local audio/video tracks
        initWsConnection();

        if (!gotException && isValidClient()) {
            Toast.makeText(this, "Signaling Connected", Toast.LENGTH_LONG).show();
        } else {
            notifySignalingConnectionFailed();
        }
    }

    private void notifySignalingConnectionFailed() {
        finish();
        Toast.makeText(this, "Connection error to signaling", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Intent intent = getIntent();
        mChannelArn = intent.getStringExtra(KEY_CHANNEL_ARN);
        mWssEndpoint = intent.getStringExtra(KEY_WSS_ENDPOINT);

        // If no client identifier is present, a random one will be created.
        if (Strings.isNullOrEmpty(mClientId)) {
            mClientId = UUID.randomUUID().toString();
        }

        ArrayList<String> mUserNames = intent.getStringArrayListExtra(KEY_ICE_SERVER_USER_NAME);
        ArrayList<String> mPasswords = intent.getStringArrayListExtra(KEY_ICE_SERVER_PASSWORD);
        ArrayList<List<String>> mUrisList = (ArrayList<List<String>>) intent.getSerializableExtra(KEY_ICE_SERVER_URI);
        mRegion = KinesisVideoWebRtcDemoApp.getRegion();
        rootEglBase = EglBase.create();

        final IceServer stun = IceServer
            .builder(String.format("stun:stun.kinesisvideo.%s.amazonaws.com:443", mRegion))
            .createIceServer();

        peerIceServers.add(stun);

        if (mUrisList != null) {
            for (int i = 0; i < mUrisList.size(); i++) {
                final String turnServer = mUrisList.get(i).toString();
                final IceServer iceServer = IceServer.builder(turnServer.replace("[", "").replace("]", ""))
                    .setUsername(mUserNames.get(i))
                    .setPassword(mPasswords.get(i))
                    .createIceServer();

                Log.d(TAG, "IceServer details (TURN) = " + iceServer.toString());
                peerIceServers.add(iceServer);
            }
        }

        setContentView(R.layout.activity_webrtc_main);

        PeerConnectionFactory.initialize(PeerConnectionFactory
            .InitializationOptions
            .builder(this)
            .createInitializationOptions());

        // codecs are mandatory even if we aren't using them.
        final VideoDecoderFactory vdf = new DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext());
        final VideoEncoderFactory vef = new DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true);

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(vdf)
            .setVideoEncoderFactory(vef)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(getApplicationContext()).createAudioDeviceModule())
            .createPeerConnectionFactory();

        // Enable Google WebRTC debug logs
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();

        connectedStatusText = findViewById(R.id.connectedStatus);
    }

    private void createLocalPeerConnection() {
        final Context context = this;
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
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Connection to peer failed!", Toast.LENGTH_LONG).show());
                } else if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Connected to peer!", Toast.LENGTH_LONG).show());
                }
            }

            @Override
            public void onDataChannel(final DataChannel dataChannel) {
                super.onDataChannel(dataChannel);

                dataChannel.registerObserver(new DataChannel.Observer() {
                    @Override
                    public void onBufferedAmountChange(long l) {
                        // no op on receiver side
                    }

                    @Override
                    public void onStateChange() {
                        Log.d(TAG, "Remote Data Channel onStateChange: state: " + dataChannel.state().toString());
                    }

                    @Override
                    public void onMessage(DataChannel.Buffer buffer) {
                        runOnUiThread(() -> {
                            final byte[] bytes;
                            if (buffer.data.hasArray()) {
                                bytes = buffer.data.array();
                            } else {
                                bytes = new byte[buffer.data.remaining()];
                                buffer.data.get(bytes);
                            }

                            final NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                                    .setSmallIcon(R.mipmap.ic_launcher)
                                    .setLargeIcon(BitmapFactory.decodeResource(getApplicationContext().getResources(),
                                        R.mipmap.ic_launcher))
                                    .setContentTitle("Message from Peer!")
                                    .setContentText(new String(bytes, Charset.defaultCharset()))
                                    .setPriority(NotificationCompat.PRIORITY_MAX)
                                    .setAutoCancel(true);
                            final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getApplicationContext());

                            // notificationId is a unique int for each notification that you must define
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                return;
                            }
                            notificationManager.notify(mNotificationId++, builder.build());

                            Toast.makeText(getApplicationContext(), "New message from peer, check notification.", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            }
        });

        addDataChannelToLocalPeer();
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

        final String senderClientId = mClientId;
        return new Message("ICE_CANDIDATE", null, senderClientId,
                new String(Base64.encode(messagePayload.getBytes(),
                        Base64.URL_SAFE | Base64.NO_WRAP)));
    }

    private void addDataChannelToLocalPeer() {
        Log.d(TAG, "Data channel addDataChannelToLocalPeer");
        final DataChannel localDataChannel = localPeer.createDataChannel("data-channel-of-" + mClientId, new DataChannel.Init());
        localDataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long l) {
                Log.d(TAG, "Local Data Channel onBufferedAmountChange called with amount " + l);
            }

            @Override
            public void onStateChange() {
                Log.d(TAG, "Local Data Channel onStateChange: state: " + localDataChannel.state().toString());

                if (connectedStatusText != null) {
                    runOnUiThread(() -> {
                        if (localDataChannel.state() == DataChannel.State.OPEN) {
                            connectedStatusText.setText("Connected");
                        } else {
                            connectedStatusText.setText("Disconnected");
                        }
                    });
                }
            }

            @Override
            public void onMessage(final DataChannel.Buffer buffer) {
                // Send out data, no op on sender side
            }
        });
    }

    // when mobile sdk is viewer
    private void createSdpOffer() {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

        if (localPeer == null) {
            createLocalPeerConnection();
        }

        localPeer.createOffer(new KinesisVideoSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                localPeer.setLocalDescription(new KinesisVideoSdpObserver(), sessionDescription);
                final Message sdpOfferMessage = Message.createOfferMessage(sessionDescription, mClientId);
                if (isValidClient()) {
                    client.sendSdpOffer(sdpOfferMessage);
                } else {
                    notifySignalingConnectionFailed();
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

    /**
     * Constructs and returns signed URL for the specified endpoint.
     *
     * @param endpoint The websocket endpoint (master or viewer endpoint)
     * @return A signed URL. {@code null} if there was an issue fetching credentials.
     */
    private URI getSignedUri(final String endpoint) {
        final String accessKey = mCreds.getAWSAccessKeyId();
        final String secretKey = mCreds.getAWSSecretKey();
        final String sessionToken = Optional.of(mCreds)
                .filter(creds -> creds instanceof AWSSessionCredentials)
                .map(awsCredentials -> (AWSSessionCredentials) awsCredentials)
                .map(AWSSessionCredentials::getSessionToken)
                .orElse("");

        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            Toast.makeText(this, "Failed to fetch credentials!", Toast.LENGTH_LONG).show();
            return null;
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
