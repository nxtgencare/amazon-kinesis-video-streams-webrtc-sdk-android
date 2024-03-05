package com.amazonaws.kinesisvideo.service.webrtc;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.websocket.DeploymentException;

public class WebRtcService {
    private static final String AUDIO_TRACK_ID = "WebRtcServiceTrackId";
    private static final String TAG = "WebRtcService";

    protected PeerConnectionFactory peerConnectionFactory;
    protected AudioManager audioManager;
    protected int originalAudioMode;
    protected boolean originalSpeakerphoneOn;
    protected EglBase rootEglBase;
    private Optional<BroadcastClientConnection> maybeBroadcastClient = Optional.empty();
    private final Map<String, ListenerClientConnection> listenerClients = new ConcurrentHashMap<>();
    private Consumer<ServiceStateChange> stateChangeCallback;

    private boolean broadcastRunning;
    private boolean broadcastEnabled;

    private final AudioTrack audioTrack;

    private String username;
    private final List<String> remoteUsernames = new ArrayList<>();
    private boolean mute = true;

    private AwsManager awsManager;

    public WebRtcService(
        Context context,
        AudioManager audioManager,
        Consumer<ServiceStateChange> stateChangeCallBack
    ) throws AWSKinesisVideoClientCreationException {
        this.audioManager = audioManager;
        this.originalAudioMode = audioManager.getMode();
        this.originalSpeakerphoneOn = audioManager.isSpeakerphoneOn();
        this.rootEglBase = EglBase.create();
        this.stateChangeCallback = stateChangeCallBack;
        this.peerConnectionFactory = PeerConnectionFactoryBuilder.build(context, rootEglBase);
        this.audioTrack = getAudioTrack();

        refreshCredentials();

        Timer healthMonitor = new Timer();
        healthMonitor.schedule(new TimerTask() {
           @Override
           public void run() {
               checkConnectionHealth();
           }
        }, 0, 10000);
    }

    private void checkConnectionHealth() {
        listenerClients.values().forEach(
            c -> c.getPeerManagers().forEach(m -> {
                String username = m.getChannelDescription().getChannelName();
                if (m.requiresRestart()) {
                    Log.d(TAG, String.format("Restarting %s listener connection", username));
                    c.resetPeer(username);

                    if (remoteUsernames.contains(username)) {
                        startListener(username);
                    } else {
                        stateChangeCallback.accept(ServiceStateChange.close(m.getChannelDescription()));
                    }
                }
            })
        );

        final List<String> peerManagersToRemove = new ArrayList<>();
        maybeBroadcastClient.ifPresent(c -> {
                c.getPeerManagers().stream()
                    .filter(PeerManager::requiresRestart)
                    .map(m -> m.getChannelDescription().getChannelName())
                    .forEach(username -> {
                        Log.d(TAG, String.format("Restarting %s broadcast connection", username));
                        c.resetPeer(username);
                        peerManagersToRemove.add(username);
                    });
                c.removePeers(peerManagersToRemove);
            }
        );

        if (!broadcastRunning && broadcastEnabled) {
            startBroadcast();
        }

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

    public boolean isBroadcastEnabled() {
        return broadcastEnabled;
    }

    public void startBroadcast() {
        broadcastEnabled = true;

        if (StringUtils.isEmpty(username)) {
            return;
        }

        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        ChannelDetails channelDetails = null;
        try {
            channelDetails = awsManager.getChannelDetails(username, ChannelRole.MASTER);
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
            stateChangeObserverAndForwarder.accept(ServiceStateChange.exception(channelDetails != null ? channelDetails.getChannelDescription() : null, e));
        }

        remoteUsernames.forEach(this::startListener);
    }

    public void stopBroadcast() {
        broadcastEnabled = false;

        for (PeerManager peerManager : getListenersConnectedToBroadcast()) {
            peerManager.getPeerConnection().ifPresent(PeerConnection::close);
        }

        maybeBroadcastClient.ifPresent(c -> {
            c.onDestroy();
            stateChangeObserverAndForwarder.accept(ServiceStateChange.close(c.getChannelDetails().getChannelDescription()));
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
        getListenerClient(remoteUsername).ifPresent(AbstractClientConnection::onDestroy);
        listenerClients.remove(remoteUsername);
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
            channelDetails = awsManager.getChannelDetails(remoteUsername, ChannelRole.VIEWER);
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
            stateChangeObserverAndForwarder.accept(ServiceStateChange.exception(channelDetails != null ? channelDetails.getChannelDescription() : null, e));
        }
    }

    private final Consumer<ServiceStateChange> stateChangeObserverAndForwarder = webRtcServiceStateChange -> {
        ChannelDescription channelDescription = webRtcServiceStateChange.getChannelDescription();
        if (channelDescription != null) {
            if (channelDescription.getRole() == ChannelRole.MASTER) {
                broadcastRunning = maybeBroadcastClient.map(AbstractClientConnection::isValidClient).orElse(false);
                maybeBroadcastClient
                    .flatMap(c -> c.getPeerManager(channelDescription.getChannelName()))
                    .ifPresent(m -> m.updateIceConnectionHistory(webRtcServiceStateChange.getIceConnectionState()));
            } else {
                getListenerClient(channelDescription.getChannelName())
                    .flatMap(c -> c.getPeerManager(channelDescription.getChannelName()))
                    .ifPresent(m -> m.updateIceConnectionHistory(webRtcServiceStateChange.getIceConnectionState()));
            }
        }

        if (webRtcServiceStateChange.getException().isPresent() && webRtcServiceStateChange.getException().get() instanceof DeploymentException) {
            DeploymentException de = (DeploymentException) webRtcServiceStateChange.getException().orElse(null);

            if (Optional.ofNullable(de.getMessage()).orElse("").contains("Handshake error")) {
                try {
                    refreshCredentials();
                } catch (AWSKinesisVideoClientCreationException ex) {
                    Log.e(TAG, "Exception trying to refresh credentials after handshake error", de);
                    Log.e(TAG, "Credentials exception", ex);
                    stateChangeCallback.accept(ServiceStateChange.exception(channelDescription, ex));
                }
            }
        }

        stateChangeCallback.accept(webRtcServiceStateChange);
    };

    private void refreshCredentials() throws AWSKinesisVideoClientCreationException {
        try {
            AWSCredentials credentials = AWSMobileClient.getInstance().getCredentials();
            awsManager = new AwsManager(credentials);
        } catch (Exception e) {
            throw new AWSKinesisVideoClientCreationException(e);
        }
    }

    private Optional<ListenerClientConnection> getListenerClient(String channelName) {
        return Optional.ofNullable(listenerClients.get(channelName));
    }

    public String getBroadcastChannelName() {
        return maybeBroadcastClient.map(c -> c.getChannelDetails().getChannelName()).orElse("");
    }

    public List<PeerManager> getListenersConnectedToBroadcast() {
        return maybeBroadcastClient
            .map(e -> e.getPeerManagers().stream()).orElse(Stream.empty())
            .collect(Collectors.toList());
    }

    public List<PeerManager> getRemoteBroadcastsListeningTo() {
        return listenerClients
            .values().stream()
                .flatMap(e -> e.getPeerManagers().stream())
                .collect(Collectors.toList());
    }

    public void setMute(boolean mute) {
        this.mute = mute;
        audioTrack.setEnabled(!mute);
    }
}
