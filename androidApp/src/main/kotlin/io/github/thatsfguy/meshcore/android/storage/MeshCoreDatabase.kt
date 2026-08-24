package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [
        MessageEntity::class, ContactEntity::class, ChannelEntity::class,
        PathHistoryEntity::class, DiscoveredEntity::class, NeighbourEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao
    abstract fun paths(): PathHistoryDao
    abstract fun discovered(): DiscoveredDao
    abstract fun neighbours(): NeighbourDao

    companion object {
        private const val TAG = "MeshCoreDb"
        private const val DB_NAME = "meshcore.db"

        /** Used only when an encrypted DB exists but its key is gone —
         *  the original file is preserved, never opened destructively. */
        private const val RECOVERY_DB_NAME = "meshcore-nokey.db"

        @Volatile private var instance: MeshCoreDatabase? = null

        /** v2 adds path_history — message/contact data is preserved. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `path_history` (" +
                        "`selfKey` TEXT NOT NULL, `contactKey` TEXT NOT NULL, " +
                        "`pathHex` TEXT NOT NULL, `hops` INTEGER NOT NULL, " +
                        "`successes` INTEGER NOT NULL, `failures` INTEGER NOT NULL, " +
                        "`lastWorkedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`selfKey`, `contactKey`, `pathHex`))",
                )
            }
        }

        /** v3 adds messages.attempts (retry counter). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 adds the discovery inbox. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `discovered` (" +
                        "`selfKey` TEXT NOT NULL, `keyHex` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`type` INTEGER NOT NULL, `latitude` REAL, `longitude` REAL, " +
                        "`firstHeardAt` INTEGER NOT NULL, `lastHeardAt` INTEGER NOT NULL, " +
                        "`snr` REAL NOT NULL, `rssi` INTEGER NOT NULL, `advertHex` TEXT NOT NULL, " +
                        "PRIMARY KEY(`selfKey`, `keyHex`))",
                )
            }
        }

        /**
         * v5 adds messages.reactionsJson. ALTER TABLE ADD COLUMN only —
         * no table rewrite, so existing message rows are untouched.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reactionsJson` TEXT")
            }
        }

        /** v6 adds messages.hops. ADD COLUMN only. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `hops` INTEGER")
            }
        }

        /**
         * v7 adds path_history.hashWidth. ADD COLUMN only, defaulting to
         * 0 = "unknown": existing rows were written with a hop count
         * that was really a byte count, and the width they were recorded
         * at is not recoverable from the stored hex. They are repaired
         * (or deleted) at runtime once the radio reports its width —
         * doing it here would mean guessing 1, which is exactly the
         * assumption that produced the wrong counts.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `path_history` ADD COLUMN `hashWidth` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 adds messages.arrivalPathHex / arrivalHashWidth — the route
         * a message came in on. ADD COLUMN only; existing rows keep NULL,
         * which reads as "not known" rather than "direct".
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `arrivalPathHex` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `arrivalHashWidth` INTEGER")
            }
        }

        /**
         * v9 adds messages.repeatHopsHex / repeatHashWidth — which nodes
         * were heard re-broadcasting a message WE sent. Separate from
         * arrivalPath* on purpose: that column means "the route this
         * reached me by" and an outgoing row has no such thing. They were
         * briefly the same column, and one field meaning two opposite
         * directions is how a screen ends up confidently wrong.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `repeatHopsHex` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `repeatHashWidth` INTEGER")
            }
        }

        /**
         * A node's BLE address, learned when it entered update mode.
         *
         * Without it a node that goes into update mode and stays there
         * is unreachable by both routes at once: off the mesh, and with
         * an address the app never recorded.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `otaAddress` TEXT")
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `otaAnnouncedAt` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * What a node said it was, kept for when it can no longer say.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `boardName` TEXT")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `firmwareVersion` TEXT")
            }
        }

        /**
         * Update mode as its own column.
         *
         * It used to be read off `otaAddress` being non-null, which
         * conflated a durable property of the hardware with something
         * the node is doing this afternoon. Existing rows start at 0 —
         * not in update mode — which is the right default: a node that
         * really is waiting will be re-flagged the moment it answers,
         * and the alternative would resurrect the stale claim this
         * column exists to end.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `updateModeSince` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * The watermark that turns a persisted `start ota` reply back
         * into an event.
         *
         * Separate from v12 because v12 had already been applied when
         * this was found. Adding a column to a version that has shipped
         * — even only to one phone — changes its identity hash, and Room
         * then refuses to open a database it has no migration for. A new
         * version costs nothing and is the only safe way.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `otaReplyHandledAt` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Declare everything already in the console log accounted for.
         *
         * v13 gave the `start ota` reply a watermark, but left it at 0 on
         * existing rows — so the first time a node's firmware panel was
         * opened after upgrading, a reply from days ago was consumed as
         * though it had just arrived and the node was announced as being
         * in update mode. Seen on 13 Mile immediately after the upgrade:
         * "Recorded just now", from a reply that was nothing of the kind.
         *
         * Stamping the watermark to the upgrade moment says the only
         * true thing available — this app has seen all of that history
         * already — and any flag set by it goes with it. A node that
         * really is waiting re-announces itself the moment it answers
         * again, which is the same rule every other transition follows.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `contacts` SET `otaReplyHandledAt` = " +
                        "CAST(strftime('%s','now') AS INTEGER) * 1000, " +
                        "`updateModeSince` = 0",
                )
            }
        }

        /**
         * Clear board names that are firmware versions.
         *
         * The Firmware panel asked a node `board` and `ver` as two
         * separate coroutines, so their console rows could be written in
         * the opposite order to the sends — and since a CLI reply is
         * matched to its command by position, each answer was filed
         * under the other one. The version was then stored here as the
         * node's board, which is what the firmware picker narrows on and
         * what the bootloader scan matches names against.
         *
         * The sends are sequential now and `NodeIdentityReplies` refuses
         * an answer of the wrong shape, so nothing new is written. This
         * clears what already was: null means "ask again", which is the
         * correct state, and no real `getManufacturerName()` looks like
         * a version.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `contacts` SET `boardName` = NULL WHERE `boardName` LIKE " +
                        "'%(Build:%' OR `boardName` GLOB 'v[0-9]*' OR `boardName` GLOB '[0-9]*.[0-9]*'",
                )
            }
        }

        /**
         * Clear board names that are a node's `start ota` reply.
         *
         * The same defect as v15, with a different intruder: an
         * unanswered `board` leaves the reply queue holding an
         * expectation, and `OK - mac: …` satisfied it. On a live
         * repeater this was stored and shown as the board — and it is
         * what the build picker narrows on, so it offered all
         * thirty-one boards in the release instead of the one.
         *
         * `NodeIdentityReplies` now refuses an answer of that shape, so
         * nothing new is written; this clears what already was. Null
         * means "ask again", which is correct and cheap.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `contacts` SET `boardName` = NULL WHERE `boardName` LIKE '%mac:%' " +
                        "OR `boardName` LIKE 'OK%'",
                )
            }
        }

        /**
         * v17 adds the neighbour store: a repeater's one-hop table, kept
         * with the local clock reading that produced it.
         *
         * CREATE TABLE only — nothing existing is touched. There is no
         * back-fill to do and none that would be honest: `heard_seconds_ago`
         * is elapsed time against the moment of collection, and rows that
         * were never collected have no such moment.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `neighbours` (" +
                        "`selfKey` TEXT NOT NULL, `repeaterKey` TEXT NOT NULL, " +
                        "`keyPrefixHex` TEXT NOT NULL, `snr` REAL NOT NULL, " +
                        "`heardSecondsAgo` INTEGER NOT NULL, `collectedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`selfKey`, `repeaterKey`, `keyPrefixHex`))",
                )
            }
        }

        /**
         * Open the database, encrypted with [passphrase] when one is
         * available (see [DatabaseKey]). A pre-existing PLAINTEXT
         * database is converted in place first, so turning encryption on
         * never costs the user their history.
         *
         * A null [passphrase] means the Keystore was unusable on this
         * device: we open unencrypted rather than lose data, and the
         * reason is surfaced in Settings — silently pretending to be
         * encrypted would be worse than being plainly unencrypted.
         */
        fun get(context: Context, passphrase: ByteArray? = null): MeshCoreDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, passphrase).also { instance = it }
            }

        private fun build(context: Context, passphrase: ByteArray?): MeshCoreDatabase {
            val dbFile = context.getDatabasePath(DB_NAME)
            // SQLCipher's JNI must be loaded before ANY of its classes are
            // used — including the Room open-helper factory, not just the
            // migration path. If it can't load we have no way to read an
            // encrypted database, so treat it exactly like a missing key.
            val cipherReady = passphrase != null &&
                runCatching { System.loadLibrary("sqlcipher") }.isSuccess
            val key = if (cipherReady) passphrase else null
            if (passphrase != null && !cipherReady) {
                DatabaseKey.markPlaintext(
                    "SQLCipher native library unavailable on this device — " +
                        "storage is not encrypted.",
                )
                Log.e(TAG, "libsqlcipher failed to load")
            }
            val existsPlaintext = DatabaseKey.isPlaintextDatabase(dbFile)
            val existsEncrypted = dbFile.exists() && dbFile.length() > 0 && !existsPlaintext

            // NO destructive fallbacks anywhere in this builder. Room's
            // fallbackToDestructiveMigration* would silently DELETE the
            // user's history on an unexpected schema version (e.g. after
            // installing an older build). A hard failure is recoverable;
            // a wiped database is not.
            fun builderFor(name: String) =
                Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                        MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
                    )

            if (key == null) {
                if (existsEncrypted) {
                    // We cannot decrypt the existing database. Opening it
                    // would fail (and any destructive fallback would
                    // destroy it), so leave that file untouched and run
                    // from a separate one until the key comes back.
                    DatabaseKey.markPlaintext(
                        (DatabaseKey.encryptionUnavailableReason ?: "Database key unavailable") +
                            " The encrypted database has been left intact at $DB_NAME; " +
                            "this session uses a separate, empty database.",
                    )
                    Log.e(TAG, "No key for an encrypted DB — preserving it, using $RECOVERY_DB_NAME")
                    return builderFor(RECOVERY_DB_NAME).build()
                }
                DatabaseKey.markPlaintext(
                    DatabaseKey.encryptionUnavailableReason
                        ?: "No database key available — storage is not encrypted.",
                )
                return builderFor(DB_NAME).build()
            }

            if (existsPlaintext) {
                val migrated = runCatching { migratePlaintextToEncrypted(dbFile, key) }
                if (migrated.isFailure) {
                    // The original file is guaranteed untouched by the
                    // migration's own rollback; carry on unencrypted
                    // rather than risk the data.
                    return builderFor(DB_NAME).build()
                }
            }

            DatabaseKey.markEncrypted()
            return builderFor(DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphraseText(key)))
                .build()
        }

        /**
         * The passphrase as printable hex TEXT. SQLCipher's byte[] API
         * and its `KEY '...'` SQL form both run a KDF over these bytes,
         * so using one representation everywhere keeps the migration and
         * the runtime open in agreement.
         */
        private fun passphraseText(passphrase: ByteArray): ByteArray =
            passphrase.joinToString("") { "%02x".format(it) }.encodeToByteArray()

        /**
         * Convert a cleartext database into an encrypted one with
         * `sqlcipher_export`, then swap it into place. On any failure the
         * original file is left untouched — history is never destroyed to
         * satisfy encryption.
         */
        private fun migratePlaintextToEncrypted(dbFile: File, passphrase: ByteArray) {
            val staging = File(dbFile.parentFile, dbFile.name + ".encrypting")
            val backup = File(dbFile.parentFile, dbFile.name + ".plaintext-backup")
            val keyText = passphraseText(passphrase)
            try {
                staging.delete()

                // 1. Export the cleartext DB into a new encrypted file.
                //    CREATE_IF_NECESSARY matters: SQLite gives an ATTACHed
                //    database the MAIN connection's open flags.
                val plain = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, "", null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null,
                )
                val version = plain.version
                val expectedTables = plain.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table'", null,
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

                val escapedPath = staging.absolutePath.replace("'", "''")
                // Single-quoted KEY = passphrase (KDF applied), matching how
                // SupportOpenHelperFactory keys the database at runtime.
                plain.execSQL(
                    "ATTACH DATABASE '" + escapedPath + "' AS encrypted KEY '" +
                        keyText.decodeToString() + "'",
                )
                // sqlcipher_export is a SELECT: execSQL refuses it.
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
                plain.execSQL("DETACH DATABASE encrypted")
                plain.close()

                // 2. VERIFY the new file before anything is destroyed: it
                //    must open with our key and carry the same tables.
                val verify = SQLiteDatabase.openDatabase(
                    staging.absolutePath, keyText, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, null,
                )
                val gotTables = verify.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table'", null,
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                verify.version = version   // sqlcipher_export drops user_version
                verify.close()
                check(gotTables >= expectedTables) {
                    "encrypted copy has $gotTables tables, expected at least $expectedTables"
                }

                // 3. Swap via a BACKUP, never a delete-then-rename: if the
                //    rename fails we must still have the original.
                backup.delete()
                check(dbFile.renameTo(backup)) { "could not set the plaintext database aside" }
                if (!staging.renameTo(dbFile)) {
                    // Put the original back exactly as it was.
                    check(backup.renameTo(dbFile)) { "CRITICAL: could not restore the database" }
                    error("could not move the encrypted database into place")
                }

                // 4. Only now is it safe to drop the old WAL/SHM + backup.
                File(dbFile.parentFile, dbFile.name + "-wal").delete()
                File(dbFile.parentFile, dbFile.name + "-shm").delete()
                backup.delete()
                Log.i(TAG, "Database migrated to encrypted storage (v$version, $gotTables tables)")
            } catch (t: Throwable) {
                staging.delete()
                // If we got as far as setting the original aside, restore it.
                if (backup.exists() && !dbFile.exists()) {
                    if (backup.renameTo(dbFile)) {
                        Log.w(TAG, "Restored the plaintext database after a failed migration")
                    } else {
                        Log.e(TAG, "CRITICAL: plaintext database left at ${backup.name}")
                    }
                }
                Log.e(TAG, "Plaintext to encrypted migration failed", t)
                DatabaseKey.markPlaintext(
                    "Could not migrate the existing database to encrypted storage " +
                        "(${t::class.simpleName}); it remains unencrypted and intact.",
                )
                throw t
            }
        }
    }
}
