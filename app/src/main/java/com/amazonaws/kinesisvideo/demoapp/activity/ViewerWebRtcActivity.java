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
import java.util.concurrent.atomic.AtomicReference;

import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_CHANNEL_ARN;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_PASSWORD;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_URI;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_USER_NAME;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_WSS_ENDPOINT;

public class ViewerWebRtcActivity extends AppCompatActivity {
    private static final String TAG = "ViewerWebRtcActivity";

    private TextView connectedStatusText = null;

    private ViewerWebRtc viewerWebRtc;

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        viewerWebRtc.onDestroy();
        finish();
        super.onDestroy();
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        AtomicReference<AWSCredentials> mCreds = new AtomicReference<>();
        runOnUiThread(() -> mCreds.set(KinesisVideoWebRtcDemoApp.getCredentialsProvider().getCredentials()));

        // Start websocket after adding local audio/video tracks
        try {
            viewerWebRtc.initWsConnection(
                mCreds.get(),
                this::notifySignalingConnectionFailed,
                this::iceConnectionStateChanged
            );

            Toast.makeText(this, "Signaling Connected", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            notifySignalingConnectionFailed(e);
        }
    }

    private void iceConnectionStateChanged(PeerConnection.IceConnectionState iceConnectionState) {
        if (iceConnectionState == PeerConnection.IceConnectionState.FAILED) {
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Connection to peer failed!", Toast.LENGTH_LONG).show());
            connectedStatusText.setText("Disconnected");
        } else if (iceConnectionState == PeerConnection.IceConnectionState.CONNECTED) {
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Connected to peer!", Toast.LENGTH_LONG).show());
            connectedStatusText.setText("Connected");
        }
    }

    private void notifySignalingConnectionFailed(Exception e) {
        finish();
        Log.e(TAG, "Error during notification signalling", e);
        Toast.makeText(this, "Connection error to signaling", Toast.LENGTH_LONG).show();
        connectedStatusText.setText("Disconnected");
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final Intent intent = getIntent();
        this.viewerWebRtc = new ViewerWebRtc(
            this,
            UUID.randomUUID().toString(),
            intent.getStringExtra(KEY_CHANNEL_ARN),
            intent.getStringExtra(KEY_WSS_ENDPOINT),
            intent.getStringArrayListExtra(KEY_ICE_SERVER_USER_NAME),
            intent.getStringArrayListExtra(KEY_ICE_SERVER_PASSWORD),
            (ArrayList<List<String>>) intent.getSerializableExtra(KEY_ICE_SERVER_URI),
            "ca-central-1",
            (AudioManager) getSystemService(Context.AUDIO_SERVICE)
        );

        setContentView(R.layout.activity_webrtc_main);
        connectedStatusText = findViewById(R.id.connectedStatus);
    }

}
