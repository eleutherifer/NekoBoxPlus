package io.nekohasekai.sagernet.backup.throne

import androidx.core.database.sqlite.transaction
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.internal.*
import io.nekohasekai.sagernet.group.XrayParser
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable

/** One-way migration. No source object is updated, and the target database is never the live Room database. */
object ThrExport {
    data class Prepared(val file: File, val report: ThrExportReport) {
        fun discard() { file.delete() }
    }

    fun prepare(selection: ThrSelection): Prepared {
        require(selection.any())
        val directory = File(app.cacheDir, "throne-export").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            ?.forEach { it.delete() }
        val database = File.createTempFile("throne-", ".db", directory)
        val backup = File.createTempFile("Throne-backup-", ".thrbackup", directory)
        try {
            val omissions = mutableListOf<ThrOmission>()
            val snapshot = SagerDatabase.instance.runInTransaction(Callable {
                Snapshot(SagerDatabase.groupDao.allGroups(), SagerDatabase.proxyDao.getAll(), SagerDatabase.rulesDao.allRules())
            })
            // Settings have their own Room database; read them in a separate, short transaction.
            val stored = PublicDatabase.instance.runInTransaction(Callable { PublicDatabase.kvPairDao.all() })
            val groups = snapshot.groups.sortedWith(compareBy(ProxyGroup::userOrder, ProxyGroup::id))
            val profiles = if (selection.profiles) convertProfiles(snapshot.profiles, groups, omissions) else emptyList()
            val ids = profiles.mapTo(hashSetOf()) { it["id"] as Long }
            val routes = if (selection.routes) ThrRoutes.convert(snapshot.rules, ids,
                DataStore.tunUnrecognizedTraffic, omissions, app.getString(R.string.thr_tun_protection),
                app.getString(R.string.thr_common_routes), bypassMode = DataStore.proxyApps && DataStore.bypass, resolveRuleSet = {
                    if (it.startsWith("rsip:") || it.startsWith("rssite:")) moe.matsuri.nb4a.processRulesetUrl(it).first else it
                }) else emptyList()
            val groupRoutes = mutableMapOf<Long, Long>()
            val routeDns = mutableMapOf<Long, String>()
            val allRoutes = routes.toMutableList()
            if (selection.routes && selection.profiles) for (group in groups) {
                val sub = group.subscription ?: continue
                if (sub.routingEnabled != true) continue
                val storedRouting = io.nekohasekai.sagernet.routing.SubscriptionRoutingRepository.stored(sub) ?: continue
                val candidate = storedRouting.candidate()
                val policy = io.nekohasekai.sagernet.routing.SubscriptionRoutingPolicy(candidate)
                val converted = ThrRoutes.convert(policy.rules(), ids, DataStore.tunUnrecognizedTraffic, omissions,
                    app.getString(R.string.thr_tun_protection), candidate.name,
                    bypassMode = DataStore.proxyApps && DataStore.bypass).first()
                val id = allRoutes.size + 1L
                allRoutes += converted.copy(id = id)
                groupRoutes[group.id] = id
                val dns = JSONObject()
                for (setting in candidate.settings) {
                    when (setting.kind) {
                        io.nekohasekai.sagernet.routing.RoutingSettingKind.REMOTE_DNS,
                        io.nekohasekai.sagernet.routing.RoutingSettingKind.DIRECT_DNS -> {
                            val target = if (setting.kind == io.nekohasekai.sagernet.routing.RoutingSettingKind.REMOTE_DNS) "remote_dns" else "direct_dns"
                            val servers = setting.value.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
                            if (servers.size == 1) dns.put(target, ThrSettings.dnsAddress(servers.single()))
                            else omissions += ThrOmission("group", group.id, "subscription_routing", setting.kind.name, entityName = group.displayName())
                        }
                        io.nekohasekai.sagernet.routing.RoutingSettingKind.FAKE_DNS -> dns.put("fake_dns", setting.value.toBoolean())
                        else -> omissions += ThrOmission("group", group.id, "subscription_routing", setting.kind.name, entityName = group.displayName())
                    }
                }
                if (dns.length() > 0) {
                    val defaults = ThrSettings.convert(stored, mutableListOf())
                    for (key in listOf("remote_dns", "direct_dns", "remote_dns_disable_ipv6", "direct_dns_disable_ipv6",
                        "enable_dns_routing", "fakeip_disable_ipv6")) {
                        if (!dns.has(key)) defaults[key]?.let { dns.put(key, if (it == "true" || it == "false") it.toBoolean() else it) }
                    }
                    if (!dns.has("fake_dns")) dns.put("fake_dns", defaults["fakedns"].toBoolean())
                    routeDns[id] = dns.toString()
                }
            }
            if (selection.routes && CustomDnsServerStore.allServers().isNotEmpty()) {
                omissions += ThrOmission("setting", null, "dns_rule")
            }
            val settings = if (selection.settings) ThrSettings.convert(stored, omissions).toMutableMap() else mutableMapOf()
            if (selection.settings) {
                if (!selection.routes && DataStore.tunUnrecognizedTraffic != "insecure") {
                    omissions += ThrOmission("setting", null, "unsupported_setting", Key.TUN_UNRECOGNIZED_TRAFFIC)
                }
                if (!selection.profiles) {
                    stored.filter { it.key in setOf(Key.GLOBAL_TCP_FAST_OPEN, Key.GLOBAL_TCP_MULTI_PATH, Key.GLOBAL_UDP_FRAGMENT) }
                        .forEach { omissions += ThrOmission("setting", null, "unsupported_setting", it.key) }
                }
                if (selection.profiles) groups.firstOrNull()?.let { first ->
                    settings["current_group"] = groups.firstOrNull { it.id == DataStore.selectedGroup }?.id?.toString() ?: first.id.toString()
                }
                settings["remember_id"] = DataStore.selectedProxy.takeIf { it in ids }?.toString() ?: "-1919"
                if (routes.isNotEmpty()) {
                    val cachedMode = runCatching { libcore.Libcore.loadClashModeFromCache(io.nekohasekai.sagernet.Param.LIBCORE_CACHE_FILE_PATH) }
                        .getOrDefault("")
                    val mode = io.nekohasekai.sagernet.fmt.resolveActiveClashMode(cachedMode, snapshot.rules.filter { it.enabled })
                    settings["current_route_id"] = (routes.firstOrNull { it.mode.equals(mode, true) } ?: routes.first()).id.toString()
                }
            }
            SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
                db.disableWriteAheadLogging()
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                DesktopSchema.DDL.forEach(db::execSQL)
                db.transaction {
                    if (selection.profiles) {
                        for ((index, group) in groups.withIndex()) {
                            insert(db, "groups", groupRow(group, ids, profiles, omissions, group.id in groupRoutes) + mapOf("route_id" to (groupRoutes[group.id] ?: -1L)))
                            insert(db, "groups_order", mapOf("group_id" to group.id, "display_order" to index))
                        }
                        profiles.forEach { insert(db, "profiles", it) }
                    }
                    for (route in allRoutes) {
                        val endpoints = profiles.filter { it["type"] in setOf("openvpn", "openconnect") }.map { it["id"] }
                        insert(db, "route_profiles", mapOf("id" to route.id, "name" to route.name,
                            "default_outbound_id" to -1, "endpoint_profile_ids" to JSONArray(endpoints).toString(),
                            "custom_dns" to if (route.id in routeDns) 1 else 0, "dns_json" to routeDns[route.id].orEmpty()))
                        route.rules.forEachIndexed { index, row ->
                            insert(db, "route_rules", row + mapOf("route_profile_id" to route.id, "rule_order" to index))
                        }
                    }
                    settings.forEach { (key, value) -> insert(db, "settings", mapOf("key" to key, "value" to value)) }
                    insert(db, "entity_ids", mapOf("profile_last_id" to (ids.maxOrNull() ?: 0),
                        "group_last_id" to (if (selection.profiles) groups.maxOfOrNull { it.id } ?: 0 else 0),
                        "route_profile_last_id" to allRoutes.size, "otp_profile_last_id" to 0))
                }
                verify(db)
            }
            backup.outputStream().use { ThrBackup.write(it, database, selection, BuildConfig.VERSION_NAME) }
            return Prepared(backup, ThrExportReport(profiles.size, allRoutes.size, settings.size, omissions.distinct()))
        } catch (error: Throwable) {
            backup.delete()
            throw error
        } finally {
            database.delete()
            File(database.path + "-journal").delete()
        }
    }

    private data class Snapshot(val groups: List<ProxyGroup>, val profiles: List<ProxyEntity>, val rules: List<RuleEntity>)

    internal fun convertProfiles(
        source: List<ProxyEntity>, groups: List<ProxyGroup>, omissions: MutableList<ThrOmission>,
    ): List<ThrRow> {
        val ordered = source.sortedWith(compareBy(ProxyEntity::groupId, ProxyEntity::userOrder, ProxyEntity::id))
        val ranks = ordered.mapIndexed { index, profile -> profile.id to index.toLong() * 1_000_000 }.toMap().toMutableMap()
        val byId = source.associateBy { it.id }
        val converted = linkedMapOf<Long, ThrRow>()
        val visiting = hashSetOf<Long>()
        val rejected = hashSetOf<Long>()
        var nextId = maxOf(0, source.maxOfOrNull { it.id } ?: 0) + 1
        val groupIds = groups.mapTo(hashSetOf()) { it.id }
        fun convert(profile: ProxyEntity): Boolean {
            if (profile.id in converted) return true
            if (profile.id in rejected) return false
            if (!visiting.add(profile.id)) return false
            val profileOmissions = mutableListOf<ThrOmission>()
            try {
                if (profile.groupId !in groupIds) throw ThrProfiles.Unsupported("missing_target")
                val bean = profile.requireBean()
                if (!bean.customConfigJson.isNullOrBlank() || !bean.customOutboundJson.isNullOrBlank()) {
                    throw ThrProfiles.Unsupported("custom_json")
                }
                val json = if (bean is ChainBean) {
                    if (bean.proxies.isEmpty() || bean.proxies.any { id -> byId[id]?.let { convert(it) } != true }) {
                        throw ThrProfiles.Unsupported("missing_target")
                    }
                    // Both editors store entry-hop first. NekoBox's resolver reverses only for runtime generation.
                    JSONObject().put("type", "chain").put("name", bean.name).put("list", JSONArray(bean.proxies))
                } else {
                    if (bean is ProxySetBean && bean.mode == ProxySetBean.MODE_URL_TEST) {
                        val children = bean.decodeEmbeddedProfiles()
                        children.forEachIndexed { index, child ->
                            child.id = nextId++
                            ranks[child.id] = (ranks[profile.id] ?: 0) + index + 1
                            child.groupId = profile.groupId
                            child.userOrder = profile.userOrder
                            convert(child)
                        }
                        throw ThrProfiles.Unsupported("urltest_wrapper")
                    }
                    // Older raw Xray balancers are converted with the existing parser, then flattened.
                    if (profile.configBean != null && profile.configBean!!.config.contains("\"balancers\"")) {
                        val parsed = XrayParser.parse(profile.configBean!!.config)
                        if (parsed != null) {
                            parsed.forEachIndexed { index, childBean ->
                                val child = ProxyEntity(id = nextId++, groupId = profile.groupId, userOrder = profile.userOrder)
                                    .putBean(childBean)
                                ranks[child.id] = (ranks[profile.id] ?: 0) + index + 1
                                convert(child)
                            }
                            throw ThrProfiles.Unsupported("urltest_wrapper")
                        }
                    }
                    ThrProfiles.convert(profile, groups.firstOrNull { it.id == profile.groupId }, omissions = profileOmissions)
                }
                val type = json.optString("type", "xrayvless").let { if (it == "hysteria2") "hysteria" else it }
                converted[profile.id] = mapOf("id" to profile.id, "gid" to profile.groupId, "type" to type,
                    "name" to bean.name.orEmpty(), "outbound_json" to json.toString(),
                    "traffic_dl" to profile.rx, "traffic_up" to profile.tx,
                    "latency" to profile.ping.coerceAtLeast(0), "test_country" to profile.countryCode)
                return true
            } catch (error: Exception) {
                rejected += profile.id
                profileOmissions += ThrOmission("profile", profile.id,
                    (error as? ThrProfiles.Unsupported)?.reason ?: "profile_options", (error as? ThrProfiles.Unsupported)?.field)
                return false
            } finally {
                if (profileOmissions.isNotEmpty()) {
                    val name = runCatching { profile.displayName() }.getOrNull()
                    omissions += profileOmissions.map { it.copy(entityName = name) }
                }
                visiting.remove(profile.id)
            }
        }
        source.sortedWith(compareBy(ProxyEntity::groupId, ProxyEntity::userOrder, ProxyEntity::id)).forEach { convert(it) }
        return converted.values.sortedBy { ranks[it["id"] as Long] ?: Long.MAX_VALUE }
    }

    private fun groupRow(group: ProxyGroup, ids: Set<Long>, profiles: List<ThrRow>, omissions: MutableList<ThrOmission>, routingExported: Boolean = false): ThrRow {
        fun reference(id: Long): Long {
            if (id <= 0) return -1
            if (id in ids) return id
            omissions += ThrOmission("group", group.id, "missing_target", entityName = group.displayName())
            return -1
        }
        val row = mutableMapOf<String, Any?>("id" to group.id, "name" to group.displayName(),
            "profiles_json" to JSONArray(profiles.filter { it["gid"] == group.id }.map { it["id"] }).toString(),
            "front_proxy_id" to reference(group.frontProxy), "landing_proxy_id" to reference(group.landingProxy))
        if (DataStore.groupOrderModeAlways) {
            row["sort_method"] = when (group.order) { 1 -> 3; 2 -> 8; else -> 0 }
        } else if (group.order in setOf(1, 2)) {
            omissions += ThrOmission("group", group.id, "unsupported_setting", "order", entityName = group.displayName())
        }
        if (group.isSelector) omissions += ThrOmission("group", group.id, "profile_options", entityName = group.displayName())
        val sub = group.subscription
        if (group.type == GroupType.SUBSCRIPTION && sub != null) {
            row["url"] = sub.link
            row["sub_last_update"] = sub.lastUpdated.toLong()
            row["skip_auto_update"] = if (sub.autoUpdate == true) 0 else 1
            val options = JSONObject().put("user_agent", sub.customUserAgent.orEmpty())
                .put("remove_duplicates", sub.deduplication == true)
            if (sub.autoUpdate == true) options.put("update_interval", sub.autoUpdateDelay)
            options.put("send_hwid", if (sub.hwidEnabled == true) 1 else 2)
            if (sub.hwidEnabled == true) options.put("hwid", sub.hwid.orEmpty())
            if (sub.filterMode != 0 && !sub.filterRegex.isNullOrBlank()) {
                options.put(if (sub.filterMode == 1) "name_include" else "name_exclude", sub.filterRegex)
            }
            row["sub_options_json"] = options.toString()
            row["info"] = sub.subscriptionUserinfo
            row["sub_metadata_json"] = JSONObject().apply {
                put("valid", true)
                put("expire", sub.expireAt ?: 0L)
                put("announce", sub.announcement.orEmpty())
                put("support_url", sub.supportUrl.orEmpty())
                put("web_url", sub.profileWebPageUrl.orEmpty().ifBlank { sub.homepage.orEmpty() })
                // Retain the quota from the provider header; metadata supersedes the legacy info column.
                Regex("(upload|download|total|expire)\\s*=\\s*(\\d+)").findAll(sub.subscriptionUserinfo.orEmpty()).forEach {
                    put(it.groupValues[1], it.groupValues[2].toLongOrNull() ?: 0L)
                    if (it.groupValues[1] == "total") put("has_quota", true)
                }
            }.toString()
            if (sub.forceResolve == true || sub.updateWhenConnectedOnly == true || sub.spoofApp != 0 ||
                sub.spoofDevice == true || !sub.serverDnsResolver.isNullOrBlank()) {
                for ((field, active) in listOf("forceResolve" to (sub.forceResolve == true),
                    "updateWhenConnectedOnly" to (sub.updateWhenConnectedOnly == true),
                    "spoofApp" to (sub.spoofApp != 0), "spoofDevice" to (sub.spoofDevice == true),
                    "serverDnsResolver" to !sub.serverDnsResolver.isNullOrBlank())) {
                    if (active) omissions += ThrOmission("group", group.id, "subscription_options", field, entityName = group.displayName())
                }
            }
            if ((!routingExported && (sub.routingEnabled == true || !sub.routingPayload.isNullOrBlank())) || !sub.autoRoutingUrl.isNullOrBlank()) {
                omissions += ThrOmission("group", group.id, "subscription_routing", entityName = group.displayName())
            }
        }
        return row
    }

    private fun insert(db: SQLiteDatabase, table: String, row: ThrRow) {
        val values = ContentValues().apply {
            row.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
        db.insertOrThrow(table, null, values)
    }

    private fun verify(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
        db.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
        db.rawQuery("SELECT id, profiles_json FROM groups", null).use { cursor ->
            while (cursor.moveToNext()) {
                val listed = JSONArray(cursor.getString(1)).let { array -> (0 until array.length()).map { array.getLong(it) } }
                val actual = db.rawQuery("SELECT id FROM profiles WHERE gid = ?", arrayOf(cursor.getLong(0).toString())).use {
                    buildSet { while (it.moveToNext()) add(it.getLong(0)) }
                }
                check(listed.size == listed.toSet().size && listed.toSet() == actual)
            }
        }
    }
}
