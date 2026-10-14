import time
from uuid import uuid4

import pytest
from conftest import headers, respond, rules_headers, send
from firebase_admin import exceptions, messaging

from social_api.auth import Identity
from social_api.notifications import FirebasePushSender, InvalidPushToken, NotificationService


class RecordingPushSender:
    def __init__(self, error=None):
        self.calls = []
        self.error = error

    def send(self, token, data):
        self.calls.append((token, data))
        if self.error:
            raise self.error


def register(client, user, token="test-token", device_id=None):
    device_id = device_id or str(uuid4())
    response = client.put(
        f"/v1/notification-devices/{device_id}", headers=headers(user), json={"token": token}
    )
    assert response.status_code == 204, response.text
    return device_id


def test_new_request_pushes_only_to_recipient_devices_once(client, profiles):
    sender = RecordingPushSender()
    client.app.state.push_sender = sender
    register(client, "bob", "bob-phone")
    register(client, "bob", "bob-tablet")
    register(client, "alice", "alice-phone")
    register(client, "charlie", "charlie-phone")
    request = send(client, profiles).json()
    assert {token for token, _ in sender.calls} == {"bob-phone", "bob-tablet"}
    assert all(
        data
        == {
            "type": "friend_request",
            "request_id": request["id"],
            "recipient_uid": "firebase-uid-bob",
            "sender_name": "Alice",
        }
        for _, data in sender.calls
    )
    assert send(client, profiles).json() == request
    assert len(sender.calls) == 2


def test_registration_reassigns_installation_and_old_logout_cannot_remove_it(
    client, profiles, database
):
    device_id = register(client, "alice", "shared-device")
    register(client, "bob", "shared-device", device_id)
    path = f"/v1/notification-devices/{device_id}"
    assert client.delete(path, headers=headers("alice")).status_code == 204
    assert database.notification_devices.document(device_id).get().to_dict()["firebase_uid"] == (
        "firebase-uid-bob"
    )
    sender = RecordingPushSender()
    client.app.state.push_sender = sender
    send(client, profiles)
    assert len(sender.calls) == 1
    assert sender.calls[0][1]["recipient_uid"] == "firebase-uid-bob"
    assert client.delete(path, headers=headers("bob")).status_code == 204
    assert not database.notification_devices.document(device_id).get().exists


def test_rotated_token_replaces_old_token(client, profiles):
    device_id = register(client, "bob", "old-token")
    register(client, "bob", "new-token", device_id)
    sender = RecordingPushSender()
    client.app.state.push_sender = sender
    send(client, profiles)
    assert [token for token, _ in sender.calls] == ["new-token"]


def test_invalid_token_is_removed_without_failing_friend_request(client, profiles, database):
    device_id = register(client, "bob")
    client.app.state.push_sender = RecordingPushSender(InvalidPushToken())
    assert send(client, profiles).status_code == 200
    assert not database.notification_devices.document(device_id).get().exists


def test_fcm_outage_preserves_pending_request_and_registration(client, profiles, database):
    device_id = register(client, "bob")
    client.app.state.push_sender = RecordingPushSender(exceptions.UnavailableError("unavailable"))
    response = send(client, profiles)
    assert response.status_code == 200
    assert client.get("/v1/friend-requests", headers=headers("bob")).json() == [response.json()]
    assert database.notification_devices.document(device_id).get().exists


def test_delivery_does_not_push_resolved_or_rotated_requests(client, profiles, database):
    register(client, "bob")
    request = send(client, profiles).json()
    respond(client, request["id"], action="decline")
    sender = RecordingPushSender()
    notifications = NotificationService(database)
    notifications.friend_request(request, sender)
    assert sender.calls == []
    send(client, profiles)
    notifications.friend_request(request, sender)
    assert sender.calls == []


def test_account_deletion_removes_only_its_devices(client, profiles, database, monkeypatch):
    alice_device = register(client, "alice")
    bob_device = register(client, "bob")
    monkeypatch.setattr(
        client.app.state.verifier,
        "verify",
        lambda _: Identity("firebase-uid-alice", int(time.time())),
    )
    try:
        assert client.delete("/v1/me", headers=headers("alice")).status_code == 204
        assert not database.notification_devices.document(alice_device).get().exists
        assert database.notification_devices.document(bob_device).get().exists
    finally:
        database.deletions.document("firebase-uid-alice").delete()


@pytest.mark.parametrize(
    "body", [{"token": ""}, {"token": "x" * 4097}, {"token": "x", "uid": "bob"}]
)
def test_device_registration_rejects_invalid_or_forged_input(client, profiles, body):
    response = client.put(
        f"/v1/notification-devices/{uuid4()}", headers=headers("alice"), json=body
    )
    assert response.status_code == 422


def test_device_registration_requires_authentication_and_profile(client):
    path = f"/v1/notification-devices/{uuid4()}"
    assert client.put(path, json={"token": "x"}).status_code == 401
    assert client.put(path, headers=headers("alice"), json={"token": "x"}).status_code == 404


def test_device_documents_are_private_to_backend(client, profiles, raw_client, settings):
    device_id = register(client, "bob")
    path = f"social_backends/{settings.firestore_namespace}/notification_devices/{device_id}"
    for user in (None, "alice", "bob"):
        assert raw_client.get(path, headers=rules_headers(user)).status_code == 403
        assert (
            raw_client.patch(path, headers=rules_headers(user), json={"fields": {}}).status_code
            == 403
        )


def test_sender_uses_high_priority_data_only_message(monkeypatch):
    captured = []
    monkeypatch.setattr(messaging, "send", lambda message, app: captured.append((message, app)))
    FirebasePushSender("firebase-app").send("recipient-token", {"type": "friend_request"})
    message, app = captured[0]
    assert message.token == "recipient-token"
    assert message.notification is None
    assert message.data == {"type": "friend_request"}
    assert message.android.priority == "high"
    assert app == "firebase-app"
