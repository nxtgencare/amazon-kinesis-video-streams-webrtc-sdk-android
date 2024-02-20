package com.amazonaws.kinesisvideo.demoapp.fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.demoapp.R;
import com.amazonaws.kinesisvideo.demoapp.service.MasterWebRtc;
import com.amazonaws.kinesisvideo.demoapp.service.ViewerWebRtc;
import com.amazonaws.kinesisvideo.demoapp.service.WebRtc;

import org.webrtc.PeerConnection;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.amazonaws.mobile.auth.core.internal.util.ThreadUtils.runOnUiThread;

public class StreamWebRtcConfigurationFragment extends Fragment {
    private static final String TAG = "StreamWebRtcConfigurationFragment";

    private Button mMasterButton;
    private Button mViewerButton;

    private MasterWebRtc masterWebRtc;
    private ViewerWebRtc viewerWebRtc;

    private boolean masterRunning;
    private boolean viewerRunning;

    private final AtomicReference<AWSCredentials> mCreds = new AtomicReference<>();
    private AudioManager audioManager;

    public static StreamWebRtcConfigurationFragment newInstance() {
        StreamWebRtcConfigurationFragment s = new StreamWebRtcConfigurationFragment();
        return s;
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
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        runOnUiThread(() -> mCreds.set(KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials()));
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioManager = (AudioManager) getActivity().getSystemService(Context.AUDIO_SERVICE);

        EditText mMasterChannelName = view.findViewById(R.id.master_channel_name);
        mMasterButton = view.findViewById(R.id.master);
        StreamWebRtcConfigurationFragment parent = this;
        mMasterButton.setOnClickListener(event -> {
            Thread thread = new Thread(() -> {
                    if (masterRunning) {
                        stopWebRtc(masterWebRtc);
                        setMasterRunning(false);
                    } else {
                        try {
                            parent.masterWebRtc = new MasterWebRtc(
                                getActivity(),
                                "ca-central-1",
                                mMasterChannelName.getText().toString(),
                                audioManager
                            );
                            mMasterButton.setEnabled(true);
                            webRtcButtonWhenClicked(masterWebRtc, masterRunning, parent::setMasterRunning);
                        } catch(Exception e){
                            notifyWebRtcConstructFailed(e);
                            mMasterButton.setEnabled(false);
                        }
                    }
            });
            thread.start();
        });

        EditText mViewerChannelName = view.findViewById(R.id.viewer_channel_name);
        mViewerButton = view.findViewById(R.id.viewer);
        mViewerButton.setOnClickListener(
            event -> {
                Thread thread = new Thread(() -> {
                    if (viewerRunning) {
                        stopWebRtc(viewerWebRtc);
                        setViewerRunning(false);
                    } else {
                        try {
                            viewerWebRtc = new ViewerWebRtc(
                                    getActivity(),
                                    "ca-central-1",
                                    mViewerChannelName.getText().toString(),
                                    mViewerChannelName.getText().toString(),
                                    audioManager
                            );
                            mViewerButton.setEnabled(true);
                            webRtcButtonWhenClicked(viewerWebRtc, viewerRunning, parent::setViewerRunning);
                        } catch (Exception e) {
                            notifyWebRtcConstructFailed(e);
                            mViewerButton.setEnabled(false);
                        }
                    }
                });
                thread.start();
            }
        );
    }

    private void setMasterRunning(Boolean running) {
        masterRunning = running;
        runOnUiThread(() -> mMasterButton.setText(masterRunning ? R.string.stop : R.string.start));
    }

    private void setViewerRunning(Boolean running) {
        viewerRunning = running;
        runOnUiThread(() -> mViewerButton.setText(viewerRunning ? R.string.stop : R.string.start));
    }

    @Override
    public void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        masterWebRtc.onDestroy();
        viewerWebRtc.onDestroy();
        super.onDestroy();
    }

    private void webRtcButtonWhenClicked(WebRtc webRtc, Boolean isRunning, Consumer<Boolean> setRunningCallback) {
        if (!isRunning) {
            if (startWebRtc(webRtc, setRunningCallback)) {
                setRunningCallback.accept(true);
            } else {
                setRunningCallback.accept(false);
            }
        }
    }

    private boolean startWebRtc(WebRtc webRtc, Consumer<Boolean> setRunningCallback) {
        // Start websocket after adding local audio/video tracks
        try {
            webRtc.initWsConnection(
                mCreds.get(),
                this::notifySignalingConnectionFailed,
                getIceConnectionStateChangedCallback(setRunningCallback)
            );
            runOnUiThread(() -> Toast.makeText(getContext(), "Signaling Connected", Toast.LENGTH_LONG).show());
            return true;
        } catch (Exception e) {
            notifySignalingConnectionFailed(e);
            return false;
        }
    }

    private void stopWebRtc(WebRtc webRtc) {
        webRtc.onDestroy();
    }

    private Consumer<PeerConnection.IceConnectionState> getIceConnectionStateChangedCallback(Consumer<Boolean> setRunningCallback) {
        return (iceConnectionState -> {
            if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
                runOnUiThread(() -> Toast.makeText(getContext(), "Connection to peer failed!", Toast.LENGTH_LONG).show());
                setRunningCallback.accept(false);
            } else if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
                runOnUiThread(() -> Toast.makeText(getContext(), "Connected to peer!", Toast.LENGTH_LONG).show());
                setRunningCallback.accept(true);
            }
        });
    }

    private void notifySignalingConnectionFailed(Exception e) {
        Log.e(TAG, "Error during notification signalling", e);
        runOnUiThread(() -> Toast.makeText(getContext(), "Connection error to signaling", Toast.LENGTH_LONG).show());
    }

    private void notifyWebRtcConstructFailed(Exception e) {
        Log.e(TAG, "Error during webrtc construction", e);
        runOnUiThread(() -> Toast.makeText(getContext(), "WebRtc object failed to created", Toast.LENGTH_LONG).show());
    }
}