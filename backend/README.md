# Social API

Start with account profiles and the friend graph: Share needs to check both ownership and an accepted friendship, and Save-a-copy needs to check whether the source is accessible. The Android app already provides email/password and Google sign-in through Firebase Auth. This API reuses that identity instead of maintaining another password database.

This API implements profiles, username lookup, friend requests, persistent bidirectional friendships, explicit friend sharing, Publish/unpublish, and Save-a-copy in Cloud Firestore. It uses Jason-Ren's existing root `decks/{deckId}` and `cards` schema, with optional source attribution on copied decks. Android syncs deck data directly through Firestore and calls this API from the Community and Share & publish screens. See [Android community usage](../docs/android-community.md).

## Run locally

For online hosting, follow the [Cloud Run deployment guide](DEPLOYMENT.md). It includes the project permissions, Secret Manager setup, and PowerShell deployment commands.

Requires Python 3.12. Run these commands from `backend` in PowerShell:

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
```

Use the **same Firebase project and `(default)` Firestore database as the Android app**. The checked-in `.firebaserc` selects `com-comp90018-flashcards`; the Python API requires that project ID explicitly in its environment. Configure Application Default Credentials for the Firebase Admin SDK and Firestore server client. For local development, a service-account JSON can be stored outside the repository or in the ignored `backend/secrets` directory. Do not use the Android `google-services.json` as an Admin credential.

```powershell
$env:FIREBASE_PROJECT_ID = 'com-comp90018-flashcards'
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:/path/to/service-account.json'
$env:FIRESTORE_DATABASE_ID = '(default)'
$env:SOCIAL_FIRESTORE_NAMESPACE = 'default'
```

Generate a random identity key once, retain it securely, and reuse it whenever starting the API against this database. The following local setup keeps it in an ignored file without printing it:

```powershell
New-Item -ItemType Directory -Force secrets | Out-Null
if (-not (Test-Path secrets/identity-hmac-key.txt)) {
    .venv/Scripts/python.exe -c "import pathlib,secrets; pathlib.Path('secrets/identity-hmac-key.txt').write_text(secrets.token_hex(32))"
}
$env:SOCIAL_IDENTITY_HMAC_KEY = (Get-Content secrets/identity-hmac-key.txt -Raw).Trim()
.venv/Scripts/python.exe -m uvicorn social_api.main:app --host 127.0.0.1 --port 8000
```

Open [interactive API documentation](http://127.0.0.1:8000/docs). `/health` is unauthenticated; `/v1/*` requires a Firebase ID token. Missing identity-key/project configuration stops startup. A schema-version and key-fingerprint document is checked at startup; changing the key for existing social data stops startup to avoid orphaning profiles. The project ID, database ID, and namespace select the data location, so keep all three stable between deployments. Selecting another location selects a separate dataset. Environment variables must be set in the shell; no `.env` file is loaded automatically.

The repository-root [`firebase.json`](../firebase.json), [`firestore.rules`](../firestore.rules), and [`firestore.indexes.json`](../firestore.indexes.json) are the single deployment configuration. They contain the two deck and three relationship composite indexes, plus a collection-group field index on `shares.recipient_id` for account deletion. There is no separate backend rules/index configuration. Before testing against a live database, deploy the root configuration and wait until all indexes are ready. After installing the local CLI with `npm ci`, run from `backend`:

```powershell
npx firebase deploy --config ../firebase.json --only firestore:rules,firestore:indexes --project com-comp90018-flashcards
```

This command changes the live project's configuration; it has not been run as part of this implementation. Rules deny direct mobile/web access to social data and share grants, while permitting authorized deck/card reads. The Admin SDK bypasses rules, so the API independently enforces the same deck access policy. If moving to a named database, update the root Firebase configuration, API environment, and Android Firestore initialization together. The API does not provision the database, deploy rules/indexes, or enable billing automatically.

Firebase Admin verifies tokens, including expiration and revocation, against the configured project. It needs working credentials, permission to read Firebase Auth users, and network access to Firebase. The server refuses `FIREBASE_AUTH_EMULATOR_HOST`; it has no runtime setting that bypasses authentication. Tests inject a fake verifier directly in the application factory.

## Android authentication contract

After the existing Google or email sign-in succeeds, obtain a **Firebase ID token**, not the Google provider token, from the signed-in Firebase user:

```kotlin
// Inside a coroutine, using the existing injected FirebaseAuthProvider.
val user = checkNotNull(firebaseAuthProvider.auth?.currentUser)
val token = checkNotNull(user.getIdToken(false).await().token)
// Set the HTTP header: Authorization: Bearer <token>
```

The app already depends on `kotlinx-coroutines-play-services` for `await()`. Use a fresh SDK token when making requests; do not persist or log it. On `401`, refresh once with `getIdToken(true)` and retry, then require sign-in if still rejected. A UID, email, or `sender_id` supplied by a client is never accepted as proof of identity.

Firebase handles account registration, passwords, password reset, and Google sign-in. `PUT /v1/me` creates/updates the separate social profile after sign-in. The social API's public `id` is a UUID; deck `ownerId` values remain Firebase UIDs. Deck ownership is checked against the verified token. A server-only `user_ids` mapping resolves a share recipient's public UUID to their Firebase UID. Existing profiles are upgraded on their next `GET /v1/me` or `PUT /v1/me` without changing public IDs or usernames. Sharing to an older profile before this refresh returns `409 recipient_profile_refresh_required`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| PUT | `/v1/me` | Create/update the current user's username and display name |
| GET | `/v1/me` | Read the current profile |
| DELETE | `/v1/me` | Delete this user's application data after recent reauthentication; client then deletes the Firebase login |
| GET | `/v1/users/lookup?username=bob` | Exact, case-insensitive username lookup |
| POST | `/v1/friend-requests` | Send a request with `{"recipient_id":"<public UUID>"}` |
| GET | `/v1/friend-requests` | List your incoming pending requests |
| POST | `/v1/friend-requests/{id}/respond` | `{"action":"accept"}`, `decline`, or `cancel` |
| GET | `/v1/friends` | List your accepted friends |
| DELETE | `/v1/friends/{public_user_id}` | Remove a friendship for both users |
| POST | `/v1/decks/{deck_id}/shares` | Owner shares with an accepted friend: `{"recipient_id":"<public UUID>"}` |
| DELETE | `/v1/decks/{deck_id}/shares/{recipient_id}` | Owner revokes an explicit share; safe to retry |
| POST | `/v1/decks/{deck_id}/publish` | Owner makes a deck public; returns the deck; safe to retry |
| DELETE | `/v1/decks/{deck_id}/publish` | Owner makes a deck private; keeps explicit shares; returns the deck |
| POST | `/v1/decks/{deck_id}/copies` | Save an accessible deck as a private copy: `{"request_id":"<new UUID>","name":"Optional name"}` |
| GET | `/v1/decks/{deck_id}` | Read a deck as its owner, an explicit shared friend, or a signed-in public reader |
| GET | `/v1/decks/public` | List public decks in newest-update order, with current owner profile display names |
| GET | `/v1/decks/{deck_id}/cards` | Read cards with the same access check; `limit` and `offset` supported |

Usernames are trimmed, lowercased, unique, and 3–24 ASCII letters, digits, or underscores. Display names are trimmed and 1–80 characters. A profile must exist before performing friend actions or looking up users. Profile responses contain only `id`, `username`, `display_name`, `created_at`, and `updated_at`; emails, tokens, and Firebase UIDs are not returned.

List endpoints support `limit` (1–100, default 50) and `offset` (0–10000, default 0). Requests also support `direction=incoming|outgoing` and `status=pending|accepted|declined|cancelled|removed`. Requests sort newest-created-first; friends sort newest-accepted-first, with request ID ascending as a stable tie-breaker. Cards sort by `position` then document ID. Friend ordering changed from the SQLite prototype's alphabetical order so Firestore can page without reading every friend's profile. Fetch another page by increasing the offset by the page size; an empty page ends the list. Concurrent changes can shift offset-based pages, so refresh from offset zero after a mutation. Firestore charges reads for skipped offsets; cursor pagination can replace this contract if lists grow large.

## Account onboarding and deletion

Android checks `GET /v1/me` immediately after sign-in. A `404 profile_required` opens mandatory username/display-name onboarding; an existing profile opens the dashboard. The dashboard's Account page updates only the display name through `PUT /v1/me`, preserving the username.

`DELETE /v1/me` requires a verified token with an `auth_time` within the last five minutes; missing/stale authentication returns `403 recent_login_required` before any data changes. Refreshing a token alone does not count as reauthentication. The endpoint returns `204` after application-data cleanup; it does **not** delete the Firebase Authentication user. The Android client reauthenticates first, pauses sync, calls this endpoint, clears that user's local data and upload queue, and finally calls `FirebaseUser.delete()`.

Cleanup removes owned decks and their subcollections, incoming share grants, relationships/request pointers, username reservation, public-ID mapping, social profile and that user's copy receipts. Other users' saved copies and copy receipts remain independent. A server-only `account_deletions/{Firebase UID}` marker is committed before cleanup; API transactions and Firestore rules read it to prevent stale clients from recreating data. A partial cleanup is resumable with the same identity, even after its social profile is removed. Repeated successful calls return `204`. Completed markers retain only namespace, completion status and timestamp; keeping the UID marker prevents stale-token resurrection. Do not reuse a deleted Firebase UID for a different account. This is a minimal deletion barrier rather than a complete erasure of every reference in other users' copied-deck attribution or backups.

The API returns `409 account_deleting` for other actions after deletion starts. Clients should show the recovery screen and retry deletion after reauthentication. Deploy the updated rules and the `shares.recipient_id` collection-group index before API `0.3.0`. No Firebase Auth administrator role is needed: the backend retains its existing read-only Auth permission, and the authenticated Firebase client deletes its own login.

## Deck sharing contract

Upload the deck and cards through the existing Android `DeckRemoteDataSource` first. The owner calls `POST /v1/decks/{deckId}/shares` with a friend's social profile UUID. The response includes `deck_id`, `recipient_id`, and `created_at`; repeating the same share returns the same grant. Only an accepted friend can receive a share, and only the deck owner can issue or revoke one. No client-supplied owner or Firebase UID is accepted in the request body.

Sharing leaves `visibility` as `private` or `public`, leaves the owner unchanged, and does not copy the deck. Recipients can read, but cannot edit, delete, or re-share the source. Both API reads and Android's existing `getDeck(deckId)` / `listCards(deckId)` check the current friendship and grant. Removing either side of the friendship immediately denies subsequent server reads of private decks. Becoming friends again requires a new explicit share. Public decks remain readable by all signed-in users regardless of friendship or share revocation. Previously downloaded data cannot be recalled by server permissions.

Grants live in `decks/{deckId}/shares/{recipientFirebaseUid}`. Each records the owner UID, recipient public UUID, social namespace, relationship pair ID, current friend-request ID, participant hashes, and creation timestamp. Rules look up the grant and the accepted relationship; clients cannot read or write grant documents directly. The API grants within its configured social namespace, and reads evaluate the namespace recorded in the grant, just as the rules do. Firestore does not automatically delete subcollections when a deck is deleted, so deck IDs must not be reused for unrelated decks. A grant is also bound to its owner UID, preventing it from authorizing a different owner who recreates that ID.

API deck/card responses preserve the existing camelCase fields and add `deckId` / `cardId` from the document IDs. Firestore `updatedAt` and optional `createdAt` are returned as ISO timestamps over HTTP. Deck detail and public catalogue reads add optional `ownerDisplayName`, resolved from the current social profile. The catalogue resolves each distinct owner once, omits legacy creator emails, and excludes decks owned by deleting accounts; it never includes private decks, including the caller's own or shared decks. Android opens shared private decks by a pasted deck code and loads Discover through `/v1/decks/public`. A shared-deck inbox and notifications are not included yet.

## Publish and Save-a-copy contract

Only the owner can publish or unpublish. Publishing sets `visibility` to `public`, making the deck and cards readable by every signed-in user through the API, the existing public catalogue, and direct Firestore reads. Unpublishing sets it to `private`; existing explicit friend shares continue to work. Neither operation changes the owner, cards, share grants, or source attribution. `updatedAt` changes only when visibility changes. Repeating the same action returns the current deck without another timestamp update. Publish and copy require Firebase sign-in but do not require a social profile.

Save-a-copy accepts a source owned by the caller, a public source, or a private source explicitly shared through a current friendship. Being friends alone does not grant access. The response is HTTP `200` with the new deck; fetch its cards through `GET /v1/decks/{newDeckId}/cards` or the Android Firestore client. The new deck:

- Belongs to the caller, starts private, and has new deck and card UUIDs.
- Uses the source name unless an optional trimmed name of 1–200 characters is supplied.
- Copies card fronts, backs, positions, and optional image URI strings. Image files are not duplicated; local device URIs are not made portable.
- Calculates `cardCount` from the actual cards and copies no share grants, SM-2/FSRS progress, or unknown fields. Android must initialize fresh study state when importing the new IDs into Room.
- Records `copiedFrom: {deckId, ownerId, copiedAt}` for the immediate source. `ownerId` is the source owner's Firebase UID; `copiedAt` is a Firestore timestamp, serialized as ISO text over HTTP. A copy of a copy points to that immediate copy. Direct clients cannot create, change, or remove this metadata, but the copy owner can edit the deck's content, share it, and publish it.

The copy is independent: subsequent source edits, deletion, unpublishing, revoked sharing, or unfriending do not change or remove it. Access checks, card reads, all destination writes, and the retry receipt happen in one Firestore transaction, so a failed action leaves no partial copy. This synchronous endpoint accepts up to **400 cards and 4 MiB of serialized copied content**; larger sources return `413 deck_too_large` and require splitting into smaller decks. Malformed source cards or a non-string source name return `409 invalid_source_deck`.

Generate `request_id` once per Save-a-copy action and retain it and the request body until the outcome is known. Retrying the same UUID with the same source and normalized name returns the same copy, including after an API restart or loss of access to the source. It returns the copy's current metadata if the owner has since edited it. A different source or name with that UUID returns `409 copy_request_conflict`. If the completed copy was deleted, the retry returns `410 copy_deleted`; it never silently recreates it. Another intentional copy requires a new UUID. Receipts are private, scoped to the signed-in user and social namespace, and retained without automatic expiration.

Deploy the updated root Firestore rules before enabling these endpoints in a live deployment, so source attribution is protected from client edits. No additional composite indexes are needed.

## Two-user walkthrough

Obtain Firebase ID tokens for two accounts using the existing Android sign-in or Firebase client SDK. Keep the tokens in your local terminal. Replace the placeholders below:

```powershell
$api = 'http://127.0.0.1:8000'
$aliceHeaders = @{ Authorization = 'Bearer <ALICE_FIREBASE_ID_TOKEN>' }
$bobHeaders = @{ Authorization = 'Bearer <BOB_FIREBASE_ID_TOKEN>' }

$alice = Invoke-RestMethod "$api/v1/me" -Method Put -Headers $aliceHeaders `
    -ContentType 'application/json' -Body '{"username":"alice","display_name":"Alice"}'
$bob = Invoke-RestMethod "$api/v1/me" -Method Put -Headers $bobHeaders `
    -ContentType 'application/json' -Body '{"username":"bob","display_name":"Bob"}'

$requestBody = @{ recipient_id = $bob.id } | ConvertTo-Json
$request = Invoke-RestMethod "$api/v1/friend-requests" -Method Post -Headers $aliceHeaders `
    -ContentType 'application/json' -Body $requestBody

Invoke-RestMethod "$api/v1/friend-requests" -Headers $bobHeaders
Invoke-RestMethod "$api/v1/friend-requests/$($request.id)/respond" -Method Post -Headers $bobHeaders `
    -ContentType 'application/json' -Body '{"action":"accept"}'
Invoke-RestMethod "$api/v1/friends" -Headers $aliceHeaders
Invoke-RestMethod "$api/v1/friends" -Headers $bobHeaders
```

After Alice uploads a deck and cards through the existing cloud deck client, continue with its UUID:

```powershell
$deckId = '<ALICE_UPLOADED_DECK_UUID>'
Invoke-RestMethod "$api/v1/decks/$deckId/shares" -Method Post -Headers $aliceHeaders `
    -ContentType 'application/json' -Body (@{ recipient_id = $bob.id } | ConvertTo-Json)

# Retain this body unchanged if the copy request needs retrying.
$copyBody = @{ request_id = [guid]::NewGuid().ToString(); name = 'My study copy' } | ConvertTo-Json
$copy = Invoke-RestMethod "$api/v1/decks/$deckId/copies" -Method Post -Headers $bobHeaders `
    -ContentType 'application/json' -Body $copyBody
Invoke-RestMethod "$api/v1/decks/$($copy.deckId)/cards" -Headers $bobHeaders

Invoke-RestMethod "$api/v1/decks/$deckId/publish" -Method Post -Headers $aliceHeaders
Invoke-RestMethod "$api/v1/decks/$deckId/publish" -Method Delete -Headers $aliceHeaders
Invoke-RestMethod "$api/v1/decks/$deckId/shares/$($bob.id)" -Method Delete -Headers $aliceHeaders
# Bob now loses source access but still owns the saved copy.
Invoke-RestMethod "$api/v1/decks/$($copy.deckId)" -Headers $bobHeaders
```

## State transitions and client errors

Only the recipient can accept/decline, and only the sender can cancel. Only acceptance creates a friendship. A single stored relationship represents both sides. Sending yourself a request is rejected. Crossed requests return `409 incoming_request_exists`; they do not silently accept each other.

Duplicate sends return the existing pending request. Retrying the same completed response is safe and returns the existing result; attempting a different action on a closed request returns `409`. After declining/cancelling/removing, a new request gets a new ID. Stale responses to the old ID therefore cannot change the new request. The latest relationship state is retained, not a complete request history. Removing an absent friendship is safe and does not cancel a pending request.

Known application/validation errors use this format:

```json
{"error":{"code":"incoming_request_exists","message":"Respond to the incoming request first."}}
```

| Status | Meaning | Client behavior |
|---|---|---|
| 401 | Missing, invalid, expired, revoked, or disabled-user token | Refresh token once; then sign in |
| 403 | This participant cannot perform that action | Roll back optimistic update |
| 404 | Profile/request/deck not found or inaccessible | Set up profile for `profile_required`; otherwise refresh |
| 409 | Username/relationship conflict, changed copy request, or malformed source | Reload state; use a new copy request UUID for changed options; fix malformed source data |
| 410 | The copy associated with this request UUID was deleted | Start a new copy action with a new UUID if wanted |
| 413 | Source exceeds the synchronous copy limits | Split the source into smaller decks |
| 422 | Invalid fields or self-request | Show validation feedback |
| 503 | Firebase verification/storage unavailable or transaction contention | Roll back, reload current state, offer retry |

After a timeout the outcome is unknown: reload before retrying; for Save-a-copy, retry the same stored request UUID and body to recover the completed result without duplicates. The Android UI disables actions while pending and waits for server confirmation. Its API client rejects responses after an account change, and navigation resets for the new user.

## Storage and privacy

Firestore is the only runtime storage backend. Data lives under `social_backends/{SOCIAL_FIRESTORE_NAMESPACE}` in the selected project's database:

| Subcollection | Document ID | Purpose |
|---|---|---|
| `users` | HMAC of Firebase identity | Public UUID, username, display name, timestamps |
| `user_ids` | Public UUID | Private mapping to identity hash and Firebase UID for deck sharing |
| `usernames` | `u_` + normalized username | Transactional username reservation |
| `relationships` | Sorted pair of identity hashes | One state for both friendship participants |
| `request_ids` | Current request UUID | Resolve a request to its canonical pair |
| `copy_requests` | Identity hash + `_` + client request UUID | Source/options, destination ID, and attribution receipt for retrying one copy action |
| `metadata` | `config` | Schema version and identity-key fingerprint |

Profiles retain their public IDs during edits. Username changes atomically reserve the new name and release the old one. Relationships use a deterministic pair document, so two API instances cannot create duplicate edges. Resends atomically delete the old request-ID mapping and create the new one. Every write operation uses a Firestore transaction with all reads before writes, with up to five attempts and randomized backoff for transaction conflicts. There are no process-local locks or files providing persistence. List queries apply their limit/offset in Firestore and batch-fetch the page's current profiles; profile renames therefore appear immediately without rewriting the graph.

Identity hashes use HMAC-SHA256 over the project ID + Firebase UID with a server-only random key, preserving the prototype's hash format. `SOCIAL_DATABASE_PATH` is no longer used. Existing SQLite files are left untouched and are not automatically uploaded or imported. If another developer has populated a SQLite database, preserve it and plan an explicit import before switching their deployment. The local database inspected during this change contained zero profiles and relationships.

This replaces the documents' vague “salted/hashed friend graph” idea with a defined keyed hash. **It is pseudonymization, not encryption or an anonymous graph.** Anyone who obtains the database can still see relationship topology and join it to public usernames. Passwords, emails, and tokens are not stored. Raw Firebase UIDs are now retained in the server-only `user_ids` mapping and as share recipient document IDs to support Android access rules; deck `ownerId` already uses a raw UID. Profile responses still exclude Firebase UIDs, and graph edges still use hashes. Do not claim a database leak would hide who is friends with whom. Full graph confidentiality requires a separate threat model and design.

Back up the identity key separately from Firestore. Key rotation requires a planned identity migration; changing the environment value is not a migration. Firestore schema version 1 is recorded in `metadata/config`, and unknown versions are rejected.

For Cloud Run, use its attached service account and Application Default Credentials instead of shipping a service-account JSON. That identity needs Firestore data access (for example, `roles/datastore.user`) and permission to read Firebase Auth users for revocation checks (for example, `roles/firebaseauth.viewer`). Supply the stable HMAC key through Secret Manager. Do not set `FIRESTORE_EMULATOR_HOST` in a deployed service; the API rejects it when running on Cloud Run. Deployment is described in [DEPLOYMENT.md](DEPLOYMENT.md). Rate limits/abuse controls, block lists, and a general schema/data migration process are not configured here.

## Tests

Integration tests use the actual Firestore emulator with a `demo-social-api` project and a separate namespace for every test. They never use the configured live project's data. Requires Node 20+ and Java 21+, in addition to Python. Install the Firebase CLI locally, activate the Python environment, and run:

```powershell
.venv/Scripts/Activate.ps1
npm ci --no-audit --no-fund
npm run test:emulator
.venv/Scripts/python.exe -m ruff check .
.venv/Scripts/python.exe -m ruff format --check .
```

The first run downloads the Firestore emulator. Subsequent tests need no live Firebase project or credentials. On Windows, point `JAVA_HOME` and `PATH` to a Java 21 installation if the default `java` is older. If PowerShell activation is disabled, prepend the virtual environment to the current shell with `$env:PATH = "$PWD/.venv/Scripts;$env:PATH"`, then run `npm run test:emulator`. Tests fail explicitly when the emulator environment is absent instead of falling back to a live database. Auth/configuration unit tests can run separately with `.venv/Scripts/python.exe -m pytest tests/test_auth.py -q`.

Tests exercise account ownership, validation, atomic username reservations, request permissions, symmetric friendship, duplicate retries, concurrent requests across separate app instances, pagination, persistence across app restarts, and rejection of changed identity configuration. Deck tests exercise both the API and authenticated client requests against the canonical root rules: owner/public/private reads, share/revoke/unfriend/refriend, publish/unpublish, copied content and attribution, concurrent copy retries, independent copies, and atomic failure with no partial writes. They also cover forbidden edits and forged grants/attribution, identity-mapping upgrades, and existing catalogue queries. Auth adapter tests mock the Firebase SDK boundary and check revocation/error handling; they do not replace a live Firebase smoke test. CI runs the emulator and these checks independently of the Android build.

Deletion tests cover recent authentication, scoped cleanup, retention of others' copies, username reuse, interruption/retry, and the Firestore deletion barrier. The emulator does not enforce production index requirements. Deploy the checked-in indexes before the live smoke test; emulator success alone does not verify production IAM, index readiness, or Firebase credentials.

## Further integration work

Android profile setup, friend requests, share/revoke, Publish/unpublish, and Save-a-copy are connected. Copies import into Room with new IDs and fresh study state; interrupted copies retain a request UUID for retry. Remaining work includes a shared-deck inbox, notifications, portable image uploads, and a live two-account acceptance test against deployed Firebase rules.

The older `discussion1509.md` removes friends; this implementation follows the current request to include them and the supplied Project Plan.

References: [Firebase token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens), [Firebase Admin setup](https://firebase.google.com/docs/admin/setup), [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions), [Firestore emulator](https://firebase.google.com/docs/emulator-suite/connect_firestore), [FastAPI testing](https://fastapi.tiangolo.com/tutorial/testing/).
