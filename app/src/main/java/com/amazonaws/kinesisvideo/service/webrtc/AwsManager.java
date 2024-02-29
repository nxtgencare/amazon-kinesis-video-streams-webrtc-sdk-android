package com.amazonaws.kinesisvideo.service.webrtc;

import android.util.Log;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.kinesisvideo.service.webrtc.exception.AwsSignalingChannelCreationException;
import com.amazonaws.kinesisvideo.service.webrtc.exception.AwsSignalingChannelDoesntExistException;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDescription;
import com.amazonaws.kinesisvideo.service.webrtc.model.ChannelDetails;
import com.amazonaws.kinesisvideo.service.webrtc.util.AwsUtils;
import com.amazonaws.services.kinesisvideo.AWSKinesisVideoClient;
import com.amazonaws.services.kinesisvideo.model.ChannelRole;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.CreateSignalingChannelResult;
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelRequest;
import com.amazonaws.services.kinesisvideo.model.DescribeSignalingChannelResult;
import com.amazonaws.services.kinesisvideo.model.GetSignalingChannelEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetSignalingChannelEndpointResult;
import com.amazonaws.services.kinesisvideo.model.ResourceEndpointListItem;
import com.amazonaws.services.kinesisvideo.model.ResourceNotFoundException;
import com.amazonaws.services.kinesisvideo.model.SingleMasterChannelEndpointConfiguration;
import com.amazonaws.services.kinesisvideosignaling.AWSKinesisVideoSignalingClient;
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigRequest;
import com.amazonaws.services.kinesisvideosignaling.model.GetIceServerConfigResult;
import com.amazonaws.services.kinesisvideosignaling.model.IceServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AwsManager {
    private static final String[] SUPPORTED_PROTOCOLS = new String[]{"WSS", "HTTPS"};
    private static final String TAG = "AwsManager";

    private final Map<ChannelDescription, ChannelDetails> channels = new ConcurrentHashMap<>();
    private final String region;
    private final AWSCredentials credentials;
    private final AWSKinesisVideoClient awsKinesisVideoClient;

    public AwsManager(AWSCredentials credentials, String region) {
        this.region = region;
        this.credentials = credentials;
        this.awsKinesisVideoClient = AwsUtils.getAwsKinesisVideoClient(credentials, region);
    }

    public ChannelDetails getChannelDetails(String region, String channelName, ChannelRole role) throws Exception {
        final ChannelDescription channelDescription = new ChannelDescription(region, channelName, role);
        if (channels.containsKey(channelDescription)) {
            return channels.get(channelDescription);
        }

        final String channelArn = getChannelArn(channelName, role);
        final List<ResourceEndpointListItem> endpointList = getEndPointList(channelArn, role);
        final List<IceServer> iceServerList = getIceServerList(region, channelArn, role, endpointList);
        ChannelDetails channelDetails = new ChannelDetails(channelDescription, channelArn, endpointList, iceServerList);
        channels.put(channelDescription, channelDetails);

        return channelDetails;
    }

    private String getChannelArn(String channelName, ChannelRole role) throws Exception {
        // Use the Kinesis Video Client to call DescribeSignalingChannel API.
        //  If that fails with ResourceNotFoundException, the channel does not exist.
        //  If we are connecting as Master, if it doesn't exist, we attempt to create
        //  it by calling CreateSignalingChannel API.
        try {
            final DescribeSignalingChannelResult describeSignalingChannelResult = awsKinesisVideoClient.describeSignalingChannel(
                    new DescribeSignalingChannelRequest()
                            .withChannelName(channelName));

            Log.i(TAG, "Channel ARN is " + describeSignalingChannelResult.getChannelInfo().getChannelARN());
            return describeSignalingChannelResult.getChannelInfo().getChannelARN();
        } catch (final ResourceNotFoundException e) {
            if (role.equals(ChannelRole.MASTER)) {
                try {
                    CreateSignalingChannelResult createSignalingChannelResult = awsKinesisVideoClient.createSignalingChannel(
                        new CreateSignalingChannelRequest()
                            .withChannelName(channelName));

                    return createSignalingChannelResult.getChannelARN();
                } catch (Exception ex) {
                    throw new AwsSignalingChannelCreationException(ex);
                }
            } else {
                throw new AwsSignalingChannelDoesntExistException
                        (e);
            }
        } catch (Exception ex) {
            throw new Exception("Describe Signaling Channel failed with Exception " + ex);
        }
    }

    private List<ResourceEndpointListItem> getEndPointList(String channelArn, ChannelRole role) throws Exception {
        //  Use the Kinesis Video Client to call GetSignalingChannelEndpoint.
        //  Each signaling channel is assigned an HTTPS and WSS endpoint to connect
        //  to for data-plane operations, which we fetch using the GetSignalingChannelEndpoint API,
        //  and a WEBRTC endpoint to for storage data-plane operations.
        //  Attempting to obtain the WEBRTC endpoint if the signaling channel is not configured
        //  will result in an InvalidArgumentException.
        try {
            final GetSignalingChannelEndpointResult getSignalingChannelEndpointResult = awsKinesisVideoClient.getSignalingChannelEndpoint(
                new GetSignalingChannelEndpointRequest()
                    .withChannelARN(channelArn)
                    .withSingleMasterChannelEndpointConfiguration(
                        new SingleMasterChannelEndpointConfiguration()
                            .withProtocols(SUPPORTED_PROTOCOLS)
                            .withRole(role)));
            Log.i(TAG, "Endpoints " + getSignalingChannelEndpointResult.toString());
            return getSignalingChannelEndpointResult.getResourceEndpointList();
        } catch (Exception e) {
            throw new Exception("Get Signaling Endpoint failed with Exception " + e.getMessage());
        }
    }

    private List<IceServer> getIceServerList(
        String region,
        String channelArn,
        ChannelRole role,
        List<ResourceEndpointListItem> endpointList
    ) throws Exception {
        String dataEndpoint = null;
        for (ResourceEndpointListItem endpoint : endpointList) {
            if (endpoint.getProtocol().equals("HTTPS")) {
                dataEndpoint = endpoint.getResourceEndpoint();
            }
        }

        //  Construct the Kinesis Video Signaling Client. The HTTPS endpoint from the
        //  GetSignalingChannelEndpoint response above is used with this client. This
        //  client is just used for getting ICE servers, not for actual signaling.
        //  Call GetIceServerConfig in order to obtain TURN ICE server info.
        //  Note: the STUN endpoint will be `stun:stun.kinesisvideo.${region}.amazonaws.com:443`
        try {
            final AWSKinesisVideoSignalingClient awsKinesisVideoSignalingClient = AwsUtils.getAwsKinesisVideoSignalingClient(credentials, region, dataEndpoint);
            GetIceServerConfigResult getIceServerConfigResult = awsKinesisVideoSignalingClient.getIceServerConfig(
                new GetIceServerConfigRequest().withChannelARN(channelArn).withClientId(role.name()));
            return getIceServerConfigResult.getIceServerList();
        } catch (Exception e) {
            throw new Exception("Get Ice Server Config failed with Exception " + e.getMessage());
        }
    }

    public AWSCredentials getCredentials() { return credentials; }

}
