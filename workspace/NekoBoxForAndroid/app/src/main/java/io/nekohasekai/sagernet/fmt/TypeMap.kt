package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity

object TypeMap : HashMap<String, Int>() {
    init {
        this["socks"] = ProxyEntity.TYPE_SOCKS
        this["http"] = ProxyEntity.TYPE_HTTP
        this["ss"] = ProxyEntity.TYPE_SS
        this["ssr"] = ProxyEntity.TYPE_SSR
        this["vmess"] = ProxyEntity.TYPE_VMESS
        this["trojan"] = ProxyEntity.TYPE_TROJAN
        this["trojan-go"] = ProxyEntity.TYPE_TROJAN_GO
        this["mieru"] = ProxyEntity.TYPE_MIERU
        this["naive"] = ProxyEntity.TYPE_NAIVE
        this["hysteria"] = ProxyEntity.TYPE_HYSTERIA
        this["ssh"] = ProxyEntity.TYPE_SSH
        this["wg"] = ProxyEntity.TYPE_WG
        this["awg"] = ProxyEntity.TYPE_AWG
        this["tuic"] = ProxyEntity.TYPE_TUIC
        this["juicity"] = ProxyEntity.TYPE_JUICITY
        this["trusttunnel"] = ProxyEntity.TYPE_TRUST_TUNNEL
        this["masque"] = ProxyEntity.TYPE_MASQUE
        this["direct"] = ProxyEntity.TYPE_DIRECT
        this["tailscale"] = ProxyEntity.TYPE_TAILSCALE
        this["openvpn"] = ProxyEntity.TYPE_OPENVPN
        this["openconnect"] = ProxyEntity.TYPE_OPENCONNECT
        this["snell"] = ProxyEntity.TYPE_SNELL
        this["byedpi"] = ProxyEntity.TYPE_BYEDPI
        this["anytls"] = ProxyEntity.TYPE_ANYTLS
        this["snell"] = ProxyEntity.TYPE_SNELL
        this["neko"] = ProxyEntity.TYPE_NEKO
        this["config"] = ProxyEntity.TYPE_CONFIG
    }

    val reversed = HashMap<Int, String>()

    init {
        TypeMap.forEach { (key, type) ->
            reversed[type] = key
        }
    }

}
