package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

import static io.nekohasekai.sagernet.ConstantsKt.CONNECTION_TEST_URL;

public class ProxySetBean extends InternalBean {

    public static final int TYPE_LIST = 0;
    public static final int TYPE_GROUP = 1;

    public static final int MODE_SELECTOR = 0;
    public static final int MODE_URL_TEST = 1;

    public int mode = MODE_SELECTOR;
    public long defaultOutbound = 0L;

    public int type = TYPE_LIST;
    public List<Long> proxies = new ArrayList<>();
    public String embeddedProfilesJson = "[]";
    public long groupId = 0L;
    public String groupFilterNotRegex = "";
    public boolean skipInsecureProfiles = false;

    public boolean interruptExistConnections = false;

    public String testURL = CONNECTION_TEST_URL;
    public String testInterval = "3m";
    public String testIdleTimeout = "3m";
    public int testTolerance = 50;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (type != TYPE_LIST && type != TYPE_GROUP) {
            type = TYPE_LIST;
        }
        if (mode != MODE_SELECTOR && mode != MODE_URL_TEST) {
            mode = MODE_SELECTOR;
        }
        if (proxies == null) proxies = new ArrayList<>();
        if (JavaUtil.isNullOrBlank(embeddedProfilesJson)) embeddedProfilesJson = "[]";
        if (groupFilterNotRegex == null) groupFilterNotRegex = "";
        if (JavaUtil.isNullOrBlank(testURL)) testURL = CONNECTION_TEST_URL;
        if (JavaUtil.isNullOrBlank(testInterval)) testInterval = "3m";
        if (JavaUtil.isNullOrBlank(testIdleTimeout)) testIdleTimeout = "3m";
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        }
        int hash = Math.abs(hashCode());
        return "URLTest " + hash;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(4);
        output.writeBoolean(interruptExistConnections);
        output.writeString(testURL);
        output.writeString(testInterval);
        output.writeString(testIdleTimeout);
        output.writeInt(testTolerance);

        output.writeInt(type);
        if (type == TYPE_GROUP) {
            output.writeLong(groupId);
            output.writeString(groupFilterNotRegex);
        } else {
            output.writeInt(proxies.size());
            for (Long proxy : proxies) {
                output.writeLong(proxy);
            }
        }

        output.writeInt(mode);
        output.writeLong(defaultOutbound);
        output.writeBoolean(skipInsecureProfiles);
        output.writeString(embeddedProfilesJson);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        interruptExistConnections = input.readBoolean();
        testURL = input.readString();
        testInterval = input.readString();
        testIdleTimeout = input.readString();
        testTolerance = input.readInt();

        type = input.readInt();
        if (type == TYPE_GROUP) {
            groupId = input.readLong();
            if (version >= 1) {
                groupFilterNotRegex = input.readString();
            }
        } else {
            int length = input.readInt();
            proxies = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                proxies.add(input.readLong());
            }
        }
        if (version >= 2) {
            mode = input.readInt();
            defaultOutbound = input.readLong();
        } else {
            mode = MODE_URL_TEST;
            defaultOutbound = 0L;
        }
        if (version >= 3) {
            skipInsecureProfiles = input.readBoolean();
        } else {
            skipInsecureProfiles = false;
        }
        if (version >= 4) {
            embeddedProfilesJson = input.readString();
        } else {
            embeddedProfilesJson = "[]";
        }
    }

    public String displayType() {
        return mode == MODE_URL_TEST ? "URLTest" : "Selector";
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("proxy-set");
    }

    @NotNull
    @Override
    public ProxySetBean clone() {
        return KryoConverters.deserialize(new ProxySetBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ProxySetBean> CREATOR = new CREATOR<ProxySetBean>() {
        @NonNull
        @Override
        public ProxySetBean newInstance() {
            return new ProxySetBean();
        }

        @Override
        public ProxySetBean[] newArray(int size) {
            return new ProxySetBean[size];
        }
    };
}
