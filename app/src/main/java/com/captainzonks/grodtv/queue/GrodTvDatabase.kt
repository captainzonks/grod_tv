package com.captainzonks.grodtv.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QueueEntity::class, NowPlayingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class GrodTvDatabase : RoomDatabase() {
    abstract fun queueDao(): QueueDao
    abstract fun nowPlayingDao(): NowPlayingDao

    companion object {
        fun build(context: Context): GrodTvDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GrodTvDatabase::class.java,
                "grod_tv.db",
            ).build()

        fun buildInMemory(context: Context): GrodTvDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                GrodTvDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
    }
}
