package io.nekohasekai.sagernet.fmt.hysteria;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.ktx.NetsKt;
import kotlin.text.StringsKt;

public class HysteriaBean extends AbstractBean {
    public Integer protocolVersion;

    // Use serverPorts instead of serverPort
    public String serverPorts;

    // HY1 & 2

    public String authPayload;
    public String obfuscation;
    public String sni;
    public String caText;
    public Integer uploadMbps;
    public Integer downloadMbps;
    public Boolean allowInsecure;
    public Integer streamReceiveWindow;
    public Integer connectionReceiveWindow;
    public Boolean disableMtuDiscovery;
    public Integer hopInterval;
    public String hopIntervalMax;
    public String bbrProfile;
    public Boolean brutalDebug;
    public String obfsType;
    public Integer geckoMinPacketSize;
    public Integer geckoMaxPacketSize;
    public String realmServerUrl;
    public String realmToken;
    public String realmId;
    public String realmStunServers;
    public Integer realmIpVersion;
    public Boolean realmPortMapping;
    public String realmPortMappingTimeout;
    public String realmPortMappingLifetime;

    // HY1

    public String alpn;

    public static final int TYPE_NONE = 0;
    public static final int TYPE_STRING = 1;
    public static final int TYPE_BASE64 = 2;
    public Integer authPayloadType;

    public static final int PROTOCOL_UDP = 0;
    public static final int PROTOCOL_FAKETCP = 1;
    public static final int PROTOCOL_WECHAT_VIDEO = 2;
    public Integer protocol;

    @Override
    public boolean canMapping() {
        return protocol != PROTOCOL_FAKETCP;
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocolVersion == null) protocolVersion = 2;

        if (authPayloadType == null) authPayloadType = TYPE_NONE;
        if (authPayload == null) authPayload = "";
        if (protocol == null) protocol = PROTOCOL_UDP;
        if (obfuscation == null) obfuscation = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (caText == null) caText = "";
        if (allowInsecure == null) allowInsecure = false;

        if (protocolVersion == 1) {
            if (uploadMbps == null) uploadMbps = 10;
            if (downloadMbps == null) downloadMbps = 50;
        } else {
            if (uploadMbps == null) uploadMbps = 0;
            if (downloadMbps == null) downloadMbps = 0;
        }

        if (streamReceiveWindow == null) streamReceiveWindow = 0;
        if (connectionReceiveWindow == null) connectionReceiveWindow = 0;
        if (disableMtuDiscovery == null) disableMtuDiscovery = false;
        if (hopInterval == null) hopInterval = 10;
        if (serverPorts == null) serverPorts = "443";
        if (hopIntervalMax == null) hopIntervalMax = "";
        if (bbrProfile == null) bbrProfile = "";
        if (brutalDebug == null) brutalDebug = false;
        if (obfsType == null) obfsType = "salamander";
        if (geckoMinPacketSize == null) geckoMinPacketSize = 0;
        if (geckoMaxPacketSize == null) geckoMaxPacketSize = 0;
        if (realmServerUrl == null) realmServerUrl = "";
        if (realmToken == null) realmToken = "";
        if (realmId == null) realmId = "";
        if (realmStunServers == null) realmStunServers = "";
        if (realmIpVersion == null) realmIpVersion = 0;
        if (realmPortMapping == null) realmPortMapping = false;
        if (realmPortMappingTimeout == null) realmPortMappingTimeout = "";
        if (realmPortMappingLifetime == null) realmPortMappingLifetime = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(8);
        super.serialize(output);

        output.writeInt(protocolVersion);

        output.writeInt(authPayloadType);
        output.writeString(authPayload);
        output.writeInt(protocol);
        output.writeString(obfuscation);
        output.writeString(sni);
        output.writeString(alpn);

        output.writeInt(uploadMbps);
        output.writeInt(downloadMbps);
        output.writeBoolean(allowInsecure);

        output.writeString(caText);
        output.writeInt(streamReceiveWindow);
        output.writeInt(connectionReceiveWindow);
        output.writeBoolean(disableMtuDiscovery);
        output.writeInt(hopInterval);
        output.writeString(serverPorts);
        output.writeString(hopIntervalMax);
        output.writeString(bbrProfile);
        output.writeBoolean(brutalDebug);
        output.writeString(obfsType);
        output.writeInt(geckoMinPacketSize);
        output.writeInt(geckoMaxPacketSize);
        output.writeString(realmServerUrl);
        output.writeString(realmToken);
        output.writeString(realmId);
        output.writeString(realmStunServers);
        output.writeInt(realmIpVersion);
        output.writeBoolean(realmPortMapping);
        output.writeString(realmPortMappingTimeout);
        output.writeString(realmPortMappingLifetime);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version >= 7) {
            protocolVersion = input.readInt();
        } else {
            protocolVersion = 1;
        }
        authPayloadType = input.readInt();
        authPayload = input.readString();
        if (version >= 3) {
            protocol = input.readInt();
        }
        obfuscation = input.readString();
        sni = input.readString();
        if (version >= 2) {
            alpn = input.readString();
        }
        uploadMbps = input.readInt();
        downloadMbps = input.readInt();
        allowInsecure = input.readBoolean();
        if (version >= 1) {
            caText = input.readString();
            streamReceiveWindow = input.readInt();
            connectionReceiveWindow = input.readInt();
            if (version != 4) disableMtuDiscovery = input.readBoolean(); // note: skip 4
        }
        if (version >= 5) {
            hopInterval = input.readInt();
        }
        if (version >= 6) {
            serverPorts = input.readString();
        } else {
            // old update to new
            if (HysteriaFmtKt.isMultiPort(serverAddress)) {
                serverPorts = StringsKt.substringAfterLast(serverAddress, ":", serverAddress);
                serverAddress = StringsKt.substringBeforeLast(serverAddress, ":", serverAddress);
            } else {
                serverPorts = serverPort.toString();
            }
        }
        if (version >= 8) {
            hopIntervalMax = input.readString();
            bbrProfile = input.readString();
            brutalDebug = input.readBoolean();
            obfsType = input.readString();
            geckoMinPacketSize = input.readInt();
            geckoMaxPacketSize = input.readInt();
            realmServerUrl = input.readString();
            realmToken = input.readString();
            realmId = input.readString();
            realmStunServers = input.readString();
            realmIpVersion = input.readInt();
            realmPortMapping = input.readBoolean();
            realmPortMappingTimeout = input.readString();
            realmPortMappingLifetime = input.readString();
        }
    }

    @Override
    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPorts;
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("hysteria");
    }

    @NotNull
    @Override
    public HysteriaBean clone() {
        return KryoConverters.deserialize(new HysteriaBean(), KryoConverters.serialize(this));
    }

    public static final Creator<HysteriaBean> CREATOR = new CREATOR<HysteriaBean>() {
        @NonNull
        @Override
        public HysteriaBean newInstance() {
            return new HysteriaBean();
        }

        @Override
        public HysteriaBean[] newArray(int size) {
            return new HysteriaBean[size];
        }
    };
}
