package com.comp90018.flashcards.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.auth.AuthState
import com.comp90018.flashcards.data.local.entity.DeckSyncOutboxEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records local deck edits and pushes them when the device is online.
 *
 * Callers return as soon as the outbox row is stored. Network work runs on a background
 * coroutine, and [DeckSyncWorker] retries it after the process dies or connectivity returns.
 */
@Singleton
class DeckSyncCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: DeckSyncStore,
        private val deckCloudSync: DeckCloudSync,
        private val authRepository: AuthRepository,
        private val connectivity: ConnectivityMonitor,
        private val clock: Clock,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private val started = AtomicBoolean(false)
        private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun start() {
            if (!started.compareAndSet(false, true)) {
                return
            }
            scope.launch {
                authRepository.authState.collect { state ->
                    val uid = (state as? AuthState.SignedIn)?.user?.uid
                    if (uid != null) {
                        requestSync(uid)
                    }
                }
            }
        }

        suspend fun deckChanged(
            deckId: String,
            ownerId: String,
        ) {
            enqueue(deckId, ownerId, DeckSyncOperation.UPSERT)
        }

        suspend fun deckDeleted(
            deckId: String,
            ownerId: String,
        ) {
            enqueue(deckId, ownerId, DeckSyncOperation.DELETE)
        }

        fun requestSync(ownerId: String? = authRepository.currentUid) {
            val uid = ownerId ?: return
            scheduleWorker()
            scope.launch { syncNow(uid) }
        }

        @Suppress("TooGenericExceptionCaught")
        suspend fun syncNow(ownerId: String): DeckSyncOutcome =
            try {
                mutex.withLock {
                    if (authRepository.currentUid != ownerId) {
                        DeckSyncOutcome.RETRY
                    } else {
                        backfill(ownerId)
                        if (connectivity.isOnline()) drain(ownerId) else DeckSyncOutcome.RETRY
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Deck sync failed", error)
                DeckSyncOutcome.RETRY
            }

        private suspend fun enqueue(
            deckId: String,
            ownerId: String,
            operation: DeckSyncOperation,
        ) {
            if (deckId.isBlank() || ownerId.isBlank()) {
                return
            }
            store.outboxDao.enqueue(
                deckId = deckId,
                ownerId = ownerId,
                operation = operation.name,
                enqueuedAtEpochMillis = clock.millis(),
            )
            requestSync(ownerId)
        }

        private suspend fun backfill(ownerId: String) {
            if (preferences.getBoolean(backfillKey(ownerId), false)) {
                return
            }
            store.deckDao.listDecksForOwner(ownerId).forEach { deck ->
                val pending = store.outboxDao.find(deck.deckId)
                if (pending?.operation != DeckSyncOperation.DELETE.name) {
                    store.outboxDao.enqueue(
                        deckId = deck.deckId,
                        ownerId = deck.ownerId,
                        operation = DeckSyncOperation.UPSERT.name,
                        enqueuedAtEpochMillis = clock.millis(),
                    )
                }
            }
            preferences.edit().putBoolean(backfillKey(ownerId), true).apply()
        }

        private suspend fun drain(ownerId: String): DeckSyncOutcome {
            var outcome = DeckSyncOutcome.UP_TO_DATE
            while (outcome == DeckSyncOutcome.UP_TO_DATE) {
                val pending = store.outboxDao.pendingForOwner(ownerId)
                if (pending.isEmpty()) {
                    break
                }
                outcome = applyAll(pending)
            }
            return outcome
        }

        private suspend fun applyAll(items: List<DeckSyncOutboxEntity>): DeckSyncOutcome {
            var outcome = DeckSyncOutcome.UP_TO_DATE
            for (item in items) {
                val step = apply(item)
                if (step == DeckSyncStep.Retry || step == DeckSyncStep.Deferred) {
                    outcome = if (step == DeckSyncStep.Retry) DeckSyncOutcome.RETRY else DeckSyncOutcome.DEFERRED
                    break
                }
                if (step == DeckSyncStep.Skipped) {
                    Log.i(TAG, "Dropped cloud sync for deck ${item.deckId}")
                }
                store.outboxDao.deleteIfRevision(item.deckId, item.revision)
            }
            return outcome
        }

        private suspend fun apply(item: DeckSyncOutboxEntity): DeckSyncStep {
            val operation = DeckSyncOperation.entries.find { it.name == item.operation } ?: return DeckSyncStep.Skipped
            val localDeck = store.deckDao.getDeckById(item.deckId)
            val localCards = localDeck?.let { store.cardDao.listCardsByDeckId(it.deckId) }.orEmpty()
            return deckCloudSync.execute(operation, item.deckId, localDeck, localCards)
        }

        private fun scheduleWorker() {
            val request =
                OneTimeWorkRequestBuilder<DeckSyncWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }

        private fun backfillKey(ownerId: String): String = "backfill_v2_$ownerId"

        private companion object {
            const val TAG = "DeckSync"
            const val PREFS = "deck_cloud_sync"
            const val UNIQUE_WORK = "deck-cloud-sync"
            const val BACKOFF_SECONDS = 30L
        }
    }
