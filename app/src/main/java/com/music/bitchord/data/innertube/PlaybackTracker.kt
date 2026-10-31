package com.music.bitchord.data.innertube

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registers plays against the signed-in account's YouTube Music history, so
 * recommendations on the home tab reflect what's actually been listened to.
 *
 * Three pings make one play, which is the sequence a real client performs:
 *
 *  - `videostatsPlaybackUrl` the moment a track becomes audible. This creates
 *    the history entry.
 *  - `atrUrl` a few seconds in, which is what separates a play that started
 *    from a play that happened.
 *  - `videostatsWatchtimeUrl` as it plays on, and once more when it ends. An
 *    entry with no watchtime behind it looks like a track that was skipped,
 *    which is close to worthless as a recommendation signal.
 *
 * All three carry the same client-playback-nonce, which is what ties them to
 * one play; a nonce reused across tracks gets the second play discarded.
 *
 * Best-effort only: any failure here must never affect playback itself. That is
 * a constraint on how failures are handled, not permission to ignore them —
 * everything below is retried, because the alternative is what this file did
 * before, which was to lose a play to a single momentary refusal and never
 * mention it again.
 */
object PlaybackTracker {

    private const val TAG = "BitChord"

    /** Report watched time once this much new audio has gone by. */
    private const val REPORT_INTERVAL_SECONDS = 30L

    /**
     * Attempts at opening a session before the play is written off.
     *
     * A track starting is the worst possible moment to require a network round
     * trip to succeed first time: it is exactly when a stream is being resolved,
     * a connection may be handing over between wifi and cellular, and the player
     * is saturating whatever is left. One attempt, which is what this had, meant
     * a play lost for good to a blip that had nothing to do with it.
     */
    private const val OPEN_ATTEMPTS = 3

    private const val OPEN_RETRY_DELAY_MS = 2_000L

    /**
     * A videoId, as opposed to anything else that can be a media id.
     *
     * Local files carry their `content://` URI as an id, and a module source
     * carries whatever that module uses. Asking Google to register a play of one
     * of those is a request that cannot succeed, made once per track, and it was
     * being made — the guard is here rather than at the call site because this
     * object is the thing that knows what an id has to look like to be useful.
     */
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    private class Session(
        val videoId: String,
        val cpn: String,
        val tracking: Innertube.PlaybackTracking,
    ) {
        var reportedSeconds = 0L

        /** Set before the network call, so a slow flush can't stack up behind itself. */
        var flushingTo = 0L

        var atrSent = false
    }

    /**
     * The record a session leaves behind when the radio was off.
     *
     * No tracking URLs, no `atr` schedule, no HTTP calls while the track is
     * playing — those answers are fetched when the queue is drained. All we
     * know up front is the videoId, the nonce we picked for this play, when it
     * started, and how many seconds have gone by.
     */
    private class OfflineSession(
        val videoId: String,
        val cpn: String,
        val startedAtMs: Long,
    ) {
        var watchtimeSeconds = 0L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _registeredPlays = MutableStateFlow(0)

    /**
     * Bumped each time a play lands in the account's history. The home feed's
     * lead shelf is built from that history, so it has gone stale whenever this
     * moves — the counter is the signal to re-fetch, and carries no meaning
     * beyond having changed.
     */
    val registeredPlays: StateFlow<Int> = _registeredPlays.asStateFlow()

    /** Guards session hand-over: starting a track must not race its own pings. */
    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    /**
     * The current play's offline draft, if any. Mutually exclusive with
     * [session]: once a track is being tracked for real against YouTube we
     * stop holding an offline copy of it.
     */
    @Volatile
    private var offline: OfflineSession? = null

    /** The track a session is being opened for, so repeat calls don't stack up. */
    @Volatile
    private var opening: String? = null

    /** Guard around queue drain so a connectivity flap can't start it twice. */
    private val drainLock = Mutex()

    /**
     * Call when [videoId] becomes audible — both on play/resume and when the
     * queue moves on. A no-op while the same track is already being tracked, so
     * a pause/resume does not register a second play.
     */
    fun onPlaying(videoId: String) {
        if (!VIDEO_ID.matches(videoId)) return
        if (Innertube.cookie == null) return
        // A downloaded track plays perfectly well with the radio off — the
        // whole point of downloading it — so this is the one place that has
        // to ask before trying rather than let a request find out the hard
        // way. [meteredConnection] is null exactly when there is no active
        // network: with no way to open a session right now, the play is held
        // as a draft on disk and flushed once connectivity returns. That is
        // the behavior YouTube Music's own client has — downloaded tracks
        // played offline count against history retroactively.
        if (AppSettings.meteredConnection.value == null) {
            openOffline(videoId)
            return
        }
        if (session?.videoId == videoId || opening == videoId) return
        // Any stale offline draft is about to be replaced by a live session,
        // so stop accumulating against it. The track changed and this call is
        // what tells the tracker that; the previous draft is persisted by
        // whichever path routed us here (onTrackChanged / onPlaybackFinished).
        offline = null
        opening = videoId
        scope.launch(TrackLog.about(videoId)) {
            try {
                openWithRetries(videoId)
            } finally {
                if (opening == videoId) opening = null
            }
        }
    }

    /**
     * Open a local draft for a track that started while offline.
     *
     * A pause/resume on the same track must not record a second play, so this
     * is a no-op when the draft is already open for that videoId. The nonce
     * is picked now and travels with the entry through persistence, so a
     * flush that spans a process restart still carries one CPN per play.
     */
    private fun openOffline(videoId: String) {
        val existing = offline
        if (existing != null && existing.videoId == videoId) return
        // The queue moved on without a chance to persist the previous draft —
        // flush it now so its watchtime is not lost to the next track's start.
        if (existing != null) persistOffline(existing)
        offline = OfflineSession(
            videoId = videoId,
            cpn = Innertube.newCpn(),
            startedAtMs = System.currentTimeMillis(),
        )
    }

    private fun persistOffline(draft: OfflineSession) {
        // A draft that never passed the ATR threshold is a track that was
        // skipped the moment it started: not worth sending as a history entry
        // even once the radio comes back.
        if (draft.watchtimeSeconds < DEFAULT_ATR_SECONDS) return
        OfflinePlayQueue.enqueue(
            OfflinePlayQueue.Entry(
                videoId = draft.videoId,
                cpn = draft.cpn,
                startedAtMs = draft.startedAtMs,
                watchtimeSeconds = draft.watchtimeSeconds,
            ),
        )
    }

    /** Mirrors YouTube's own default ATR schedule — the "did they really listen" bar. */
    private const val DEFAULT_ATR_SECONDS = 5L

    /**
     * Call when the queue moves to a different track. The previous session's
     * watched time is flushed before it is dropped, so a track skipped at the
     * two-minute mark is reported as two minutes rather than lost.
     */
    fun onTrackChanged(positionSeconds: Long) {
        val closingOffline = offline
        if (closingOffline != null) {
            closingOffline.watchtimeSeconds = maxOf(closingOffline.watchtimeSeconds, positionSeconds)
            offline = null
            persistOffline(closingOffline)
        }
        val closing = session ?: return
        session = null
        scope.launch(TrackLog.about(closing.videoId)) {
            runCatching { flush(closing, positionSeconds, final = true) }
                .onFailure {
                    TrackLog.w(TAG, "final watchtime ping failed for ${closing.videoId}: ${it.message}")
                }
        }
    }

    /**
     * Periodic progress report for the current track, in seconds played.
     * Cheap to call often — it only hits the network every
     * [REPORT_INTERVAL_SECONDS] of new audio.
     */
    fun onProgress(videoId: String, positionSeconds: Long) {
        val draft = offline
        if (draft != null && draft.videoId == videoId) {
            draft.watchtimeSeconds = maxOf(draft.watchtimeSeconds, positionSeconds)
        }
        val current = session ?: return
        if (current.videoId != videoId) return
        if (!current.atrSent && positionSeconds >= current.tracking.atrAfterSeconds) {
            current.atrSent = true
            val atrUrl = current.tracking.atrUrl
            if (atrUrl != null) {
                scope.launch(TrackLog.about(videoId)) {
                    runCatching { Innertube.pingAtr(atrUrl, current.cpn) }
                        .onFailure { TrackLog.w(TAG, "atr ping failed for $videoId: ${it.message}") }
                }
            }
        }
        // Against `flushingTo`, not `reportedSeconds`. The sampler runs every
        // five seconds and a ping takes longer than that on a bad connection,
        // so gating on a value only written *after* the request returned fired
        // the same report several times over.
        if (positionSeconds - maxOf(current.reportedSeconds, current.flushingTo) < REPORT_INTERVAL_SECONDS) return
        scope.launch(TrackLog.about(videoId)) {
            runCatching { flush(current, positionSeconds) }
                .onFailure { TrackLog.w(TAG, "watchtime ping failed for $videoId: ${it.message}") }
        }
    }

    /**
     * Close out the current play for good — the queue running dry, or the
     * service going away.
     *
     * Neither of those fires a track transition, so without this a session that
     * ended by finishing its last song reported everything except the part that
     * says it finished. [withContext] `NonCancellable` because the usual caller
     * is a teardown that is about to cancel everything in sight.
     */
    fun onPlaybackFinished(positionSeconds: Long) {
        val closingOffline = offline
        if (closingOffline != null) {
            closingOffline.watchtimeSeconds = maxOf(closingOffline.watchtimeSeconds, positionSeconds)
            offline = null
            persistOffline(closingOffline)
        }
        val closing = session ?: return
        session = null
        scope.launch(TrackLog.about(closing.videoId)) {
            withContext(NonCancellable) {
                runCatching { flush(closing, positionSeconds, final = true) }
                    .onFailure {
                        TrackLog.w(TAG, "closing watchtime ping failed for ${closing.videoId}: ${it.message}")
                    }
            }
        }
    }

    /**
     * [open], given more than one chance.
     *
     * Stops early on the three answers that will not change: another track
     * having become current in the meantime, the session having opened, and
     * YouTube declining to issue a tracking block for this track at all. Only
     * genuine failures are repeated.
     */
    private suspend fun openWithRetries(videoId: String) {
        repeat(OPEN_ATTEMPTS) { attempt ->
            if (opening != videoId) return
            val settled = try {
                open(videoId)
            } catch (e: CancellationException) {
                // Not a failure, and not ours to swallow: the only thing that
                // cancels this is the tracker's own scope going away.
                throw e
            } catch (e: Throwable) {
                TrackLog.w(
                    TAG,
                    "history registration failed for $videoId " +
                        "(attempt ${attempt + 1}/$OPEN_ATTEMPTS): ${e.message}",
                )
                false
            }
            if (settled) return
            if (attempt < OPEN_ATTEMPTS - 1) delay(OPEN_RETRY_DELAY_MS)
        }
    }

    /**
     * @return whether there is anything left to try. False means the attempt is
     *   worth repeating — the answer was a failure, not a verdict.
     */
    private suspend fun open(videoId: String): Boolean = lock.withLock {
        // The one thing the tracking request cannot be answered without. Fetched
        // through [StreamResolver] so it is shared with — and usually already
        // warmed by — the resolve that is starting this very track.
        val signatureTimestamp = StreamResolver.signatureTimestamp(videoId)
        if (signatureTimestamp == null) {
            TrackLog.w(TAG, "no signature timestamp yet; retrying history for $videoId")
            return@withLock false
        }
        val tracking = Innertube.playbackTracking(videoId, signatureTimestamp)
        if (tracking == null) {
            TrackLog.d(TAG, "no playback tracking for $videoId (guest, or the player declined)")
            // A verdict, not a failure — asking again with the same timestamp
            // gets the same answer.
            return@withLock true
        }
        val fresh = Session(videoId, Innertube.newCpn(), tracking)
        val status = Innertube.pingPlayback(tracking.playbackUrl, fresh.cpn)
        session = fresh
        _registeredPlays.value++
        TrackLog.d(TAG, "history entry created for $videoId (HTTP $status)")
        true
    }

    private suspend fun flush(target: Session, positionSeconds: Long, final: Boolean = false) {
        val url = target.tracking.watchtimeUrl ?: return
        // A final report is worth sending even at a position already covered:
        // it is the `final=1` that matters, not the number.
        if (!final && positionSeconds <= target.reportedSeconds) return
        target.flushingTo = maxOf(target.flushingTo, positionSeconds)
        lock.withLock {
            val status = Innertube.pingWatchtime(url, target.cpn, positionSeconds, final)
            target.reportedSeconds = maxOf(target.reportedSeconds, positionSeconds)
            TrackLog.d(
                TAG,
                "watchtime ${positionSeconds}s reported for ${target.videoId}" +
                    "${if (final) " (final)" else ""} (HTTP $status)",
            )
        }
    }

    /**
     * Opens the queue of offline plays and keeps it draining whenever the
     * device is online. One call at process start, from [BitChordApplication].
     *
     * The observer fires once per distinct connectivity state, so a Wi-Fi to
     * cellular handover does not restart a drain that is already underway;
     * the first value — null or not — triggers a startup drain attempt,
     * which covers the queue a previous launch left behind.
     */
    fun start(context: android.content.Context) {
        OfflinePlayQueue.init(context)
        // StateFlow already de-dupes consecutive equal values, so Wi-Fi to
        // cellular — same `true`, same `false` — won't restart a drain.
        scope.launch {
            AppSettings.meteredConnection.collect { metered ->
                if (metered != null) drainOfflineQueue()
            }
        }
    }

    /**
     * Replay every queued play against YouTube's history endpoints.
     *
     * One entry at a time, by design: the three-ping sequence (playback → atr
     * → watchtime(final=1)) is cheap individually but hitting the servers in
     * parallel reads as noise, and this is a background catch-up — the user
     * is not waiting on it. Entries that succeed are removed; entries that
     * fail stay in the queue for the next drain. The `atr` ping is dropped on
     * entries that never crossed the ATR threshold, which already fell out of
     * [persistOffline], so by the time we are draining every entry is one
     * whose play legitimately happened.
     */
    private suspend fun drainOfflineQueue() {
        if (Innertube.cookie == null) return
        drainLock.withLock {
            val entries = OfflinePlayQueue.snapshot()
            if (entries.isEmpty()) return@withLock
            TrackLog.d(TAG, "draining ${entries.size} offline play(s)")
            for (entry in entries) {
                val ok = runCatching { replayOne(entry) }
                    .onFailure {
                        TrackLog.w(TAG, "offline replay failed for ${entry.videoId}: ${it.message}")
                    }
                    .getOrDefault(false)
                if (ok) OfflinePlayQueue.remove(entry.cpn)
            }
        }
    }

    /**
     * One queued play, played back against the stats endpoints.
     *
     * The tracking URLs are fetched fresh — they rotate, and the signature
     * timestamp a draft was written with is already out of date by the time
     * the drain gets here. The CPN stored with the entry is what gets reused,
     * so playback / atr / watchtime are a single play as far as YouTube is
     * concerned.
     *
     * @return true when the entry has been sent and can be dropped from the
     *   queue. False keeps it around for the next drain.
     */
    private suspend fun replayOne(entry: OfflinePlayQueue.Entry): Boolean {
        val signatureTimestamp = StreamResolver.signatureTimestamp(entry.videoId) ?: return false
        val tracking = Innertube.playbackTracking(entry.videoId, signatureTimestamp)
        if (tracking == null) {
            // The player declined this video on this account; asking later
            // with the same inputs gets the same answer, so drop it.
            TrackLog.d(TAG, "no playback tracking for queued ${entry.videoId}; dropping")
            return true
        }
        Innertube.pingPlayback(tracking.playbackUrl, entry.cpn)
        tracking.atrUrl?.let { runCatching { Innertube.pingAtr(it, entry.cpn) } }
        val watchtimeUrl = tracking.watchtimeUrl
        if (watchtimeUrl != null) {
            Innertube.pingWatchtime(watchtimeUrl, entry.cpn, entry.watchtimeSeconds, final = true)
        }
        _registeredPlays.value++
        TrackLog.d(
            TAG,
            "offline play replayed for ${entry.videoId} " +
                "(${entry.watchtimeSeconds}s heard, cpn ${entry.cpn})",
        )
        return true
    }
}
