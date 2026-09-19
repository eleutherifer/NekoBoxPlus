package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.ProxySetBean
import io.nekohasekai.sagernet.fmt.internal.decodeEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.internal.hasEmbeddedProfiles
import io.nekohasekai.sagernet.fmt.masterdns.MasterDnsVPNBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.toUri
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.openconnect.OpenConnectBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.naive.toUri
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.toUri
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.snell.toUri
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.toUri
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.canUseSingBox
import io.nekohasekai.sagernet.fmt.trojan_go.toUri
import io.nekohasekai.sagernet.fmt.trusttunnel.TrustTunnelBean
import io.nekohasekai.sagernet.fmt.trusttunnel.toUri
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.toUri
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.toAmneziaWGUri
import io.nekohasekai.sagernet.fmt.wireguard.toWireGuardUri
import io.nekohasekai.sagernet.fmt.wireguard.buildAmneziaWGConfig
import io.nekohasekai.sagernet.fmt.wireguard.buildWireGuardConfig
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.profile.*
import moe.matsuri.nb4a.SingBoxOptions.BrutalOptions
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.byedpi.ByeDPIBean
import moe.matsuri.nb4a.proxy.byedpi.ByeDPISettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.direct.DirectBean
import moe.matsuri.nb4a.proxy.direct.DirectSettingsActivity
import moe.matsuri.nb4a.proxy.neko.*
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity

@Entity(
    tableName = "proxy_entities", indices = [Index("groupId", name = "groupId")]
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var groupId: Long = 0L,
    var type: Int = 0,
    var userOrder: Long = 0L,
    var tx: Long = 0L,
    var rx: Long = 0L,
    var status: Int = 0,
    var ping: Int = 0,
    var uuid: String = "",
    var error: String? = null,
    var countryCode: String = "",
    var countrySource: Int = 0,
    var socksBean: SOCKSBean? = null,
    var httpBean: HttpBean? = null,
    var ssBean: ShadowsocksBean? = null,
    var ssrBean: ShadowsocksRBean? = null,
    var vmessBean: VMessBean? = null,
    var trojanBean: TrojanBean? = null,
    var trojanGoBean: TrojanGoBean? = null,
    var mieruBean: MieruBean? = null,
    var naiveBean: NaiveBean? = null,
    var hysteriaBean: HysteriaBean? = null,
    var tuicBean: TuicBean? = null,
    var juicityBean: JuicityBean? = null,
    var snellBean: SnellBean? = null,
    var masterDnsVPNBean: MasterDnsVPNBean? = null,
    var byeDPIBean: ByeDPIBean? = null,
    var sshBean: SSHBean? = null,
    var wgBean: WireGuardBean? = null,
    var awgBean: AmneziaWGBean? = null,
    var shadowTLSBean: ShadowTLSBean? = null,
    var anyTLSBean: AnyTLSBean? = null,
    var trustTunnelBean: TrustTunnelBean? = null,
    var masqueBean: MasqueBean? = null,
    var directBean: DirectBean? = null,
    var tailscaleBean: TailscaleBean? = null,
    var openVPNBean: OpenVPNBean? = null,
    var openConnectBean: OpenConnectBean? = null,
    var proxySetBean: ProxySetBean? = null,
    var chainBean: ChainBean? = null,
    var nekoBean: NekoBean? = null,
    var configBean: ConfigBean? = null,
) : Serializable() {

    companion object {
        const val TYPE_SOCKS = 0
        const val TYPE_HTTP = 1
        const val TYPE_SS = 2
        const val TYPE_SSR = 3
        const val TYPE_VMESS = 4
        const val TYPE_TROJAN = 6

        const val TYPE_SSH = 17
        const val TYPE_WG = 18

        const val TYPE_TROJAN_GO = 7
        const val TYPE_NAIVE = 9
        const val TYPE_HYSTERIA = 15
        const val TYPE_SHADOWTLS = 19
        const val TYPE_TUIC = 20
        const val TYPE_MIERU = 21
        const val TYPE_ANYTLS = 22
        const val TYPE_JUICITY = 23
        const val TYPE_AWG = 24
        const val TYPE_SNELL = 25
        const val TYPE_PROXY_SET = 26
        const val TYPE_MASTERDNSVPN = 27
        const val TYPE_BYEDPI = 28
        const val TYPE_TRUST_TUNNEL = 29
        const val TYPE_MASQUE = 30
        const val TYPE_DIRECT = 31
        const val TYPE_TAILSCALE = 32
        const val TYPE_OPENVPN = 33
        const val TYPE_OPENCONNECT = 34

        const val TYPE_CONFIG = 998
        const val TYPE_NEKO = 999

        const val TYPE_CHAIN = 8

        val chainName by lazy { app.getString(R.string.proxy_chain) }

        @JvmField
        val CREATOR = object : CREATOR<ProxyEntity>() {

            override fun newInstance(): ProxyEntity {
                return ProxyEntity()
            }

            override fun newArray(size: Int): Array<ProxyEntity?> {
                return arrayOfNulls(size)
            }
        }
    }

    @Ignore
    @Transient
    var dirty: Boolean = false

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(1)

        output.writeLong(id)
        output.writeLong(groupId)
        output.writeInt(type)
        output.writeLong(userOrder)
        output.writeLong(tx)
        output.writeLong(rx)
        output.writeInt(status)
        output.writeInt(ping)
        output.writeString(uuid)
        output.writeString(error)

        val data = KryoConverters.serialize(requireBean())
        output.writeVarInt(data.size, true)
        output.writeBytes(data)

        output.writeBoolean(dirty)
        output.writeString(countryCode)
        output.writeInt(countrySource)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        val version = input.readInt()

        id = input.readLong()
        groupId = input.readLong()
        type = input.readInt()
        userOrder = input.readLong()
        tx = input.readLong()
        rx = input.readLong()
        status = input.readInt()
        ping = input.readInt()
        uuid = input.readString()
        error = input.readString()
        putByteArray(input.readBytes(input.readVarInt(true)))

        dirty = input.readBoolean()
        if (version >= 1) {
            countryCode = input.readString()
            countrySource = input.readInt()
        }
    }


    fun putByteArray(byteArray: ByteArray) {
        when (type) {
            TYPE_SOCKS -> socksBean = KryoConverters.socksDeserialize(byteArray)
            TYPE_HTTP -> httpBean = KryoConverters.httpDeserialize(byteArray)
            TYPE_SS -> ssBean = KryoConverters.shadowsocksDeserialize(byteArray)
            TYPE_SSR -> ssrBean = KryoConverters.shadowsocksrDeserialize(byteArray)
            TYPE_VMESS -> vmessBean = KryoConverters.vmessDeserialize(byteArray)
            TYPE_TROJAN -> trojanBean = KryoConverters.trojanDeserialize(byteArray)
            TYPE_TROJAN_GO -> trojanGoBean = KryoConverters.trojanGoDeserialize(byteArray)
            TYPE_MIERU -> mieruBean = KryoConverters.mieruDeserialize(byteArray)
            TYPE_NAIVE -> naiveBean = KryoConverters.naiveDeserialize(byteArray)
            TYPE_HYSTERIA -> hysteriaBean = KryoConverters.hysteriaDeserialize(byteArray)
            TYPE_SSH -> sshBean = KryoConverters.sshDeserialize(byteArray)
            TYPE_WG -> wgBean = KryoConverters.wireguardDeserialize(byteArray)
            TYPE_AWG -> awgBean = KryoConverters.amneziaWGDeserialize(byteArray)
            TYPE_TUIC -> tuicBean = KryoConverters.tuicDeserialize(byteArray)
            TYPE_JUICITY -> juicityBean = KryoConverters.juicityDeserialize(byteArray)
            TYPE_SNELL -> snellBean = KryoConverters.snellDeserialize(byteArray)
            TYPE_MASTERDNSVPN -> masterDnsVPNBean = KryoConverters.masterDnsVPNDeserialize(byteArray)
            TYPE_BYEDPI -> byeDPIBean = KryoConverters.byeDPIDeserialize(byteArray)
            TYPE_SHADOWTLS -> shadowTLSBean = KryoConverters.shadowTLSDeserialize(byteArray)
            TYPE_ANYTLS -> anyTLSBean = KryoConverters.anyTLSDeserialize(byteArray)
            TYPE_TRUST_TUNNEL -> trustTunnelBean = KryoConverters.trustTunnelDeserialize(byteArray)
            TYPE_MASQUE -> masqueBean = KryoConverters.masqueDeserialize(byteArray)
            TYPE_DIRECT -> directBean = KryoConverters.directDeserialize(byteArray)
            TYPE_TAILSCALE -> tailscaleBean = KryoConverters.tailscaleDeserialize(byteArray)
            TYPE_OPENVPN -> openVPNBean = KryoConverters.openVPNDeserialize(byteArray)
            TYPE_OPENCONNECT -> openConnectBean = KryoConverters.openConnectDeserialize(byteArray)
            TYPE_PROXY_SET -> proxySetBean = KryoConverters.proxySetDeserialize(byteArray)
            TYPE_CHAIN -> chainBean = KryoConverters.chainDeserialize(byteArray)
            TYPE_NEKO -> nekoBean = KryoConverters.nekoDeserialize(byteArray)
            TYPE_CONFIG -> configBean = KryoConverters.configDeserialize(byteArray)
        }
    }

    fun displayType(): String = when (type) {
        TYPE_SOCKS -> socksBean!!.protocolName()
        TYPE_HTTP -> if (httpBean!!.isTLS()) "HTTPS" else "HTTP"
        TYPE_SS -> "Shadowsocks"
        TYPE_SSR -> "ShadowsocksR"
        TYPE_VMESS -> if (vmessBean!!.isVLESS) "VLESS" else "VMess"
        TYPE_TROJAN -> "Trojan"
        TYPE_TROJAN_GO -> "Trojan-Go"
        TYPE_MIERU -> "Mieru"
        TYPE_NAIVE -> "Naïve"
        TYPE_HYSTERIA -> "Hysteria" + hysteriaBean!!.protocolVersion
        TYPE_SSH -> "SSH"
        TYPE_WG -> "WireGuard"
        TYPE_AWG -> "AmneziaWG"
        TYPE_TUIC -> "TUIC"
        TYPE_JUICITY -> "Juicity"
        TYPE_SNELL -> "Snell"
        TYPE_MASTERDNSVPN -> "MasterDnsVPN"
        TYPE_BYEDPI -> "ByeDPI"
        TYPE_SHADOWTLS -> "ShadowTLS"
        TYPE_ANYTLS -> "AnyTLS"
        TYPE_TRUST_TUNNEL -> "TrustTunnel"
        TYPE_MASQUE -> "MASQUE"
        TYPE_DIRECT -> "Direct"
        TYPE_TAILSCALE -> "Tailscale"
        TYPE_OPENVPN -> "OpenVPN"
        TYPE_OPENCONNECT -> "OpenConnect"
        TYPE_PROXY_SET -> proxySetBean!!.displayType()
        TYPE_CHAIN -> chainName
        TYPE_NEKO -> nekoBean!!.displayType()
        TYPE_CONFIG -> configBean!!.displayType()
        else -> "Undefined type $type"
    }

    fun displayName() = requireBean().displayName()
    fun displayAddress() = requireBean().displayAddress()

    internal fun beanOrNull(): AbstractBean? {
        return when (type) {
            TYPE_SOCKS -> socksBean
            TYPE_HTTP -> httpBean
            TYPE_SS -> ssBean
            TYPE_SSR -> ssrBean
            TYPE_VMESS -> vmessBean
            TYPE_TROJAN -> trojanBean
            TYPE_TROJAN_GO -> trojanGoBean
            TYPE_MIERU -> mieruBean
            TYPE_NAIVE -> naiveBean
            TYPE_HYSTERIA -> hysteriaBean
            TYPE_SSH -> sshBean
            TYPE_WG -> wgBean
            TYPE_AWG -> awgBean
            TYPE_TUIC -> tuicBean
            TYPE_JUICITY -> juicityBean
            TYPE_SNELL -> snellBean
            TYPE_MASTERDNSVPN -> masterDnsVPNBean
            TYPE_BYEDPI -> byeDPIBean
            TYPE_SHADOWTLS -> shadowTLSBean
            TYPE_ANYTLS -> anyTLSBean
            TYPE_TRUST_TUNNEL -> trustTunnelBean
            TYPE_MASQUE -> masqueBean
            TYPE_DIRECT -> directBean
            TYPE_TAILSCALE -> tailscaleBean
            TYPE_OPENVPN -> openVPNBean
            TYPE_OPENCONNECT -> openConnectBean
            TYPE_PROXY_SET -> proxySetBean
            TYPE_CHAIN -> chainBean
            TYPE_NEKO -> nekoBean
            TYPE_CONFIG -> configBean
            else -> null
        }
    }

    fun requireBean(): AbstractBean {
        return beanOrNull() ?: error("Null or undefined profile type $type")
    }

    fun haveLink(): Boolean {
        return when (type) {
            TYPE_CHAIN -> false
            TYPE_PROXY_SET -> false
            else -> true
        }
    }

    fun haveStandardLink(): Boolean {
        return when (requireBean()) {
            is ShadowTLSBean -> false
            is NekoBean -> false
            is ConfigBean -> false
            is DirectBean -> false
            is TailscaleBean -> false
            is OpenVPNBean -> false
            is OpenConnectBean -> false
            is ProxySetBean -> false
            is ChainBean -> false
            is ByeDPIBean -> false
            else -> true
        }
    }

    fun toStdLink(compact: Boolean = false): String = with(requireBean()) {
        when (this) {
            is SOCKSBean -> toUri()
            is HttpBean -> toUri()
            is ShadowsocksBean -> toUri()
            is ShadowsocksRBean -> toUri()
            is VMessBean -> toUriVMessVLESSTrojan(false)
            is TrojanBean -> toUriVMessVLESSTrojan(true)
            is TrojanGoBean -> toUri()
            is NaiveBean -> toUri()
            is HysteriaBean -> toUri()
            is TuicBean -> toUri()
            is JuicityBean -> toUri()
            is TrustTunnelBean -> toUri()
            is SnellBean -> toUri()
            is SSHBean -> toUri()
            is MasterDnsVPNBean -> toUri()
            is MieruBean -> toUri()
            is ByeDPIBean -> ""
            is AnyTLSBean -> toUri()
            is MasqueBean -> toUniversalLink()
            is WireGuardBean -> toWireGuardUri()
            is AmneziaWGBean -> toAmneziaWGUri()
            is ProxySetBean -> error("Proxy sets can only be exported as configuration")
            is NekoBean -> ""
            is DirectBean -> ""
            else -> toUniversalLink()
        }
    }

    fun usesUniversalLinkForGroupExport(): Boolean = when (requireBean()) {
        is TailscaleBean, is OpenVPNBean, is OpenConnectBean -> true
        else -> false
    }

    fun toGroupExportLink(): String? = with(requireBean()) {
        when {
            haveStandardLink() -> runCatching { toStdLink(compact = true) }.getOrNull()
            usesUniversalLinkForGroupExport() -> runCatching { toUniversalLink() }.getOrNull()
            else -> null
        }
    }

    fun exportConfig(): Pair<String, String> {
        var name = "${requireBean().displayName()}.json"

        return with(requireBean()) {
            when (this) {
                is WireGuardBean -> return buildWireGuardConfig() to "${displayName()}.conf"
                is AmneziaWGBean -> return buildAmneziaWGConfig() to "${displayName()}.conf"
            }
            StringBuilder().apply {
                val config = buildConfig(this@ProxyEntity, forExport = true)
                append(config.config)

                if (!config.externalIndex.all { it.chain.isEmpty() }) {
                    name = "profiles.txt"
                }

                for ((chain) in config.externalIndex) {
                    chain.entries.forEachIndexed { index, (port, profile) ->
                        when (val bean = profile.requireBean()) {
                            is TrojanGoBean -> {
                                append("\n\n")
                                append(bean.buildTrojanGoConfig(port))
                            }

                            is NaiveBean -> {
                                append("\n\n")
                                append(bean.buildNaiveConfig(port))
                            }

                            is HysteriaBean -> {
                                append("\n\n")
                                append(bean.buildHysteria1Config(port, null))
                            }
                        }
                    }
                }
            }.toString()
        } to name
    }

    fun needExternal(): Boolean {
        return when (type) {
            TYPE_TROJAN_GO -> !trojanGoBean!!.canUseSingBox()
            TYPE_HYSTERIA -> !hysteriaBean!!.canUseSingBox()
            TYPE_NEKO -> true
            else -> false
        }
    }

    fun isByeDPI(): Boolean = type == TYPE_BYEDPI

    fun containsByeDPI(): Boolean {
        if (isByeDPI()) return true
        return when (val bean = requireBean()) {
            is ChainBean -> {
                val profiles = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
                bean.proxies.any { proxyId -> profiles[proxyId]?.containsByeDPI() == true }
            }

            is ProxySetBean -> {
                val profiles = if (bean.hasEmbeddedProfiles()) {
                    bean.decodeEmbeddedProfiles()
                } else {
                    when (bean.type) {
                        ProxySetBean.TYPE_LIST -> SagerDatabase.proxyDao.getEntities(bean.proxies)
                        ProxySetBean.TYPE_GROUP -> SagerDatabase.proxyDao.getByGroup(bean.groupId)
                        else -> emptyList()
                    }
                }
                profiles.any { it.id != id && it.containsByeDPI() }
            }

            else -> false
        }
    }

    fun containsMasterDnsVPN(): Boolean {
        if (type == TYPE_MASTERDNSVPN) return true
        return when (val bean = requireBean()) {
            is ChainBean -> {
                val profiles = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
                bean.proxies.any { proxyId -> profiles[proxyId]?.containsMasterDnsVPN() == true }
            }

            is ProxySetBean -> {
                val profiles = if (bean.hasEmbeddedProfiles()) {
                    bean.decodeEmbeddedProfiles()
                } else {
                    when (bean.type) {
                        ProxySetBean.TYPE_LIST -> SagerDatabase.proxyDao.getEntities(bean.proxies)
                        ProxySetBean.TYPE_GROUP -> SagerDatabase.proxyDao.getByGroup(bean.groupId)
                        else -> emptyList()
                    }
                }
                profiles.any { it.id != id && it.containsMasterDnsVPN() }
            }

            else -> false
        }
    }

    fun startsWithByeDPI(): Boolean {
        if (isByeDPI()) return true
        val bean = requireBean()
        if (bean !is ChainBean) return false
        val firstProfileId = bean.proxies.firstOrNull() ?: return false
        val firstProfile = SagerDatabase.proxyDao.getById(firstProfileId) ?: return false
        return firstProfile.startsWithByeDPI()
    }

    fun singMux(): MultiplexOptions? {
        return when (type) {
            TYPE_VMESS -> MultiplexOptions().apply {
                enabled = vmessBean!!.enableMux
                padding = vmessBean!!.muxPadding
                protocol = when (vmessBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    3 -> "mux.cool"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (vmessBean!!.muxMode == 1) {
                    max_connections = vmessBean!!.muxMaxConnections
                    min_streams = vmessBean!!.muxMinStreams
                } else {
                    max_streams = vmessBean!!.muxConcurrency
                }
                if (vmessBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = vmessBean!!.muxBrutalUpMbps
                        down_mbps = vmessBean!!.muxBrutalDownMbps
                    }
                }
            }

            TYPE_TROJAN -> MultiplexOptions().apply {
                enabled = trojanBean!!.enableMux
                padding = trojanBean!!.muxPadding
                protocol = when (trojanBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    3 -> "mux.cool"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (trojanBean!!.muxMode == 1) {
                    max_connections = trojanBean!!.muxMaxConnections
                    min_streams = trojanBean!!.muxMinStreams
                } else {
                    max_streams = trojanBean!!.muxConcurrency
                }
                if (trojanBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = trojanBean!!.muxBrutalUpMbps
                        down_mbps = trojanBean!!.muxBrutalDownMbps
                    }
                }
            }

            TYPE_SS -> MultiplexOptions().apply {
                enabled = ssBean!!.enableMux
                padding = ssBean!!.muxPadding
                protocol = when (ssBean!!.muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    3 -> "mux.cool"
                    else -> "h2mux"
                }
                // muxMode 0: max_streams mode, 1: connections mode
                if (ssBean!!.muxMode == 1) {
                    max_connections = ssBean!!.muxMaxConnections
                    min_streams = ssBean!!.muxMinStreams
                } else {
                    max_streams = ssBean!!.muxConcurrency
                }
                if (ssBean!!.muxBrutal == true) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = ssBean!!.muxBrutalUpMbps
                        down_mbps = ssBean!!.muxBrutalDownMbps
                    }
                }
            }

            else -> null
        }
    }

    fun putBean(bean: AbstractBean): ProxyEntity {
        socksBean = null
        httpBean = null
        ssBean = null
        ssrBean = null
        vmessBean = null
        trojanBean = null
        trojanGoBean = null
        mieruBean = null
        naiveBean = null
        hysteriaBean = null
        sshBean = null
        wgBean = null
        awgBean = null
        tuicBean = null
        juicityBean = null
        snellBean = null
        masterDnsVPNBean = null
        byeDPIBean = null
        shadowTLSBean = null
        anyTLSBean = null
        trustTunnelBean = null
        masqueBean = null
        directBean = null
        tailscaleBean = null
        openVPNBean = null
        openConnectBean = null
        proxySetBean = null
        chainBean = null
        configBean = null
        nekoBean = null

        when (bean) {
            is SOCKSBean -> {
                type = TYPE_SOCKS
                socksBean = bean
            }

            is HttpBean -> {
                type = TYPE_HTTP
                httpBean = bean
            }

            is ShadowsocksBean -> {
                type = TYPE_SS
                ssBean = bean
            }

            is ShadowsocksRBean -> {
                type = TYPE_SSR
                ssrBean = bean
            }

            is VMessBean -> {
                type = TYPE_VMESS
                vmessBean = bean
            }

            is TrojanBean -> {
                type = TYPE_TROJAN
                trojanBean = bean
            }

            is TrojanGoBean -> {
                type = TYPE_TROJAN_GO
                trojanGoBean = bean
            }

            is MieruBean -> {
                type = TYPE_MIERU
                mieruBean = bean
            }

            is NaiveBean -> {
                type = TYPE_NAIVE
                naiveBean = bean
            }

            is HysteriaBean -> {
                type = TYPE_HYSTERIA
                hysteriaBean = bean
            }

            is SSHBean -> {
                type = TYPE_SSH
                sshBean = bean
            }

            is WireGuardBean -> {
                type = TYPE_WG
                wgBean = bean
            }

            is AmneziaWGBean -> {
                type = TYPE_AWG
                awgBean = bean
            }

            is TuicBean -> {
                type = TYPE_TUIC
                tuicBean = bean
            }

            is JuicityBean -> {
                type = TYPE_JUICITY
                juicityBean = bean
            }

            is SnellBean -> {
                type = TYPE_SNELL
                snellBean = bean
            }

            is MasterDnsVPNBean -> {
                type = TYPE_MASTERDNSVPN
                masterDnsVPNBean = bean
            }

            is ByeDPIBean -> {
                type = TYPE_BYEDPI
                byeDPIBean = bean
            }

            is ShadowTLSBean -> {
                type = TYPE_SHADOWTLS
                shadowTLSBean = bean
            }

            is AnyTLSBean -> {
                type = TYPE_ANYTLS
                anyTLSBean = bean
            }

            is TrustTunnelBean -> {
                type = TYPE_TRUST_TUNNEL
                trustTunnelBean = bean
            }

            is MasqueBean -> {
                type = TYPE_MASQUE
                masqueBean = bean
            }

            is DirectBean -> {
                type = TYPE_DIRECT
                directBean = bean
            }

            is TailscaleBean -> {
                type = TYPE_TAILSCALE
                tailscaleBean = bean
            }

            is OpenVPNBean -> {
                type = TYPE_OPENVPN
                openVPNBean = bean
            }

            is OpenConnectBean -> {
                type = TYPE_OPENCONNECT
                openConnectBean = bean
            }

            is ProxySetBean -> {
                type = TYPE_PROXY_SET
                proxySetBean = bean
            }

            is ChainBean -> {
                type = TYPE_CHAIN
                chainBean = bean
            }

            is NekoBean -> {
                type = TYPE_NEKO
                nekoBean = bean
            }

            is ConfigBean -> {
                type = TYPE_CONFIG
                configBean = bean
            }

            else -> error("Undefined type $type")
        }
        return this
    }

    fun settingIntent(ctx: Context, isSubscription: Boolean): Intent {
        return Intent(
            ctx, when (type) {
                TYPE_SOCKS -> SocksSettingsActivity::class.java
                TYPE_HTTP -> HttpSettingsActivity::class.java
                TYPE_SS -> ShadowsocksSettingsActivity::class.java
                TYPE_SSR -> ShadowsocksRSettingsActivity::class.java
                TYPE_VMESS -> VMessSettingsActivity::class.java
                TYPE_TROJAN -> TrojanSettingsActivity::class.java
                TYPE_TROJAN_GO -> TrojanGoSettingsActivity::class.java
                TYPE_MIERU -> MieruSettingsActivity::class.java
                TYPE_NAIVE -> NaiveSettingsActivity::class.java
                TYPE_HYSTERIA -> HysteriaSettingsActivity::class.java
                TYPE_SSH -> SSHSettingsActivity::class.java
                TYPE_WG -> WireGuardSettingsActivity::class.java
                TYPE_AWG -> AmneziaWGSettingsActivity::class.java
                TYPE_TUIC -> TuicSettingsActivity::class.java
                TYPE_JUICITY -> JuicitySettingsActivity::class.java
                TYPE_SNELL -> SnellSettingsActivity::class.java
                TYPE_MASTERDNSVPN -> MasterDnsVPNSettingsActivity::class.java
                TYPE_BYEDPI -> ByeDPISettingsActivity::class.java
                TYPE_SHADOWTLS -> ShadowTLSSettingsActivity::class.java
                TYPE_ANYTLS -> AnyTLSSettingsActivity::class.java
                TYPE_TRUST_TUNNEL -> TrustTunnelSettingsActivity::class.java
                TYPE_MASQUE -> MasqueSettingsActivity::class.java
                TYPE_DIRECT -> DirectSettingsActivity::class.java
                TYPE_TAILSCALE -> TailscaleSettingsActivity::class.java
                TYPE_OPENVPN -> OpenVPNSettingsActivity::class.java
                TYPE_OPENCONNECT -> OpenConnectSettingsActivity::class.java
                TYPE_PROXY_SET -> ProxySetSettingsActivity::class.java
                TYPE_CHAIN -> ChainSettingsActivity::class.java
                TYPE_CONFIG -> ConfigSettingActivity::class.java
                else -> throw IllegalArgumentException()
            }
        ).apply {
            putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
            putExtra(ProfileSettingsActivity.EXTRA_IS_SUBSCRIPTION, isSubscription)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("select * from proxy_entities")
        fun getAll(): List<ProxyEntity>

        @Query("SELECT id FROM proxy_entities ORDER BY id")
        fun getAllIds(): List<Long>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getIdsByGroup(groupId: Long): List<Long>

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities WHERE id in (:proxyIds)")
        fun getEntities(proxyIds: List<Long>): List<ProxyEntity>

        @Query("SELECT COUNT(*) FROM proxy_entities WHERE groupId = :groupId")
        fun countByGroup(groupId: Long): Long

        @Query("SELECT  MAX(userOrder) + 1 FROM proxy_entities WHERE groupId = :groupId")
        fun nextOrder(groupId: Long): Long?

        @Query("SELECT * FROM proxy_entities WHERE id = :proxyId")
        fun getById(proxyId: Long): ProxyEntity?

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyId)")
        fun deleteById(proxyId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        @Query("DELETE FROM proxy_entities WHERE groupId in (:groupId)")
        fun deleteByGroup(groupId: LongArray)

        @Delete
        fun deleteProxy(proxy: ProxyEntity): Int

        @Delete
        fun deleteProxy(proxies: List<ProxyEntity>): Int

        @Update
        fun updateProxy(proxy: ProxyEntity): Int

        @Update
        fun updateProxy(proxies: List<ProxyEntity>): Int

        @Query("UPDATE proxy_entities SET rx = :rx, tx = :tx WHERE id = :proxyId")
        fun updateTraffic(proxyId: Long, rx: Long, tx: Long): Int

        @Query("UPDATE proxy_entities SET rx = 0, tx = 0 WHERE id IN (:profileIds)")
        fun resetTraffic(profileIds: LongArray): Int

        @Query("UPDATE proxy_entities SET status = 0, ping = 0, error = NULL WHERE status != 0 OR ping != 0 OR error IS NOT NULL")
        fun clearTestResults(): Int

        @Insert
        fun addProxy(proxy: ProxyEntity): Long

        @Insert
        fun insert(proxies: List<ProxyEntity>)

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteAll(groupId: Long): Int

        @Query("DELETE FROM proxy_entities")
        fun reset()

    }

    override fun describeContents(): Int {
        return 0
    }
}
