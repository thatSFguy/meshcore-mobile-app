package io.github.thatsfguy.meshcore.android.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, ContactEntity::class, ChannelEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MeshCoreDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
    abstract fun channels(): ChannelDao

    companion object {
        @Volatile private var instance: MeshCoreDatabase? = null

        fun get(context: Context): MeshCoreDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshCoreDatabase::class.java,
                    "meshcore.db",
                ).fallbackToDestructiveMigrationOnDowngrade().build().also { instance = it }
            }
    }
}
