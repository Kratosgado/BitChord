package com.music.bitchord.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // ---- Playlist list ----

    @Query("SELECT * FROM playlist_cache ORDER BY title COLLATE NOCASE ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_cache ORDER BY title COLLATE NOCASE ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Query("SELECT MAX(refreshedAt) FROM playlist_cache")
    suspend fun lastRefreshedAt(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Query("DELETE FROM playlist_cache WHERE playlistId NOT IN (:activeIds)")
    suspend fun deleteStale(activeIds: List<String>)

    @Transaction
    suspend fun replaceAll(playlists: List<PlaylistEntity>) {
        upsertPlaylists(playlists)
        if (playlists.isNotEmpty()) deleteStale(playlists.map { it.playlistId })
    }

    @Query("DELETE FROM playlist_cache WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: String)

    // ---- Membership ----

    @Query("SELECT videoId FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun getVideoIds(playlistId: String): List<String>

    @Query(
        """
        SELECT playlistId FROM playlist_tracks
        WHERE videoId = :videoId
        GROUP BY playlistId
        """
    )
    suspend fun playlistsContaining(videoId: String): List<String>

    /**
     * The timestamp of the most recent membership fetch for [playlistId],
     * or null if the membership for this playlist has never been cached.
     */
    @Query(
        "SELECT MAX(refreshedAt) FROM playlist_tracks WHERE playlistId = :playlistId"
    )
    suspend fun membershipRefreshedAt(playlistId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearTracks(playlistId: String)

    @Transaction
    suspend fun replaceTrackList(playlistId: String, videoIds: List<String>, now: Long) {
        clearTracks(playlistId)
        upsertTracks(videoIds.map { PlaylistTrackEntity(playlistId, it, now) })
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: PlaylistTrackEntity)

    @Query(
        "DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND videoId = :videoId"
    )
    suspend fun removeTrack(playlistId: String, videoId: String)
}
