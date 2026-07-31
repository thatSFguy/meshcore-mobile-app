package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class, ContactEntity::class, ChannelEntity::class,
        PathHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao
    abstract fun paths(): PathHistoryDao

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

        fun get(context: Context): MeshCoreDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshCoreDatabase::class.java,
                    "meshcore.db",
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}
