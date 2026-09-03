package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import io.nekohasekai.sagernet.ktx.JsonHashNormalizer;
import moe.matsuri.nb4a.utils.JavaUtil;

import java.util.Locale;

public abstract class StandardV2RayBean extends AbstractBean {

    public String uuid;
    public String encryption; // or VLESS flow
    public String vlessEncryption; // VLESS ncryption

    //////// End of VMess & VLESS ////////

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade
    public String type;

    public String host;

    public String path;

    // --------------------------------------- tls?

    public String security;

    public String sni;

    public String alpn;

    public String utlsFingerprint;

    public Boolean allowInsecure;

    // --------------------------------------- reality


    public String realityPubKey;

    public String realityShortId;


    // --------------------------------------- //

    public Integer wsMaxEarlyData;
    public String earlyDataHeaderName;

    public String certificates;

    // --------------------------------------- xhttp

    public String xhttpMode;
    public String xhttpExtra;

    // Tier-1 XHTTP UI fields (Kryo v9)
    public String xhttpUplinkDataPlacement;   // "body" | "header" | "cookie"
    public String xhttpSessionPlacement;      // "path" | "header" | "query" | "cookie"
    public String xhttpPaddingMethod;         // "repeat-x" | "tokenish"
    public Boolean xhttpPaddingObfsMode;      // false | true

    // Tier-2 XHTTP UI fields (Kryo v10)
    public Boolean xhttpNoGrpcHeader;         // no_grpc_header
    public Boolean xhttpNoSseHeader;          // no_sse_header
    // Xmux UI fields (Kryo v11) — range values as "N" or "N-M"
    public String xhttpXmuxMaxConcurrency;    // xmux.max_concurrency
    public String xhttpXmuxMaxConnections;    // xmux.max_connections
    public String xhttpXmuxCMaxReuseTimes;    // xmux.c_max_reuse_times
    public String xhttpXmuxHMaxRequestTimes;  // xmux.h_max_request_times
    public String xhttpXmuxHMaxReusableSecs;  // xmux.h_max_reusable_secs
    public String xhttpXmuxHKeepAlivePeriod;  // xmux.h_keep_alive_period
    public String xhttpXPaddingKey;           // x_padding_key
    public String xhttpXPaddingHeader;        // x_padding_header (header name)
    public String xhttpXPaddingPlacement;     // x_padding_placement
    public String xhttpUplinkHttpMethod;      // uplink_http_method
    public String xhttpUplinkDataKey;         // uplink_data_key
    public String xhttpSessionKey;            // session_id_key
    public String xhttpSessionPlacementOld;   // session_placement
    public String xhttpSessionKeyOld;         // session_key
    public String xhttpSeqPlacement;          // seq_placement
    public String xhttpSeqKey;               // seq_key
    public String xhttpSessionIdTable;        // session_id_table
    public String xhttpSessionIdLength;       // session_id_length
    public String xhttpHeaders;              // headers, one "Name: value" per line
    public String xhttpXPaddingBytes;         // x_padding_bytes
    public String xhttpScMaxEachPostBytes;    // sc_max_each_post_bytes
    public String xhttpScMinPostsIntervalMs;  // sc_min_posts_interval_ms
    public String xhttpScMaxBufferedPosts;    // sc_max_buffered_posts
    public String xhttpScStreamUpServerSecs;  // sc_stream_up_server_secs
    public String xhttpUplinkChunkSize;       // uplink_chunk_size
    public String xhttpServerMaxHeaderBytes;  // server_max_header_bytes
    public String xhttpCongestionController; // H3: "", "bbr", "cubic", "reno"
    public String xhttpCwnd;                 // H3 BBR initial CWND; blank/0 = 32

    // --------------------------------------- kcp

    public String mKcpSeed;
    public String headerType;
    public Integer kcpMtu;
    public Integer kcpTti;
    public Integer kcpCwndMultiplier;
    public Integer kcpMaxSendingWindow;

    // --------------------------------------- ech

    public Boolean enableECH;

    public String echConfig;

    // --------------------------------------- Mux

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;
    public Integer muxConcurrency;  // max_streams
    public Integer muxMode;         // 0: max_streams, 1: connections
    public Integer muxMaxConnections;
    public Integer muxMinStreams;
    public Boolean muxBrutal;
    public Integer muxBrutalUpMbps;
    public Integer muxBrutalDownMbps;


    // --------------------------------------- //

    public static final int PACKET_ENCODING_NONE = 0;
    public static final int PACKET_ENCODING_PACKETADDR = 1;
    public static final int PACKET_ENCODING_XUDP = 2;
    public static final int PACKET_ENCODING_NOT_SPECIFIED = 3;

    public Integer packetEncoding;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(uuid)) uuid = "";

        if (JavaUtil.isNullOrBlank(encryption)) encryption = "";
        if (JavaUtil.isNullOrBlank(vlessEncryption)) vlessEncryption = "";

        if (JavaUtil.isNullOrBlank(type)) type = "tcp";
        else if ("h2".equals(type)) type = "http";

        type = type.toLowerCase(Locale.ROOT);

        if (JavaUtil.isNullOrBlank(host)) host = "";
        if (JavaUtil.isNullOrBlank(path)) path = "";

        if (JavaUtil.isNullOrBlank(security)) {
            if (this instanceof TrojanBean) {
                security = "tls";
            } else {
                security = "none";
            }
        }
        if (realityPubKey == null) realityPubKey = "";
        if (realityShortId == null) realityShortId = "";

        if (JavaUtil.isNullOrBlank(sni)) sni = "";
        if (JavaUtil.isNullOrBlank(alpn)) alpn = "";

        if (JavaUtil.isNullOrBlank(certificates)) certificates = "";
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = "";
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = "";

        if (wsMaxEarlyData == null) wsMaxEarlyData = 0;
        if (allowInsecure == null) allowInsecure = false;
        if (packetEncoding == null) packetEncoding = 0;

        if (enableECH == null) enableECH = false;
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = "";

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 0;
        if (muxConcurrency == null) muxConcurrency = 8;
        if (muxMode == null) muxMode = 0;
        if (muxMaxConnections == null) muxMaxConnections = 4;
        if (muxMinStreams == null) muxMinStreams = 4;
        if (muxBrutal == null) muxBrutal = false;
        if (muxBrutalUpMbps == null) muxBrutalUpMbps = 100;
        if (muxBrutalDownMbps == null) muxBrutalDownMbps = 100;

        if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = "auto";
        if (JavaUtil.isNullOrBlank(xhttpExtra)) xhttpExtra = "";
        if (JavaUtil.isNullOrBlank(xhttpUplinkDataPlacement)) xhttpUplinkDataPlacement = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionPlacement)) xhttpSessionPlacement = "";
        if (JavaUtil.isNullOrBlank(xhttpPaddingMethod)) xhttpPaddingMethod = "";
        if (xhttpPaddingObfsMode == null) xhttpPaddingObfsMode = false;
        if (xhttpNoGrpcHeader == null) xhttpNoGrpcHeader = false;
        if (xhttpNoSseHeader == null) xhttpNoSseHeader = false;
        if (JavaUtil.isNullOrBlank(xhttpXmuxMaxConcurrency)) xhttpXmuxMaxConcurrency = "";
        if (JavaUtil.isNullOrBlank(xhttpXmuxMaxConnections)) xhttpXmuxMaxConnections = "";
        if (JavaUtil.isNullOrBlank(xhttpXmuxCMaxReuseTimes)) xhttpXmuxCMaxReuseTimes = "";
        if (JavaUtil.isNullOrBlank(xhttpXmuxHMaxRequestTimes)) xhttpXmuxHMaxRequestTimes = "";
        if (JavaUtil.isNullOrBlank(xhttpXmuxHMaxReusableSecs)) xhttpXmuxHMaxReusableSecs = "";
        if (JavaUtil.isNullOrBlank(xhttpXmuxHKeepAlivePeriod)) xhttpXmuxHKeepAlivePeriod = "";
        if (JavaUtil.isNullOrBlank(xhttpXPaddingKey)) xhttpXPaddingKey = "";
        if (JavaUtil.isNullOrBlank(xhttpXPaddingHeader)) xhttpXPaddingHeader = "";
        if (JavaUtil.isNullOrBlank(xhttpXPaddingPlacement)) xhttpXPaddingPlacement = "";
        if (JavaUtil.isNullOrBlank(xhttpUplinkHttpMethod)) xhttpUplinkHttpMethod = "";
        if (JavaUtil.isNullOrBlank(xhttpUplinkDataKey)) xhttpUplinkDataKey = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionKey)) xhttpSessionKey = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionPlacementOld)) xhttpSessionPlacementOld = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionKeyOld)) xhttpSessionKeyOld = "";
        if (JavaUtil.isNullOrBlank(xhttpSeqPlacement)) xhttpSeqPlacement = "";
        if (JavaUtil.isNullOrBlank(xhttpSeqKey)) xhttpSeqKey = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionIdTable)) xhttpSessionIdTable = "";
        if (JavaUtil.isNullOrBlank(xhttpSessionIdLength)) xhttpSessionIdLength = "";
        if (JavaUtil.isNullOrBlank(xhttpHeaders)) xhttpHeaders = "";
        if (JavaUtil.isNullOrBlank(xhttpXPaddingBytes)) xhttpXPaddingBytes = "";
        if (JavaUtil.isNullOrBlank(xhttpScMaxEachPostBytes)) xhttpScMaxEachPostBytes = "";
        if (JavaUtil.isNullOrBlank(xhttpScMinPostsIntervalMs)) xhttpScMinPostsIntervalMs = "";
        if (JavaUtil.isNullOrBlank(xhttpScMaxBufferedPosts)) xhttpScMaxBufferedPosts = "";
        if (JavaUtil.isNullOrBlank(xhttpScStreamUpServerSecs)) xhttpScStreamUpServerSecs = "";
        if (JavaUtil.isNullOrBlank(xhttpUplinkChunkSize)) xhttpUplinkChunkSize = "";
        if (JavaUtil.isNullOrBlank(xhttpServerMaxHeaderBytes)) xhttpServerMaxHeaderBytes = "";
        if (JavaUtil.isNullOrBlank(xhttpCongestionController)) xhttpCongestionController = "";
        if (JavaUtil.isNullOrBlank(xhttpCwnd)) xhttpCwnd = "";

        if (JavaUtil.isNullOrBlank(mKcpSeed)) mKcpSeed = "";
        if (JavaUtil.isNullOrBlank(headerType)) headerType = "none";
    }

    @Override
    protected void normalizeJsonFieldsForHash() {
        super.normalizeJsonFieldsForHash();
        xhttpExtra = JsonHashNormalizer.normalizeJsonStringOrRaw(xhttpExtra);
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(18);
        super.serialize(output);
        output.writeString(uuid);
        output.writeString(encryption);
        output.writeString(vlessEncryption);
        if (this instanceof VMessBean) {
            output.writeInt(((VMessBean) this).alterId);
        }

        output.writeString(type);
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(wsMaxEarlyData);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "http": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "grpc": {
                output.writeString(path);
                break;
            }
            case "httpupgrade": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "xhttp": {
                output.writeString(host);
                output.writeString(path);
                output.writeString(xhttpMode);
                output.writeString(xhttpExtra);
                // v9
                output.writeString(xhttpUplinkDataPlacement);
                output.writeString(xhttpSessionPlacement);
                output.writeString(xhttpPaddingMethod);
                output.writeBoolean(xhttpPaddingObfsMode);
                // v10
                output.writeBoolean(xhttpNoGrpcHeader);
                output.writeBoolean(xhttpNoSseHeader);
                // v11
                output.writeString(xhttpXmuxMaxConcurrency);
                output.writeString(xhttpXmuxMaxConnections);
                output.writeString(xhttpXmuxCMaxReuseTimes);
                output.writeString(xhttpXmuxHMaxRequestTimes);
                output.writeString(xhttpXmuxHMaxReusableSecs);
                output.writeString(xhttpXmuxHKeepAlivePeriod);
                output.writeString(xhttpXPaddingKey);
                output.writeString(xhttpXPaddingHeader);
                output.writeString(xhttpXPaddingPlacement);
                output.writeString(xhttpUplinkHttpMethod);
                output.writeString(xhttpUplinkDataKey);
                output.writeString(xhttpSessionKey);
                output.writeString(xhttpSeqPlacement);
                output.writeString(xhttpSeqKey);
                // v12
                output.writeString(xhttpHeaders);
                // v13
                output.writeString(xhttpXPaddingBytes);
                output.writeString(xhttpScMaxEachPostBytes);
                output.writeString(xhttpScMinPostsIntervalMs);
                output.writeString(xhttpScMaxBufferedPosts);
                output.writeString(xhttpScStreamUpServerSecs);
                output.writeString(xhttpUplinkChunkSize);
                output.writeString(xhttpServerMaxHeaderBytes);
                // v15
                output.writeString(xhttpSessionIdTable);
                output.writeString(xhttpSessionIdLength);
                // v16
                output.writeString(xhttpSessionPlacementOld);
                output.writeString(xhttpSessionKeyOld);
                // v18
                output.writeString(xhttpCongestionController);
                output.writeString(xhttpCwnd);
                break;
            }
            case "kcp": {
                output.writeString(mKcpSeed);
                output.writeString(headerType);
                output.writeInt(kcpMtu == null ? 0 : kcpMtu);
                output.writeInt(kcpTti == null ? 0 : kcpTti);
                output.writeInt(kcpCwndMultiplier == null ? 0 : kcpCwndMultiplier);
                output.writeInt(kcpMaxSendingWindow == null ? 0 : kcpMaxSendingWindow);
                break;
            }
        }

        output.writeString(security);
        if ("tls".equals(security) || "reality".equals(security)) {
            output.writeString(sni);
            output.writeString(alpn);
            output.writeString(certificates);
            output.writeBoolean(allowInsecure);
            output.writeString(utlsFingerprint);
            output.writeString(realityPubKey);
            output.writeString(realityShortId);
        }

        output.writeBoolean(enableECH);
        output.writeString(echConfig);

        output.writeInt(packetEncoding);

        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);
        // v7
        output.writeInt(muxMode);
        output.writeInt(muxMaxConnections);
        output.writeInt(muxMinStreams);
        // v8
        output.writeBoolean(muxBrutal);
        output.writeInt(muxBrutalUpMbps);
        output.writeInt(muxBrutalDownMbps);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        uuid = input.readString();
        encryption = input.readString();
        if (version >= 5) {
            vlessEncryption = input.readString();
        }
        if (this instanceof VMessBean) {
            ((VMessBean) this).alterId = input.readInt();
        }

        type = input.readString();
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                host = input.readString();
                path = input.readString();
                wsMaxEarlyData = input.readInt();
                earlyDataHeaderName = input.readString();
                break;
            }
            case "http": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "grpc": {
                path = input.readString();
                if (version < 4) {
                    // 解决老版本数据的读取问题
                    input.readString();
                    input.readString();
                }
                break;
            }
            case "httpupgrade": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "xhttp": {
                if (version >= 4) {
                    host = input.readString();
                    path = input.readString();
                    xhttpMode = input.readString();
                    xhttpExtra = input.readString();
                }
                if (version >= 9) {
                    xhttpUplinkDataPlacement = input.readString();
                    xhttpSessionPlacement = input.readString();
                    xhttpPaddingMethod = input.readString();
                    xhttpPaddingObfsMode = input.readBoolean();
                }
                if (version >= 10) {
                    xhttpNoGrpcHeader = input.readBoolean();
                    xhttpNoSseHeader = input.readBoolean();
                }
                if (version >= 11) {
                    xhttpXmuxMaxConcurrency = input.readString();
                    xhttpXmuxMaxConnections = input.readString();
                    xhttpXmuxCMaxReuseTimes = input.readString();
                    xhttpXmuxHMaxRequestTimes = input.readString();
                    xhttpXmuxHMaxReusableSecs = input.readString();
                    xhttpXmuxHKeepAlivePeriod = input.readString();
                    xhttpXPaddingKey = input.readString();
                    xhttpXPaddingHeader = input.readString();
                    xhttpXPaddingPlacement = input.readString();
                    xhttpUplinkHttpMethod = input.readString();
                    xhttpUplinkDataKey = input.readString();
                    xhttpSessionKey = input.readString();
                    xhttpSeqPlacement = input.readString();
                    xhttpSeqKey = input.readString();
                }
                if (version >= 12) {
                    xhttpHeaders = input.readString();
                }
                if (version >= 13) {
                    xhttpXPaddingBytes = input.readString();
                    xhttpScMaxEachPostBytes = input.readString();
                    xhttpScMinPostsIntervalMs = input.readString();
                    xhttpScMaxBufferedPosts = input.readString();
                    xhttpScStreamUpServerSecs = input.readString();
                    xhttpUplinkChunkSize = input.readString();
                    xhttpServerMaxHeaderBytes = input.readString();
                }
                if (version >= 15) {
                    xhttpSessionIdTable = input.readString();
                    xhttpSessionIdLength = input.readString();
                }
                if (version >= 16) {
                    xhttpSessionPlacementOld = input.readString();
                    xhttpSessionKeyOld = input.readString();
                }
                if (version >= 18) {
                    xhttpCongestionController = input.readString();
                    xhttpCwnd = input.readString();
                }
                break;
            }
            case "kcp": {
                if (version >= 6) {
                    mKcpSeed = input.readString();
                    headerType = input.readString();
                    if (version >= 14) {
                        int mtu = input.readInt();
                        int tti = input.readInt();
                        int cwnd = input.readInt();
                        kcpMtu = mtu == 0 ? null : mtu;
                        kcpTti = tti == 0 ? null : tti;
                        kcpCwndMultiplier = cwnd == 0 ? null : cwnd;
                        if (version >= 17) {
                            int maxSendingWindow = input.readInt();
                            kcpMaxSendingWindow = maxSendingWindow == 0 ? null : maxSendingWindow;
                        }
                    }
                }
                break;
            }
        }

        security = input.readString();
        if ("tls".equals(security) || "reality".equals(security)) {
            sni = input.readString();
            alpn = input.readString();
            certificates = input.readString();
            allowInsecure = input.readBoolean();
            utlsFingerprint = input.readString();
            realityPubKey = input.readString();
            realityShortId = input.readString();
        }

        if (version >= 1) {
            enableECH = input.readBoolean();
            if (version >= 3) {
                echConfig = input.readString();
            } else {
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            }
        } else if (version == 0) {
            // 从老版本升级上来但是 version == 0, 可能有 enableECH 也可能没有，需要做判断
            int position = input.getByteBuffer().position(); // 当前位置

            boolean tmpEnableECH = input.readBoolean();
            int tmpPacketEncoding = input.readInt();

            input.setPosition(position); // 读后归位

            if (tmpPacketEncoding != 1 && tmpPacketEncoding != 2) {
                enableECH = tmpEnableECH;
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            } // 否则后一位就是 packetEncoding
        }

        packetEncoding = input.readInt();

        if (version >= 2) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
        }

        // v7
        if (version >= 7) {
            muxMode = input.readInt();
            muxMaxConnections = input.readInt();
            muxMinStreams = input.readInt();
        }

        // v8
        if (version >= 8) {
            muxBrutal = input.readBoolean();
            muxBrutalUpMbps = input.readInt();
            muxBrutalDownMbps = input.readInt();
        }

        // Note: xhttp fields are read in the switch case above when version >= 4
        // Note: kcp fields are read in the switch case above when version >= 6
    }

    public boolean isVLESS() {
        if (this instanceof VMessBean) {
            Integer aid = ((VMessBean) this).alterId;
            return aid != null && aid == -1;
        }
        return false;
    }

}
