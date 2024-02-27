package com.amazonaws.kinesisvideo.demoapp.service;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
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

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WebRtcService {
    private static final String TAG = "WebRtcService";
    private static final String[] SUPPORTED_PROTOCOLS = new String[]{"WSS", "HTTPS"};
    private static final String AUDIO_TRACK_ID = "WebRtcServiceTrackId";

    private final AWSKinesisVideoClient awsKinesisVideoClient;
    protected PeerConnectionFactory peerConnectionFactory;
    protected String region;
    protected AudioManager audioManager;
    protected int originalAudioMode;
    protected boolean originalSpeakerphoneOn;
    protected EglBase rootEglBase;

    private final Map<ChannelDescription, ChannelDetails> channels = new ConcurrentHashMap<>();
    private Optional<WebRtcBroadcastClientConnection> maybeBroadcastClient = Optional.empty();
    private final Map<String, WebRtcListenerClientConnection> listenerClients = new ConcurrentHashMap<>();
    private Consumer<WebRtcServiceStateChange> stateChangeCallback;

    private boolean broadcastRunning;
    private final AudioTrack audioTrack;
    private final AWSCredentials creds;

    public WebRtcService(
        Context context,
        AWSCredentials creds,
        String region,
        AudioManager audioManager,
        Consumer<WebRtcServiceStateChange> stateChangeCallBack
    ) throws Exception {
        this.region = region;
        this.audioManager = audioManager;
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
        this.rootEglBase = EglBase.create();
        this.stateChangeCallback = stateChangeCallBack;
        this.creds = creds;

        try {
            awsKinesisVideoClient = getAwsKinesisVideoClient(region);
        } catch (Exception e) {
            // TODO: Better exceptions
            throw new Exception("Create client failed with " + e.getMessage());
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

        this.audioManager = audioManager;
        originalAudioMode = audioManager.getMode();
        originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
    }

    /**
     * Constructs and returns signed URL for the specified endpoint.
     *
     * @param endpoint The websocket endpoint (master or viewer endpoint)
     * @return A signed URL. {@code null} if there was an issue fetching credentials.
     */
    public static URI getSignedUri(ChannelDetails channelDetails, AWSCredentials creds, String endpoint) throws Exception {
        final String accessKey = creds.getAWSAccessKeyId();
        final String secretKey = creds.getAWSSecretKey();
        final String sessionToken = Optional.of(creds)
                .filter(c -> c instanceof AWSSessionCredentials)
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
                URI.create(channelDetails.getWssEndpoint()),
                channelDetails.getRegion(),
                new Date().getTime()
        );
    }

    public ChannelDetails getChannelDetails(String region, String channelName, ChannelRole role) throws Exception {
        final ChannelDescription channelDescription = new ChannelDescription(region, channelName, role);
        if (channels.containsKey(channelDescription)) {
            return channels.get(channelDescription);
        }

        final String channelArn = getChannelArn(channelName, role);
        final List<ResourceEndpointListItem> endpointList = getEndPointList(channelArn, role);
        final List<IceServer> iceServerList = getIceServerList(region, channelArn, role, endpointList);
        ChannelDetails channelDetails = new ChannelDetails(channelDescription, channelArn, endpointList, iceServerList);
        channels.put(channelDescription, channelDetails);

        return channelDetails;
    }

    private String getChannelArn(String channelName, ChannelRole role) throws Exception {
        // Use the Kinesis Video Client to call DescribeSignalingChannel API.
        //  If that fails with ResourceNotFoundException, the channel does not exist.
        //  If we are connecting as Master, if it doesn't exist, we attempt to create
        //  it by calling CreateSignalingChannel API.
        try {
            final DescribeSignalingChannelResult describeSignalingChannelResult = awsKinesisVideoClient.describeSignalingChannel(
                new DescribeSignalingChannelRequest()
                        .withChannelName(channelName));

            Log.i(TAG, "Channel ARN is " + describeSignalingChannelResult.getChannelInfo().getChannelARN());
            return describeSignalingChannelResult.getChannelInfo().getChannelARN();
        } catch (final ResourceNotFoundException e) {
            if (role.equals(ChannelRole.MASTER)) {
                try {
                    CreateSignalingChannelResult createSignalingChannelResult = awsKinesisVideoClient.createSignalingChannel(
                        new CreateSignalingChannelRequest()
                            .withChannelName(channelName));

                    return createSignalingChannelResult.getChannelARN();
                } catch (Exception ex) {
                    throw new Exception("Create Signaling Channel failed with Exception " + ex.getMessage());
                }
            } else {
                throw new Exception("Signaling Channel " + channelName + " doesn't exist!");
            }
        } catch (Exception ex) {
            throw new Exception("Describe Signaling Channel failed with Exception " + ex);
        }
    }

    private List<ResourceEndpointListItem> getEndPointList(String channelArn, ChannelRole role) throws Exception {
        //  Use the Kinesis Video Client to call GetSignalingChannelEndpoint.
        //  Each signaling channel is assigned an HTTPS and WSS endpoint to connect
        //  to for data-plane operations, which we fetch using the GetSignalingChannelEndpoint API,
        //  and a WEBRTC endpoint to for storage data-plane operations.
        //  Attempting to obtain the WEBRTC endpoint if the signaling channel is not configured
        //  will result in an InvalidArgumentException.
        try {
            final GetSignalingChannelEndpointResult getSignalingChannelEndpointResult = awsKinesisVideoClient.getSignalingChannelEndpoint(
                new GetSignalingChannelEndpointRequest()
                    .withChannelARN(channelArn)
                    .withSingleMasterChannelEndpointConfiguration(
                        new SingleMasterChannelEndpointConfiguration()
                            .withProtocols(SUPPORTED_PROTOCOLS)
                            .withRole(role)));
            Log.i(TAG, "Endpoints " + getSignalingChannelEndpointResult.toString());
            return getSignalingChannelEndpointResult.getResourceEndpointList();
        } catch (Exception e) {
            throw new Exception("Get Signaling Endpoint failed with Exception " + e.getMessage());
        }
    }

    private List<IceServer> getIceServerList(
        String region,
        String channelArn,
        ChannelRole role,
        List<ResourceEndpointListItem> endpointList
    ) throws Exception {
        String dataEndpoint = null;
        for (ResourceEndpointListItem endpoint : endpointList) {
            if (endpoint.getProtocol().equals("HTTPS")) {
                dataEndpoint = endpoint.getResourceEndpoint();
            }
        }

        //  Construct the Kinesis Video Signaling Client. The HTTPS endpoint from the
        //  GetSignalingChannelEndpoint response above is used with this client. This
        //  client is just used for getting ICE servers, not for actual signaling.
        //  Call GetIceServerConfig in order to obtain TURN ICE server info.
        //  Note: the STUN endpoint will be `stun:stun.kinesisvideo.${region}.amazonaws.com:443`
        try {
            final AWSKinesisVideoSignalingClient awsKinesisVideoSignalingClient = getAwsKinesisVideoSignalingClient(region, dataEndpoint);
            GetIceServerConfigResult getIceServerConfigResult = awsKinesisVideoSignalingClient.getIceServerConfig(
                new GetIceServerConfigRequest().withChannelARN(channelArn).withClientId(role.name()));
            return getIceServerConfigResult.getIceServerList();
        } catch (Exception e) {
            throw new Exception("Get Ice Server Config failed with Exception " + e.getMessage());
        }
    }

    private AWSKinesisVideoClient getAwsKinesisVideoClient(final String region) {
        final AWSKinesisVideoClient awsKinesisVideoClient = new AWSKinesisVideoClient(creds);

        awsKinesisVideoClient.setRegion(Region.getRegion(region));
        awsKinesisVideoClient.setSignerRegionOverride(region);
        awsKinesisVideoClient.setServiceNameIntern("kinesisvideo");
        return awsKinesisVideoClient;
    }

    private AWSKinesisVideoSignalingClient getAwsKinesisVideoSignalingClient(final String region, final String endpoint) {
        final AWSKinesisVideoSignalingClient client = new AWSKinesisVideoSignalingClient(creds);
        client.setRegion(Region.getRegion(region));
        client.setSignerRegionOverride(region);
        client.setServiceNameIntern("kinesisvideo");
        client.setEndpoint(endpoint);
        return client;
    }

    public void onDestroy() {
        resetAudioManager();

        if (rootEglBase != null) {
            rootEglBase.release();
            rootEglBase = null;
        }

        maybeBroadcastClient.ifPresent(WebRtcClientConnection::onDestroy);
        listenerClients.values().forEach(WebRtcClientConnection::onDestroy);
    }

    private void resetAudioManager() {
        audioManager.setMode(originalAudioMode);
        audioManager.setSpeakerphoneOn(originalSpeakerphoneOn);
    }

    public boolean broadcastRunning() {
        return broadcastRunning;
    }

    public void startBroadcast(String channelName) {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        ChannelDetails channelDetails = null;
        try {
            channelDetails = getChannelDetails(region, channelName, ChannelRole.MASTER);
        } catch (Exception e) {
            stateChangeObserverAndForwarder.accept(WebRtcServiceStateChange.exception(null, new WebRtcChannelDetailsException(e)));
        }

        try {
            maybeBroadcastClient = Optional.of(
                new WebRtcBroadcastClientConnection(
                    peerConnectionFactory,
                    channelDetails,
                    audioTrack,
                    stateChangeObserverAndForwarder
                )
            );
            maybeBroadcastClient.get().initWsConnection(creds);
            audioTrack.setEnabled(true);
        } catch (Exception e) {
            broadcastRunning = false;
            stateChangeObserverAndForwarder.accept(WebRtcServiceStateChange.exception(channelDetails, e));
        }
    }

    public void stopBroadcast() {
        maybeBroadcastClient.ifPresent(c -> {
            c.onDestroy();
            stateChangeObserverAndForwarder.accept(WebRtcServiceStateChange.close(c.channelDetails));
        });
        audioTrack.setEnabled(false);
        resetAudioManager();
        broadcastRunning = false;
        maybeBroadcastClient = Optional.empty();
    }

    public void startListener(String channelName, String clientId) {
        ChannelDetails channelDetails = null;
        try {
            channelDetails = getChannelDetails(region, channelName, ChannelRole.VIEWER);
        } catch (Exception e) {
            stateChangeCallback.accept(WebRtcServiceStateChange.exception(null, new WebRtcChannelDetailsException(e)));
        }

        try {
            WebRtcListenerClientConnection connection = new WebRtcListenerClientConnection(
                peerConnectionFactory,
                channelDetails,
                clientId,
                stateChangeObserverAndForwarder
            );

            connection.initWsConnection(creds);
            listenerClients.put(channelName, connection);
        } catch (Exception e) {
            stateChangeObserverAndForwarder.accept(WebRtcServiceStateChange.exception(channelDetails, e));
        }
    }

    private final Consumer<WebRtcServiceStateChange> stateChangeObserverAndForwarder = webRtcServiceStateChange -> {
        if (
            webRtcServiceStateChange.getChannelDetails() != null &&
            webRtcServiceStateChange.getChannelDetails().getRole() == ChannelRole.MASTER
        ) {
            broadcastRunning = maybeBroadcastClient.map(WebRtcClientConnection::isValidClient).orElse(false);
        }
        stateChangeCallback.accept(webRtcServiceStateChange);
    };

    public String getBroadcastChannelName() {
        return maybeBroadcastClient.map(c -> c.channelDetails.getChannelName()).orElse("");
    }

    public List<PeerManager> getListenersConnectedToBroadcast() {
        return maybeBroadcastClient
            .map(e -> e.getPeerStatus().stream()).orElse(Stream.empty())
            .collect(Collectors.toList());
    }

    public List<PeerManager> getRemoteBroadcastsListeningTo() {
        return listenerClients
            .values().stream()
                .flatMap(e -> e.getPeerStatus().stream())
                .collect(Collectors.toList());
    }
}
