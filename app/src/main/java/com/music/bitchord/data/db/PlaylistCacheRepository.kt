package com.music.bitchord.data.db

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** How long a cached playlist list or membership set is considered fresh. */
private const val TTL_MS = 30 * 60 * 1000L // 30 minutes

class PlaylistCacheRepository(context: Context) {

    private val db = BitChordDatabase.get(context)
    private val dao = db.playlistDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Playlist list -------------------------------------------------------

    /** Emits the cached playlist list instantly, then stays live for updates. */
    fun observePlaylists(): Flow<List<UserPlaylist>> =
        dao.observePlaylists().map { rows -> rows.map(PlaylistEntity::toModel) }

    /**
     * Returns cached playlists immediately. If the cache is stale (or empty),
     * fires a background refresh so the next emission from [observePlaylists]
     * reflects the latest server state.
     */
    suspend fun getPlaylists(forceRefresh: Boolean = false): List<UserPlaylist> {
        val cached = dao.getPlaylists()
        val stale = isStale(dao.lastRefreshedAt())
        if (forceRefresh || stale || cached.isEmpty()) {
            scope.launch { refreshPlaylists() }
        }
        return cached.map(PlaylistEntity::toModel)
    }

    suspend fun refreshPlaylists() {
        YtMusicRepository.userPlaylists().onSuccess { playlists ->
            val now = System.currentTimeMillis()
            dao.replaceAll(playlists.map { it.toEntity(now) })
        }
    }

    /** Call after creating, renaming, or deleting a playlist so the cache stays consistent. */
    suspend fun invalidatePlaylists() {
        scope.launch { refreshPlaylists() }
    }

    suspend fun removePlaylistFromCache(playlistId: String) {
        dao.deletePlaylist(playlistId)
        dao.clearTracks(playlistId)
    }

    // ---- Membership ----------------------------------------------------------

    /**
     * Returns the set of playlistIds that already contain [videoId], using
     * the local cache. Triggers background fetches for any playlists whose
     * membership is missing or stale.
     */
    suspend fun playlistsContaining(videoId: String): Set<String> =
        dao.playlistsContaining(videoId).toHashSet()

    /**
     * Ensures membership data exists for every playlist in [playlists].
     * Serves from cache for fresh playlists; fetches from the network for
     * stale ones (capped at 3 concurrent requests).
     */
    suspend fun preloadMembership(playlists: List<UserPlaylist>) {
        val toFetch = playlists.filter { pl ->
            isStale(dao.membershipRefreshedAt(pl.playlistId))
        }
        if (toFetch.isEmpty()) return

        val limiter = Semaphore(3)
        withContext(Dispatchers.IO) {
            toFetch.forEach { playlist ->
                scope.launch {
                    limiter.withPermit { fetchAndCacheTrackList(playlist) }
                }
            }
        }
    }

    /**
     * Returns videoIds cached for [playlistId]. If the cache is stale, kicks
     * off a background fetch and returns whatever is locally available now.
     */
    suspend fun getVideoIds(playlistId: String): Set<String> {
        if (isStale(dao.membershipRefreshedAt(playlistId))) {
            scope.launch {
                dao.getPlaylists()
                    .firstOrNull { it.playlistId == playlistId }
                    ?.toModel()
                    ?.let { fetchAndCacheTrackList(it) }
            }
        }
        return dao.getVideoIds(playlistId).toHashSet()
    }

    /** Records a successful add in the local cache without a re-fetch. */
    suspend fun recordTrackAdded(playlistId: String, videoId: String) {
        val now = System.currentTimeMillis()
        dao.insertTrack(PlaylistTrackEntity(playlistId, videoId, now))
    }

    /** Records a successful removal in the local cache without a re-fetch. */
    suspend fun recordTrackRemoved(playlistId: String, videoId: String) {
        dao.removeTrack(playlistId, videoId)
    }

    // ---- Internal ------------------------------------------------------------

    private suspend fun fetchAndCacheTrackList(playlist: UserPlaylist) {
        YtMusicRepository.allSongs(playlist.browseId).onSuccess { songs ->
            val now = System.currentTimeMillis()
            dao.replaceTrackList(playlist.playlistId, songs.map { it.videoId }, now)
        }
    }

    private fun isStale(refreshedAt: Long?): Boolean =
        refreshedAt == null || System.currentTimeMillis() - refreshedAt > TTL_MS

    // ---- Mappers -------------------------------------------------------------

    private fun PlaylistEntity.toModel() = UserPlaylist(
        playlistId = playlistId,
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
    )

    private fun UserPlaylist.toEntity(now: Long) = PlaylistEntity(
        playlistId = playlistId,
        title = title,
        subtitle = subtitle,
        thumbnailUrl = thumbnailUrl,
        refreshedAt = now,
    )
}
