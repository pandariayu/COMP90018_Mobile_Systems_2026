package com.comp90018.flashcards.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.comp90018.flashcards.data.auth.AuthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Retries the deck outbox when the device has a network connection, including after process death.
 */
@HiltWorker
class DeckSyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val authRepository: AuthRepository,
        private val coordinator: DeckSyncCoordinator,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            val uid = authRepository.currentUid ?: return Result.success()
            return when (coordinator.syncNow(uid)) {
                DeckSyncOutcome.RETRY -> Result.retry()
                DeckSyncOutcome.DEFERRED, DeckSyncOutcome.UP_TO_DATE -> Result.success()
            }
        }
    }
