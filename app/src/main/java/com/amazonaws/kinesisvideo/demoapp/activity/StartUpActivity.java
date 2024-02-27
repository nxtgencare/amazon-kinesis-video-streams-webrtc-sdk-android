package com.amazonaws.kinesisvideo.demoapp.activity;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.amazonaws.kinesisvideo.demoapp.BuildConfig;
import com.amazonaws.kinesisvideo.demoapp.util.ActivityUtils;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.Callback;
import com.amazonaws.mobile.client.UserStateDetails;
import com.amazonaws.mobile.client.results.SignInResult;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

public class StartUpActivity extends AppCompatActivity {
    private static final String TAG = StartUpActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final AWSMobileClient auth = AWSMobileClient.getInstance();
        initializeMobileClient(auth);

        final AppCompatActivity thisActivity = this;
        supportFinishAfterTransition();

        AsyncTask.execute(() -> ActivityUtils.startActivity(thisActivity, SimpleNavActivity.class));
    }

    private void initializeMobileClient(AWSMobileClient client) {
        final CountDownLatch latch = new CountDownLatch(1);

        client.initialize(getApplicationContext(), new Callback<UserStateDetails>() {
            @Override
            public void onResult(UserStateDetails result) {
                Log.d(TAG, "onResult: user state: " + result.getUserState());
                client.signIn(
                    BuildConfig.KINESIS_USERNAME,
                    BuildConfig.KINESIS_PASSWORD,
                    Collections.emptyMap(),
                    new Callback<SignInResult>() {
                        @Override
                        public void onResult(SignInResult result) {
                            Log.d(TAG,String.format("Sign In Attempt Info: sign in state: %s",result.getSignInState()));
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.e(TAG, String.format("Sign In Attempt error: %s", e.getMessage()), e);
                            latch.countDown();
                        }
                    }
                );
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "onError: Initialization error of the mobile client", e);
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
