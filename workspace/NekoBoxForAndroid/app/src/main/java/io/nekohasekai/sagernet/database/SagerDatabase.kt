package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.app.AppGraph
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 28,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        private val RUSSIA_DOMAIN_RULE_NAMES = listOf(
            "Domain rule for Russia",
            "Правило домену для Russia",
            "Russia 網域規則",
            "Regla de dominio para Russia",
            "Domeneregel for Russia",
            "Russia için etki alanı kuralı",
            "Russia 域名规则",
            "Aturan domain untuk Russia",
            "دستورالعمل برای دامنه‌های Russia",
            "Règle de domaine pour Russia",
            "Правило домена для Russia",
            "قاعدة المجال ل Russia",
        )

        private val BROKEN_RUSSIA_DOMAIN_LINES = listOf(
            "regexp:\\.su" to "regexp:\\.su$",
            "regexp:\\.рф" to "regexp:\\.рф$",
            "regexp:\\.by" to "regexp:\\.by$",
            "regexp:\\.ru.com" to "regexp:\\.ru.com$",
            "regexp:\\.ru.net" to "regexp:\\.ru.net$",
            "regexp:\\.moscow" to "regexp:\\.moscow$",
            "regexp:\\.xn--p1ai" to "regexp:\\.xn--p1ai$",
            "regexp:\\.xn--p1acf" to "regexp:\\.xn--p1acf$",
            "regexp:\\.xn--80aswg" to "regexp:\\.xn--80aswg$",
            "regexp:\\.xn--c1avg" to "regexp:\\.xn--c1avg$",
            "regexp:\\.xn--80asehdb" to "regexp:\\.xn--80asehdb$",
            "regexp:\\.xn--d1acj3b" to "regexp:\\.xn--d1acj3b$",
            "regexp:\\.xn--90ais" to "regexp:\\.xn--90ais$",
        )

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN networkType TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN wifiSsid TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN wifiBssid TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN proxySetBean BLOB"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN masterDnsVPNBean BLOB"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN snellBean BLOB"
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN byeDPIBean BLOB"
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    UPDATE rules
                    SET domains = 'regexp:' || substr(domains, 7)
                    WHERE domains LIKE 'regex:%'
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE rules
                    SET domains = replace(domains, char(10) || 'regex:', char(10) || 'regexp:')
                    WHERE domains LIKE '%' || char(10) || 'regex:%'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN createDnsRule INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val namePlaceholders = RUSSIA_DOMAIN_RULE_NAMES.joinToString(",") { "?" }
                for ((oldLine, newLine) in BROKEN_RUSSIA_DOMAIN_LINES) {
                    database.execSQL(
                        """
                        UPDATE rules
                        SET domains = substr(
                            replace(char(10) || domains || char(10), ?, ?),
                            2,
                            length(replace(char(10) || domains || char(10), ?, ?)) - 2
                        )
                        WHERE name IN ($namePlaceholders)
                          AND (
                            domains = ?
                            OR domains LIKE ?
                            OR domains LIKE ?
                            OR domains LIKE ?
                          )
                        """.trimIndent(),
                        arrayOf(
                            "\n$oldLine\n",
                            "\n$newLine\n",
                            "\n$oldLine\n",
                            "\n$newLine\n",
                        ) + RUSSIA_DOMAIN_RULE_NAMES + arrayOf(
                            oldLine,
                            "$oldLine\n%",
                            "%\n$oldLine\n%",
                            "%\n$oldLine",
                        )
                    )
                }
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_groups ADD COLUMN forceUTLS TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN trustTunnelBean BLOB"
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_groups ADD COLUMN originOrder TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE rules ADD COLUMN clashMode TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN masqueBean BLOB"
                )
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE proxy_entities ADD COLUMN directBean BLOB"
                )
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE rules ADD COLUMN type TEXT NOT NULL DEFAULT 'normal'")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsAction TEXT NOT NULL DEFAULT 'route'")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsServer TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsStrategy TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsDisableCache INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsRewriteTtl INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsClientSubnet TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsRcode TEXT NOT NULL DEFAULT 'NOERROR'")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsRejectMethod TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsPredefinedAnswer TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsPredefinedNs TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE rules ADD COLUMN dnsPredefinedExtra TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE proxy_entities ADD COLUMN tailscaleBean BLOB")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE proxy_entities ADD COLUMN openVPNBean BLOB")
                database.execSQL("ALTER TABLE proxy_entities ADD COLUMN openConnectBean BLOB")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.addColumnIfMissing("proxy_entities", "openVPNBean", "BLOB")
                database.addColumnIfMissing("proxy_entities", "openConnectBean", "BLOB")
                database.addColumnIfMissing("proxy_groups", "enableMux", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("proxy_groups", "muxType", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("proxy_groups", "muxMode", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("proxy_groups", "muxConcurrency", "INTEGER NOT NULL DEFAULT 8")
                database.addColumnIfMissing("proxy_groups", "muxMaxConnections", "INTEGER NOT NULL DEFAULT 4")
                database.addColumnIfMissing("proxy_groups", "muxMinStreams", "INTEGER NOT NULL DEFAULT 4")
                database.addColumnIfMissing("proxy_groups", "muxPadding", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("proxy_groups", "muxBrutal", "INTEGER NOT NULL DEFAULT 0")
                database.addColumnIfMissing("proxy_groups", "muxBrutalUpMbps", "INTEGER NOT NULL DEFAULT 100")
                database.addColumnIfMissing("proxy_groups", "muxBrutalDownMbps", "INTEGER NOT NULL DEFAULT 100")
                database.addColumnIfMissing("proxy_entities", "countryCode", "TEXT NOT NULL DEFAULT ''")
                database.addColumnIfMissing("proxy_entities", "countrySource", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Both branches shipped different version-27 schemas. Fill the columns missing
                // from either layout so upgrades from plus and plus-1.14 converge on version 28.
                database.addColumnIfMissing("proxy_entities", "openVPNBean", "BLOB")
                database.addColumnIfMissing("proxy_entities", "openConnectBean", "BLOB")
                database.addColumnIfMissing("proxy_entities", "countryCode", "TEXT NOT NULL DEFAULT ''")
                database.addColumnIfMissing("proxy_entities", "countrySource", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun SupportSQLiteDatabase.addColumnIfMissing(
            table: String,
            column: String,
            definition: String,
        ) {
            val exists = query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
        }

        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .addMigrations(
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                )
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(AppGraph.databaseExecutor)
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
