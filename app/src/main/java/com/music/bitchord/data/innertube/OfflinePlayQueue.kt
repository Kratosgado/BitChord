package com.music.bitchord.data.innertube

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent record of plays that happened while offline.
 *
 * The three-ping sequence [PlaybackTracker] fires against YouTube is the only
 * thing that lets a play land in the account's history, and all three pings go
 * over the network — so a play that started with the radio off cannot be
 * registered until the radio is back on. The real YouTube Music client holds
 * that gap by remembering the play and reporting it the next time it reaches
 * the servers; this is the store that lets us do the same.
 *
 * One record per completed offline play. The CPN is generated at play time, so
 * every entry already carries a unique nonce — nothing has to be reserved at
 * flush time, and two devices draining in parallel cannot collide. Watchtime
 * is the seconds actually heard, bounded by the final progress tick.
 *
 * Capped at [MAX_ENTRIES]. Someone offline for weeks still shouldn't fill the
 * preference file with history pings, and recommendations past a few hundred
 * tracks ago are not improved by the next one.
 */
object OfflinePlayQueue {

    @Serializable
    data class Entry(
        val videoId: String,
        val cpn: String,
        val startedAtMs: Long,
        val watchtimeSeconds: Long,
    )

    private const val PREFS_NAME = "bitchord_offline_plays"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 500

    private lateinit var prefs: SharedPreferences

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun isReady(): Boolean = this::prefs.isInitialized

    @Synchronized
    fun enqueue(entry: Entry) {
        if (!isReady()) return
        val existing = readAll().toMutableList()
        existing.add(entry)
        // Oldest first out when we overflow: a weeks-stale play is already past
        // the point of being useful as a recommendation signal.
        while (existing.size > MAX_ENTRIES) existing.removeAt(0)
        write(existing)
    }

    @Synchronized
    fun snapshot(): List<Entry> = if (isReady()) readAll() else emptyList()

    @Synchronized
    fun remove(cpn: String) {
        if (!isReady()) return
        val remaining = readAll().filterNot { it.cpn == cpn }
        write(remaining)
    }

    @Synchronized
    fun clear() {
        if (!isReady()) return
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun readAll(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Entry>>(raw) }.getOrDefault(emptyList())
    }

    private fun write(entries: List<Entry>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_ENTRIES).apply()
            return
        }
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(entries)).apply()
    }
}
