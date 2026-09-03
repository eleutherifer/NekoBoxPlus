package io.nekohasekai.sagernet.fmt.trusttunnel;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class TrustTunnelBean extends AbstractBean {

    public String username;
    public String password;
    public Boolean healthCheck;

    public Boolean quic;
    public Boolean forceQuic;
    public Boolean useCronetQuic;
    public Boolean useCronetHttps;
    public String quicCongestionControl;

    public String serverName;
    public String alpn;
    public String certificates;
    public String certPublicKeySha256;
    public String utlsFingerprint;
    public Boolean allowInsecure;
    public Boolean tlsFragment;
    public String tlsFragmentFallbackDelay;
    public Boolean tlsRecordFragment;
    public Boolean ech;
    public String echConfig;
    public String echQueryServerName;
    public String clientCert;
    public String clientKey;
    public String tlsSpoof;
    public String tlsSpoofMethod;
    public String clientRandomPrefix;

    private void migrateLegacyCronetFingerprint() {
        if ("cronet".equals(utlsFingerprint)) {
            useCronetHttps = true;
            utlsFingerprint = "";
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (serverPort == null || serverPort == 1080) serverPort = 443;
        if (username == null) username = "";
        if (password == null) password = "";
        if (healthCheck == null) healthCheck = false;
        if (quic == null) quic = false;
        if (forceQuic == null) forceQuic = false;
        if (useCronetQuic == null) useCronetQuic = false;
        if (useCronetHttps == null) useCronetHttps = false;
        if (quicCongestionControl == null) quicCongestionControl = "bbr";
        if (serverName == null) serverName = "";
        if (alpn == null) alpn = "";
        if (certificates == null) certificates = "";
        if (certPublicKeySha256 == null) certPublicKeySha256 = "";
        if (utlsFingerprint == null) utlsFingerprint = "firefox";
        if (allowInsecure == null) allowInsecure = false;
        if (tlsFragment == null) tlsFragment = false;
        if (tlsFragmentFallbackDelay == null) tlsFragmentFallbackDelay = "0s";
        if (tlsRecordFragment == null) tlsRecordFragment = false;
        if (ech == null) ech = false;
        if (echConfig == null) echConfig = "";
        if (echQueryServerName == null) echQueryServerName = "";
        if (clientCert == null) clientCert = "";
        if (clientKey == null) clientKey = "";
        if (tlsSpoof == null) tlsSpoof = "";
        if (tlsSpoofMethod == null) tlsSpoofMethod = "";
        if (clientRandomPrefix == null) clientRandomPrefix = "";
        migrateLegacyCronetFingerprint();
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        migrateLegacyCronetFingerprint();
        output.writeInt(4);
        super.serialize(output);
        output.writeString(username);
        output.writeString(password);
        output.writeBoolean(healthCheck);
        output.writeBoolean(quic);
        output.writeBoolean(forceQuic);
        output.writeBoolean(useCronetQuic);
        output.writeBoolean(useCronetHttps);
        output.writeString(quicCongestionControl);
        output.writeString(serverName);
        output.writeString(alpn);
        output.writeString(certificates);
        output.writeString(certPublicKeySha256);
        output.writeString(utlsFingerprint);
        output.writeBoolean(allowInsecure);
        output.writeBoolean(tlsFragment);
        output.writeString(tlsFragmentFallbackDelay);
        output.writeBoolean(tlsRecordFragment);
        output.writeBoolean(ech);
        output.writeString(echConfig);
        output.writeString(echQueryServerName);
        output.writeString(clientCert);
        output.writeString(clientKey);
        output.writeString(tlsSpoof);
        output.writeString(tlsSpoofMethod);
        output.writeString(clientRandomPrefix);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        password = input.readString();
        healthCheck = input.readBoolean();
        quic = input.readBoolean();
        if (version >= 3) {
            forceQuic = input.readBoolean();
            useCronetQuic = input.readBoolean();
        }
        if (version >= 4) {
            useCronetHttps = input.readBoolean();
        }
        quicCongestionControl = input.readString();
        serverName = input.readString();
        alpn = input.readString();
        certificates = input.readString();
        certPublicKeySha256 = input.readString();
        utlsFingerprint = input.readString();
        allowInsecure = input.readBoolean();
        tlsFragment = input.readBoolean();
        tlsFragmentFallbackDelay = input.readString();
        tlsRecordFragment = input.readBoolean();
        ech = input.readBoolean();
        echConfig = input.readString();
        echQueryServerName = input.readString();
        clientCert = input.readString();
        clientKey = input.readString();
        if (version >= 1) {
            tlsSpoof = input.readString();
            tlsSpoofMethod = input.readString();
        }
        if (version >= 2) {
            clientRandomPrefix = input.readString();
        }
    }

    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof TrustTunnelBean)) return;
        TrustTunnelBean bean = (TrustTunnelBean) other;
        bean.allowInsecure = allowInsecure;
        bean.utlsFingerprint = utlsFingerprint;
        bean.ech = ech;
        bean.echConfig = echConfig;
        bean.tlsFragment = tlsFragment;
        bean.tlsFragmentFallbackDelay = tlsFragmentFallbackDelay;
        bean.tlsRecordFragment = tlsRecordFragment;
        bean.healthCheck = healthCheck;
        bean.quic = quic;
        bean.forceQuic = forceQuic;
        bean.useCronetQuic = useCronetQuic;
        bean.useCronetHttps = useCronetHttps;
        bean.quicCongestionControl = quicCongestionControl;
        bean.clientRandomPrefix = clientRandomPrefix;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("trusttunnel");
    }

    @NotNull
    @Override
    public TrustTunnelBean clone() {
        return KryoConverters.deserialize(new TrustTunnelBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TrustTunnelBean> CREATOR = new CREATOR<TrustTunnelBean>() {
        @NonNull
        @Override
        public TrustTunnelBean newInstance() {
            return new TrustTunnelBean();
        }

        @Override
        public TrustTunnelBean[] newArray(int size) {
            return new TrustTunnelBean[size];
        }
    };
}
