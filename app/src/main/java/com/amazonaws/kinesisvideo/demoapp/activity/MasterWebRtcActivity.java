package com.amazonaws.kinesisvideo.demoapp.activity;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.demoapp.KinesisVideoWebRtcDemoApp;
import com.amazonaws.kinesisvideo.demoapp.R;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_CHANNEL_ARN;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_PASSWORD;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_URI;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_ICE_SERVER_USER_NAME;
import static com.amazonaws.kinesisvideo.demoapp.fragment.StreamWebRtcConfigurationFragment.KEY_WSS_ENDPOINT;

public class MasterWebRtcActivity extends AppCompatActivity {
    private static final String TAG = "MasterWebRtcActivity";

    private TextView connectedStatusText = null;

    private MasterWebRtc webRtc;

    @Override
    protected void onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null);
        webRtc.onDestroy();
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
            webRtc.initWsConnection(
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

        this.webRtc = new MasterWebRtc(
                this,
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
