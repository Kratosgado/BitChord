package com.music.bitchord.data.db

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.LikeStatus
import java.util.concurrent.TimeUnit

/**
 * Drains the [PendingActionEntity] table in [queuedAt] order whenever the
 * device has a validated network connection.
 *
 * Each action is marked IN_FLIGHT before the API call and either deleted on
 * success or retried (up to [MAX_RETRIES]) on failure. Actions that exceed
 * the retry limit are marked FAILED and left for the user to inspect.
 */
class QueueDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val dao = BitChordDatabase.get(applicationContext).pendingActionDao()

    override suspend fun doWork(): Result {
        val actions = dao.pendingAndInFlight()
        if (actions.isEmpty()) return Result.success()

        var anyFailed = false

        for (action in actions) {
            if (action.retryCount >= MAX_RETRIES) {
                dao.setStatus(action.id, PendingActionStatus.FAILED)
                anyFailed = true
                continue
            }

            dao.setStatus(action.id, PendingActionStatus.IN_FLIGHT)

            val ok = when (action.actionType) {
                PendingActionType.ADD_TO_PLAYLIST -> {
                    val playlistId = action.playlistId ?: run {
                        dao.delete(action.id)
                        return@when true
                    }
                    YtMusicRepository.addToPlaylist(playlistId, listOf(action.videoId))
                        .onSuccess { dao.delete(action.id) }
                        .isSuccess
                }
                PendingActionType.LIKE -> {
                    YtMusicRepository.rate(action.videoId, LikeStatus.LIKE)
                        .onSuccess { dao.delete(action.id) }
                        .isSuccess
                }
                PendingActionType.UNLIKE -> {
                    YtMusicRepository.rate(action.videoId, LikeStatus.INDIFFERENT)
                        .onSuccess { dao.delete(action.id) }
                        .isSuccess
                }
                PendingActionType.DISLIKE -> {
                    YtMusicRepository.rate(action.videoId, LikeStatus.DISLIKE)
                        .onSuccess { dao.delete(action.id) }
                        .isSuccess
                }
                else -> {
                    // Unknown action type from a future version — skip it.
                    dao.delete(action.id)
                    true
                }
            }

            if (!ok) {
                dao.incrementRetry(action.id)
                anyFailed = true
            }
        }

        // If anything failed, signal WorkManager to retry the whole worker with
        // backoff so transient failures don't permanently strand the queue.
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val WORK_NAME = "queue_drain"

        /** Enqueues a drain run if one isn't already pending or running. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueDrainWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
