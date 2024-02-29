package com.amazonaws.kinesisvideo.service.webrtc;

import android.content.Context;
import android.media.AudioManager;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.service.webrtc.connection.AbstractClientConnection;
import com.amazonaws.kinesisvideo.service.webrtc.connection.BroadcastClientConnection;
import com.amazonaws.kinesisvideo.service.webrtc.connection.ListenerClientConnection;
import com.amazonaws.kinesisvideo.service.webrtc.exception.AWSKinesisVideoClientCreationException;
import com.amazonaws.kinesisvideo.service.webrtc.exception.ChannelDetailsException;
import com.amazonaws.kinesisvideo.service.webrtc.factory.PeerConnectionFactoryBuilder;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDescription;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.service.webrtc.model.ServiceStateChange;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import org.apache.commons.lang3.StringUtils;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.EglBase;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class WebRtcService {
    private static final String AUDIO_TRACK_ID = "WebRtcServiceTrackId";

    protected PeerConnectionFactory peerConnectionFactory;
    protected String region;
    protected AudioManager audioManager;
    protected int originalAudioMode;
    protected boolean originalSpeakerphoneOn;
    protected EglBase rootEglBase;
    private Optional<BroadcastClientConnection> maybeBroadcastClient = Optional.empty();
    private final Map<String, ListenerClientConnection> listenerClients = new ConcurrentHashMap<>();
    private Consumer<ServiceStateChange> stateChangeCallback;

    private boolean broadcastRunning;
    private final AudioTrack audioTrack;

    private String username;
    private final List<String> remoteUsernames = new ArrayList<>();
    private boolean mute = true;

    private final Map<ChannelDescription, LocalDateTime> lastConnectedTime = new ConcurrentHashMap<>();

    private final AwsManager awsManager;

    public WebRtcService(
        Context context,
        AudioManager audioManager,
        Consumer<ServiceStateChange> stateChangeCallBack
    ) throws Exception {
        this.audioManager = audioManager;
        this.originalAudioMode = audioManager.getMode();
        this.originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
        this.rootEglBase = EglBase.create();
        this.stateChangeCallback = stateChangeCallBack;
        this.peerConnectionFactory = PeerConnectionFactoryBuilder.build(context, rootEglBase);
        this.audioTrack = getAudioTrack();

        try {
            AWSCredentials credentials = AWSMobileClient.getInstance().getCredentials();
            awsManager = new AwsManager(credentials, region);
        } catch (Exception e) {
            throw new AWSKinesisVideoClientCreationException(e);
        }

        /*
        Timer healthMonitor = new Timer();
        healthMonitor.schedule(new TimerTask() {
           @Override
           public void run() {
               checkConnectionHealth();
           }
        }, 0, 10000);*/
    }

    public void onDestroy() {
        resetAudioManager();

        if (rootEglBase != null) {
            rootEglBase.release();
            rootEglBase = null;
        }

        maybeBroadcastClient.ifPresent(AbstractClientConnection::onDestroy);
        listenerClients.values().forEach(AbstractClientConnection::onDestroy);
    }

    public void setUsername(String username) { this.username = username; }

    public boolean broadcastRunning() {
        return broadcastRunning;
    }

    public void startBroadcast() {
        if (StringUtils.isEmpty(username)) {
            return;
        }

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        ChannelDetails channelDetails = null;
        try {
            channelDetails = awsManager.getChannelDetails(region, username, ChannelRole.MASTER);
        } catch (Exception e) {
            stateChangeObserverAndForwarder.accept(ServiceStateChange.exception(null, new ChannelDetailsException(e)));
        }

        try {
            maybeBroadcastClient = Optional.of(
                new BroadcastClientConnection(
                    peerConnectionFactory,
                    channelDetails,
                    audioTrack,
                    stateChangeObserverAndForwarder
                )
            );
            maybeBroadcastClient.get().initWsConnection(awsManager.getCredentials());
            audioTrack.setEnabled(!mute);
        } catch (Exception e) {
            broadcastRunning = false;
            stateChangeObserverAndForwarder.accept(ServiceStateChange.exception(channelDetails, e));
        }

        remoteUsernames.forEach(this::startListener);
    }

    public void stopBroadcast() {
        disconnect();

        maybeBroadcastClient.ifPresent(c -> {
            c.onDestroy();
            stateChangeObserverAndForwarder.accept(ServiceStateChange.close(c.getChannelDetails()));
        });
        audioTrack.setEnabled(false);
        resetAudioManager();
        broadcastRunning = false;
        maybeBroadcastClient = Optional.empty();
    }

    public void addRemoteUserToConference(String remoteUsername) {
        remoteUsernames.add(remoteUsername);

        if (broadcastRunning) {
            startListener(remoteUsername);
        }
    }

    public void removeRemoteUserFromConference(String remoteUsername) {
        remoteUsernames.remove(remoteUsername);
        Optional.ofNullable(listenerClients.get(remoteUsername)).ifPresent(AbstractClientConnection::onDestroy);
        listenerClients.remove(remoteUsername);
    }

    public void disconnect() {
        for (PeerManager peerManager : getListenersConnectedToBroadcast()) {
            peerManager.getPeerConnection().ifPresent(PeerConnection::close);
        }

        for (PeerManager peerManager : getRemoteBroadcastsListeningTo()) {
            peerManager.getPeerConnection().ifPresent(PeerConnection::close);
        }
    }

    private AudioTrack getAudioTrack() {
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        return peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
    }

    private void resetAudioManager() {
        audioManager.setMode(originalAudioMode);
        audioManager.setSpeakerphoneOn(originalSpeakerphoneOn);
    }

    private void startListener(String remoteUsername) {
        if (StringUtils.isAnyBlank(remoteUsername, username)) {
            return;
        }

        ChannelDetails channelDetails = null;
        try {
            channelDetails = awsManager.getChannelDetails(region, remoteUsername, ChannelRole.VIEWER);
        } catch (Exception e) {
            stateChangeCallback.accept(ServiceStateChange.exception(null, new ChannelDetailsException(e)));
        }

        try {
            ListenerClientConnection connection = new ListenerClientConnection(
                peerConnectionFactory,
                channelDetails,
                username,
                stateChangeObserverAndForwarder
            );

            connection.initWsConnection(awsManager.getCredentials());
            listenerClients.put(remoteUsername, connection);
        } catch (Exception e) {
            stateChangeObserverAndForwarder.accept(ServiceStateChange.exception(channelDetails, e));
        }
    }

    private final Consumer<ServiceStateChange> stateChangeObserverAndForwarder = webRtcServiceStateChange -> {
        ChannelDetails channelDetails = webRtcServiceStateChange.getChannelDetails();
        if (channelDetails != null) {
            if (channelDetails.getRole() == ChannelRole.MASTER) {
                broadcastRunning = maybeBroadcastClient.map(AbstractClientConnection::isValidClient).orElse(false);
            }

            if (broadcastRunning &&
                channelDetails.getRole() == ChannelRole.VIEWER &&
                remoteUsernames.contains(channelDetails.getChannelName()) &&
                Arrays.asList(
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED,
                        PeerConnection.IceConnectionState.DISCONNECTED
                ).contains(webRtcServiceStateChange.getIceConnectionState())
            ) {
                // Try to reconnect
                startListener(channelDetails.getChannelName());
            }

            if (webRtcServiceStateChange.getIceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
                !lastConnectedTime.containsKey(channelDetails.getChannelDescription())
            ) {
                lastConnectedTime.put(channelDetails.getChannelDescription(), LocalDateTime.now());
            }
        }

        stateChangeCallback.accept(webRtcServiceStateChange);
    };

    public String getBroadcastChannelName() {
        return maybeBroadcastClient.map(c -> c.getChannelDetails().getChannelName()).orElse("");
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

    public void setMute(boolean mute) {
        this.mute = mute;
        audioTrack.setEnabled(!mute);
    }
}
