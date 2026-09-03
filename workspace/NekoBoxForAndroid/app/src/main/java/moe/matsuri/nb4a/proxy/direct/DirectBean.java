package moe.matsuri.nb4a.proxy.direct;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.fmt.internal.InternalBean;
import moe.matsuri.nb4a.utils.JavaUtil;

/**
 * Profile that produces a sing-box "direct" outbound. It has no server of its own;
 * its only purpose is to let the user keep routing (notably AdBlock) running without
 * any real proxy.
 */
public class DirectBean extends InternalBean {

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "Direct";
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0); // version
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt(); // version
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("direct");
    }

    @NotNull
    @Override
    public DirectBean clone() {
        return KryoConverters.deserialize(new DirectBean(), KryoConverters.serialize(this));
    }

    public static final Creator<DirectBean> CREATOR = new CREATOR<DirectBean>() {
        @NonNull
        @Override
        public DirectBean newInstance() {
            return new DirectBean();
        }

        @Override
        public DirectBean[] newArray(int size) {
            return new DirectBean[size];
        }
    };
}
