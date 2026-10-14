import logging
from datetime import timedelta
from typing import Protocol

from firebase_admin import exceptions, messaging
from google.api_core.exceptions import GoogleAPICallError
from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from social_api.database import Database
from social_api.errors import ApiError
from social_api.service import now

logger = logging.getLogger(__name__)


class InvalidPushToken(Exception):
    pass


class PushSender(Protocol):
    def send(self, token: str, data: dict[str, str]) -> None: ...


class FirebasePushSender:
    def __init__(self, firebase_app):
        self.app = firebase_app

    def send(self, token: str, data: dict[str, str]) -> None:
        # Data-only messages let Android build the same action buttons in both
        # foreground and background. A notification payload bypasses our service.
        message = messaging.Message(
            token=token,
            data=data,
            android=messaging.AndroidConfig(priority="high", ttl=timedelta(days=1)),
        )
        try:
            messaging.send(message, app=self.app)
        except (messaging.UnregisteredError, messaging.SenderIdMismatchError) as error:
            raise InvalidPushToken from error


class DisabledPushSender:
    """No external messaging in tests that supply an alternate token verifier."""

    def send(self, token: str, data: dict[str, str]) -> None:
        pass


class NotificationService:
    def __init__(self, database: Database):
        self.database = database

    def register(self, uid: str, device_id: str, token: str):
        subject = self.database.subject_hash(uid)

        def register_device(transaction):
            profile = self.database.users.document(subject).get(transaction=transaction).to_dict()
            if profile is None:
                raise ApiError(404, "profile_required", "Create your social profile first.")
            transaction.set(
                self.database.notification_devices.document(device_id),
                {
                    "subject_hash": subject,
                    "firebase_uid": uid,
                    "token": token,
                    "updated_at": now(),
                },
            )

        self.database.run(register_device, uid=uid)

    def unregister(self, uid: str, device_id: str):
        subject = self.database.subject_hash(uid)
        ref = self.database.notification_devices.document(device_id)

        def unregister_device(transaction):
            row = ref.get(transaction=transaction).to_dict()
            # An old account's logout cannot remove a registration reassigned
            # to the new account on the same installation.
            if row and row["subject_hash"] == subject:
                transaction.delete(ref)

        self.database.run(unregister_device, uid=uid)

    def friend_request(self, request: dict, sender: PushSender):
        # Push failures must never turn a committed friendship action into an
        # HTTP failure. Pending requests remain available through the normal API.
        try:
            self._deliver(request, sender)
        except (GoogleAPICallError, exceptions.FirebaseError):
            logger.warning("Friend request push unavailable for request %s", request["id"])

    def _deliver(self, request: dict, sender: PushSender):
        pointer = self.database.request_ids.document(request["id"]).get().to_dict()
        if not pointer:
            return
        current = self.database.relationships.document(pointer["pair_id"]).get().to_dict()
        if not current or current["id"] != request["id"] or current["status"] != "pending":
            return
        devices = self.database.notification_devices.where(
            filter=FieldFilter("subject_hash", "==", current["recipient_hash"])
        )
        delivered_tokens = set()
        for document in devices.stream():
            device = document.to_dict()
            if self.database.deletions.document(device["firebase_uid"]).get().exists:
                continue
            if device["token"] in delivered_tokens:
                continue
            delivered_tokens.add(device["token"])
            data = {
                "type": "friend_request",
                "request_id": request["id"],
                "recipient_uid": device["firebase_uid"],
                "sender_name": request["sender"]["display_name"],
            }
            try:
                sender.send(device["token"], data)
            except InvalidPushToken:
                # Do not delete a newer token installed while delivery was in flight.
                document.reference.delete(option=firestore.LastUpdateOption(document.update_time))
            except exceptions.FirebaseError:
                logger.warning("FCM delivery failed for request %s", request["id"])
