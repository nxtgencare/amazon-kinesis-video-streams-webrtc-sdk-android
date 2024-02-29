package com.amazonaws.kinesisvideo.demoapp.fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.demoapp.adapters.PeerAdapter;
import com.amazonaws.kinesisvideo.service.webrtc.PeerManager;
import com.amazonaws.kinesisvideo.service.webrtc.WebRtcService;
import com.amazonaws.kinesisvideo.service.webrtc.model.ServiceStateChange;
import com.amazonaws.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

import static com.amazonaws.mobile.auth.core.internal.util.ThreadUtils.runOnUiThread;

public class StreamWebRtcConfigurationFragment extends Fragment {
    private static final String TAG = "StreamWebRtcConfigurationFragment";
    private WebRtcService webRtcService;
    private Button startBroadcastButton;
    private Button addRemoteUsernameButton;

    private RecyclerView connectedListeners;
    private RecyclerView connectedBroadcasts;

    private TextView broadcastStatus;
    private EditText remoteUsername;
    private EditText username;
    private SwitchCompat mute;

    private PeerAdapter broadcastAdapter;
    private PeerAdapter listenerAdapter;

    private final List<PeerManager> remoteListeners = new ArrayList<>();
    private final List<PeerManager> remoteBroadcasts = new ArrayList<>();

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

        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

        try {
            webRtcService = new WebRtcService(getActivity(), audioManager, this::webRtcServiceStateChange);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LinearLayoutManager viewersLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        LinearLayoutManager mastersLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);

        viewersLayoutManager.scrollToPosition(0);
        mastersLayoutManager.scrollToPosition(0);

        associateComponents();

        username.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int count, int after) {}

            @Override
            public void afterTextChanged(Editable editable) {
                String username = editable.toString();
                webRtcService.setUsername(username);
                startBroadcastButton.setEnabled(!StringUtils.isBlank(username));
            }
        });

        connectedListeners.setLayoutManager(viewersLayoutManager);
        connectedBroadcasts.setLayoutManager(mastersLayoutManager);

        broadcastAdapter = new PeerAdapter(remoteBroadcasts, p -> webRtcService.removeRemoteUserFromConference(p));
        listenerAdapter = new PeerAdapter(remoteListeners, p -> {}); // Broadcast can't remove anything

        connectedListeners.setAdapter(listenerAdapter);
        connectedBroadcasts.setAdapter(broadcastAdapter);

        startBroadcastButton.setOnClickListener(event -> {
            runOnUiThread(() -> {
                startBroadcastButton.setText(R.string.connecting);
                startBroadcastButton.setEnabled(false);
                username.setEnabled(false);
            });
            Thread thread = new Thread(() -> {
                if (webRtcService.broadcastRunning()) {
                    webRtcService.stopBroadcast();
                } else {
                    webRtcService.startBroadcast();
                }
            });
            thread.start();
        });

        addRemoteUsernameButton.setOnClickListener(event -> {
            Thread thread = new Thread(() -> webRtcService.addRemoteUserToConference(getRemoteUsername()));
            thread.start();
        });

        mute.setOnCheckedChangeListener((button, isChecked) -> webRtcService.setMute(isChecked));
    }

    private void associateComponents() {
        connectedListeners = view.findViewById(R.id.connected_viewers);
        connectedBroadcasts = view.findViewById(R.id.connected_masters);
        startBroadcastButton = view.findViewById(R.id.start_broadcast);
        broadcastStatus = view.findViewById(R.id.broadcast_status);
        addRemoteUsernameButton = view.findViewById(R.id.add_username);
        remoteUsername = view.findViewById(R.id.remote_broadcast_channel_name);
        username = view.findViewById(R.id.username);
        mute = view.findViewById(R.id.mute);
    }

    private String getRemoteUsername() {
        return remoteUsername.getText().toString();
    }

    private void webRtcServiceStateChange(ServiceStateChange webRtcServiceStateChange) {
        runOnUiThread(() -> {
            Log.d(TAG, "Toast: " + webRtcServiceStateChange.toString());

            if (webRtcService.broadcastRunning()) {
                broadcastStatus.setText(String.format("Broadcasting on %s", webRtcService.getBroadcastChannelName()));
            } else {
                broadcastStatus.setText(R.string.not_broadcasting);
                username.setEnabled(true);
            }

            startBroadcastButton.setText(webRtcService.broadcastRunning() ? R.string.stop : R.string.start_broadcast);
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