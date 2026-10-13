# Cloud deck database schema (Issue #18)

**Backend:** Cloud Firestore in Firebase project `com-comp90018-flashcards`
**Auth:** Firebase Auth (`uid` = deck `ownerId`)
**Single source of truth:** Firestore holds each account's decks, cards, visibility, shares, profiles, and friendships. Room on Android is only an offline cache of deck/card content for My Decks and study. Friends/social data are not cached in Room; the app reads them from the social API (which uses Firestore). Per-user study progress (FSRS) stays on-device only.
**Architecture:** Firebase Auth and Cloud Firestore, with direct Android deck access and a Python social API for profiles, friendships, sharing, publishing, and copying. See [backend/README.md](../backend/README.md).

This document is the missing write-up for [SPIKE #17](https://github.com/hboykett/COMP90018_Mobile_Systems_2026/issues/17) and the schema provisioned for [#18](https://github.com/hboykett/COMP90018_Mobile_Systems_2026/issues/18).

## Scope

| In scope (#18) | Out of scope (later issues) |
|---|---|
| Firestore project wiring, rules, indexes | Upload / download UI (#20) |
| Deck + card document shape | Friends / publish endpoints (#22–#23) |
| Thin Android remote data source | Favourites list |
| Visibility `private` / `public` | Per-user study progress on shared decks |

Per-user spaced-repetition progress stays in **local Room** until a later sync design. Do not store SM-2 / FSRS fields on shared deck documents.

The table above describes the original #18 scope. The social API now implements friend sharing, Publish/unpublish, and Save-a-copy. The original deck/card fields and `private` / `public` visibility values remain unchanged; copied decks add optional source attribution.

## Collections

```text
decks/{deckId}
  cards/{cardId}
  shares/{recipientFirebaseUid}  # server-managed permissions
social_backends/{namespace}/...  # profiles, friendships, private ID mappings, copy receipts
account_deletions/{FirebaseUid}  # server-only, durable deletion barrier
```

### `decks/{deckId}`

| Field | Type | Notes |
|---|---|---|
| `name` | string | Display name |
| `ownerId` | string | Firebase Auth uid |
| `visibility` | string | `"private"` or `"public"` (publish for search / Save-a-copy) |
| `updatedAt` | timestamp | UTC; used for catalogue ordering and conflict hints |
| `createdAt` | timestamp? | First-created time; My Decks sorts newest first |
| `cardCount` | number | Denormalised count for catalogue lists |
| `authorEmail` | string? | Legacy creator email metadata; no longer written by Publish or used by Discover |
| `copiedFrom` | map? | API-issued source attribution on saved copies; absent on original decks |

Document id = same UUID as local `DeckEntity.deckId` so sync can match rows later.

The social API's deck detail and public catalogue responses add optional `ownerDisplayName`, resolved from the owner's current social profile rather than persisted on the deck. Discover displays and searches this name. The catalogue endpoint `/v1/decks/public` preserves newest-update ordering and excludes decks belonging to accounts being deleted.

`copiedFrom` contains `deckId` (immediate source UUID), `ownerId` (source owner's Firebase UID), and `copiedAt` (UTC timestamp). The API creates a new private deck owned by the caller, with fresh deck/card UUIDs and an actual card count. It copies only card content, including image URI references; it does not copy image files, sharing permissions, or study progress. Copies remain independent of later changes to the source. See the [copy contract and limits](../backend/README.md#publish-and-save-a-copy-contract).

### `decks/{deckId}/cards/{cardId}`

| Field | Type | Notes |
|---|---|---|
| `front` | string | |
| `back` | string | |
| `position` | number | Order within the deck |
| `frontImageUri` | string? | Optional; may be local or remote URI later |
| `backImageUri` | string? | Optional |

Document id = same UUID as local `CardEntity.cardId`.

## Security rules (summary)

Committed in [`firestore.rules`](../firestore.rules):

- Signed-in users only (no anonymous access).
- Owner can create / update / delete their decks and cards.
- Any signed-in user can read decks (and their cards) when `visibility == "public"`.
- Private decks are readable by the owner or an explicitly shared recipient whose friendship is still accepted.
- Sharing grants read-only access to both the deck and its cards. Only the API may issue or revoke grants, after checking ownership and friendship.
- Removing a friendship revokes private access; a new friendship requires a new share. Public access remains available to all signed-in users.
- Social documents and share grants cannot be read or written directly by clients.
- An account with a deletion marker cannot read or write through the client rules. The API creates the marker before removing cloud data, so stale sync uploads cannot recreate deleted decks. Only the API can access markers; completed markers remain after the Firebase login is deleted.
- `ownerId` cannot be changed on update.
- Only the API can create `copiedFrom`; clients cannot add, alter, or remove attribution on updates. Owners can still edit copy contents. The existing Android data source uses merge writes, which preserve this metadata.
- Unpublishing preserves explicit friend shares. Revoking source access does not delete previously saved copies.

## Indexes

Committed in [`firestore.indexes.json`](../firestore.indexes.json):

- `visibility` + `updatedAt` — public catalogue
- `ownerId` + `updatedAt` — “my cloud decks”
- `sender_hash` + `status` + `created_at` + `id` — outgoing friend requests
- `recipient_hash` + `status` + `created_at` + `id` — incoming friend requests
- `members` (array contains) + `status` + `updated_at` + `id` — accepted friends
- `shares.recipient_id` (ascending, collection-group field index) — remove incoming shares during account deletion

All five composite indexes, the share-recipient field index and access policies are deployed through the root `firebase.json`. Backend emulator tests also use this configuration; no separate backend rules/index files should be deployed.

## Provisioning checklist

1. Firebase Console → project `com-comp90018-flashcards` → **Build → Firestore Database** → Create database (start in production mode if rules will be deployed immediately).
2. Install [Firebase CLI](https://firebase.google.com/docs/cli) and log in.
3. From the repo root:

```bash
firebase use com-comp90018-flashcards
firebase deploy --only firestore:rules,firestore:indexes
```

4. Ensure `app/google-services.json` is present (gitignored) so the Android app can reach the same project.

## Sync (#19) / account consistency (#70)

**Firestore is authoritative** for deck content and for friends/social data. Room mirrors decks for offline use; the social API mirrors friends in Firestore.

Online sync order:

1. Enqueue any local decks not yet marked for upload (one-time backfill per user).
2. **Push** the outbox to Firestore.
3. **Pull** owned decks from Firestore into Room (upsert cards; remove local owned decks that no longer exist in Firestore).

Saving a deck does not wait on the network; the outbox uploads in the background.

- Study progress (FSRS) is not uploaded or downloaded.
- Cloud document ids stay equal to the local UUIDs.
- The first push creates the deck as `private`. Later pushes update `name`, cards, `cardCount`, and `updatedAt` only, so a published deck stays public (#20).
- Deleting a local deck deletes that cloud deck; other devices drop it on the next pull.
- A deck with a pending outbox row is not overwritten or deleted by a pull until that upload finishes.
- Friends, profiles, shares, and publish state are always read/written through the social API → Firestore (no Room copy).

## Android client

- Dependency: `com.google.firebase:firebase-firestore` (Firebase BOM already in the app).
- Models: `com.comp90018.flashcards.data.remote`. Background push: `com.comp90018.flashcards.data.sync`.
- Existing `getDeck(deckId)` and `listCards(deckId)` can read explicitly shared private decks after a grant is issued through the API. The Android Community screens now connect profiles, friends, share/revoke, publishing, and Save-a-copy. Shared private decks open by code; copies import into Room with new IDs and fresh study state. See [Android community usage](android-community.md).
