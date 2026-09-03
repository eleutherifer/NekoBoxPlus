package io.nekohasekai.sagernet.database

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions.BrutalOptions
import moe.matsuri.nb4a.SingBoxOptions.MultiplexOptions

@Entity(tableName = "proxy_groups")
data class ProxyGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var ungrouped: Boolean = false,
    var name: String? = null,
    var type: Int = GroupType.BASIC,
    var subscription: SubscriptionBean? = null,
    var order: Int = GroupOrder.ORIGIN,
    var isSelector: Boolean = false,
    var frontProxy: Long = -1L,
    var landingProxy: Long = -1L,
    var forceUTLS: String = "",
    var enableMux: Boolean = false,
    var muxType: Int = 0,
    var muxMode: Int = 0,
    var muxConcurrency: Int = 8,
    var muxMaxConnections: Int = 4,
    var muxMinStreams: Int = 4,
    var muxPadding: Boolean = false,
    var muxBrutal: Boolean = false,
    var muxBrutalUpMbps: Int = 100,
    var muxBrutalDownMbps: Int = 100,
    var originOrder: String = "",
) : Serializable() {

    @Transient
    var export = false

    override fun initializeDefaultValues() {
        subscription?.applyDefaultValues()
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        if (export) {

            output.writeInt(0)
            output.writeString(name)
            output.writeInt(type)
            val subscription = subscription!!
            subscription.serializeForShare(output)

        } else {
            output.writeInt(3)
            output.writeLong(id)
            output.writeLong(userOrder)
            output.writeBoolean(ungrouped)
            output.writeString(name)
            output.writeInt(type)

            if (type == GroupType.SUBSCRIPTION) {
                subscription?.serializeToBuffer(output)
            }
            output.writeInt(order)
            output.writeBoolean(isSelector)
            output.writeLong(frontProxy)
            output.writeLong(landingProxy)
            output.writeString(forceUTLS)
            output.writeString(originOrder)
            output.writeBoolean(enableMux)
            output.writeInt(muxType)
            output.writeInt(muxMode)
            output.writeInt(muxConcurrency)
            output.writeInt(muxMaxConnections)
            output.writeInt(muxMinStreams)
            output.writeBoolean(muxPadding)
            output.writeBoolean(muxBrutal)
            output.writeInt(muxBrutalUpMbps)
            output.writeInt(muxBrutalDownMbps)
        }
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        if (export) {
            val version = input.readInt()

            name = input.readString()
            type = input.readInt()
            val subscription = SubscriptionBean()
            this.subscription = subscription

            subscription.deserializeFromShare(input)
        } else {
            val version = input.readInt()

            id = input.readLong()
            userOrder = input.readLong()
            ungrouped = input.readBoolean()
            name = input.readString()
            type = input.readInt()

            if (type == GroupType.SUBSCRIPTION) {
                val subscription = SubscriptionBean()
                this.subscription = subscription

                subscription.deserializeFromBuffer(input)
            }
            order = input.readInt()
            if (version >= 1) {
                isSelector = input.readBoolean()
                frontProxy = input.readLong()
                landingProxy = input.readLong()
                forceUTLS = input.readString().orEmpty()
            }
            if (version >= 2) {
                originOrder = input.readString().orEmpty()
            }
            if (version >= 3) {
                enableMux = input.readBoolean()
                muxType = input.readInt()
                muxMode = input.readInt()
                muxConcurrency = input.readInt()
                muxMaxConnections = input.readInt()
                muxMinStreams = input.readInt()
                muxPadding = input.readBoolean()
                muxBrutal = input.readBoolean()
                muxBrutalUpMbps = input.readInt()
                muxBrutalDownMbps = input.readInt()
            }
        }
    }

    fun originOrderIds(): List<Long> {
        if (originOrder.isBlank()) return emptyList()
        return originOrder.splitToSequence(',')
            .mapNotNull { it.toLongOrNull() }
            .distinct()
            .toList()
    }

    fun setOriginOrderIds(ids: Iterable<Long>) {
        originOrder = ids.distinct().joinToString(",")
    }

    fun singMux(): MultiplexOptions? =
        if (!enableMux) {
            null
        } else {
            MultiplexOptions().apply {
                enabled = true
                padding = muxPadding
                protocol = when (muxType) {
                    1 -> "smux"
                    2 -> "yamux"
                    3 -> "mux.cool"
                    else -> "h2mux"
                }
                if (muxMode == 1) {
                    max_connections = muxMaxConnections
                    min_streams = muxMinStreams
                } else {
                    max_streams = muxConcurrency
                }
                if (muxBrutal) {
                    brutal = BrutalOptions().apply {
                        enabled = true
                        up_mbps = muxBrutalUpMbps
                        down_mbps = muxBrutalDownMbps
                    }
                }
            }
        }

    fun displayName(): String {
        return name.takeIf { !it.isNullOrBlank() } ?: app.getString(R.string.group_default)
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM proxy_groups ORDER BY userOrder")
        fun allGroups(): List<ProxyGroup>

        @Query("SELECT * FROM proxy_groups WHERE type = ${GroupType.SUBSCRIPTION}")
        suspend fun subscriptions(): List<ProxyGroup>

        @Query("SELECT MAX(userOrder) + 1 FROM proxy_groups")
        fun nextOrder(): Long?

        @Query("SELECT * FROM proxy_groups WHERE id = :groupId")
        fun getById(groupId: Long): ProxyGroup?

        @Query("DELETE FROM proxy_groups WHERE id = :groupId")
        fun deleteById(groupId: Long): Int

        @Delete
        fun deleteGroup(group: ProxyGroup)

        @Delete
        fun deleteGroup(groupList: List<ProxyGroup>)

        @Insert
        fun createGroup(group: ProxyGroup): Long

        @Update
        fun updateGroup(group: ProxyGroup)

        @Query("DELETE FROM proxy_groups")
        fun reset()

        @Insert
        fun insert(groupList: List<ProxyGroup>)

    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<ProxyGroup>() {

            override fun newInstance(): ProxyGroup {
                return ProxyGroup()
            }

            override fun newArray(size: Int): Array<ProxyGroup?> {
                return arrayOfNulls(size)
            }
        }
    }

}
