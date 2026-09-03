package io.nekohasekai.sagernet.fmt.tailscale;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class TailscaleBean extends AbstractBean {

    public String authKey;
    public String controlURL;
    public Boolean ephemeral;
    public String hostname;
    public Boolean acceptRoutes;
    public String exitNode;
    public Boolean exitNodeAllowLANAccess;
    public String advertiseRoutes;
    public Boolean advertiseExitNode;
    public String advertiseTags;
    public Integer relayServerPort;
    public String relayServerStaticEndpoints;
    public String udpTimeout;
    public Boolean magicDNS;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (authKey == null) authKey = "";
        if (controlURL == null) controlURL = "";
        if (ephemeral == null) ephemeral = false;
        if (hostname == null) hostname = "";
        if (acceptRoutes == null) acceptRoutes = false;
        if (exitNode == null) exitNode = "";
        if (exitNodeAllowLANAccess == null) exitNodeAllowLANAccess = false;
        if (advertiseRoutes == null) advertiseRoutes = "";
        if (advertiseExitNode == null) advertiseExitNode = false;
        if (advertiseTags == null) advertiseTags = "";
        if (relayServerPort == null) relayServerPort = 0;
        if (relayServerStaticEndpoints == null) relayServerStaticEndpoints = "";
        if (udpTimeout == null) udpTimeout = "";
        if (magicDNS == null) magicDNS = false;
        if (name == null || name.isBlank()) name = "Tailscale";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(authKey);
        output.writeString(controlURL);
        output.writeBoolean(ephemeral);
        output.writeString(hostname);
        output.writeBoolean(acceptRoutes);
        output.writeString(exitNode);
        output.writeBoolean(exitNodeAllowLANAccess);
        output.writeString(advertiseRoutes);
        output.writeBoolean(advertiseExitNode);
        output.writeString(advertiseTags);
        output.writeInt(relayServerPort);
        output.writeString(relayServerStaticEndpoints);
        output.writeString(udpTimeout);
        output.writeBoolean(magicDNS);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt();
        super.deserialize(input);
        authKey = input.readString();
        controlURL = input.readString();
        ephemeral = input.readBoolean();
        hostname = input.readString();
        acceptRoutes = input.readBoolean();
        exitNode = input.readString();
        exitNodeAllowLANAccess = input.readBoolean();
        advertiseRoutes = input.readString();
        advertiseExitNode = input.readBoolean();
        advertiseTags = input.readString();
        relayServerPort = input.readInt();
        relayServerStaticEndpoints = input.readString();
        udpTimeout = input.readString();
        magicDNS = input.readBoolean();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public String displayAddress() {
        if (!hostname.isBlank()) return hostname;
        if (!controlURL.isBlank()) return controlURL;
        return "Tailscale";
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("tailscale");
    }

    @NotNull
    @Override
    public TailscaleBean clone() {
        return KryoConverters.deserialize(new TailscaleBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TailscaleBean> CREATOR = new CREATOR<TailscaleBean>() {
        @NonNull
        @Override
        public TailscaleBean newInstance() {
            return new TailscaleBean();
        }

        @Override
        public TailscaleBean[] newArray(int size) {
            return new TailscaleBean[size];
        }
    };
}
