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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.demoapp.service.WebRtcService;
import com.amazonaws.kinesisvideo.demoapp.service.WebRtcServiceStateChange;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.amazonaws.mobile.auth.core.internal.util.ThreadUtils.runOnUiThread;

public class StreamWebRtcConfigurationFragment extends Fragment {
    private WebRtcService webRtcService;
    private Button masterStartButton;
    private Button viewerStartButton;

    private final AtomicReference<AWSCredentials> creds = new AtomicReference<>();

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
        runOnUiThread(() -> creds.set(KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials()));
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AudioManager audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

        try {
            webRtcService = new WebRtcService(getActivity(), creds.get(),"ca-central-1", audioManager, this::webRtcServiceStateChange);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        EditText mMasterChannelName = view.findViewById(R.id.master_channel_name);
        masterStartButton = view.findViewById(R.id.master);
        masterStartButton.setOnClickListener(event -> {
            Thread thread = new Thread(() -> {
                    if (webRtcService.masterRunning()) {
                        webRtcService.stopMaster();
                    } else {
                        webRtcService.startMaster(mMasterChannelName.getText().toString());
                    }
            });
            thread.start();
        });

        EditText mViewerChannelName = view.findViewById(R.id.viewer_channel_name);
        viewerStartButton = view.findViewById(R.id.viewer);
        viewerStartButton.setOnClickListener(
            event -> {
                Thread thread = new Thread(() -> {
                    if (webRtcService.viewerRunning()) {
                        webRtcService.stopViewer();
                    } else {
                        webRtcService.startViewer(mViewerChannelName.getText().toString(), UUID.randomUUID().toString());
                    }
                });
                thread.start();
            }
        );
    }

    private void webRtcServiceStateChange(WebRtcServiceStateChange webRtcServiceStateChange) {
        runOnUiThread(() -> {
            Toast.makeText(getContext(), webRtcServiceStateChange.toString(), Toast.LENGTH_LONG).show();
            // TODO: Iterate over service status fields and set button values and stuff
            masterStartButton.setText(webRtcService.masterRunning() ? R.string.stop : R.string.start);
            viewerStartButton.setText(webRtcService.viewerRunning() ? R.string.stop : R.string.start);
        });

    }

    @Override
    public void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        webRtcService.onDestroy();
        super.onDestroy();
    }

}