package com.comp90018.flashcards.data.sync

enum class DeckSyncStep {
    Synced,
    Skipped,
    Retry,
    Deferred,
}

enum class DeckSyncOutcome {
    UP_TO_DATE,
    RETRY,
    DEFERRED,
}
