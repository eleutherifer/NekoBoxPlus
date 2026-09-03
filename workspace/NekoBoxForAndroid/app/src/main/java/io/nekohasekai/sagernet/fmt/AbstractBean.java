package io.nekohasekai.sagernet.fmt;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import io.nekohasekai.sagernet.ktx.JsonHashNormalizer;
import io.nekohasekai.sagernet.ktx.NetsKt;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class AbstractBean extends Serializable {

    public String serverAddress;
    public Integer serverPort;

    public String name;

    //

    public String customOutboundJson;
    public String customConfigJson;

    // sing-box 1.13 dial options shared by native outbounds/endpoints.
    public Boolean disableTcpKeepAlive;
    public String tcpKeepAlive;
    public String tcpKeepAliveInterval;
    public Boolean tcpFastOpen;
    public Boolean tcpMultiPath;
    public Boolean udpFragment;

    // Android-usable sing-box 1.13 outbound TLS options.
    // These are persisted explicitly below and mapped into sing-box TLS options.
    // Keep them out of Gson: some profiles have fields with the same names and
    // Gson rejects a class hierarchy containing duplicate JSON field names.
    public transient String tlsCurvePreferences;
    public transient String tlsCertificatePublicKeySha256;
    public transient String tlsXrayCertificateSha256;
    public transient String tlsClientCertificate;
    public transient String tlsClientKey;
    public transient String echQueryServerName;
    public transient String tlsHandshakeTimeout;

    // sing-box 1.14 QUIC options shared by Hysteria and TUIC.
    public transient String quicIdleTimeout;
    public transient String quicKeepAlivePeriod;
    public transient Long quicStreamReceiveWindow;
    public transient Long quicConnectionReceiveWindow;
    public transient Integer quicMaxConcurrentStreams;
    public transient Integer quicInitialPacketSize;
    public transient Boolean quicDisablePathMtuDiscovery;

    //
    public transient String finalAddress;
    public transient int finalPort;

    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return displayAddress();
        }
    }

    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPort;
    }

    public String network() {
        return "tcp,udp";
    }

    public boolean canICMPing() {
        return true;
    }

    public boolean canTCPing() {
        return true;
    }

    public boolean canMapping() {
        return true;
    }

    @Override
    public void initializeDefaultValues() {
        if (JavaUtil.isNullOrBlank(serverAddress)) {
            serverAddress = "127.0.0.1";
        } else if (serverAddress.startsWith("[") && serverAddress.endsWith("]")) {
            serverAddress = NetsKt.unwrapIPV6Host(serverAddress);
        }
        if (serverPort == null) {
            serverPort = 1080;
        }
        if (name == null) name = "";

        finalAddress = serverAddress;
        finalPort = serverPort;

        if (customOutboundJson == null) customOutboundJson = "";
        if (customConfigJson == null) customConfigJson = "";
        if (disableTcpKeepAlive == null) disableTcpKeepAlive = false;
        if (tcpKeepAlive == null) tcpKeepAlive = "";
        if (tcpKeepAliveInterval == null) tcpKeepAliveInterval = "";
        if (tcpFastOpen == null) tcpFastOpen = false;
        if (tcpMultiPath == null) tcpMultiPath = false;
        if (tlsCurvePreferences == null) tlsCurvePreferences = "";
        if (tlsCertificatePublicKeySha256 == null) tlsCertificatePublicKeySha256 = "";
        if (tlsXrayCertificateSha256 == null) tlsXrayCertificateSha256 = "";
        if (tlsClientCertificate == null) tlsClientCertificate = "";
        if (tlsClientKey == null) tlsClientKey = "";
        if (echQueryServerName == null) echQueryServerName = "";
        if (tlsHandshakeTimeout == null) tlsHandshakeTimeout = "";
        if (quicIdleTimeout == null) quicIdleTimeout = "";
        if (quicKeepAlivePeriod == null) quicKeepAlivePeriod = "";
        if (quicStreamReceiveWindow == null) quicStreamReceiveWindow = 0L;
        if (quicConnectionReceiveWindow == null) quicConnectionReceiveWindow = 0L;
        if (quicMaxConcurrentStreams == null) quicMaxConcurrentStreams = 0;
        if (quicInitialPacketSize == null) quicInitialPacketSize = 0;
        if (quicDisablePathMtuDiscovery == null) quicDisablePathMtuDiscovery = false;
    }


    @Override
    public void serializeToBuffer(@NonNull ByteBufferOutput output) {
        serialize(output);

        output.writeInt(4);
        output.writeString(name);
        output.writeString(customOutboundJson);
        output.writeString(customConfigJson);
        output.writeBoolean(disableTcpKeepAlive);
        output.writeString(tcpKeepAlive);
        output.writeString(tcpKeepAliveInterval);
        output.writeString(tlsCurvePreferences);
        output.writeString(tlsCertificatePublicKeySha256);
        output.writeString(tlsClientCertificate);
        output.writeString(tlsClientKey);
        output.writeString(echQueryServerName);
        output.writeString(tlsHandshakeTimeout);
        output.writeString(quicIdleTimeout);
        output.writeString(quicKeepAlivePeriod);
        output.writeLong(quicStreamReceiveWindow);
        output.writeLong(quicConnectionReceiveWindow);
        output.writeInt(quicMaxConcurrentStreams);
        output.writeInt(quicInitialPacketSize);
        output.writeBoolean(quicDisablePathMtuDiscovery);
        output.writeBoolean(tcpFastOpen);
        output.writeBoolean(tcpMultiPath);
        output.writeString(udpFragment == null ? "" : udpFragment.toString());
        output.writeString(tlsXrayCertificateSha256);
    }

    @Override
    public void deserializeFromBuffer(@NonNull ByteBufferInput input) {
        deserialize(input);

        int extraVersion = input.readInt();

        name = input.readString();
        customOutboundJson = input.readString();
        customConfigJson = input.readString();
        if (extraVersion >= 2) {
            disableTcpKeepAlive = input.readBoolean();
            tcpKeepAlive = input.readString();
            tcpKeepAliveInterval = input.readString();
            tlsCurvePreferences = input.readString();
            tlsCertificatePublicKeySha256 = input.readString();
            tlsClientCertificate = input.readString();
            tlsClientKey = input.readString();
            echQueryServerName = input.readString();
        }
        int remainingExtraBytes = input.limit() - input.position();
        boolean legacyPlusVersion3 = extraVersion == 3 && remainingExtraBytes < 28;
        if (extraVersion >= 3 && !legacyPlusVersion3) {
            tlsHandshakeTimeout = input.readString();
            quicIdleTimeout = input.readString();
            quicKeepAlivePeriod = input.readString();
            quicStreamReceiveWindow = input.readLong();
            quicConnectionReceiveWindow = input.readLong();
            quicMaxConcurrentStreams = input.readInt();
            quicInitialPacketSize = input.readInt();
            quicDisablePathMtuDiscovery = input.readBoolean();
        }
        if (extraVersion >= 4 || legacyPlusVersion3) {
            tcpFastOpen = input.readBoolean();
            tcpMultiPath = input.readBoolean();
            String udpFragmentValue = input.readString();
            if ("true".equalsIgnoreCase(udpFragmentValue)) {
                udpFragment = true;
            } else if ("false".equalsIgnoreCase(udpFragmentValue)) {
                udpFragment = false;
            }
        }
        if (extraVersion >= 4) {
            tlsXrayCertificateSha256 = input.readString();
        }
    }

    public void serialize(ByteBufferOutput output) {
        output.writeString(serverAddress);
        output.writeInt(serverPort);
    }

    public void deserialize(ByteBufferInput input) {
        serverAddress = input.readString();
        serverPort = input.readInt();
    }

    @NotNull
    @Override
    public abstract AbstractBean clone();

    @NotNull
    public abstract String getHash();

    protected void normalizeJsonFieldsForHash() {
        customOutboundJson = JsonHashNormalizer.normalizeJsonStringOrRaw(customOutboundJson);
        customConfigJson = JsonHashNormalizer.normalizeJsonStringOrRaw(customConfigJson);
    }

    @NotNull
    protected final String buildTypedHash(@NotNull String type) {
        AbstractBean copy = copyWithoutName();
        copy.normalizeJsonFieldsForHash();
        byte[] data = KryoConverters.serialize(copy);
        long hash = 0xcbf29ce484222325L;
        for (byte datum : data) {
            hash ^= datum & 0xffL;
            hash *= 0x100000001b3L;
        }
        return type + ':' + Long.toUnsignedString(hash, 16);
    }

    private byte[] serializeWithoutName() {
        return KryoConverters.serialize(copyWithoutName());
    }

    private AbstractBean copyWithoutName() {
        AbstractBean copy = clone();
        copy.name = "";
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Arrays.equals(serializeWithoutName(), ((AbstractBean) o).serializeWithoutName());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(serializeWithoutName());
    }

    @NotNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + JavaUtil.gson.toJson(this);
    }
}
