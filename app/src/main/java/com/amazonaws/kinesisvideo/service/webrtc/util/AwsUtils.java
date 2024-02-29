package com.amazonaws.kinesisvideo.service.webrtc.util;

import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.config.AWSConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.services.kinesisvideo.AWSKinesisVideoClient;
import com.amazonaws.services.kinesisvideosignaling.AWSKinesisVideoSignalingClient;

import org.json.JSONException;
import org.json.JSONObject;

public class AwsUtils {
    private static final String TAG = "AwsUtils";

    /**
     * Parse awsconfiguration.json and extract the region from it.
     *
     * @return The region in String form. {@code null} if not.
     * @throws IllegalStateException if awsconfiguration.json is not properly configured.
     */
    public static String getRegion() {
        final AWSConfiguration configuration = AWSMobileClient.getInstance().getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("awsconfiguration.json has not been properly configured!");
        }

        final JSONObject jsonObject = configuration.optJsonObject("CredentialsProvider");

        String region = null;
        try {
            region = (String) ((JSONObject) (((JSONObject) jsonObject.get("CognitoIdentity")).get("Default"))).get("Region");
        } catch (final JSONException e) {
            Log.e(TAG, "Got exception when extracting region from cognito setting.", e);
        }
        return region;
    }

    public static AWSKinesisVideoClient getAwsKinesisVideoClient(AWSCredentials credentials, String region) {
        final AWSKinesisVideoClient awsKinesisVideoClient = new AWSKinesisVideoClient(credentials);
        awsKinesisVideoClient.setRegion(Region.getRegion(region));
        awsKinesisVideoClient.setSignerRegionOverride(region);
        awsKinesisVideoClient.setServiceNameIntern("kinesisvideo");
        return awsKinesisVideoClient;
    }

    public static AWSKinesisVideoSignalingClient getAwsKinesisVideoSignalingClient(AWSCredentials credentials, String region, String endpoint) {
        final AWSKinesisVideoSignalingClient client = new AWSKinesisVideoSignalingClient(credentials);
        client.setRegion(Region.getRegion(region));
        client.setSignerRegionOverride(region);
        client.setServiceNameIntern("kinesisvideo");
        client.setEndpoint(endpoint);
        return client;
    }
}
