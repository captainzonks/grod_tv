package com.captainzonks.grodtv.queue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_entries")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    @ColumnInfo(name = "video_id") val videoId: String,
    val title: String,
    val position: Int,
)

@Entity(tableName = "now_playing")
data class NowPlayingEntity(
    @PrimaryKey val singleton: Int = 0,
    @ColumnInfo(name = "video_id") val videoId: String,
    val title: String,
) {
    companion object {
        const val ID: Int = 0
    }
}
