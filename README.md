# COMP90018_Mobile_Systems_2026
COMP90018 Mobile Computing Systems Programming Sem 2 2026 Group Project

Clashcards is a spaced-repetition flashcard app built with Jetpack Compose, Room, Hilt, and Navigation Compose.

## Requirements

- JDK 25 for the Gradle daemon; JDK 21 for compilation (provisioned automatically if missing). App bytecode targets Java 17.
- Android Studio (latest stable) or the Gradle wrapper on the command line

The build uses Gradle 9.1.0, Android Gradle Plugin 8.13.2, Kotlin/Compose compiler 2.3.21, Compose BOM 2025.10.01, Hilt 2.57.2, and Room 2.8.3. Install Android SDK Platform 36 for compilation; `minSdk` remains 26 and `targetSdk` remains 34. Detekt is pinned to 2.0.0-alpha.3 with Compose rules 0.5.8 because the previous Detekt release cannot run on the Java 25 build daemon. Detekt 2.0 is a prerelease and future upgrades may require configuration changes.

## Google sign-in

Authentication is Firebase Auth. Users can continue with Google, or register and sign in with email and password. Decks stay in the local Room database and are filtered by the Firebase uid. The app never stores the Google password or a plaintext password.

`app/google-services.json` is gitignored. Without that file the project still compiles, and the login button explains that sign-in is not configured.

After sign-in, new users choose a community username and display name before entering My Decks. The dashboard's **Account** button opens display-name editing, logout and account deletion with fresh sign-in confirmation. Returning users with a cached profile can study local decks offline. Account deletion requires the updated backend and Firestore configuration; follow [the deployment update guide](backend/DEPLOYMENT.md#updating-an-existing-service-for-onboarding-and-account-deletion).

1. Create a Firebase project and add an Android app with package name `com.comp90018.flashcards`.
2. From the repo root, run `./gradlew :app:signingReport` and add the **debug** SHA-1 to that Android app. Google sign-in fails with `DEVELOPER_ERROR` (code 10) when the SHA-1 is missing.
3. In Firebase Authentication, enable the Google provider.
4. Download `google-services.json` into `app/google-services.json` and rebuild. The web client id (`client_type` 3) is read into the `web_client_id` string resource.
5. Demo on a device with Google Play, using two Google accounts. A plain emulator often cannot complete Google sign-in.

## Cloud decks (Firestore)

**Cloud Firestore is the single source of truth** for each account's decks and friends (same Firebase project as Auth: `com-comp90018-flashcards`). Room only caches deck/card content for offline My Decks and study; friends and profiles are loaded from the social API. Sync pushes local deck edits, then pulls owned cloud decks so phone and emulator stay aligned. Study progress stays on-device.

Schema, security rules, and provisioning steps: [docs/cloud-deck-schema.md](docs/cloud-deck-schema.md).

1. In the Firebase Console, create a Firestore database for the project (if it does not exist yet).
2. Install the [Firebase CLI](https://firebase.google.com/docs/cli), then from the repo root:

```bash
firebase use com-comp90018-flashcards
firebase deploy --only firestore:rules,firestore:indexes
```

Rules and indexes live in `firestore.rules` and `firestore.indexes.json`. The Android client depends on `firebase-firestore`. `DeckSyncCoordinator` uploads the outbox, then refreshes Room from Firestore when online. Study progress stays in Room only.

## Social API and deck actions

The [Python social API](backend/README.md) uses the same Firebase Auth project and Firestore database for profiles, friend requests, explicit read-only deck sharing, Publish/unpublish, and Save-a-copy. Deck `ownerId` remains the Firebase UID; copies receive new IDs, private visibility, and protected source attribution. Removing a friendship revokes private source access in both the API and the Firestore rules; previously saved copies remain independent.

The root Firebase configuration includes five composite indexes (two deck, three social), a share-recipient collection-group field index, and the combined rules. Run `npm run test:emulator` from `backend` after following its setup instructions to test both the API and direct-client access rules. Open **Community** from My Decks for public decks and friends, or **Account** to manage your profile; your deck's **⋮** menu offers **Publish / Unpublish** and **Share with friends**. Public catalogue rows and previews save copies through the same API workflow. See [Android community setup and usage](docs/android-community.md). API and rules updates need separate deployment; see the [Cloud Run guide](backend/DEPLOYMENT.md).

## Friend request notifications

New friend requests support **Accept** and **Decline** directly in Android notifications. Foreground refresh recovers pending requests; background delivery uses FCM and requires social API 0.4.0 plus messaging permissions for the runtime account. See [setup and the two-device demo](docs/friend-request-notifications.md).

## Building & running

Duo and clash rounds support automatic start/pause from the forehead pose, with
manual Start available. See [pose behavior and device checks](docs/heads-up-pose.md).

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

`.github/workflows/ci.yml` runs two parallel jobs on every pull request and on every push to `main`: Android checks (`./gradlew check`: tests, Android Lint, ktlint, detekt) and Social API checks (Ruff lint/format checks and the API/Firestore rules tests in the emulator). Each job has its own environment: JDK 25 for the Android build daemon with a JDK 21 compilation toolchain; Python 3.12, Node 20, and Java 21 for the social API.

A red check on a PR means one of those failed — click into the job's logs to see which task and what it reported. There's no auto-fix step in CI. For Android formatting issues, run `./gradlew ktlintFormat detekt` locally; for social API checks, follow [backend/README.md](backend/README.md), then push the fix.

### Troubleshooting: Java runtime and Gradle sync errors

The project uses Gradle 9.1.0 and Java 25 daemon criteria to support Android Studio's Java 25 sync tooling. Compilation uses a separate Java 21 toolchain, and app bytecode still targets Java 17.

Keep Android Studio on its bundled runtime. In Settings > Build, Execution, Deployment > Build Tools > Gradle, use the project's Java 25 Daemon JVM criteria. Switching a newer Android Studio's boot runtime to Java 21 can prevent the IDE from starting.

For command-line builds, set `JAVA_HOME` to your JDK 25 installation. The daemon criteria in `gradle/gradle-daemon-jvm.properties` select Java 25. The Foojay resolver provisions missing toolchains. Do not put a personal JDK path in the project's `gradle.properties`; if you need a machine-specific override for other Gradle projects, use your user-level properties:

```properties
# ~/.gradle/gradle.properties (create if it doesn't exist)
org.gradle.java.home=/path/to/your/jdk-25
```

Find your installed JDKs' paths with `/usr/libexec/java_home -V` (macOS) or `update-alternatives --list java` (Linux). Don't add `org.gradle.java.home` to the project's own `gradle.properties` — it would hardcode your personal file path for every other contributor.
