package io.nekohasekai.sagernet.fmt.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenVPNBean extends AbstractBean {
    public String mode;
    public String network;
    public String additionalRemotes;
    public Boolean remoteRandom;
    public String username;
    public String password;
    public String authRetry;
    public String staticChallenge;
    public Boolean staticChallengeEcho;
    public String addresses;
    public String peerAddress;
    public String peerAddressIPv6;
    public String topology;
    public String staticKey;
    public String staticKeyDirection;
    public Integer mtu;
    public String udpTimeout;
    public String tlsServerName;
    public String tlsServerNameType;
    public String caCertificates;
    public String clientCertificate;
    public String clientKey;
    public String peerFingerprints;
    public String remoteCertificateKU;
    public String remoteCertificateEKU;
    public String remoteCertificateTLS;
    public String certificateProfile;
    public String nsCertificateType;
    public String tlsVersionMin;
    public String tlsVersionMax;
    public String tlsCipher;
    public String tlsGroups;
    public String controlWrapType;
    public String controlWrapKey;
    public String controlWrapDirection;
    public String dataCiphers;
    public String dataCiphersFallback;
    public String auth;
    public String cipher;
    public Integer mssFix;
    public Boolean mssFixDisabled;
    public String mssFixMode;
    public Integer fragment;
    public Integer replayWindow;
    public String replayWindowTime;
    public String compression;
    public String compressionLZO;
    public String allowCompression;
    public Boolean routeNoPull;
    public String pullFilters;
    public String routes;
    public String routeGateway;
    public Integer routeMetric;
    public Boolean redirectGateway;
    public String redirectGatewayFlags;
    public Boolean redirectPrivate;
    public Boolean blockIPv6;
    public String pingInterval;
    public String pingRestart;
    public Boolean pingRestartDisabled;
    public String renegotiateInterval;
    public Boolean renegotiateDisabled;
    public Long renegotiateBytes;
    public Long renegotiatePackets;
    public String tlsTimeout;
    public String handshakeWindow;
    public Integer explicitExitNotify;
    public Boolean usePushedDNS;
    public Boolean acceptPushedDefaultResolvers;
    public Boolean expandPushedSearchDomains;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (serverPort == 1080) serverPort = 1194;
        if (mode == null) mode = "tls";
        if (network == null) network = "udp";
        if (additionalRemotes == null) additionalRemotes = "";
        if (remoteRandom == null) remoteRandom = false;
        if (username == null) username = "";
        if (password == null) password = "";
        if (authRetry == null) authRetry = "";
        if (staticChallenge == null) staticChallenge = "";
        if (staticChallengeEcho == null) staticChallengeEcho = false;
        if (addresses == null) addresses = "";
        if (peerAddress == null) peerAddress = "";
        if (peerAddressIPv6 == null) peerAddressIPv6 = "";
        if (topology == null) topology = "";
        if (staticKey == null) staticKey = "";
        if (staticKeyDirection == null) staticKeyDirection = "";
        if (mtu == null) mtu = 0;
        if (udpTimeout == null) udpTimeout = "";
        if (tlsServerName == null) tlsServerName = "";
        if (tlsServerNameType == null) tlsServerNameType = "";
        if (caCertificates == null) caCertificates = "";
        if (clientCertificate == null) clientCertificate = "";
        if (clientKey == null) clientKey = "";
        if (peerFingerprints == null) peerFingerprints = "";
        if (remoteCertificateKU == null) remoteCertificateKU = "";
        if (remoteCertificateEKU == null) remoteCertificateEKU = "";
        if (remoteCertificateTLS == null) remoteCertificateTLS = "";
        if (certificateProfile == null) certificateProfile = "";
        if (nsCertificateType == null) nsCertificateType = "";
        if (tlsVersionMin == null) tlsVersionMin = "";
        if (tlsVersionMax == null) tlsVersionMax = "";
        if (tlsCipher == null) tlsCipher = "";
        if (tlsGroups == null) tlsGroups = "";
        if (controlWrapType == null) controlWrapType = "";
        if (controlWrapKey == null) controlWrapKey = "";
        if (controlWrapDirection == null) controlWrapDirection = "";
        if (dataCiphers == null) dataCiphers = "";
        if (dataCiphersFallback == null) dataCiphersFallback = "";
        if (auth == null) auth = "";
        if (cipher == null) cipher = "";
        if (mssFix == null) mssFix = 0;
        if (mssFixDisabled == null) mssFixDisabled = false;
        if (mssFixMode == null) mssFixMode = "";
        if (fragment == null) fragment = 0;
        if (replayWindow == null) replayWindow = 0;
        if (replayWindowTime == null) replayWindowTime = "";
        if (compression == null) compression = "";
        if (compressionLZO == null) compressionLZO = "";
        if (allowCompression == null) allowCompression = "";
        if (routeNoPull == null) routeNoPull = false;
        if (pullFilters == null) pullFilters = "";
        if (routes == null) routes = "";
        if (routeGateway == null) routeGateway = "";
        if (routeMetric == null) routeMetric = 0;
        if (redirectGateway == null) redirectGateway = false;
        if (redirectGatewayFlags == null) redirectGatewayFlags = "";
        if (redirectPrivate == null) redirectPrivate = false;
        if (blockIPv6 == null) blockIPv6 = false;
        if (pingInterval == null) pingInterval = "";
        if (pingRestart == null) pingRestart = "";
        if (pingRestartDisabled == null) pingRestartDisabled = false;
        if (renegotiateInterval == null) renegotiateInterval = "";
        if (renegotiateDisabled == null) renegotiateDisabled = false;
        if (renegotiateBytes == null) renegotiateBytes = 0L;
        if (renegotiatePackets == null) renegotiatePackets = 0L;
        if (tlsTimeout == null) tlsTimeout = "";
        if (handshakeWindow == null) handshakeWindow = "";
        if (explicitExitNotify == null) explicitExitNotify = 0;
        if (usePushedDNS == null) usePushedDNS = false;
        if (acceptPushedDefaultResolvers == null) acceptPushedDefaultResolvers = false;
        if (expandPushedSearchDomains == null) expandPushedSearchDomains = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(2);
        super.serialize(output);
        output.writeString(network);
        output.writeString(additionalRemotes);
        output.writeBoolean(remoteRandom);
        output.writeString(username);
        output.writeString(password);
        output.writeString(authRetry);
        output.writeString(staticChallenge);
        output.writeBoolean(staticChallengeEcho);
        output.writeInt(mtu);
        output.writeString(udpTimeout);
        output.writeString(tlsServerName);
        output.writeString(tlsServerNameType);
        output.writeString(caCertificates);
        output.writeString(clientCertificate);
        output.writeString(clientKey);
        output.writeString(peerFingerprints);
        output.writeString(remoteCertificateKU);
        output.writeString(remoteCertificateEKU);
        output.writeString(tlsVersionMin);
        output.writeString(tlsVersionMax);
        output.writeString(tlsCipher);
        output.writeString(tlsGroups);
        output.writeString(controlWrapType);
        output.writeString(controlWrapKey);
        output.writeString(controlWrapDirection);
        output.writeString(dataCiphers);
        output.writeString(dataCiphersFallback);
        output.writeString(auth);
        output.writeInt(mssFix);
        output.writeInt(fragment);
        output.writeString(compression);
        output.writeString(compressionLZO);
        output.writeString(allowCompression);
        output.writeBoolean(routeNoPull);
        output.writeString(pullFilters);
        output.writeString(routes);
        output.writeString(routeGateway);
        output.writeInt(routeMetric);
        output.writeBoolean(redirectGateway);
        output.writeString(redirectGatewayFlags);
        output.writeString(pingInterval);
        output.writeString(pingRestart);
        output.writeString(renegotiateInterval);
        output.writeInt(explicitExitNotify);
        output.writeString(mode);
        output.writeString(addresses);
        output.writeString(peerAddress);
        output.writeString(peerAddressIPv6);
        output.writeString(topology);
        output.writeString(staticKey);
        output.writeString(staticKeyDirection);
        output.writeString(remoteCertificateTLS);
        output.writeString(certificateProfile);
        output.writeString(nsCertificateType);
        output.writeString(cipher);
        output.writeBoolean(mssFixDisabled);
        output.writeString(mssFixMode);
        output.writeInt(replayWindow);
        output.writeString(replayWindowTime);
        output.writeBoolean(redirectPrivate);
        output.writeBoolean(blockIPv6);
        output.writeBoolean(pingRestartDisabled);
        output.writeBoolean(renegotiateDisabled);
        output.writeLong(renegotiateBytes);
        output.writeLong(renegotiatePackets);
        output.writeString(tlsTimeout);
        output.writeString(handshakeWindow);
        output.writeBoolean(usePushedDNS);
        output.writeBoolean(acceptPushedDefaultResolvers);
        output.writeBoolean(expandPushedSearchDomains);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        network = input.readString();
        additionalRemotes = input.readString();
        remoteRandom = input.readBoolean();
        username = input.readString();
        password = input.readString();
        authRetry = input.readString();
        staticChallenge = input.readString();
        staticChallengeEcho = input.readBoolean();
        mtu = input.readInt();
        udpTimeout = input.readString();
        tlsServerName = input.readString();
        tlsServerNameType = input.readString();
        caCertificates = input.readString();
        clientCertificate = input.readString();
        clientKey = input.readString();
        peerFingerprints = input.readString();
        remoteCertificateKU = input.readString();
        remoteCertificateEKU = input.readString();
        tlsVersionMin = input.readString();
        tlsVersionMax = input.readString();
        tlsCipher = input.readString();
        tlsGroups = input.readString();
        controlWrapType = input.readString();
        controlWrapKey = input.readString();
        controlWrapDirection = input.readString();
        dataCiphers = input.readString();
        dataCiphersFallback = input.readString();
        auth = input.readString();
        mssFix = input.readInt();
        fragment = input.readInt();
        compression = input.readString();
        compressionLZO = input.readString();
        allowCompression = input.readString();
        routeNoPull = input.readBoolean();
        pullFilters = input.readString();
        routes = input.readString();
        routeGateway = input.readString();
        routeMetric = input.readInt();
        redirectGateway = input.readBoolean();
        redirectGatewayFlags = input.readString();
        pingInterval = input.readString();
        pingRestart = input.readString();
        renegotiateInterval = input.readString();
        explicitExitNotify = input.readInt();
        if (version >= 2) {
            mode = input.readString();
            addresses = input.readString();
            peerAddress = input.readString();
            peerAddressIPv6 = input.readString();
            topology = input.readString();
            staticKey = input.readString();
            staticKeyDirection = input.readString();
            remoteCertificateTLS = input.readString();
            certificateProfile = input.readString();
            nsCertificateType = input.readString();
            cipher = input.readString();
            mssFixDisabled = input.readBoolean();
            mssFixMode = input.readString();
            replayWindow = input.readInt();
            replayWindowTime = input.readString();
            redirectPrivate = input.readBoolean();
            blockIPv6 = input.readBoolean();
            pingRestartDisabled = input.readBoolean();
            renegotiateDisabled = input.readBoolean();
            renegotiateBytes = input.readLong();
            renegotiatePackets = input.readLong();
            tlsTimeout = input.readString();
            handshakeWindow = input.readString();
            usePushedDNS = input.readBoolean();
            acceptPushedDefaultResolvers = input.readBoolean();
            expandPushedSearchDomains = input.readBoolean();
        } else {
            mode = "tls";
            addresses = peerAddress = peerAddressIPv6 = topology = staticKey = staticKeyDirection = "";
            remoteCertificateTLS = certificateProfile = nsCertificateType = cipher = mssFixMode = replayWindowTime = "";
            mssFixDisabled = redirectPrivate = blockIPv6 = pingRestartDisabled = renegotiateDisabled = false;
            replayWindow = 0;
            renegotiateBytes = renegotiatePackets = 0L;
            tlsTimeout = handshakeWindow = "";
            usePushedDNS = acceptPushedDefaultResolvers = expandPushedSearchDomains = false;
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("openvpn");
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(
                new OpenVPNBean(),
                KryoConverters.serialize(this)
        );
    }

    public static final Creator<OpenVPNBean> CREATOR = new CREATOR<OpenVPNBean>() {
        @NonNull
        @Override
        public OpenVPNBean newInstance() {
            return new OpenVPNBean();
        }

        @Override
        public OpenVPNBean[] newArray(int size) {
            return new OpenVPNBean[size];
        }
    };
}
