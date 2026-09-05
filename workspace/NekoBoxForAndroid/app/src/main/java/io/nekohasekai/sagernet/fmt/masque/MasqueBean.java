package io.nekohasekai.sagernet.fmt.masque;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class MasqueBean extends AbstractBean {

    public Boolean useHTTP2;
    public Boolean useIPv6;
    public String allowedIPs;

    public String profileId;
    public String profileAuthToken;
    public String profilePrivateKey;
    public Boolean profileRecreate;
    public Long profileDetour;

    public String configPrivateKey;
    public String configEndpointV4;
    public String configEndpointV6;
    public String configEndpointH2V4;
    public String configEndpointH2V6;
    public String configEndpointPubKey;
    public String configLicense;
    public String configId;
    public String configAccessToken;
    public String configIPv4;
    public String configIPv6;

    public String udpTimeout;
    public String udpKeepalivePeriod;
    public Integer udpInitialPacketSize;
    public String reconnectDelay;

    public String tlsSNI;
    public Boolean tlsInsecure;
    public String tlsCipherSuites;
    public String tlsCurvePreferences;
    public Boolean tlsFragment;
    public String tlsFragmentFallbackDelay;
    public Boolean tlsRecordFragment;
    public Boolean tlsKernelTx;
    public Boolean tlsKernelRx;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (useHTTP2 == null) useHTTP2 = false;
        if (useIPv6 == null) useIPv6 = false;
        if (allowedIPs == null) allowedIPs = "";
        if (profileId == null) profileId = "";
        if (profileAuthToken == null) profileAuthToken = "";
        if (profilePrivateKey == null) profilePrivateKey = "";
        if (profileRecreate == null) profileRecreate = false;
        if (profileDetour == null) profileDetour = 0L;
        if (configPrivateKey == null) configPrivateKey = "";
        if (configEndpointV4 == null) configEndpointV4 = "";
        if (configEndpointV6 == null) configEndpointV6 = "";
        if (configEndpointH2V4 == null) configEndpointH2V4 = "";
        if (configEndpointH2V6 == null) configEndpointH2V6 = "";
        if (configEndpointPubKey == null) configEndpointPubKey = "";
        if (configLicense == null) configLicense = "";
        if (configId == null) configId = "";
        if (configAccessToken == null) configAccessToken = "";
        if (configIPv4 == null) configIPv4 = "";
        if (configIPv6 == null) configIPv6 = "";
        if (udpTimeout == null) udpTimeout = "5m0s";
        if (udpKeepalivePeriod == null) udpKeepalivePeriod = "30s";
        if (udpInitialPacketSize == null) udpInitialPacketSize = 0;
        if (reconnectDelay == null) reconnectDelay = "5s";
        if (tlsSNI == null) tlsSNI = "consumer-masque.cloudflareclient.com";
        if (tlsInsecure == null) tlsInsecure = false;
        if (tlsCipherSuites == null) tlsCipherSuites = "";
        if (tlsCurvePreferences == null) tlsCurvePreferences = "";
        if (tlsFragment == null) tlsFragment = false;
        if (tlsFragmentFallbackDelay == null) tlsFragmentFallbackDelay = "";
        if (tlsRecordFragment == null) tlsRecordFragment = false;
        if (tlsKernelTx == null) tlsKernelTx = false;
        if (tlsKernelRx == null) tlsKernelRx = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeBoolean(useHTTP2);
        output.writeBoolean(useIPv6);
        output.writeString(allowedIPs);
        output.writeString(profileId);
        output.writeString(profileAuthToken);
        output.writeString(profilePrivateKey);
        output.writeBoolean(profileRecreate);
        output.writeLong(profileDetour);
        output.writeString(configPrivateKey);
        output.writeString(configEndpointV4);
        output.writeString(configEndpointV6);
        output.writeString(configEndpointH2V4);
        output.writeString(configEndpointH2V6);
        output.writeString(configEndpointPubKey);
        output.writeString(configLicense);
        output.writeString(configId);
        output.writeString(configAccessToken);
        output.writeString(configIPv4);
        output.writeString(configIPv6);
        output.writeString(udpTimeout);
        output.writeString(udpKeepalivePeriod);
        output.writeInt(udpInitialPacketSize);
        output.writeString(reconnectDelay);
        output.writeString(tlsSNI);
        output.writeBoolean(tlsInsecure);
        output.writeString(tlsCipherSuites);
        output.writeString(tlsCurvePreferences);
        output.writeBoolean(tlsFragment);
        output.writeString(tlsFragmentFallbackDelay);
        output.writeBoolean(tlsRecordFragment);
        output.writeBoolean(tlsKernelTx);
        output.writeBoolean(tlsKernelRx);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version == 0) {
            input.readBoolean();
            input.readString();
        }
        useHTTP2 = input.readBoolean();
        useIPv6 = input.readBoolean();
        allowedIPs = input.readString();
        profileId = input.readString();
        profileAuthToken = input.readString();
        profilePrivateKey = input.readString();
        profileRecreate = input.readBoolean();
        profileDetour = input.readLong();
        if (version >= 3) {
            configPrivateKey = input.readString();
            configEndpointV4 = input.readString();
            configEndpointV6 = input.readString();
            configEndpointH2V4 = input.readString();
            configEndpointH2V6 = input.readString();
            configEndpointPubKey = input.readString();
            configLicense = input.readString();
            configId = input.readString();
            configAccessToken = input.readString();
            configIPv4 = input.readString();
            configIPv6 = input.readString();
        } else {
            configPrivateKey = "";
            configEndpointV4 = "";
            configEndpointV6 = "";
            configEndpointH2V4 = "";
            configEndpointH2V6 = "";
            configEndpointPubKey = "";
            configLicense = "";
            configId = "";
            configAccessToken = "";
            configIPv4 = "";
            configIPv6 = "";
        }
        udpTimeout = input.readString();
        udpKeepalivePeriod = input.readString();
        udpInitialPacketSize = input.readInt();
        reconnectDelay = input.readString();
        if (version >= 2) {
            tlsSNI = input.readString();
        } else {
            tlsSNI = "consumer-masque.cloudflareclient.com";
        }
        tlsInsecure = input.readBoolean();
        tlsCipherSuites = input.readString();
        tlsCurvePreferences = input.readString();
        tlsFragment = input.readBoolean();
        tlsFragmentFallbackDelay = input.readString();
        tlsRecordFragment = input.readBoolean();
        tlsKernelTx = input.readBoolean();
        tlsKernelRx = input.readBoolean();
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("masque");
    }

    @Override
    public String displayAddress() {
        if (!Objects.equals(configEndpointV4, "")) {
            return configEndpointV4;
        }

        if (!Objects.equals(configEndpointV6, "")) {
            return configEndpointV6;
        }

        if (!Objects.equals(configEndpointH2V4, "")) {
            return configEndpointH2V4;
        }

        if (!Objects.equals(configEndpointH2V6, "")) {
            return configEndpointH2V6;
        }

        return "MASQUE";
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public MasqueBean clone() {
        return KryoConverters.deserialize(new MasqueBean(), KryoConverters.serialize(this));
    }

    public static final Creator<MasqueBean> CREATOR = new CREATOR<MasqueBean>() {
        @NonNull
        @Override
        public MasqueBean newInstance() {
            return new MasqueBean();
        }

        @Override
        public MasqueBean[] newArray(int size) {
            return new MasqueBean[size];
        }
    };
}
