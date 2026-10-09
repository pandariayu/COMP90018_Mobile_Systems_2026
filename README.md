# COMP90018_Mobile_Systems_2026
COMP90018 Mobile Computing Systems Programming Sem 2 2026 Group Project

A spaced-repetition flashcard app built with Jetpack Compose, Room, Hilt, and Navigation Compose.

## Requirements

- JDK 17
- Android Studio (latest stable) or the Gradle wrapper on the command line

## Google sign-in

Authentication is Firebase Auth. Users can continue with Google, or register and sign in with email and password. Decks stay in the local Room database and are filtered by the Firebase uid. The app never stores the Google password or a plaintext password.

`app/google-services.json` is gitignored. Without that file the project still compiles, and the login button explains that sign-in is not configured.

1. Create a Firebase project and add an Android app with package name `com.comp90018.flashcards`.
2. From the repo root, run `./gradlew :app:signingReport` and add the **debug** SHA-1 to that Android app. Google sign-in fails with `DEVELOPER_ERROR` (code 10) when the SHA-1 is missing.
3. In Firebase Authentication, enable the Google provider.
4. Download `google-services.json` into `app/google-services.json` and rebuild. The web client id (`client_type` 3) is read into the `web_client_id` string resource.
5. Demo on a device with Google Play, using two Google accounts. A plain emulator often cannot complete Google sign-in.

## Cloud decks (Firestore)

Shared deck storage uses **Cloud Firestore** in the same Firebase project as Auth (`com-comp90018-flashcards`). Local Room remains the source of truth on device. Deck and card edits are pushed in the background when the device is online. Upload / download UI (publish and save-a-copy) is still a separate Stage 2 issue.

Schema, security rules, and provisioning steps: [docs/cloud-deck-schema.md](docs/cloud-deck-schema.md).

1. In the Firebase Console, create a Firestore database for the project (if it does not exist yet).
2. Install the [Firebase CLI](https://firebase.google.com/docs/cli), then from the repo root:

```bash
firebase use com-comp90018-flashcards
firebase deploy --only firestore:rules,firestore:indexes
```

Rules and indexes live in `firestore.rules` and `firestore.indexes.json`. The Android client depends on `firebase-firestore`. `DeckSyncCoordinator` pushes local deck changes when the network is available. Study progress stays in Room.

## Social API and friend sharing

The [Python social API](backend/README.md) uses the same Firebase Auth project and Firestore database for profiles, friend requests, and explicit read-only deck sharing. Deck `ownerId` remains the Firebase UID, and deck/card fields are unchanged. Removing a friendship revokes private-deck access in both the API and the Firestore rules.

The root Firebase configuration includes all five indexes (two deck, three social) and the combined rules. Run `npm run test:emulator` from `backend` after following its setup instructions to test both the API and direct-client access rules. Android Friends/sharing screens and API deployment are still pending.

## Building & running

```bash
./gradlew assembleDebug   # build a debug APK
./gradlew test            # run unit tests
./gradlew check           # run tests, lint, ktlint, and detekt
```

## Code style & static analysis

The project uses [ktlint](https://github.com/pinterest/ktlint) (via the [JLLeitschuh Gradle plugin](https://github.com/JLLeitschuh/ktlint-gradle)) for formatting and [detekt](https://detekt.dev/) for static analysis. Both run automatically as part of `./gradlew check`.

| Task                  | What it does                                                  |
|------------------------|----------------------------------------------------------------|
| `./gradlew ktlintCheck` | Verifies Kotlin code follows the official style, no changes made |
| `./gradlew ktlintFormat` | Auto-formats Kotlin code to fix what it can                   |
| `./gradlew detekt`      | Runs static analysis (complexity, unused code, Compose best practices, etc.) |

Detekt's findings are written to an HTML report at `app/build/reports/detekt/detekt.html` (open it in a browser) — this is a build artifact, not committed to git, and gets regenerated on every run.

Before pushing, run:

```bash
./gradlew ktlintFormat detekt
```

### Configuration

- `.editorconfig` — formatting rules ktlint reads (indent size, max line length, etc.). Also configures Android Studio's own formatter to match. Wildcard imports are disallowed everywhere **except** `androidx.compose.foundation.layout.*`, `androidx.compose.material3.*`, and `androidx.compose.runtime.*`, matching [Compose's own API guidelines](https://github.com/androidx/androidx/blob/androidx-main/compose/docs/compose-api-guidelines.md).
- `config/detekt/detekt.yml` — detekt rule overrides. Builds on detekt's default ruleset (`buildUponDefaultConfig`), so only deviations from the defaults are listed here.
- `config/detekt/baseline.xml` — pre-existing findings from before detekt was introduced, so `check` doesn't fail on legacy code. **New code must not add entries here.** If you need to suppress a specific new finding, prefer an inline `@Suppress("RuleId")` (with a comment explaining why) over regenerating the baseline. To regenerate the baseline after intentionally accepting a batch of findings, run `./gradlew detektBaseline`.

Detekt also pulls in the [Compose rules](https://mrmans0n.github.io/compose-rules/) ruleset (`io.nlopez.compose.rules:detekt`), which checks Compose-specific conventions such as exposing a `modifier: Modifier` parameter on stateless composables.

## Continuous integration

`.github/workflows/ci.yml` runs two parallel jobs on every pull request and on every push to `main`: Android checks (`./gradlew check`: tests, Android Lint, ktlint, detekt) and Social API checks (Ruff lint/format checks and the API/Firestore rules tests in the emulator). Each job has its own environment: JDK 17 for Android; Python 3.12, Node 20, and Java 21 for the social API.

A red check on a PR means one of those failed — click into the job's logs to see which task and what it reported. There's no auto-fix step in CI. For Android formatting issues, run `./gradlew ktlintFormat detekt` locally; for social API checks, follow [backend/README.md](backend/README.md), then push the fix.

### Troubleshooting: `./gradlew` fails with `What went wrong: 26` (or similar bare number)

This project's Gradle wrapper (8.7) doesn't support very new JDKs — if your machine's default `java` is something like JDK 24+ (check with `java -version`), Gradle fails to even start, with an unhelpful error that's just the major version number.

Fix: install JDK 17 (e.g. via [Adoptium](https://adoptium.net/) or `sdk install java 17.0.x-tem` with [SDKMAN](https://sdkman.io/)), then point Gradle at it **globally on your machine** — not in this repo, since the path is machine-specific:

```properties
# ~/.gradle/gradle.properties (create if it doesn't exist)
org.gradle.java.home=/path/to/your/jdk-17
```

Find your installed JDKs' paths with `/usr/libexec/java_home -V` (macOS) or `update-alternatives --list java` (Linux). Don't add `org.gradle.java.home` to the project's own `gradle.properties` — it would hardcode your personal file path for every other contributor.
