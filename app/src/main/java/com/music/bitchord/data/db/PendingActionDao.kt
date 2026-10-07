package com.music.bitchord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingActionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(action: PendingActionEntity): Long

    @Query(
        """
        SELECT * FROM pending_actions
        WHERE status != '${PendingActionStatus.FAILED}'
        ORDER BY queuedAt ASC
        """
    )
    suspend fun pendingAndInFlight(): List<PendingActionEntity>

    @Query("SELECT COUNT(*) FROM pending_actions WHERE status = '${PendingActionStatus.PENDING}'")
    suspend fun pendingCount(): Int

    @Query(
        "UPDATE pending_actions SET status = :status WHERE id = :id"
    )
    suspend fun setStatus(id: Long, status: String)

    @Query(
        "UPDATE pending_actions SET retryCount = retryCount + 1, status = '${PendingActionStatus.PENDING}' WHERE id = :id"
    )
    suspend fun incrementRetry(id: Long)

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        DELETE FROM pending_actions
        WHERE actionType = '${PendingActionType.ADD_TO_PLAYLIST}'
          AND videoId = :videoId
          AND playlistId = :playlistId
          AND status = '${PendingActionStatus.PENDING}'
        """
    )
    suspend fun cancelPendingAdd(videoId: String, playlistId: String)
}
