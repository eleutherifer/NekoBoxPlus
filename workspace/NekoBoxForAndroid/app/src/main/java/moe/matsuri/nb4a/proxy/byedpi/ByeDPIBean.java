package moe.matsuri.nb4a.proxy.byedpi;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class ByeDPIBean extends AbstractBean {

    public String cliStrategy;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (cliStrategy == null) cliStrategy = "";
    }

    @Override
    public boolean canICMPing() {
        return false;
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @Override
    public String displayAddress() {
        return "byedpi";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(cliStrategy);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt();
        super.deserialize(input);
        cliStrategy = input.readString();
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("byedpi");
    }

    @NotNull
    @Override
    public ByeDPIBean clone() {
        return KryoConverters.deserialize(new ByeDPIBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ByeDPIBean> CREATOR = new CREATOR<ByeDPIBean>() {
        @NonNull
        @Override
        public ByeDPIBean newInstance() {
            return new ByeDPIBean();
        }

        @Override
        public ByeDPIBean[] newArray(int size) {
            return new ByeDPIBean[size];
        }
    };
}
