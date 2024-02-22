package com.amazonaws.kinesisvideo.demoapp.service;

import com.amazonaws.services.kinesisvideo.model.ChannelRole;

import java.util.Objects;

public class ChannelDescription {
    private final String region;
    private final String channelName;
    private final ChannelRole role;

    public ChannelDescription(String region, String channelName, ChannelRole role) {
        this.region = region;
        this.channelName = channelName;
        this.role = role;
    }

    public String getRegion() { return region; }
    public String getChannelName() { return channelName; }
    public ChannelRole getRole() { return role; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelDescription that = (ChannelDescription) o;
        return Objects.equals(region, that.region) && Objects.equals(channelName, that.channelName) && role == that.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, channelName, role);
    }
}
