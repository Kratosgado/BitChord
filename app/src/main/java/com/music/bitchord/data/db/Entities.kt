package com.music.bitchord.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_cache")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    /** Epoch ms when this row was last written from the network. */
    val refreshedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "videoId"],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val videoId: String,
    /** Epoch ms when this playlist's full track list was last fetched. */
    val refreshedAt: Long,
)

@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String,   // ADD_TO_PLAYLIST | LIKE | DISLIKE | UNLIKE
    val videoId: String,
    val playlistId: String?,  // null for like/dislike
    val queuedAt: Long,       // epoch ms, determines processing order
    val retryCount: Int = 0,
    val status: String = PendingActionStatus.PENDING,
)

object PendingActionStatus {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val FAILED = "FAILED"
}

object PendingActionType {
    const val ADD_TO_PLAYLIST = "ADD_TO_PLAYLIST"
    const val LIKE = "LIKE"
    const val DISLIKE = "DISLIKE"
    const val UNLIKE = "UNLIKE"
}
