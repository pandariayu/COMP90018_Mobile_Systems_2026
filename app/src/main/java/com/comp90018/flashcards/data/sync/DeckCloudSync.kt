package com.comp90018.flashcards.data.sync

import com.comp90018.flashcards.data.local.entity.CardEntity
import com.comp90018.flashcards.data.local.entity.DeckEntity
import com.comp90018.flashcards.data.remote.DeckRemoteDataSource
import com.comp90018.flashcards.data.remote.RemoteCard
import com.comp90018.flashcards.data.remote.RemoteDeck
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes one local deck to Firestore. Reads come from the server so a cached copy is not treated
 * as already synced. FSRS state is never sent.
 */
@Singleton
class DeckCloudSync
    @Inject
    constructor(
        private val remote: DeckRemoteDataSource,
        private val clock: Clock,
    ) {
        suspend fun execute(
            operation: DeckSyncOperation,
            deckId: String,
            localDeck: DeckEntity?,
            localCards: List<CardEntity>,
        ): DeckSyncStep =
            if (operation == DeckSyncOperation.DELETE || localDeck == null) {
                remote.deleteDeck(deckId).toStep()
            } else {
                pushSnapshot(localDeck, localCards)
            }

        private suspend fun pushSnapshot(
            localDeck: DeckEntity,
            localCards: List<CardEntity>,
        ): DeckSyncStep =
            loadSnapshot(localDeck.deckId).fold(
                onSuccess = { (remoteDeck, remoteCards) ->
                    when (
                        val plan =
                            DeckSyncPlanner.plan(
                                localDeck,
                                localCards,
                                remoteDeck,
                                remoteCards,
                                clock.instant(),
                            )
                    ) {
                        DeckSyncPlan.Unchanged -> DeckSyncStep.Synced
                        DeckSyncPlan.Foreign -> DeckSyncStep.Skipped
                        is DeckSyncPlan.Push ->
                            remote.pushDeck(plan.deck, plan.cards, plan.deleteCardIds, plan.create).toStep()
                    }
                },
                onFailure = ::syncFailureStep,
            )

        private suspend fun loadSnapshot(deckId: String): Result<Pair<RemoteDeck?, List<RemoteCard>>> =
            remote.getDeck(deckId, fromServer = true).fold(
                onSuccess = { deck -> loadRemoteCards(deckId, deck != null).map { cards -> deck to cards } },
                onFailure = { error ->
                    // Read rules deny a missing deck, because they require the document to exist.
                    // That is a new deck, not a reason to drop the upload.
                    if (error.isUnreadableCloudDeck()) {
                        Result.success(null to emptyList())
                    } else {
                        Result.failure(error)
                    }
                },
            )

        private suspend fun loadRemoteCards(
            deckId: String,
            remoteDeckExists: Boolean,
        ): Result<List<RemoteCard>> =
            if (remoteDeckExists) {
                remote.listCards(deckId, fromServer = true)
            } else {
                Result.success(emptyList())
            }

        private fun Result<Unit>.toStep(): DeckSyncStep =
            fold(onSuccess = { DeckSyncStep.Synced }, onFailure = ::syncFailureStep)
    }

internal fun syncFailureStep(error: Throwable): DeckSyncStep =
    when {
        error is IllegalStateException && error.message.orEmpty().contains("not configured") ->
            DeckSyncStep.Deferred
        error is FirebaseFirestoreException && isPermanentFirestoreCode(error.code.name) ->
            DeckSyncStep.Skipped
        else -> DeckSyncStep.Retry
    }

internal class CloudDeckHiddenException : Exception("Cloud deck is missing or not readable.")

internal fun Throwable.isUnreadableCloudDeck(): Boolean {
    val code = (this as? FirebaseFirestoreException)?.code?.name
    return this is CloudDeckHiddenException || code == "PERMISSION_DENIED" || code == "NOT_FOUND"
}

internal fun isPermanentFirestoreCode(code: String): Boolean =
    code == FirebaseFirestoreException.Code.PERMISSION_DENIED.name ||
        code == FirebaseFirestoreException.Code.INVALID_ARGUMENT.name
