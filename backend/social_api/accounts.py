import time
from datetime import UTC, datetime

from google.cloud.firestore_v1.base_query import FieldFilter

from social_api.auth import Identity
from social_api.database import Database
from social_api.errors import ApiError


class AccountService:
    """Resumable data deletion, followed by Firebase login deletion in the Android client."""

    def __init__(self, database: Database):
        self.database = database

    def delete_data(self, identity: Identity):
        if (
            not isinstance(identity.auth_time, int)
            or not -60 <= time.time() - identity.auth_time <= 300
        ):
            raise ApiError(403, "recent_login_required", "Sign in again to delete your account.")
        uid = identity.uid
        subject = self.database.subject_hash(uid)
        marker = self.database.deletions.document(uid)

        def begin(transaction):
            saved = marker.get(transaction=transaction).to_dict()
            if saved is not None:
                if saved["namespace"] != self.database.root.id:
                    raise ApiError(
                        409, "deletion_namespace_conflict", "Contact the project administrator."
                    )
                return saved
            profile = self.database.users.document(subject).get(transaction=transaction).to_dict()
            row = {
                "namespace": self.database.root.id,
                "profile_id": profile["id"] if profile else None,
                "complete": False,
            }
            transaction.create(marker, row)
            return row

        saved = self.database.run(begin)
        # The marker blocks API transactions and client writes throughout partial cleanup.
        if not saved["complete"]:
            self._delete_decks(uid)
            self._delete_shares(saved["profile_id"])
            self._delete_relationships(subject)
            self._delete_copy_receipts(subject)
            self._delete_notification_devices(subject)
            self._finish_profile(subject, marker)

    def _delete_decks(self, uid):
        decks = self.database.client.collection("decks").where(
            filter=FieldFilter("ownerId", "==", uid)
        )
        for deck in decks.stream():
            self.database.client.recursive_delete(deck.reference)

    def _delete_shares(self, profile_id):
        if profile_id is None:
            return
        query = self.database.client.collection_group("shares").where(
            filter=FieldFilter("recipient_id", "==", profile_id)
        )
        for share in query.stream():
            if share.to_dict().get("namespace") == self.database.root.id:
                share.reference.delete()

    def _delete_relationships(self, subject):
        query = self.database.relationships.where(
            filter=FieldFilter("members", "array_contains", subject)
        )
        for document in query.stream():
            self.database.request_ids.document(document.to_dict()["id"]).delete()
            document.reference.delete()

    def _delete_copy_receipts(self, subject):
        prefix = subject + "_"
        query = (
            self.database.copy_requests.order_by("__name__")
            .start_at({"__name__": self.database.copy_requests.document(prefix)})
            .end_before({"__name__": self.database.copy_requests.document(prefix + "\uf8ff")})
        )
        for document in query.stream():
            document.reference.delete()

    def _delete_notification_devices(self, subject):
        query = self.database.notification_devices.where(
            filter=FieldFilter("subject_hash", "==", subject)
        )
        for document in query.stream():
            document.reference.delete()

    def _finish_profile(self, subject, marker):
        def finish(transaction):
            profile_ref = self.database.users.document(subject)
            profile = profile_ref.get(transaction=transaction).to_dict()
            if profile is not None:
                username = self.database.username_ref(profile["username"])
                reservation = username.get(transaction=transaction).to_dict()
                if reservation and reservation.get("subject_hash") == subject:
                    transaction.delete(username)
                transaction.delete(self.database.user_ids.document(profile["id"]))
                transaction.delete(profile_ref)
            # Retain only a minimal barrier against stale Firebase tokens and sync workers.
            transaction.set(
                marker,
                {
                    "namespace": self.database.root.id,
                    "complete": True,
                    "completed_at": datetime.now(UTC),
                },
            )

        self.database.run(finish)
