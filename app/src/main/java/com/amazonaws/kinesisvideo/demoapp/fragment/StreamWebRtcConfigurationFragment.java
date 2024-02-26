package com.amazonaws.kinesisvideo.demoapp.fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.demoapp.adapters.PeerAdapter;
import com.amazonaws.kinesisvideo.demoapp.service.PeerManager;
import com.amazonaws.kinesisvideo.demoapp.service.WebRtcService;
import com.amazonaws.kinesisvideo.demoapp.service.WebRtcServiceStateChange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.amazonaws.mobile.auth.core.internal.util.ThreadUtils.runOnUiThread;

public class StreamWebRtcConfigurationFragment extends Fragment {
    private WebRtcService webRtcService;
    private Button startBroadcastButton;
    private Button addRemoteBroadcastListenerButton;

    private RecyclerView connectedListeners;
    private RecyclerView connectedBroadcasts;

    private TextView broadcastStatus;
    private EditText remoteBroadcastChannelName;

    private PeerAdapter broadcastAdapter;
    private PeerAdapter listenerAdapter;

    private final List<PeerManager> remoteListeners = new ArrayList<>();
    private final List<PeerManager> remoteBroadcasts = new ArrayList<>();

    private final AtomicReference<AWSCredentials> creds = new AtomicReference<>();
    private View view;

    public static StreamWebRtcConfigurationFragment newInstance() {
        return new StreamWebRtcConfigurationFragment();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        if (getActivity() != null) {
            if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this.getActivity(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this.getActivity(), new String[]{Manifest.permission.RECORD_AUDIO}, 9393);
            }

            getActivity().setTitle(getActivity().getString(R.string.title_fragment_channel));
        }

        return inflater.inflate(R.layout.fragment_stream_webrtc_configuration, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.view = view;

        runOnUiThread(() -> creds.set(KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials()));
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

        try {
            webRtcService = new WebRtcService(getActivity(), creds.get(),"ca-central-1", audioManager, this::webRtcServiceStateChange);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LinearLayoutManager viewersLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        LinearLayoutManager mastersLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);

        viewersLayoutManager.scrollToPosition(0);
        mastersLayoutManager.scrollToPosition(0);

        associateComponents();

        connectedListeners.setLayoutManager(viewersLayoutManager);
        connectedBroadcasts.setLayoutManager(mastersLayoutManager);

        listenerAdapter = new PeerAdapter(remoteListeners);
        broadcastAdapter = new PeerAdapter(remoteBroadcasts);

        connectedListeners.setAdapter(listenerAdapter);
        connectedBroadcasts.setAdapter(broadcastAdapter);

        startBroadcastButton.setOnClickListener(event -> {
            runOnUiThread(() -> {
                startBroadcastButton.setText(R.string.connecting);
                startBroadcastButton.setEnabled(false);
            });
            Thread thread = new Thread(() -> {
                if (webRtcService.masterRunning()) {
                    webRtcService.stopMaster();
                } else {
                    webRtcService.startMaster(getBroadcastChannelName());
                }
            });
            thread.start();
        });

        addRemoteBroadcastListenerButton.setOnClickListener(event -> {
            Thread thread = new Thread(() -> webRtcService.startListener(getRemoteBroadcastChannelName(), getUsername()));
            thread.start();
        });
    }

    private void associateComponents() {
        connectedListeners = view.findViewById(R.id.connected_viewers);
        connectedBroadcasts = view.findViewById(R.id.connected_masters);
        startBroadcastButton = view.findViewById(R.id.start_broadcast);
        broadcastStatus = view.findViewById(R.id.broadcast_status);
        addRemoteBroadcastListenerButton = view.findViewById(R.id.add_remote_broadcast_listener);
        remoteBroadcastChannelName = view.findViewById(R.id.remote_broadcast_channel_name);
    }

    private String getBroadcastChannelName() {
        return String.format("channel-%s", getUsername());
    }

    private String getRemoteBroadcastChannelName() {
        return remoteBroadcastChannelName.getText().toString();
    }

    private String getUsername() {
        EditText username = view.findViewById(R.id.username);
        return username.getText().toString();
    }

    private void webRtcServiceStateChange(WebRtcServiceStateChange webRtcServiceStateChange) {
        runOnUiThread(() -> {
            Toast.makeText(getContext(), webRtcServiceStateChange.toString(), Toast.LENGTH_LONG).show();

            if (webRtcService.masterRunning()) {
                broadcastStatus.setText(String.format("Broadcasting on %s", webRtcService.getBroadcastChannelName()));
            } else {
                broadcastStatus.setText(R.string.not_broadcasting);
            }

            startBroadcastButton.setText(webRtcService.masterRunning() ? R.string.stop : R.string.start_broadcast);
            startBroadcastButton.setEnabled(true);

            List<PeerManager> newViewers = webRtcService.getListenersConnectedToBroadcast();
            List<PeerManager> newMasters = webRtcService.getRemoteBroadcastsListeningTo();

            mergePeers(remoteListeners, newViewers, listenerAdapter);
            mergePeers(remoteBroadcasts, newMasters, broadcastAdapter);
        });

    }

    private void mergePeers(List<PeerManager> peers, List<PeerManager> newPeers, PeerAdapter adapter) {
        peers.clear();
        peers.addAll(newPeers);
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onDestroy() {
        webRtcService.onDestroy();
        super.onDestroy();
    }

}