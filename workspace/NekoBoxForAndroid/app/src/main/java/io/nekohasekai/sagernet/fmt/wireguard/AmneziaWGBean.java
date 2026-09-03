package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class AmneziaWGBean extends AbstractBean {

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public String peerPersistentKeepalive;
    public Integer mtu;
    public String reserved;

    // AWG 1.0 obfuscation parameters
    public Integer jc;
    public Integer jmin;
    public Integer jmax;
    public Integer s1;
    public Integer s2;
    public String h1;
    public String h2;
    public String h3;
    public String h4;

    // AWG 1.5 signature chain parameters
    public String i1;
    public String i2;
    public String i3;
    public String i4;
    public String i5;

    // AWG 2.0 additional packet padding parameters
    public Integer s3;
    public Integer s4;

    // AWG 3.0 parameters
    public String headerProtectionKey;
    public String contentPaddingAddition;
    public String rekeyAfterTime;
    public String rekeyTimeout;
    public String rejectAfterTime;
    public String keepaliveTimeout;
    public String maxHandshakeAttempts;

    // AWG 3.1 parameters
    public Boolean randomTrailers;
    public Boolean disableCookies;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (peerPersistentKeepalive == null) peerPersistentKeepalive = "0";
        if (mtu == null) mtu = 1280;
        if (reserved == null) reserved = "";
        if (jc == null) jc = 3;
        if (jmin == null) jmin = 50;
        if (jmax == null) jmax = 1000;
        if (s1 == null) s1 = 0;
        if (s2 == null) s2 = 0;
        if (h1 == null) h1 = "1";
        if (h2 == null) h2 = "2";
        if (s3 == null) s3 = 0;
        if (s4 == null) s4 = 0;
        if (h3 == null) h3 = "3";
        if (h4 == null) h4 = "4";
        if (i1 == null) i1 = "";
        if (i2 == null) i2 = "";
        if (i3 == null) i3 = "";
        if (i4 == null) i4 = "";
        if (i5 == null) i5 = "";
        if (headerProtectionKey == null) headerProtectionKey = "";
        if (contentPaddingAddition == null) contentPaddingAddition = "";
        if (rekeyAfterTime == null) rekeyAfterTime = "";
        if (rekeyTimeout == null) rekeyTimeout = "";
        if (rejectAfterTime == null) rejectAfterTime = "";
        if (keepaliveTimeout == null) keepaliveTimeout = "";
        if (maxHandshakeAttempts == null) maxHandshakeAttempts = "";
        if (randomTrailers == null) randomTrailers = false;
        if (disableCookies == null) disableCookies = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4); // serialization version
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeString(peerPersistentKeepalive);
        output.writeInt(mtu);
        output.writeString(reserved);
        output.writeInt(jc);
        output.writeInt(jmin);
        output.writeInt(jmax);
        output.writeInt(s1);
        output.writeInt(s2);
        output.writeString(h1);
        output.writeString(h2);
        output.writeInt(s3);
        output.writeInt(s4);
        output.writeString(h3);
        output.writeString(h4);
        output.writeString(i1);
        output.writeString(i2);
        output.writeString(i3);
        output.writeString(i4);
        output.writeString(i5);
        output.writeString(headerProtectionKey);
        output.writeString(contentPaddingAddition);
        output.writeString(rekeyAfterTime);
        output.writeString(rekeyTimeout);
        output.writeString(rejectAfterTime);
        output.writeString(keepaliveTimeout);
        output.writeString(maxHandshakeAttempts);
        output.writeBoolean(randomTrailers);
        output.writeBoolean(disableCookies);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        if (version >= 3) {
            peerPersistentKeepalive = input.readString();
        } else if (version >= 2) {
            peerPersistentKeepalive = Integer.toString(input.readInt());
        }
        mtu = input.readInt();
        if (version >= 2) {
            reserved = input.readString();
        }
        jc = input.readInt();
        jmin = input.readInt();
        jmax = input.readInt();
        s1 = input.readInt();
        s2 = input.readInt();
        h1 = input.readString();
        h2 = input.readString();
        s3 = input.readInt();
        s4 = input.readInt();
        h3 = input.readString();
        h4 = input.readString();
        i1 = input.readString();
        i2 = input.readString();
        i3 = input.readString();
        i4 = input.readString();
        i5 = input.readString();
        if (version >= 3) {
            headerProtectionKey = input.readString();
            contentPaddingAddition = input.readString();
            rekeyAfterTime = input.readString();
            rekeyTimeout = input.readString();
            rejectAfterTime = input.readString();
            keepaliveTimeout = input.readString();
            maxHandshakeAttempts = input.readString();
        }
        if (version >= 4) {
            randomTrailers = input.readBoolean();
            disableCookies = input.readBoolean();
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("amneziawg");
    }

    @NotNull
    @Override
    public AmneziaWGBean clone() {
        return KryoConverters.deserialize(new AmneziaWGBean(), KryoConverters.serialize(this));
    }

    public static final Creator<AmneziaWGBean> CREATOR = new CREATOR<AmneziaWGBean>() {
        @NonNull
        @Override
        public AmneziaWGBean newInstance() {
            return new AmneziaWGBean();
        }

        @Override
        public AmneziaWGBean[] newArray(int size) {
            return new AmneziaWGBean[size];
        }
    };
}
