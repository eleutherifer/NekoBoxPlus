package io.nekohasekai.sagernet.database;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.Serializable;

public class SubscriptionBean extends Serializable {

    private static final int BANNER_EXPIRATION_TIME = 1 << 5;

    public Integer type;
    public String link;
    public String token;
    public Boolean forceResolve;
    public Boolean deduplication;
    public Boolean updateWhenConnectedOnly;
    public String customUserAgent;
    public Boolean autoUpdate;
    public Integer autoUpdateDelay;
    public Boolean providerAutoUpdateDefaultsApplied;
    public Integer lastUpdated;
    public Integer filterMode;
    public String filterRegex;
    public Boolean hwidEnabled;
    public Integer spoofApp;
    public String serverDnsResolver;
    public Integer bannerLayout;
    public Boolean routingEnabled;
    public String routingPayload;
    public String routingFormat;
    public String autoRoutingUrl;
    public Integer routingUpdateInterval;
    public Long routingLastUpdated;
    public Boolean routingOff;

    // SIP008

    public Long bytesUsed;
    public Long bytesRemaining;

    // Open Online Config

    public String username;
    public Integer expiryDate;
    public List<String> protocols;


    // https://github.com/crossutility/Quantumult/blob/master/extra-subscription-feature.md

    public String subscriptionUserinfo;
    public Long expireAt;
    public String announcement;
    public String announcementUrl;
    public String supportUrl;
    public String supportEmail;
    public String profileWebPageUrl;
    public String homepage;

    public SubscriptionBean() {
    }

    @Override
    public void serializeToBuffer(ByteBufferOutput output) {
        output.writeInt(8);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);
        output.writeBoolean(autoUpdate);
        output.writeInt(autoUpdateDelay);
        output.writeInt(lastUpdated);

        output.writeString(subscriptionUserinfo);

        // v2
        output.writeInt(filterMode);
        output.writeString(filterRegex);

        // v3
        output.writeBoolean(hwidEnabled);
        output.writeInt(spoofApp);

        // v4
        output.writeString(serverDnsResolver);

        // v5
        output.writeInt(bannerLayout);
        output.writeString(announcement);
        output.writeString(announcementUrl);
        output.writeString(supportUrl);
        output.writeString(supportEmail);
        output.writeString(profileWebPageUrl);
        output.writeString(homepage);

        // v6
        output.writeBoolean(routingEnabled);
        output.writeString(routingPayload);
        output.writeString(routingFormat);
        output.writeString(autoRoutingUrl);
        output.writeInt(routingUpdateInterval);
        output.writeLong(routingLastUpdated);
        output.writeBoolean(routingOff);

        // v7
        output.writeLong(expireAt);

        // v8
        output.writeBoolean(providerAutoUpdateDefaultsApplied);
    }

    public void serializeForShare(ByteBufferOutput output) {
        output.writeInt(2);

        output.writeInt(type);

        output.writeString(link);

        output.writeBoolean(forceResolve);
        output.writeBoolean(deduplication);
        output.writeBoolean(updateWhenConnectedOnly);
        output.writeString(customUserAgent);

        // v1
        output.writeInt(bannerLayout);
    }

    @Override
    public void deserializeFromBuffer(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();
        autoUpdate = input.readBoolean();
        autoUpdateDelay = input.readInt();
        lastUpdated = input.readInt();
        subscriptionUserinfo = input.readString();

        // v2
        if (version >= 2) {
            filterMode = input.readInt();
            filterRegex = input.readString();
        }

        // v3
        if (version >= 3) {
            hwidEnabled = input.readBoolean();
            spoofApp = input.readInt();
        }

        // v4
        if (version >= 4) {
            serverDnsResolver = input.readString();
        }

        // v5
        if (version >= 5) {
            bannerLayout = input.readInt();
            announcement = input.readString();
            announcementUrl = input.readString();
            supportUrl = input.readString();
            supportEmail = input.readString();
            profileWebPageUrl = input.readString();
            homepage = input.readString();
        }
        if (version >= 6) {
            routingEnabled = input.readBoolean();
            routingPayload = input.readString();
            routingFormat = input.readString();
            autoRoutingUrl = input.readString();
            routingUpdateInterval = input.readInt();
            routingLastUpdated = input.readLong();
            routingOff = input.readBoolean();
        }
        if (version >= 7) {
            expireAt = input.readLong();
        } else if (bannerLayout != null) {
            bannerLayout |= BANNER_EXPIRATION_TIME;
        }
        if (version >= 8) {
            providerAutoUpdateDefaultsApplied = input.readBoolean();
        } else {
            // Existing subscriptions must never have provider defaults applied again.
            providerAutoUpdateDefaultsApplied = true;
        }
    }

    public void deserializeFromShare(ByteBufferInput input) {
        int version = input.readInt();

        type = input.readInt();
        link = input.readString();
        forceResolve = input.readBoolean();
        deduplication = input.readBoolean();
        updateWhenConnectedOnly = input.readBoolean();
        customUserAgent = input.readString();

        if (version >= 1) {
            bannerLayout = input.readInt();
        }
        if (version < 2 && bannerLayout != null) {
            bannerLayout |= BANNER_EXPIRATION_TIME;
        }
    }

    @Override
    public void initializeDefaultValues() {
        if (type == null) type = 0;
        if (link == null) link = "";
        if (token == null) token = "";
        if (forceResolve == null) forceResolve = false;
        if (deduplication == null) deduplication = false;
        if (updateWhenConnectedOnly == null) updateWhenConnectedOnly = false;
        if (customUserAgent == null) customUserAgent = "";
        if (autoUpdate == null) autoUpdate = false;
        if (autoUpdateDelay == null) autoUpdateDelay = 1440;
        if (providerAutoUpdateDefaultsApplied == null) providerAutoUpdateDefaultsApplied = false;
        if (lastUpdated == null) lastUpdated = 0;
        if (filterMode == null) filterMode = 0;
        if (filterRegex == null) filterRegex = "";
        if (hwidEnabled == null) hwidEnabled = false;
        if (spoofApp == null) spoofApp = 0;
        if (serverDnsResolver == null) serverDnsResolver = "";
        if (bannerLayout == null) bannerLayout = 63;
        if (announcement == null) announcement = "";
        if (announcementUrl == null) announcementUrl = "";
        if (supportUrl == null) supportUrl = "";
        if (supportEmail == null) supportEmail = "";
        if (profileWebPageUrl == null) profileWebPageUrl = "";
        if (homepage == null) homepage = "";
        if (expireAt == null) expireAt = 0L;
        if (routingEnabled == null) routingEnabled = false;
        if (routingPayload == null) routingPayload = "";
        if (routingFormat == null) routingFormat = "";
        if (autoRoutingUrl == null) autoRoutingUrl = "";
        if (routingUpdateInterval == null) routingUpdateInterval = 86400;
        if (routingLastUpdated == null) routingLastUpdated = 0L;
        if (routingOff == null) routingOff = false;

        if (bytesUsed == null) bytesUsed = 0L;
        if (bytesRemaining == null) bytesRemaining = 0L;

        if (username == null) username = "";
        if (expiryDate == null) expiryDate = 0;
        if (protocols == null) protocols = new ArrayList<>();
    }

    public static final Creator<SubscriptionBean> CREATOR = new CREATOR<SubscriptionBean>() {
        @NonNull
        @Override
        public SubscriptionBean newInstance() {
            return new SubscriptionBean();
        }

        @Override
        public SubscriptionBean[] newArray(int size) {
            return new SubscriptionBean[size];
        }
    };

}
