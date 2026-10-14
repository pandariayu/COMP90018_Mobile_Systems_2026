# Deploy the social API to Cloud Run

Run these commands in PowerShell from this repository's `backend` directory. This guide deploys a live service and changes project permissions; the checked-in files do not deploy anything automatically. The existing GitHub CI workflow only runs checks.

Cloud Run builds `Dockerfile` remotely, starts the API, and provides an HTTPS URL. A local Docker installation is not required. `.gcloudignore` limits uploaded files to the runtime source and build configuration; `.dockerignore` also excludes local credentials and data from the image.

## 1. Project and tools

- Enable billing for Firebase project `com-comp90018-flashcards` (the Firebase project becomes Blaze/pay-as-you-go).
- Confirm Firebase Authentication and the `(default)` Firestore database are configured.
- Install the [Google Cloud CLI](https://cloud.google.com/sdk/docs/install) and restart PowerShell.
- Use a project administrator account for the initial API, service-account, IAM, and secret setup below. A teammate with deployment access alone may need the project owner to do those steps.
- Choose a Cloud Run region near the existing Firestore database. The example uses Sydney; check the database's location before using it.

```powershell
gcloud auth login
$projectId = 'com-comp90018-flashcards'
$region = 'australia-southeast1'
$runtimeAccount = "social-api@$projectId.iam.gserviceaccount.com"
$buildAccount = "social-api-build@$projectId.iam.gserviceaccount.com"
gcloud config set project $projectId

gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com iam.googleapis.com firestore.googleapis.com identitytoolkit.googleapis.com --project $projectId
```

Stop and resolve any failed command before continuing. New IAM permissions can take a few minutes to propagate.

## 2. Create build and runtime identities once

The running API needs database access and Firebase user lookups for token revocation checks. Its identity is separate from the build identity; neither needs a downloaded service-account key.

```powershell
gcloud iam service-accounts create social-api --display-name 'Social API runtime' --project $projectId
gcloud iam service-accounts create social-api-build --display-name 'Social API build' --project $projectId

gcloud projects add-iam-policy-binding $projectId --member "serviceAccount:$runtimeAccount" --role roles/datastore.user
gcloud projects add-iam-policy-binding $projectId --member "serviceAccount:$runtimeAccount" --role roles/firebaseauth.viewer
gcloud projects add-iam-policy-binding $projectId --member "serviceAccount:$buildAccount" --role roles/run.builder
```

If these accounts already exist, skip their creation and check their permissions. Subsequent deployers need Cloud Run deployment permissions and permission to act as both service accounts; making the service public also requires permission to change its IAM policy. See Google's [source deployment permissions](https://cloud.google.com/run/docs/deploying-source-code) and [build service account guide](https://cloud.google.com/run/docs/configuring/services/build-service-account).

## 3. Store the existing identity secret

`SOCIAL_IDENTITY_HMAC_KEY` must stay the same across deployments. If social profiles already exist in the target database, use the original key. Do not generate a replacement: startup deliberately rejects a different key for an existing social namespace.

For a new, empty social database only, generate a key if you have not already created one. The Python virtual environment comes from the [local setup](README.md#run-locally).

```powershell
New-Item -ItemType Directory -Force secrets | Out-Null
if (-not (Test-Path secrets/identity-hmac-key.txt)) {
    .venv/Scripts/python.exe -c "import pathlib,secrets; pathlib.Path('secrets/identity-hmac-key.txt').write_text(secrets.token_hex(32))"
}
```

Upload that key to Secret Manager once and grant the runtime account access to this secret:

```powershell
gcloud secrets create social-identity-hmac-key --replication-policy automatic --data-file secrets/identity-hmac-key.txt --project $projectId
gcloud secrets add-iam-policy-binding social-identity-hmac-key --member "serviceAccount:$runtimeAccount" --role roles/secretmanager.secretAccessor --project $projectId
```

If the secret already exists, reuse its existing version. The deployment below pins version `1`; use the appropriate existing version if different. Never commit or paste the key into the Dockerfile or deployment command. Keep a secure backup. See [Cloud Run secrets](https://cloud.google.com/run/docs/configuring/services/secrets).

## 4. Deploy the combined Firestore configuration

From `backend`, install the checked-in Firebase tooling, sign in with project access, and deploy the root rules, all five composite indexes, and the `shares.recipient_id` collection-group field index:

```powershell
npm ci --no-audit --no-fund
npx firebase login
npx firebase deploy --config ../firebase.json --only 'firestore:rules,firestore:indexes' --project $projectId
```

Wait for the indexes to finish building in the Firebase Console. Review any CLI prompt to remove indexes before accepting it, particularly if teammates have added live indexes outside this repository.

## 5. Deploy the API

From `backend`, run:

```powershell
gcloud run deploy social-api `
    --source . `
    --project $projectId `
    --region $region `
    --service-account $runtimeAccount `
    --build-service-account "projects/$projectId/serviceAccounts/$buildAccount" `
    --allow-unauthenticated `
    --set-env-vars "FIREBASE_PROJECT_ID=$projectId,FIRESTORE_DATABASE_ID=(default),SOCIAL_FIRESTORE_NAMESPACE=default" `
    --set-secrets 'SOCIAL_IDENTITY_HMAC_KEY=social-identity-hmac-key:1' `
    --cpu 1 `
    --memory 512Mi `
    --cpu-throttling `
    --min-instances 0 `
    --max-instances 2 `
    --concurrency 20 `
    --timeout 60
```

`--allow-unauthenticated` lets the Android app reach the HTTP service. The Python API still requires and verifies Firebase ID tokens on every `/v1/*` endpoint; `/health` and API documentation are public. Cloud Run IAM tokens and Firebase ID tokens are different, so the application handles user authentication. If your organization blocks public services, its administrator must resolve that policy or arrange an alternative ingress design.

The runtime automatically uses its attached service account. Do not set `GOOGLE_APPLICATION_CREDENTIALS`, `FIRESTORE_EMULATOR_HOST`, or `FIREBASE_AUTH_EMULATOR_HOST` on Cloud Run. The Dockerfile listens on the `PORT` supplied by Cloud Run.

This configuration uses request-based billing and scales to zero. The first request after inactivity can be slower. Maximum instances reduces scaling but is not a hard spending cap; builds, image storage, secrets, Firestore, and network traffic have separate costs. Configure billing alerts; alerts do not stop charges.

## 6. Check the deployed service

```powershell
$apiUrl = (gcloud run services describe social-api --project $projectId --region $region --format 'value(status.url)').Trim()
Invoke-RestMethod "$apiUrl/health"
Start-Process "$apiUrl/docs"
```

Health should return `{"status":"ok"}`. A request to `/v1/me` without a Firebase ID token should return `401`. In `/docs`, use **Authorize** with a real Firebase ID token from the Android app, then test profile setup and the two-user flow in [README.md](README.md#two-user-walkthrough). Health alone does not verify real Firebase token validation, index readiness, or the entire sharing flow.

The Android social HTTP client defaults to the existing Melbourne service URL. For another deployment, override the `SOCIAL_API_URL` Gradle property and rebuild the app. See [Android community setup](../docs/android-community.md).

If startup fails, inspect **Cloud Run → social-api → Logs**. Common causes are a missing secret version, missing Firestore/Auth permissions, the wrong database/project, or a changed identity key.

For future code updates, rerun step 5 with the same project, region, namespace, service accounts, and secret version. Rebuild/redeploy periodically to incorporate base-image security updates. Firestore rules/index changes are deployed separately with step 4.

## Updating an existing service for Publish and Save-a-copy

Deploy the updated root Firestore rules (step 4) before deploying API version `0.2.0`. These rules protect the new `copiedFrom` metadata from client creation or edits and remain compatible with existing decks and merge writes. The five indexes are unchanged, and no data migration is required.

Update the existing Cloud Run service rather than creating another service. For the current Melbourne deployment, the service name is `comp90018-mobile-systems-2026` and the region is `australia-southeast2`; substitute those for the example service name and region above. Keep the existing runtime/build identities, database, namespace, and `SOCIAL_IDENTITY_HMAC_KEY`. If using a repository-connected Cloud Build trigger, build the commit containing this change with its existing backend Dockerfile configuration.

After deployment, `/openapi.json` should report version `0.2.0` and include `/v1/decks/{deck_id}/publish` and `/v1/decks/{deck_id}/copies`. Use the authenticated [two-user walkthrough](README.md#two-user-walkthrough) to verify sharing, copying, publishing, and revocation on disposable test decks. Emulator tests do not verify deployed credentials or IAM permissions.

## Updating an existing service for onboarding and account deletion

This Android update adds mandatory profile onboarding and the dashboard Account page. Onboarding and display-name editing use the existing profile endpoints. Deletion requires the new `DELETE /v1/me` endpoint in API `0.3.0`.

1. Deploy the root rules and indexes with step 4. The five composite indexes remain unchanged; wait for the new collection-group field index on `shares.recipient_id` to become ready. Updated rules block client reads/writes for accounts with a server deletion marker, preventing uploads from recreating deleted data.
2. Redeploy the existing `comp90018-mobile-systems-2026` Cloud Run service in `australia-southeast2`, using step 5 or the existing repository-connected Cloud Build trigger. Preserve its database, namespace, service accounts and identity secret. No additional Firebase Auth IAM role is required.
3. Confirm `/openapi.json` reports `0.3.0` and includes `DELETE /v1/me`. Rebuild/install the Android app. Check onboarding with a new account and Account display-name editing with an existing account.
4. To verify deletion end to end, use a disposable test account and confirm fresh password/Google sign-in in Account. The API cleans application data; the Android Firebase client deletes the login afterward. Verify that its owned decks/profile/relationships are removed and another user's previously saved copy remains. This is a destructive live check; automated tests only use the emulator and local test databases.

Interrupted cleanup keeps a deletion marker and the Android recovery screen offers a safe retry. Keep completed markers: removing them could let stale clients recreate data. Deploying only the Android app against the old API disables successful deletion with an update-required error; it does not remove the Firebase login prematurely. No live deployment or real account deletion is performed by the checks in this repository.

## Updating an existing service for friend request notifications

API `0.4.0` registers Android installations and sends FCM data messages when new friend requests commit. Keep the existing Melbourne service, database, namespace and identity secret.

1. Identify the current runtime service account and grant messaging access. Run with project administrator access:

```powershell
$projectId = 'com-comp90018-flashcards'
$runtimeAccount = (gcloud run services describe comp90018-mobile-systems-2026 --project $projectId --region australia-southeast2 --format 'value(spec.template.spec.serviceAccountName)').Trim()
gcloud services enable fcm.googleapis.com --project $projectId
gcloud projects add-iam-policy-binding $projectId --member "serviceAccount:$runtimeAccount" --role roles/firebasecloudmessaging.admin
```

2. Redeploy the backend with its existing source deployment configuration or repository-connected build trigger. There are no new secrets, Firestore indexes or rules for this feature; existing rules already protect the device registry.
3. Verify `/openapi.json` reports `0.4.0` and includes `PUT`/`DELETE /v1/notification-devices/{device_id}`. Install the updated Android APK, sign in and allow notifications. Background delivery needs successful registration against this backend.
4. Follow the [two-device notification demo](../docs/friend-request-notifications.md#two-device-demonstration), checking both buttons with the receiving app in the background and confirming the resulting friendship/request state.

The Cloud Run runtime identity supplies Admin SDK credentials; no service-account key belongs in the APK. [Firebase documents the FCM API Admin role](https://firebase.google.com/docs/cloud-messaging/send/admin-sdk) for sending messages. Data-only payloads allow Android to add both actions. Push failures are logged without tokens/private payloads and preserve the pending request for foreground recovery. The local checks do not enable APIs, change IAM or deploy the live service.
