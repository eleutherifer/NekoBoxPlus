package io.nekohasekai.sagernet.fmt.openconnect;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenConnectBean extends AbstractBean {
    public String server;
    public String flavor;
    public String username;
    public String password;
    public String authGroup;
    public String cookie;
    public String tokenMode;
    public String tokenSecret;
    public String tokenPIN;
    public String tokenPassword;
    public String tokenDeviceID;
    public Integer tokenCounter;
    public String reportedOS;
    public String userAgent;
    public String clientVersion;
    public String localHostname;
    public String mobilePlatformVersion;
    public String mobileDeviceType;
    public String mobileDeviceUniqueID;
    public String fortinetHostCheck;
    public String fortinetVirtualDesktopCheck;
    public Boolean noUDP;
    public Integer dtlsLocalPort;
    public Boolean compressionDisabled;
    public String compressionMode;
    public Boolean ipv6Disabled;
    public Boolean httpKeepaliveDisabled;
    public Boolean xmlPostDisabled;
    public Boolean externalAuthDisabled;
    public Boolean passwordAuthenticationDisabled;
    public Boolean tcpKeepAliveEnabled;
    public Boolean pfs;
    public Integer mtu;
    public Integer baseMTU;
    public String dpdInterval;
    public String reconnectTimeout;
    public String trojanInterval;
    public Integer queueLength;
    public Boolean allowInsecureCrypto;
    public String udpTimeout;
    public String caCertificates;
    public String clientCertificate;
    public String clientKey;
    public String clientKeyPassword;
    public String mcaCertificate;
    public String mcaKey;
    public String mcaKeyPassword;
    public Boolean tlsInsecure;
    public String tlsServerName;
    public String tlsPeerFingerprints;
    public Boolean tlsSystemTrustDisabled;
    public String formEntries;
    public String tnccDeviceID;
    public String tnccUserAgent;
    public Boolean tnccMachineIdentification;
    public String tnccCertificates;
    public Boolean usePushedDNS;
    public Boolean acceptPushedDefaultResolvers;
    public Boolean expandPushedSearchDomains;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (server == null) server = "";
        if (flavor == null) flavor = "anyconnect";
        if (username == null) username = "";
        if (password == null) password = "";
        if (authGroup == null) authGroup = "";
        if (cookie == null) cookie = "";
        if (tokenMode == null) tokenMode = "";
        if (tokenSecret == null) tokenSecret = "";
        if (tokenPIN == null) tokenPIN = "";
        if (tokenPassword == null) tokenPassword = "";
        if (tokenDeviceID == null) tokenDeviceID = "";
        if (tokenCounter == null) tokenCounter = 0;
        if (reportedOS == null) reportedOS = "";
        if (userAgent == null) userAgent = "";
        if (clientVersion == null) clientVersion = "";
        if (localHostname == null) localHostname = "";
        if (mobilePlatformVersion == null) mobilePlatformVersion = "";
        if (mobileDeviceType == null) mobileDeviceType = "";
        if (mobileDeviceUniqueID == null) mobileDeviceUniqueID = "";
        if (fortinetHostCheck == null) fortinetHostCheck = "";
        if (fortinetVirtualDesktopCheck == null) fortinetVirtualDesktopCheck = "";
        if (noUDP == null) noUDP = false;
        if (dtlsLocalPort == null) dtlsLocalPort = 0;
        if (compressionDisabled == null) compressionDisabled = false;
        if (compressionMode == null) compressionMode = "";
        if (ipv6Disabled == null) ipv6Disabled = false;
        if (httpKeepaliveDisabled == null) httpKeepaliveDisabled = false;
        if (xmlPostDisabled == null) xmlPostDisabled = false;
        if (externalAuthDisabled == null) externalAuthDisabled = false;
        if (passwordAuthenticationDisabled == null) passwordAuthenticationDisabled = false;
        if (tcpKeepAliveEnabled == null) tcpKeepAliveEnabled = false;
        if (pfs == null) pfs = false;
        if (mtu == null) mtu = 0;
        if (baseMTU == null) baseMTU = 0;
        if (dpdInterval == null) dpdInterval = "";
        if (reconnectTimeout == null) reconnectTimeout = "";
        if (trojanInterval == null) trojanInterval = "";
        if (queueLength == null) queueLength = 0;
        if (allowInsecureCrypto == null) allowInsecureCrypto = false;
        if (udpTimeout == null) udpTimeout = "";
        if (caCertificates == null) caCertificates = "";
        if (clientCertificate == null) clientCertificate = "";
        if (clientKey == null) clientKey = "";
        if (clientKeyPassword == null) clientKeyPassword = "";
        if (mcaCertificate == null) mcaCertificate = "";
        if (mcaKey == null) mcaKey = "";
        if (mcaKeyPassword == null) mcaKeyPassword = "";
        if (tlsInsecure == null) tlsInsecure = false;
        if (tlsServerName == null) tlsServerName = "";
        if (tlsPeerFingerprints == null) tlsPeerFingerprints = "";
        if (tlsSystemTrustDisabled == null) tlsSystemTrustDisabled = false;
        if (formEntries == null) formEntries = "";
        if (tnccDeviceID == null) tnccDeviceID = "";
        if (tnccUserAgent == null) tnccUserAgent = "";
        if (tnccMachineIdentification == null) tnccMachineIdentification = false;
        if (tnccCertificates == null) tnccCertificates = "";
        if (usePushedDNS == null) usePushedDNS = false;
        if (acceptPushedDefaultResolvers == null) acceptPushedDefaultResolvers = false;
        if (expandPushedSearchDomains == null) expandPushedSearchDomains = false;
        syncServerAddress();
    }

    public void syncServerAddress() {
        if (server == null || server.isBlank()) return;
        try {
            URI uri = URI.create(server.contains("://") ? server : "https://" + server);
            if (uri.getHost() != null) serverAddress = uri.getHost();
            if (uri.getPort() > 0) {
                serverPort = uri.getPort();
            } else if (serverPort == 1080) {
                serverPort = 443;
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(2);
        super.serialize(output);
        output.writeString(server);
        output.writeString(flavor);
        output.writeString(username);
        output.writeString(password);
        output.writeString(authGroup);
        output.writeString(tokenMode);
        output.writeString(tokenSecret);
        output.writeString(tokenPIN);
        output.writeString(tokenPassword);
        output.writeString(tokenDeviceID);
        output.writeInt(tokenCounter);
        output.writeString(reportedOS);
        output.writeString(userAgent);
        output.writeBoolean(noUDP);
        output.writeBoolean(allowInsecureCrypto);
        output.writeString(udpTimeout);
        output.writeString(caCertificates);
        output.writeString(clientCertificate);
        output.writeString(clientKey);
        output.writeString(clientKeyPassword);
        output.writeString(mcaCertificate);
        output.writeString(mcaKey);
        output.writeString(mcaKeyPassword);
        output.writeString(formEntries);
        output.writeString(tnccDeviceID);
        output.writeString(tnccUserAgent);
        output.writeBoolean(tnccMachineIdentification);
        output.writeString(tnccCertificates);
        output.writeString(cookie);
        output.writeString(clientVersion);
        output.writeString(localHostname);
        output.writeString(mobilePlatformVersion);
        output.writeString(mobileDeviceType);
        output.writeString(mobileDeviceUniqueID);
        output.writeString(fortinetHostCheck);
        output.writeString(fortinetVirtualDesktopCheck);
        output.writeInt(dtlsLocalPort);
        output.writeBoolean(compressionDisabled);
        output.writeString(compressionMode);
        output.writeBoolean(ipv6Disabled);
        output.writeBoolean(httpKeepaliveDisabled);
        output.writeBoolean(xmlPostDisabled);
        output.writeBoolean(externalAuthDisabled);
        output.writeBoolean(passwordAuthenticationDisabled);
        output.writeBoolean(tcpKeepAliveEnabled);
        output.writeBoolean(pfs);
        output.writeInt(mtu);
        output.writeInt(baseMTU);
        output.writeString(dpdInterval);
        output.writeString(reconnectTimeout);
        output.writeString(trojanInterval);
        output.writeInt(queueLength);
        output.writeBoolean(tlsInsecure);
        output.writeString(tlsServerName);
        output.writeString(tlsPeerFingerprints);
        output.writeBoolean(tlsSystemTrustDisabled);
        output.writeBoolean(usePushedDNS);
        output.writeBoolean(acceptPushedDefaultResolvers);
        output.writeBoolean(expandPushedSearchDomains);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        server = input.readString();
        flavor = input.readString();
        username = input.readString();
        password = input.readString();
        authGroup = input.readString();
        tokenMode = input.readString();
        tokenSecret = input.readString();
        tokenPIN = input.readString();
        tokenPassword = input.readString();
        tokenDeviceID = input.readString();
        tokenCounter = input.readInt();
        reportedOS = input.readString();
        userAgent = input.readString();
        noUDP = input.readBoolean();
        allowInsecureCrypto = input.readBoolean();
        udpTimeout = input.readString();
        caCertificates = input.readString();
        clientCertificate = input.readString();
        clientKey = input.readString();
        clientKeyPassword = input.readString();
        mcaCertificate = input.readString();
        mcaKey = input.readString();
        mcaKeyPassword = input.readString();
        formEntries = input.readString();
        tnccDeviceID = input.readString();
        tnccUserAgent = input.readString();
        tnccMachineIdentification = input.readBoolean();
        tnccCertificates = input.readString();
        if (version >= 2) {
            cookie = input.readString();
            clientVersion = input.readString();
            localHostname = input.readString();
            mobilePlatformVersion = input.readString();
            mobileDeviceType = input.readString();
            mobileDeviceUniqueID = input.readString();
            fortinetHostCheck = input.readString();
            fortinetVirtualDesktopCheck = input.readString();
            dtlsLocalPort = input.readInt();
            compressionDisabled = input.readBoolean();
            compressionMode = input.readString();
            ipv6Disabled = input.readBoolean();
            httpKeepaliveDisabled = input.readBoolean();
            xmlPostDisabled = input.readBoolean();
            externalAuthDisabled = input.readBoolean();
            passwordAuthenticationDisabled = input.readBoolean();
            tcpKeepAliveEnabled = input.readBoolean();
            pfs = input.readBoolean();
            mtu = input.readInt();
            baseMTU = input.readInt();
            dpdInterval = input.readString();
            reconnectTimeout = input.readString();
            trojanInterval = input.readString();
            queueLength = input.readInt();
            tlsInsecure = input.readBoolean();
            tlsServerName = input.readString();
            tlsPeerFingerprints = input.readString();
            tlsSystemTrustDisabled = input.readBoolean();
            usePushedDNS = input.readBoolean();
            acceptPushedDefaultResolvers = input.readBoolean();
            expandPushedSearchDomains = input.readBoolean();
        } else {
            cookie = clientVersion = localHostname = mobilePlatformVersion = mobileDeviceType = mobileDeviceUniqueID = "";
            fortinetHostCheck = fortinetVirtualDesktopCheck = compressionMode = dpdInterval = reconnectTimeout = trojanInterval = "";
            dtlsLocalPort = mtu = baseMTU = queueLength = 0;
            compressionDisabled = ipv6Disabled = httpKeepaliveDisabled = xmlPostDisabled = externalAuthDisabled = false;
            passwordAuthenticationDisabled = tcpKeepAliveEnabled = pfs = false;
            tlsInsecure = tlsSystemTrustDisabled = false;
            tlsServerName = tlsPeerFingerprints = "";
            usePushedDNS = acceptPushedDefaultResolvers = expandPushedSearchDomains = false;
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public String displayAddress() {
        return server == null || server.isBlank() ? super.displayAddress() : server;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("openconnect");
    }

    @NotNull
    @Override
    public OpenConnectBean clone() {
        return KryoConverters.deserialize(
                new OpenConnectBean(),
                KryoConverters.serialize(this)
        );
    }

    public static final Creator<OpenConnectBean> CREATOR = new CREATOR<OpenConnectBean>() {
        @NonNull
        @Override
        public OpenConnectBean newInstance() {
            return new OpenConnectBean();
        }

        @Override
        public OpenConnectBean[] newArray(int size) {
            return new OpenConnectBean[size];
        }
    };
}
