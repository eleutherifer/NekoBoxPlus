package io.nekohasekai.sagernet.database

import android.os.Parcel
import android.os.Parcelable
import moe.matsuri.nb4a.utils.JavaUtil
import org.json.JSONArray
import org.json.JSONObject

data class CustomDnsServerEntity(
    var id: Long = 0L,
    var tag: String = "",
    var type: String = "udp",
    var userOrder: Long = 0L,
    var enabled: Boolean = true,
    var server: String = "",
    var serverPort: Int = 0,
    var path: String = "",
    var method: String = "",
    var headers: String = "",
    var domainResolver: String = "",
    var domainStrategy: String = "",
    var disableCache: Boolean = false,
    var rewriteTtl: Int = 0,
    var clientSubnet: String = "",
    var detour: String = "",
    var bindInterface: String = "",
    var inet4BindAddress: String = "",
    var inet6BindAddress: String = "",
    var connectTimeout: Long = 0L,
    var tcpFastOpen: Boolean = false,
    var tcpMultiPath: Boolean = false,
    var udpFragment: String = "",
    var tlsServerName: String = "",
    var tlsInsecure: Boolean = false,
    var tlsAlpn: String = "",
    var tlsCertificates: String = "",
    var localPreferGo: Boolean = false,
) : Parcelable {

    companion object {
        val RESERVED_TAGS = setOf("dns-direct", "dns-remote", "dns-local", "dns-fake")

        @JvmField
        val CREATOR = object : Parcelable.Creator<CustomDnsServerEntity> {
            override fun createFromParcel(parcel: Parcel): CustomDnsServerEntity {
                return CustomDnsServerEntity(parcel)
            }

            override fun newArray(size: Int): Array<CustomDnsServerEntity?> = arrayOfNulls(size)
        }

        private fun readOptionalString(parcel: Parcel): String {
            return try {
                parcel.readString().orEmpty()
            } catch (_: RuntimeException) {
                ""
            }
        }

        private fun readOptionalInt(parcel: Parcel): Int {
            return try {
                parcel.readInt()
            } catch (_: RuntimeException) {
                0
            }
        }

        private fun readOptionalLong(parcel: Parcel): Long {
            return try {
                parcel.readLong()
            } catch (_: RuntimeException) {
                0L
            }
        }

        private fun readOptionalBoolean(parcel: Parcel): Boolean {
            return try {
                parcel.readByte() != 0.toByte()
            } catch (_: RuntimeException) {
                false
            }
        }
    }

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        tag = parcel.readString().orEmpty(),
        type = parcel.readString().orEmpty().ifBlank { "udp" },
        userOrder = parcel.readLong(),
        enabled = parcel.readByte() != 0.toByte(),
        server = parcel.readString().orEmpty(),
        serverPort = parcel.readInt(),
        path = readOptionalString(parcel),
        method = readOptionalString(parcel),
        headers = readOptionalString(parcel),
        domainResolver = readOptionalString(parcel),
        domainStrategy = readOptionalString(parcel),
        disableCache = readOptionalBoolean(parcel),
        rewriteTtl = readOptionalInt(parcel),
        clientSubnet = readOptionalString(parcel),
        detour = readOptionalString(parcel),
        bindInterface = readOptionalString(parcel),
        inet4BindAddress = readOptionalString(parcel),
        inet6BindAddress = readOptionalString(parcel),
        connectTimeout = readOptionalLong(parcel),
        tcpFastOpen = readOptionalBoolean(parcel),
        tcpMultiPath = readOptionalBoolean(parcel),
        udpFragment = readOptionalString(parcel),
        tlsServerName = readOptionalString(parcel),
        tlsInsecure = readOptionalBoolean(parcel),
        tlsAlpn = readOptionalString(parcel),
        tlsCertificates = readOptionalString(parcel),
        localPreferGo = readOptionalBoolean(parcel),
    )

    fun displaySummary(): String {
        return when (type) {
            "local" -> "local"
            else -> buildString {
                append(type)
                if (server.isNotBlank()) append("://").append(server)
                if (serverPort > 0) append(":").append(serverPort)
                if (path.isNotBlank()) append(path)
            }
        }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(tag)
        parcel.writeString(type)
        parcel.writeLong(userOrder)
        parcel.writeByte(if (enabled) 1.toByte() else 0.toByte())
        parcel.writeString(server)
        parcel.writeInt(serverPort)
        parcel.writeString(path)
        parcel.writeString(method)
        parcel.writeString(headers)
        parcel.writeString(domainResolver)
        parcel.writeString(domainStrategy)
        parcel.writeByte(if (disableCache) 1.toByte() else 0.toByte())
        parcel.writeInt(rewriteTtl)
        parcel.writeString(clientSubnet)
        parcel.writeString(detour)
        parcel.writeString(bindInterface)
        parcel.writeString(inet4BindAddress)
        parcel.writeString(inet6BindAddress)
        parcel.writeLong(connectTimeout)
        parcel.writeByte(if (tcpFastOpen) 1.toByte() else 0.toByte())
        parcel.writeByte(if (tcpMultiPath) 1.toByte() else 0.toByte())
        parcel.writeString(udpFragment)
        parcel.writeString(tlsServerName)
        parcel.writeByte(if (tlsInsecure) 1.toByte() else 0.toByte())
        parcel.writeString(tlsAlpn)
        parcel.writeString(tlsCertificates)
        parcel.writeByte(if (localPreferGo) 1.toByte() else 0.toByte())
    }

    override fun describeContents(): Int = 0
}

object CustomDnsServerStore {
    fun allServers(): List<CustomDnsServerEntity> {
        val raw = DataStore.customDnsServers
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(JavaUtil.gson.fromJson(array.getJSONObject(i).toString(), CustomDnsServerEntity::class.java))
                }
            }.sortedBy { it.userOrder }
        }.getOrDefault(emptyList())
    }

    fun enabledServers(): List<CustomDnsServerEntity> = allServers().filter { it.enabled }

    fun getById(id: Long): CustomDnsServerEntity? = allServers().firstOrNull { it.id == id }

    fun save(server: CustomDnsServerEntity): CustomDnsServerEntity {
        val current = allServers().toMutableList()
        val saved =
            if (server.id == 0L) {
                server.copy(
                    id = (current.maxOfOrNull { it.id } ?: 0L) + 1L,
                    userOrder = (current.maxOfOrNull { it.userOrder } ?: 0L) + 1L,
                ).also(current::add)
            } else {
                server.also { updated ->
                    val index = current.indexOfFirst { it.id == updated.id }
                    if (index >= 0) current[index] = updated else current.add(updated)
                }
            }
        write(current)
        return saved
    }

    fun delete(server: CustomDnsServerEntity) {
        write(allServers().filterNot { it.id == server.id })
    }

    fun replaceAll(servers: List<CustomDnsServerEntity>) {
        write(servers)
    }

    private fun write(servers: List<CustomDnsServerEntity>) {
        DataStore.customDnsServers = JSONArray().apply {
            servers.sortedBy { it.userOrder }.forEach { put(JSONObject(JavaUtil.gson.toJson(it))) }
        }.toString()
    }
}
