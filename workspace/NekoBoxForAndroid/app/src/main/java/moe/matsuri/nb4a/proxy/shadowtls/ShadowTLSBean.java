package moe.matsuri.nb4a.proxy.shadowtls;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean;

public class ShadowTLSBean extends StandardV2RayBean {

    public Integer version;
    public String password;
    public Integer proxyProtocol;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        security = "tls";
        if (version == null) version = 3;
        if (password == null) password = "";
        if (proxyProtocol == null) proxyProtocol = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeInt(version);
        output.writeString(password);
        output.writeInt(proxyProtocol);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version_ = input.readInt();
        super.deserialize(input);
        version = input.readInt();
        password = input.readString();
        if (version_ >= 1) proxyProtocol = input.readInt();
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("shadowtls");
    }

    @NotNull
    @Override
    public ShadowTLSBean clone() {
        return KryoConverters.deserialize(new ShadowTLSBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowTLSBean> CREATOR = new CREATOR<ShadowTLSBean>() {
        @NonNull
        @Override
        public ShadowTLSBean newInstance() {
            return new ShadowTLSBean();
        }

        @Override
        public ShadowTLSBean[] newArray(int size) {
            return new ShadowTLSBean[size];
        }
    };
}
