package io.nekohasekai.sagernet.backup.throne

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import moe.matsuri.nb4a.proxy.direct.DirectBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercise the actual portable SQLite DDL and converted rows without an Android device or a mock SQLite engine. */
class ThrDatabaseContractTest {
    @Test fun convertedRowsSatisfyThroneSqliteSchema() {
        val available = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("Python sqlite3 is needed for the host database contract check", available)
        val profiles = ThrExport.convertProfiles(listOf(
            ProxyEntity(id = 10, groupId = 1).putBean(DirectBean().apply { initializeDefaultValues(); name = "Fixture" }),
        ), listOf(ProxyGroup(id = 1)), mutableListOf())
        val routes = ThrRoutes.convert(listOf(RuleEntity(id = 1, outbound = 10, enabled = true,
            clashMode = "Work", domains = "full:example.com", packages = setOf("fixture.app"))),
            setOf(10), "block", mutableListOf(), "TUN", "Common")
        val fixture = JSONObject().put("ddl", JSONArray(DesktopSchema.DDL))
            .put("profiles", JSONArray(profiles)).put("routes", JSONArray(routes.map { route ->
                JSONObject().put("id", route.id).put("name", route.name).put("rules", JSONArray(route.rules))
            }))
        val script = """
            import json, sqlite3, sys
            data = json.load(sys.stdin)
            db = sqlite3.connect(':memory:')
            db.execute('PRAGMA foreign_keys=ON')
            for sql in data['ddl']: db.execute(sql)
            def insert(table, row):
                cols = ','.join('"'+key+'"' for key in row)
                placeholders = ','.join('?' for key in row)
                db.execute('INSERT INTO '+table+' ('+cols+') VALUES ('+placeholders+')', list(row.values()))
            insert('groups', {'id':1, 'profiles_json':json.dumps([p['id'] for p in data['profiles']])})
            insert('groups_order', {'group_id':1, 'display_order':0})
            for row in data['profiles']: insert('profiles', row)
            for route in data['routes']:
                insert('route_profiles', {'id':route['id'], 'name':route['name']})
                for i, rule in enumerate(route['rules']):
                    insert('route_rules', dict(rule, route_profile_id=route['id'], rule_order=i))
            insert('entity_ids', {'profile_last_id':10, 'group_last_id':1, 'route_profile_last_id':2})
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
            assert db.execute('SELECT COUNT(*) FROM route_profiles').fetchone()[0] == 2
            for gid, text in db.execute('SELECT id, profiles_json FROM groups'):
                assert set(json.loads(text)) == {p[0] for p in db.execute('SELECT id FROM profiles WHERE gid=?', (gid,))}
            for rid, count, low, high in db.execute('SELECT route_profile_id, COUNT(*), MIN(rule_order), MAX(rule_order) FROM route_rules GROUP BY route_profile_id'):
                assert low == 0 and high == count-1
        """.trimIndent()
        val process = ProcessBuilder("python3", "-c", script).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(fixture.toString()) }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
