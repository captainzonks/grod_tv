package com.captainzonks.grodtv.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_entries ORDER BY position ASC")
    fun observeAll(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queue_entries ORDER BY position ASC")
    suspend fun list(): List<QueueEntity>

    @Query("SELECT COUNT(*) FROM queue_entries")
    suspend fun size(): Int

    @Query("SELECT * FROM queue_entries ORDER BY position ASC LIMIT 1")
    suspend fun peek(): QueueEntity?

    @Insert
    suspend fun insert(entity: QueueEntity): Long

    @Query("DELETE FROM queue_entries WHERE rowId = :rowId")
    suspend fun deleteByRowId(rowId: Long)

    @Query("DELETE FROM queue_entries")
    suspend fun clear()

    @Query("UPDATE queue_entries SET position = position - 1 WHERE position > :removedPosition")
    suspend fun reindexAfterRemoval(removedPosition: Int)

    @Transaction
    suspend fun pushAtEnd(videoId: String, title: String): Int {
        val nextPosition = size()
        insert(QueueEntity(videoId = videoId, title = title, position = nextPosition))
        // 1-based position returned to mirror Rust queue.push pos semantics
        return nextPosition + 1
    }

    @Transaction
    suspend fun popHead(): QueueEntity? {
        val head = peek() ?: return null
        deleteByRowId(head.rowId)
        reindexAfterRemoval(head.position)
        return head
    }

    @Transaction
    suspend fun removeAt1Based(pos1: Int): QueueEntity? {
        val pos0 = pos1 - 1
        val items = list()
        val entity = items.getOrNull(pos0) ?: return null
        deleteByRowId(entity.rowId)
        reindexAfterRemoval(entity.position)
        return entity
    }
}

@Dao
interface NowPlayingDao {

    @Query("SELECT * FROM now_playing WHERE singleton = 0 LIMIT 1")
    fun observe(): Flow<NowPlayingEntity?>

    @Query("SELECT * FROM now_playing WHERE singleton = 0 LIMIT 1")
    suspend fun get(): NowPlayingEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun set(entity: NowPlayingEntity)

    @Query("DELETE FROM now_playing WHERE singleton = 0")
    suspend fun clear()
}
