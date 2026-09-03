package io.nekohasekai.sagernet.fmt.masterdns;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class MasterDnsVPNBean extends AbstractBean {
    private static final String DEFAULT_RESOLVERS = Stream.of(
            "1.1.1.1","1.0.0.1","8.8.8.8","8.8.4.4","9.9.9.9",
            "77.88.8.8","77.88.8.1","77.88.8.2","77.88.8.3","77.88.8.7","77.88.8.88"
    ).distinct().collect(Collectors.joining(System.lineSeparator()));

    public String domains;
    public Integer dataEncryptionMethod;
    public String encryptionKey;
    public String resolvers;

    public Integer localDNSCacheMaxRecords;
    public Double localDNSCacheTTLSeconds;
    public Double localDNSPendingTimeoutSeconds;
    public Double dnsResponseFragmentTimeoutSeconds;
    public Boolean localDNSCachePersistToFile;
    public Double localDNSCacheFlushIntervalSeconds;
    public Integer resolverBalancingStrategy;
    public Integer packetDuplicationCount;
    public Integer setupPacketDuplicationCount;
    public Integer streamResolverFailoverResendThreshold;
    public Double streamResolverFailoverCooldown;
    public Boolean recheckInactiveServersEnabled;
    public Boolean autoDisableTimeoutServers;
    public Double autoDisableTimeoutWindowSeconds;
    public Boolean baseEncodeData;
    public Integer uploadCompressionType;
    public Integer downloadCompressionType;
    public Integer compressionMinSize;
    public Integer minUploadMTU;
    public Integer minDownloadMTU;
    public Integer maxUploadMTU;
    public Integer maxDownloadMTU;
    public Boolean autoRemoveLowMTUServers;
    public Integer mtuTestRetries;
    public Double mtuTestTimeout;
    public Integer mtuTestParallelism;
    public Integer rxTxWorkers;
    public Integer tunnelProcessWorkers;
    public Double tunnelPacketTimeoutSeconds;
    public Double dispatcherIdlePollIntervalSeconds;
    public Integer rxChannelSize;
    public Double socksUDPAssociateReadTimeoutSeconds;
    public Double clientTerminalStreamRetentionSeconds;
    public Double clientCancelledSetupRetentionSeconds;
    public Double sessionInitRetryBaseSeconds;
    public Double sessionInitRetryStepSeconds;
    public Integer sessionInitRetryLinearAfter;
    public Double sessionInitRetryMaxSeconds;
    public Double sessionInitBusyRetryIntervalSeconds;
    public Integer sessionInitRacingCount;
    public Double pingAggressiveIntervalSeconds;
    public Double pingLazyIntervalSeconds;
    public Double pingCooldownIntervalSeconds;
    public Double pingColdIntervalSeconds;
    public Double pingWarmThresholdSeconds;
    public Double pingCoolThresholdSeconds;
    public Double pingColdThresholdSeconds;
    public Integer maxPacketsPerBatch;
    public Integer arqWindowSize;
    public Double arqInitialRTOSeconds;
    public Double arqMaxRTOSeconds;
    public Double arqControlInitialRTOSeconds;
    public Double arqControlMaxRTOSeconds;
    public Integer arqMaxControlRetries;
    public Double arqInactivityTimeoutSeconds;
    public Double arqDataPacketTTLSeconds;
    public Double arqControlPacketTTLSeconds;
    public Integer arqMaxDataRetries;
    public Integer arqDataNackMaxGap;
    public Double arqDataNackInitialDelaySeconds;
    public Double arqDataNackRepeatSeconds;
    public Double arqTerminalDrainTimeoutSeconds;
    public Double arqTerminalAckWaitTimeoutSeconds;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (domains == null) domains = "";
        if (dataEncryptionMethod == null) dataEncryptionMethod = 1;
        if (encryptionKey == null) encryptionKey = "";
        if (resolvers == null) resolvers = DEFAULT_RESOLVERS;

        initializeDefaultAdditionalSettings();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    public void initializeDefaultAdditionalSettings() {
        if (localDNSCacheMaxRecords == null) localDNSCacheMaxRecords = 10000;
        if (localDNSCacheTTLSeconds == null) localDNSCacheTTLSeconds = 14400.0;
        if (localDNSPendingTimeoutSeconds == null) localDNSPendingTimeoutSeconds = 300.0;
        if (dnsResponseFragmentTimeoutSeconds == null) dnsResponseFragmentTimeoutSeconds = 60.0;
        if (localDNSCachePersistToFile == null) localDNSCachePersistToFile = true;
        if (localDNSCacheFlushIntervalSeconds == null) localDNSCacheFlushIntervalSeconds = 60.0;
        if (resolverBalancingStrategy == null) resolverBalancingStrategy = 2;
        if (packetDuplicationCount == null) packetDuplicationCount = 2;
        if (setupPacketDuplicationCount == null) setupPacketDuplicationCount = 2;
        if (streamResolverFailoverResendThreshold == null) streamResolverFailoverResendThreshold = 2;
        if (streamResolverFailoverCooldown == null) streamResolverFailoverCooldown = 2.5;
        if (recheckInactiveServersEnabled == null) recheckInactiveServersEnabled = true;
        if (autoDisableTimeoutServers == null) autoDisableTimeoutServers = true;
        if (autoDisableTimeoutWindowSeconds == null) autoDisableTimeoutWindowSeconds = 30.0;
        if (baseEncodeData == null) baseEncodeData = false;
        if (uploadCompressionType == null) uploadCompressionType = 0;
        if (downloadCompressionType == null) downloadCompressionType = 0;
        if (compressionMinSize == null) compressionMinSize = 120;
        if (minUploadMTU == null) minUploadMTU = 38;
        if (minDownloadMTU == null) minDownloadMTU = 100;
        if (maxUploadMTU == null) maxUploadMTU = 150;
        if (maxDownloadMTU == null) maxDownloadMTU = 500;
        if (autoRemoveLowMTUServers == null) autoRemoveLowMTUServers = true;
        if (mtuTestRetries == null) mtuTestRetries = 2;
        if (mtuTestTimeout == null) mtuTestTimeout = 2.0;
        if (mtuTestParallelism == null) mtuTestParallelism = 16;
        if (rxTxWorkers == null) rxTxWorkers = 4;
        if (tunnelProcessWorkers == null) tunnelProcessWorkers = 0;
        if (tunnelPacketTimeoutSeconds == null) tunnelPacketTimeoutSeconds = 10.0;
        if (dispatcherIdlePollIntervalSeconds == null) dispatcherIdlePollIntervalSeconds = 0.020;
        if (rxChannelSize == null) rxChannelSize = 4096;
        if (socksUDPAssociateReadTimeoutSeconds == null) socksUDPAssociateReadTimeoutSeconds = 30.0;
        if (clientTerminalStreamRetentionSeconds == null) clientTerminalStreamRetentionSeconds = 45.0;
        if (clientCancelledSetupRetentionSeconds == null) clientCancelledSetupRetentionSeconds = 120.0;
        if (sessionInitRetryBaseSeconds == null) sessionInitRetryBaseSeconds = 1.0;
        if (sessionInitRetryStepSeconds == null) sessionInitRetryStepSeconds = 1.0;
        if (sessionInitRetryLinearAfter == null) sessionInitRetryLinearAfter = 5;
        if (sessionInitRetryMaxSeconds == null) sessionInitRetryMaxSeconds = 60.0;
        if (sessionInitBusyRetryIntervalSeconds == null) sessionInitBusyRetryIntervalSeconds = 60.0;
        if (sessionInitRacingCount == null) sessionInitRacingCount = 3;
        if (pingAggressiveIntervalSeconds == null) pingAggressiveIntervalSeconds = 0.100;
        if (pingLazyIntervalSeconds == null) pingLazyIntervalSeconds = 0.750;
        if (pingCooldownIntervalSeconds == null) pingCooldownIntervalSeconds = 2.0;
        if (pingColdIntervalSeconds == null) pingColdIntervalSeconds = 15.0;
        if (pingWarmThresholdSeconds == null) pingWarmThresholdSeconds = 8.0;
        if (pingCoolThresholdSeconds == null) pingCoolThresholdSeconds = 20.0;
        if (pingColdThresholdSeconds == null) pingColdThresholdSeconds = 30.0;
        if (maxPacketsPerBatch == null) maxPacketsPerBatch = 8;
        if (arqWindowSize == null) arqWindowSize = 600;
        if (arqInitialRTOSeconds == null) arqInitialRTOSeconds = 1.0;
        if (arqMaxRTOSeconds == null) arqMaxRTOSeconds = 5.0;
        if (arqControlInitialRTOSeconds == null) arqControlInitialRTOSeconds = 0.5;
        if (arqControlMaxRTOSeconds == null) arqControlMaxRTOSeconds = 3.0;
        if (arqMaxControlRetries == null) arqMaxControlRetries = 400;
        if (arqInactivityTimeoutSeconds == null) arqInactivityTimeoutSeconds = 1800.0;
        if (arqDataPacketTTLSeconds == null) arqDataPacketTTLSeconds = 2400.0;
        if (arqControlPacketTTLSeconds == null) arqControlPacketTTLSeconds = 1200.0;
        if (arqMaxDataRetries == null) arqMaxDataRetries = 1200;
        if (arqDataNackMaxGap == null) arqDataNackMaxGap = 16;
        if (arqDataNackInitialDelaySeconds == null) arqDataNackInitialDelaySeconds = 0.1;
        if (arqDataNackRepeatSeconds == null) arqDataNackRepeatSeconds = 1.0;
        if (arqTerminalDrainTimeoutSeconds == null) arqTerminalDrainTimeoutSeconds = 120.0;
        if (arqTerminalAckWaitTimeoutSeconds == null) arqTerminalAckWaitTimeoutSeconds = 90.0;
    }

    public void resetAdditionalSettingsToDefaults() {
        localDNSCacheMaxRecords = 10000;
        localDNSCacheTTLSeconds = 14400.0;
        localDNSPendingTimeoutSeconds = 300.0;
        dnsResponseFragmentTimeoutSeconds = 60.0;
        localDNSCachePersistToFile = true;
        localDNSCacheFlushIntervalSeconds = 60.0;
        resolverBalancingStrategy = 2;
        packetDuplicationCount = 2;
        setupPacketDuplicationCount = 2;
        streamResolverFailoverResendThreshold = 2;
        streamResolverFailoverCooldown = 2.5;
        recheckInactiveServersEnabled = true;
        autoDisableTimeoutServers = true;
        autoDisableTimeoutWindowSeconds = 30.0;
        baseEncodeData = false;
        uploadCompressionType = 0;
        downloadCompressionType = 0;
        compressionMinSize = 120;
        minUploadMTU = 38;
        minDownloadMTU = 100;
        maxUploadMTU = 150;
        maxDownloadMTU = 500;
        autoRemoveLowMTUServers = true;
        mtuTestRetries = 2;
        mtuTestTimeout = 2.0;
        mtuTestParallelism = 16;
        rxTxWorkers = 4;
        tunnelProcessWorkers = 0;
        tunnelPacketTimeoutSeconds = 10.0;
        dispatcherIdlePollIntervalSeconds = 0.020;
        rxChannelSize = 4096;
        socksUDPAssociateReadTimeoutSeconds = 30.0;
        clientTerminalStreamRetentionSeconds = 45.0;
        clientCancelledSetupRetentionSeconds = 120.0;
        sessionInitRetryBaseSeconds = 1.0;
        sessionInitRetryStepSeconds = 1.0;
        sessionInitRetryLinearAfter = 5;
        sessionInitRetryMaxSeconds = 60.0;
        sessionInitBusyRetryIntervalSeconds = 60.0;
        sessionInitRacingCount = 3;
        pingAggressiveIntervalSeconds = 0.100;
        pingLazyIntervalSeconds = 0.750;
        pingCooldownIntervalSeconds = 2.0;
        pingColdIntervalSeconds = 15.0;
        pingWarmThresholdSeconds = 8.0;
        pingCoolThresholdSeconds = 20.0;
        pingColdThresholdSeconds = 30.0;
        maxPacketsPerBatch = 8;
        arqWindowSize = 600;
        arqInitialRTOSeconds = 1.0;
        arqMaxRTOSeconds = 5.0;
        arqControlInitialRTOSeconds = 0.5;
        arqControlMaxRTOSeconds = 3.0;
        arqMaxControlRetries = 400;
        arqInactivityTimeoutSeconds = 1800.0;
        arqDataPacketTTLSeconds = 2400.0;
        arqControlPacketTTLSeconds = 1200.0;
        arqMaxDataRetries = 1200;
        arqDataNackMaxGap = 16;
        arqDataNackInitialDelaySeconds = 0.1;
        arqDataNackRepeatSeconds = 1.0;
        arqTerminalDrainTimeoutSeconds = 120.0;
        arqTerminalAckWaitTimeoutSeconds = 90.0;
    }

    public void applyPreset(String preset) {
        resetAdditionalSettingsToDefaults();
        if ("mobile".equals(preset)) {
            pingCooldownIntervalSeconds = 4.0;
            pingColdIntervalSeconds = 30.0;
            pingWarmThresholdSeconds = 16.0;
            pingCoolThresholdSeconds = 40.0;
            pingColdThresholdSeconds = 60.0;
            packetDuplicationCount = 1;
            setupPacketDuplicationCount = 2;
            arqDataNackInitialDelaySeconds = 0.6;
            localDNSCacheMaxRecords = 5000;
        } else if ("censored".equals(preset)) {
            packetDuplicationCount = 4;
            setupPacketDuplicationCount = 4;
            pingAggressiveIntervalSeconds = 0.050;
            pingLazyIntervalSeconds = 0.500;
            minUploadMTU = 28;
            minDownloadMTU = 60;
            maxUploadMTU = 200;
            maxDownloadMTU = 900;
            arqInitialRTOSeconds = 2.0;
            arqMaxDataRetries = 2400;
            baseEncodeData = true;
        } else if ("throughput".equals(preset)) {
            packetDuplicationCount = 1;
            setupPacketDuplicationCount = 2;
            resolverBalancingStrategy = 4;
            uploadCompressionType = 1;
            downloadCompressionType = 1;
            minUploadMTU = 120;
            maxUploadMTU = 150;
            minDownloadMTU = 400;
            maxDownloadMTU = 500;
        }
    }

    @Override
    public String displayAddress() {
        return domains == null || domains.isBlank() ? "MasterDnsVPN" : domains;
    }

    @Override
    public String network() {
        return "tcp,udp";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        output.writeString(domains);
        output.writeInt(dataEncryptionMethod);
        output.writeString(encryptionKey);
        output.writeString(resolvers);
        output.writeInt(resolverBalancingStrategy);
        output.writeInt(packetDuplicationCount);
        output.writeInt(setupPacketDuplicationCount);
        output.writeInt(minUploadMTU);
        output.writeInt(minDownloadMTU);
        output.writeInt(maxUploadMTU);
        output.writeInt(maxDownloadMTU);
        output.writeInt(rxTxWorkers);
        output.writeInt(tunnelProcessWorkers);
        output.writeInt(maxPacketsPerBatch);
        output.writeInt(localDNSCacheMaxRecords);
        output.writeDouble(localDNSCacheTTLSeconds);
        output.writeDouble(localDNSPendingTimeoutSeconds);
        output.writeDouble(dnsResponseFragmentTimeoutSeconds);
        output.writeBoolean(localDNSCachePersistToFile);
        output.writeDouble(localDNSCacheFlushIntervalSeconds);
        output.writeInt(streamResolverFailoverResendThreshold);
        output.writeDouble(streamResolverFailoverCooldown);
        output.writeBoolean(recheckInactiveServersEnabled);
        output.writeBoolean(autoDisableTimeoutServers);
        output.writeDouble(autoDisableTimeoutWindowSeconds);
        output.writeBoolean(baseEncodeData);
        output.writeInt(uploadCompressionType);
        output.writeInt(downloadCompressionType);
        output.writeInt(compressionMinSize);
        output.writeBoolean(autoRemoveLowMTUServers);
        output.writeInt(mtuTestRetries);
        output.writeDouble(mtuTestTimeout);
        output.writeInt(mtuTestParallelism);
        output.writeDouble(tunnelPacketTimeoutSeconds);
        output.writeDouble(dispatcherIdlePollIntervalSeconds);
        output.writeInt(rxChannelSize);
        output.writeDouble(socksUDPAssociateReadTimeoutSeconds);
        output.writeDouble(clientTerminalStreamRetentionSeconds);
        output.writeDouble(clientCancelledSetupRetentionSeconds);
        output.writeDouble(sessionInitRetryBaseSeconds);
        output.writeDouble(sessionInitRetryStepSeconds);
        output.writeInt(sessionInitRetryLinearAfter);
        output.writeDouble(sessionInitRetryMaxSeconds);
        output.writeDouble(sessionInitBusyRetryIntervalSeconds);
        output.writeInt(sessionInitRacingCount);
        output.writeDouble(pingAggressiveIntervalSeconds);
        output.writeDouble(pingLazyIntervalSeconds);
        output.writeDouble(pingCooldownIntervalSeconds);
        output.writeDouble(pingColdIntervalSeconds);
        output.writeDouble(pingWarmThresholdSeconds);
        output.writeDouble(pingCoolThresholdSeconds);
        output.writeDouble(pingColdThresholdSeconds);
        output.writeInt(arqWindowSize);
        output.writeDouble(arqInitialRTOSeconds);
        output.writeDouble(arqMaxRTOSeconds);
        output.writeDouble(arqControlInitialRTOSeconds);
        output.writeDouble(arqControlMaxRTOSeconds);
        output.writeInt(arqMaxControlRetries);
        output.writeDouble(arqInactivityTimeoutSeconds);
        output.writeDouble(arqDataPacketTTLSeconds);
        output.writeDouble(arqControlPacketTTLSeconds);
        output.writeInt(arqMaxDataRetries);
        output.writeInt(arqDataNackMaxGap);
        output.writeDouble(arqDataNackInitialDelaySeconds);
        output.writeDouble(arqDataNackRepeatSeconds);
        output.writeDouble(arqTerminalDrainTimeoutSeconds);
        output.writeDouble(arqTerminalAckWaitTimeoutSeconds);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        domains = input.readString();
        dataEncryptionMethod = input.readInt();
        encryptionKey = input.readString();
        resolvers = input.readString();
        resolverBalancingStrategy = input.readInt();
        packetDuplicationCount = input.readInt();
        setupPacketDuplicationCount = input.readInt();
        minUploadMTU = input.readInt();
        minDownloadMTU = input.readInt();
        maxUploadMTU = input.readInt();
        maxDownloadMTU = input.readInt();
        rxTxWorkers = input.readInt();
        tunnelProcessWorkers = input.readInt();
        maxPacketsPerBatch = input.readInt();
        if (version >= 1) {
            localDNSCacheMaxRecords = input.readInt();
            localDNSCacheTTLSeconds = input.readDouble();
            localDNSPendingTimeoutSeconds = input.readDouble();
            dnsResponseFragmentTimeoutSeconds = input.readDouble();
            localDNSCachePersistToFile = input.readBoolean();
            localDNSCacheFlushIntervalSeconds = input.readDouble();
            streamResolverFailoverResendThreshold = input.readInt();
            streamResolverFailoverCooldown = input.readDouble();
            recheckInactiveServersEnabled = input.readBoolean();
            autoDisableTimeoutServers = input.readBoolean();
            autoDisableTimeoutWindowSeconds = input.readDouble();
            baseEncodeData = input.readBoolean();
            uploadCompressionType = input.readInt();
            downloadCompressionType = input.readInt();
            compressionMinSize = input.readInt();
            autoRemoveLowMTUServers = input.readBoolean();
            mtuTestRetries = input.readInt();
            mtuTestTimeout = input.readDouble();
            mtuTestParallelism = input.readInt();
            tunnelPacketTimeoutSeconds = input.readDouble();
            dispatcherIdlePollIntervalSeconds = input.readDouble();
            rxChannelSize = input.readInt();
            socksUDPAssociateReadTimeoutSeconds = input.readDouble();
            clientTerminalStreamRetentionSeconds = input.readDouble();
            clientCancelledSetupRetentionSeconds = input.readDouble();
            sessionInitRetryBaseSeconds = input.readDouble();
            sessionInitRetryStepSeconds = input.readDouble();
            sessionInitRetryLinearAfter = input.readInt();
            sessionInitRetryMaxSeconds = input.readDouble();
            sessionInitBusyRetryIntervalSeconds = input.readDouble();
            sessionInitRacingCount = input.readInt();
            pingAggressiveIntervalSeconds = input.readDouble();
            pingLazyIntervalSeconds = input.readDouble();
            pingCooldownIntervalSeconds = input.readDouble();
            pingColdIntervalSeconds = input.readDouble();
            pingWarmThresholdSeconds = input.readDouble();
            pingCoolThresholdSeconds = input.readDouble();
            pingColdThresholdSeconds = input.readDouble();
            arqWindowSize = input.readInt();
            arqInitialRTOSeconds = input.readDouble();
            arqMaxRTOSeconds = input.readDouble();
            arqControlInitialRTOSeconds = input.readDouble();
            arqControlMaxRTOSeconds = input.readDouble();
            arqMaxControlRetries = input.readInt();
            arqInactivityTimeoutSeconds = input.readDouble();
            arqDataPacketTTLSeconds = input.readDouble();
            arqControlPacketTTLSeconds = input.readDouble();
            arqMaxDataRetries = input.readInt();
            arqDataNackMaxGap = input.readInt();
            arqDataNackInitialDelaySeconds = input.readDouble();
            arqDataNackRepeatSeconds = input.readDouble();
            arqTerminalDrainTimeoutSeconds = input.readDouble();
            arqTerminalAckWaitTimeoutSeconds = input.readDouble();
        }
        initializeDefaultValues();
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("masterdnsvpn");
    }

    @Override
    public MasterDnsVPNBean clone() {
        return KryoConverters.deserialize(new MasterDnsVPNBean(), KryoConverters.serialize(this));
    }
}
