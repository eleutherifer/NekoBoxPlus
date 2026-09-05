/******************************************************************************
 * Copyright (C) 2022 by nekohasekai <contact-git@sekai.icu>                  *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.mieru;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.KotlinUtilKt;
import moe.matsuri.nb4a.utils.NGUtil;

public class MieruBean extends AbstractBean {

    public static final int PROTOCOL_TCP = 0;
    public static final int PROTOCOL_UDP = 1;

    public static final int MULTIPLEXING_DEFAULT = 0;
    public static final int MULTIPLEXING_OFF = 1;
    public static final int MULTIPLEXING_LOW = 2;
    public static final int MULTIPLEXING_MIDDLE = 3;
    public static final int MULTIPLEXING_HIGH = 4;

    public static final int HANDSHAKE_DEFAULT = 2;
    public static final int HANDSHAKE_STANDARD = 0;
    public static final int HANDSHAKE_NO_WAIT = 1;

    public Integer protocol;
    public String username;
    public String password;
    public Integer mtu;
    public Integer multiplexingLevel;
    public Integer handshakeMode;
    public String portRange;
    public String trafficPattern;
    public String lowEntropyMode;
    public String lowEntropyMaskRotation;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocol == null) protocol = PROTOCOL_TCP;
        if (username == null) username = "";
        if (password == null) password = "";
        if (mtu == null) mtu = 1400;
        if (multiplexingLevel == null) multiplexingLevel = MULTIPLEXING_DEFAULT;
        if (handshakeMode == null) handshakeMode = HANDSHAKE_DEFAULT;
        if (portRange == null) portRange = "";
        if (trafficPattern == null) trafficPattern = "";
        if (lowEntropyMode == null) lowEntropyMode = "";
        if (lowEntropyMaskRotation == null) lowEntropyMaskRotation = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        initializeDefaultValues();
        output.writeInt(5);
        super.serialize(output);
        output.writeInt(protocol);
        output.writeString(username);
        output.writeString(password);
        if (protocol == PROTOCOL_UDP) {
            output.writeInt(mtu);
        }
        output.writeInt(multiplexingLevel);
        output.writeInt(handshakeMode);
        output.writeString(portRange);
        output.writeString(trafficPattern);
        output.writeString(lowEntropyMode);
        output.writeString(lowEntropyMaskRotation);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version == 0) {
            String oldProtocol = input.readString();
            username = input.readString();
            password = input.readString();
            protocol = "UDP".equals(oldProtocol) ? PROTOCOL_UDP : PROTOCOL_TCP;
            if (protocol == PROTOCOL_UDP) {
                mtu = input.readInt();
            }
            return;
        }
        protocol = input.readInt();
        username = input.readString();
        password = input.readString();
        if (protocol == PROTOCOL_UDP) {
            mtu = input.readInt();
        }
        if (version >= 1) {
            multiplexingLevel = input.readInt();
        }
        if (version >= 2) {
            handshakeMode = input.readInt();
        }
        if (version >= 3) {
            portRange = input.readString();
        }
        if (version >= 4) {
            trafficPattern = input.readString();
        }
        if (version >= 5) {
            lowEntropyMode = input.readString();
            lowEntropyMaskRotation = input.readString();
        }
    }

    public void applyFeatureSettings(AbstractBean other) {
        if (!(other instanceof MieruBean)) return;
        MieruBean bean = (MieruBean) other;
        bean.multiplexingLevel = multiplexingLevel;
        bean.handshakeMode = handshakeMode;
        bean.mtu = mtu;
        bean.trafficPattern = trafficPattern;
        bean.lowEntropyMode = lowEntropyMode;
        bean.lowEntropyMaskRotation = lowEntropyMaskRotation;
    }

    @Override
    public String displayAddress() {
        if (portRange == null || portRange.isEmpty()) {
            return super.displayAddress();
        }
        if (NGUtil.INSTANCE.isIpv6Address(serverAddress)) {
            return "[" + serverAddress + "]:" + String.join(",", KotlinUtilKt.listByLineOrComma(portRange));
        } else {
            return serverAddress + ":" + String.join(",", KotlinUtilKt.listByLineOrComma(portRange));
        }
    }

    @Override
    public String network() {
        if (protocol != null && protocol == PROTOCOL_UDP) {
            return "udp";
        }
        return "tcp";
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("mieru");
    }

    @NotNull
    @Override
    public MieruBean clone() {
        return KryoConverters.deserialize(new MieruBean(), KryoConverters.serialize(this));
    }

    public static final Creator<MieruBean> CREATOR = new CREATOR<MieruBean>() {
        @NonNull
        @Override
        public MieruBean newInstance() {
            return new MieruBean();
        }

        @Override
        public MieruBean[] newArray(int size) {
            return new MieruBean[size];
        }
    };
}
