# Friend request notifications

New incoming friend requests appear in Android's notification centre with **Accept** and **Decline** actions. Either action updates the existing authenticated social API without opening the app. Tapping the notification body opens **Friends** for the matching signed-in account.

On Android 13+, choose **Allow** when the app requests notification permission after profile setup. If denied, enable it in **Settings → Apps → Clashcards → Notifications** and reopen the app. Android 8–12 use the Friend requests channel without a runtime permission prompt.

## Delivery and responses

- FCM sends a high-priority **data-only** message to each registered recipient installation. Android builds the same action buttons in foreground and background. Firebase Console notification messages do not exercise this feature; send a real friend request through the app/API.
- Existing foreground refresh also posts notifications for pending requests, recovering missed pushes and supporting the previous backend while the push update is being deployed.
- Notifications and actions are identified by request ID and originating Firebase UID. Immutable PendingIntents distinguish both actions and different requests. Duplicate delivery does not repost a dismissed request.
- An action queues one unique WorkManager response per account/request. Offline responses show a waiting notification until connectivity returns. Transient failures retry; after retry exhaustion the buttons return with an error. Success or an already-resolved/deleted request dismisses the notification.
- Account checks run in the receiver and authenticated API. Logout clears visible notifications and attempts device unregistration without indefinitely delaying offline logout. Old-account pushes are ignored locally; login reassigns the installation and refreshed FCM token. Backend account deletion removes registered devices.
- Foreground snapshots clear resolved notifications. A snapshot fetched before a new push cannot cancel its new notification.

Background delivery requires Google Play services, enabled notifications, network access, and the backend rollout below. Android can delay delivery; reopen a force-stopped app. Push is best effort: it runs after the request transaction commits, inside the HTTP request to retain Cloud Run request-based CPU. Failure leaves the request pending for foreground recovery. There is no durable server push outbox.

## Backend rollout

Install the updated Android APK and deploy social API **0.4.0**, retaining the existing service, database, namespace and identity key. Enable the FCM HTTP v1 API and grant the actual runtime service account messaging permission. See [deployment steps](../backend/DEPLOYMENT.md#updating-an-existing-service-for-friend-request-notifications).

| Authenticated endpoint | Purpose |
| --- | --- |
| `PUT /v1/notification-devices/{installation_uuid}`, body `{"token":"FCM registration token"}` | Register or refresh this installation for the authenticated user |
| `DELETE /v1/notification-devices/{installation_uuid}` | Remove this user's registration; an old account cannot remove a reassigned registration |

Registration requires a social profile. Android retries with connectivity, after sign-in/permission approval, and on FCM token rotation. Installation identity and notification history are excluded from cloud backup/device transfer. Device records live under the existing server-only `social_backends` namespace. No new Firestore rule, index or schema migration is needed.

## Two-device demonstration

1. Install the updated APK on two devices with Google Play services. Sign in as A and B, finish profile setup, and allow notifications on B.
2. Open B once with internet access so its token registers, then press Home to background it.
3. On A, send a request to B's username. Pull down B's notification centre and expand **New friend request** if necessary. Show A's name and the **Accept / Decline** buttons.
4. Tap **Accept** without opening Clashcards. The notification disappears when the response succeeds. Open Friends on both accounts and verify the friendship.
5. Remove the test friendship and send another request. Tap **Decline** on B. Verify no friendship is created and the pending request disappears. A resend creates a new request ID and notification.
6. Optional: receive another request, disconnect B, tap an action, then reconnect. The selected response is applied once after connectivity returns.

## Automated verification

Backend emulator tests cover recipient-only delivery, multiple devices, duplicates, token replacement, registration ownership, invalid-token cleanup, FCM failure, stale request IDs, input validation, private device documents and account deletion. No real FCM calls/accounts are used.

Android unit tests cover authenticated response payloads, account changes, stale requests and retries. Android notification tests cover action labels, immutable/distinct PendingIntents, duplicate suppression, refresh/push timing and account cleanup:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.comp90018.flashcards.data.notifications.FriendRequestNotificationsTest
```

References: [FCM message handling](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [Android notification actions](https://developer.android.com/develop/ui/compose/notifications/create-notification), [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
