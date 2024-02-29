package com.amazonaws.kinesisvideo.service.webrtc.model;

import android.util.Log;

import com.amazonaws.services.kinesisvideo.model.ChannelRole;
import com.amazonaws.services.kinesisvideo.model.ResourceEndpointListItem;
import com.amazonaws.services.kinesisvideosignaling.model.IceServer;

import org.webrtc.PeerConnection;

import java.util.ArrayList;
import java.util.List;

public class ChannelDetails {
    private static final String TAG = "ChannelDetails";

    private final ChannelDescription channelDescription;
    private final String channelArn;
    private final List<ResourceEndpointListItem> endpointList;
    private final List<IceServer> iceServerList;
    private final List<PeerConnection.IceServer> peerIceServers = new ArrayList<>();
    private String wssEndpoint;

    public ChannelDetails(
        ChannelDescription channelDescription,
        String channelArn,
        List<ResourceEndpointListItem> endpointList,
        List<IceServer> iceServerList
    ) {
        this.channelDescription = channelDescription;
        this.channelArn = channelArn;
        this.endpointList = endpointList;
        this.iceServerList = iceServerList;

        for (ResourceEndpointListItem endpoint : endpointList) {
            if (endpoint.getProtocol().equals("WSS")) {
                this.wssEndpoint = endpoint.getResourceEndpoint();
                break;
            }
        }

        final PeerConnection.IceServer stun = PeerConnection.IceServer
            .builder(String.format("stun:stun.kinesisvideo.%s.amazonaws.com:443", channelDescription.getRegion()))
            .createIceServer();

        peerIceServers.add(stun);

        for (IceServer iceServer : iceServerList) {
            final String turnServer = iceServer.getUris().toString();
            final PeerConnection.IceServer peerIceServer = PeerConnection.IceServer.builder(turnServer.replace("[", "").replace("]", ""))
                .setUsername(iceServer.getUsername())
                .setPassword(iceServer.getPassword())
                .createIceServer();

            Log.d(TAG, "IceServer details (TURN) = " + peerIceServer.toString());
            peerIceServers.add(peerIceServer);
        }
    }

    public String getChannelName() { return channelDescription.getChannelName(); }
    public String getRegion() { return channelDescription.getRegion(); }
    public ChannelRole getRole() { return channelDescription.getRole(); }
    public String getChannelArn() { return channelArn; }
    public List<ResourceEndpointListItem> getEndpointList() { return endpointList; }
    public List<IceServer> getIceServerList() { return iceServerList; }
    public List<PeerConnection.IceServer> getPeerIceServers() { return peerIceServers; }
    public String getWssEndpoint() { return wssEndpoint; }

    @Override
    public String toString() {
        return String.format("%s (%s)", channelDescription.getChannelName(), channelDescription.getRole());
    }
}
