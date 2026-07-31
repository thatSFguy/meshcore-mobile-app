package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class, ContactEntity::class, ChannelEntity::class,
        PathHistoryEntity::class, DiscoveredEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao
    abstract fun paths(): PathHistoryDao
    abstract fun discovered(): DiscoveredDao

    companion object {
        @Volatile private var instance: MeshCoreDatabase? = null

        /** v2 adds path_history — message/contact data is preserved. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 adds the discovery inbox. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
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

        fun get(context: Context): MeshCoreDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshCoreDatabase::class.java,
                    "meshcore.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
