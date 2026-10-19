package com.music.bitchord.playback

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The playhead, deliberately kept out of [PlayerState].
 *
 * It moves twice a second; everything else on [PlayerState] moves on a track
 * change. Carried in the same object, the two are one snapshot read — and
 * [rememberPlayerState] returns a value, which makes it non-restartable, which
 * pushes that read up into its *caller's* scope. In this app the caller is the
 * root of the whole UI, so a ticking playhead invalidated the entire tree twice
 * a second: every tab, both floating bars, and the three real-time blurs
 * underneath them, whether or not anything on screen showed a position.
 *
 * Split out and held behind a stable object, the tick is a read of this alone.
 * Whoever draws a scrubber reads it and recomposes; nobody else hears about it.
 * Take care to keep it that way — reading [positionMs] high in the tree and
 * passing the `Long` down puts the invalidation straight back where it was.
 */
@Stable
class PlaybackPosition {
    /** The latest reading of the playhead. Written through [report], with the time it was taken. */
    var positionMs by mutableLongStateOf(0L)
        private set

    /**
     * When [positionMs] was read off the player, on the [System.nanoTime] clock.
     *
     * The lyrics run a clock of their own between readings, and the only thing
     * that lets them use a reading is knowing *when* it was true.
     */
    var sampledAtNanos by mutableLongStateOf(0L)
        private set

    /**
     * Bumped by the owner every time the playhead jumps on purpose — a seek, a
     * skip, a repeat starting over — so the lyrics can tell a real jump from a
     * reading that is merely late or early.
     */
    var seeks by mutableIntStateOf(0)

    /**
     * False while playback is meant to be running but the playhead is not moving yet.
     * The phone never sets it — Media3 already reports a buffering player as not playing.
     */
    var advancing by mutableStateOf(true)

    /**
     * Records a reading of the playhead and when it was taken.
     */
    fun report(positionMs: Long, sampledAtNanos: Long = System.nanoTime()) {
        this.positionMs = positionMs
        this.sampledAtNanos = sampledAtNanos
    }
}
