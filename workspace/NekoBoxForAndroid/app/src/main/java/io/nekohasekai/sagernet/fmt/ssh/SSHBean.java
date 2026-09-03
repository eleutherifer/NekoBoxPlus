package io.nekohasekai.sagernet.fmt.ssh;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SSHBean extends AbstractBean {

    public static final int AUTH_TYPE_NONE = 0;
    public static final int AUTH_TYPE_PASSWORD = 1;
    public static final int AUTH_TYPE_PRIVATE_KEY = 2;

    public String username;
    public Integer authType;
    public String password;
    public String privateKey;
    public String privateKeyPath;
    public String privateKeyPassphrase;
    public String publicKey;
    public String hostKeyAlgorithms;
    public String clientVersion;
    public String cipher;
    public String mac;
    public String kexAlgorithm;

    @Override
    public void initializeDefaultValues() {
        if (serverPort == null) serverPort = 22;

        super.initializeDefaultValues();

        if (username == null) username = "root";
        if (authType == null) authType = AUTH_TYPE_PASSWORD;
        if (password == null) password = "";
        if (privateKey == null) privateKey = "";
        if (privateKeyPath == null) privateKeyPath = "";
        if (privateKeyPassphrase == null) privateKeyPassphrase = "";
        if (publicKey == null) publicKey = "";
        if (hostKeyAlgorithms == null) hostKeyAlgorithms = "";
        if (clientVersion == null) clientVersion = "";
        if (cipher == null) cipher = "";
        if (mac == null) mac = "";
        if (kexAlgorithm == null) kexAlgorithm = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(2);
        super.serialize(output);
        output.writeString(username);
        output.writeInt(authType);
        switch (authType) {
            case AUTH_TYPE_NONE:
                break;
            case AUTH_TYPE_PASSWORD:
                output.writeString(password);
                break;
            case AUTH_TYPE_PRIVATE_KEY:
                output.writeString(privateKey);
                output.writeString(privateKeyPassphrase);
                break;
        }
        output.writeString(publicKey);
        output.writeString(hostKeyAlgorithms);
        output.writeString(clientVersion);
        output.writeString(cipher);
        output.writeString(mac);
        output.writeString(kexAlgorithm);
        output.writeString(privateKeyPath);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        username = input.readString();
        authType = input.readInt();
        switch (authType) {
            case AUTH_TYPE_NONE:
                break;
            case AUTH_TYPE_PASSWORD:
                password = input.readString();
                break;
            case AUTH_TYPE_PRIVATE_KEY:
                privateKey = input.readString();
                privateKeyPassphrase = input.readString();
                break;
        }
        publicKey = input.readString();
        if (version >= 1) {
            hostKeyAlgorithms = input.readString();
            clientVersion = input.readString();
            cipher = input.readString();
            mac = input.readString();
            kexAlgorithm = input.readString();
        }
        if (version >= 2) {
            privateKeyPath = input.readString();
        }
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("ssh");
    }

    @NotNull
    @Override
    public SSHBean clone() {
        return KryoConverters.deserialize(new SSHBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SSHBean> CREATOR = new CREATOR<SSHBean>() {
        @NonNull
        @Override
        public SSHBean newInstance() {
            return new SSHBean();
        }

        @Override
        public SSHBean[] newArray(int size) {
            return new SSHBean[size];
        }
    };
}
