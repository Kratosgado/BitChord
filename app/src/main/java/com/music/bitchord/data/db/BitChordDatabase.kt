package com.music.bitchord.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PendingActionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BitChordDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        @Volatile private var instance: BitChordDatabase? = null

        fun get(context: Context): BitChordDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BitChordDatabase::class.java,
                "bitchord.db",
            ).build().also { instance = it }
        }
    }
}
