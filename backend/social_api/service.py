import re
from datetime import UTC, datetime
from uuid import uuid4

from google.cloud import firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from social_api.database import Database
from social_api.errors import ApiError
from social_api.models import ProfileInput


def now() -> str:
    return datetime.now(UTC).isoformat()


def public_profile(row):
    return {key: row[key] for key in ("id", "username", "display_name", "created_at", "updated_at")}


class SocialService:
    def __init__(self, database: Database):
        self.database = database

    def put_profile(self, uid: str, profile: ProfileInput):
        subject = self.database.subject_hash(uid)
        user_ref = self.database.users.document(subject)
        username_ref = self.database.username_ref(profile.username)

        def update(transaction):
            existing = user_ref.get(transaction=transaction).to_dict()
            reserved = username_ref.get(transaction=transaction).to_dict()
            if reserved is not None and reserved["subject_hash"] != subject:
                raise ApiError(409, "username_taken", "Choose another username.")
            timestamp = now()
            row = {
                "subject_hash": subject,
                "id": existing["id"] if existing else str(uuid4()),
                "username": profile.username,
                "display_name": profile.display_name,
                "created_at": existing["created_at"] if existing else timestamp,
                "updated_at": timestamp,
            }
            # All reads precede writes, as required by Firestore transactions.
            if existing and existing["username"] != profile.username:
                transaction.delete(self.database.username_ref(existing["username"]))
            transaction.set(username_ref, {"subject_hash": subject})
            transaction.set(
                self.database.user_ids.document(row["id"]),
                {"subject_hash": subject, "firebase_uid": uid},
            )
            transaction.set(user_ref, row)
            return public_profile(row)

        return self.database.run(update, uid=uid)

    def get_profile(self, uid: str):
        subject = self.database.subject_hash(uid)

        def read(transaction):
            row = self._user(subject, transaction)
            ref = self.database.user_ids.document(row["id"])
            pointer = ref.get(transaction=transaction).to_dict()
            # Lazily upgrade profiles created before Firebase deck sharing. Only
            # this account's verified token can establish its UID mapping.
            if pointer is None or pointer.get("firebase_uid") != uid:
                transaction.set(ref, {"subject_hash": subject, "firebase_uid": uid})
            return public_profile(row)

        return self.database.run(read, uid=uid)

    def find_profile(self, uid: str, username: str):
        username = username.strip().lower()

        def lookup(transaction):
            self._user(self.database.subject_hash(uid), transaction)
            # A username is a document ID, never a path supplied by the caller.
            if not re.fullmatch(r"[a-z0-9_]{3,24}", username):
                raise ApiError(404, "user_not_found", "No profile has that username.")
            reserved = self.database.username_ref(username).get(transaction=transaction).to_dict()
            if reserved is None:
                raise ApiError(404, "user_not_found", "No profile has that username.")
            return public_profile(self._user(reserved["subject_hash"], transaction))

        return self.database.run(lookup, read_only=True, uid=uid)

    def send_request(self, uid: str, recipient_id: str, on_created=None):
        sender = self.database.subject_hash(uid)

        def send(transaction):
            sender_profile = self._user(sender, transaction)
            recipient_profile = self._user_by_id(recipient_id, transaction)
            recipient = recipient_profile["subject_hash"]
            if sender == recipient:
                raise ApiError(422, "self_request", "You cannot send yourself a friend request.")
            pair_id = self.database.pair_id(sender, recipient)
            ref = self.database.relationships.document(pair_id)
            existing = ref.get(transaction=transaction).to_dict()
            if existing is not None:
                if existing["status"] == "accepted":
                    raise ApiError(409, "already_friends", "You are already friends.")
                if existing["status"] == "pending":
                    if existing["sender_hash"] == sender:
                        return self._request_view(
                            existing, sender_profile, recipient_profile
                        ), False
                    raise ApiError(
                        409, "incoming_request_exists", "Respond to the incoming request first."
                    )
            timestamp = now()
            row = {
                "id": str(uuid4()),
                "sender_hash": sender,
                "recipient_hash": recipient,
                "members": sorted((sender, recipient)),
                "status": "pending",
                "created_at": timestamp,
                "updated_at": timestamp,
            }
            # Rotate the request ID atomically, so stale accept/cancel actions get 404.
            if existing is not None:
                transaction.delete(self.database.request_ids.document(existing["id"]))
            transaction.create(self.database.request_ids.document(row["id"]), {"pair_id": pair_id})
            transaction.set(ref, row)
            return self._request_view(row, sender_profile, recipient_profile), True

        result, created = self.database.run(send, uid=uid)
        if created and on_created is not None:
            on_created(result)
        return result

    def list_requests(self, uid: str, direction: str, status: str, limit: int, offset: int):
        subject = self.database.subject_hash(uid)
        column = {"incoming": "recipient_hash", "outgoing": "sender_hash"}[direction]
        query = (
            self.database.relationships.where(filter=FieldFilter(column, "==", subject))
            .where(filter=FieldFilter("status", "==", status))
            .order_by("created_at", direction=firestore.Query.DESCENDING)
            .order_by("id")
            .limit(limit)
            .offset(offset)
        )

        def read(transaction):
            self._user(subject, transaction)
            rows = [document.to_dict() for document in query.stream(transaction=transaction)]
            profiles = self._profiles_for(rows, transaction)
            return [
                self._request_view(
                    row, profiles[row["sender_hash"]], profiles[row["recipient_hash"]]
                )
                for row in rows
            ]

        return self.database.run(read, read_only=True, uid=uid)

    def respond(self, uid: str, request_id: str, action: str):
        subject = self.database.subject_hash(uid)

        def respond_to_request(transaction):
            self._user(subject, transaction)
            pointer = (
                self.database.request_ids.document(request_id)
                .get(transaction=transaction)
                .to_dict()
            )
            if pointer is None:
                raise ApiError(404, "request_not_found", "The friend request does not exist.")
            ref = self.database.relationships.document(pointer["pair_id"])
            row = ref.get(transaction=transaction).to_dict()
            if row is None or row["id"] != request_id or subject not in row["members"]:
                raise ApiError(404, "request_not_found", "The friend request does not exist.")
            permitted = row["sender_hash"] if action == "cancel" else row["recipient_hash"]
            if subject != permitted:
                raise ApiError(
                    403, "action_forbidden", "You cannot perform that action on this request."
                )
            target = {"accept": "accepted", "decline": "declined", "cancel": "cancelled"}[action]
            if row["status"] not in ("pending", target):
                raise ApiError(409, "request_not_pending", "This request is no longer pending.")
            sender = self._user(row["sender_hash"], transaction)
            recipient = self._user(row["recipient_hash"], transaction)
            if row["status"] != target:
                row = {**row, "status": target, "updated_at": now()}
                transaction.set(ref, row)
            return self._request_view(row, sender, recipient)

        return self.database.run(respond_to_request, uid=uid)

    def list_friends(self, uid: str, limit: int, offset: int):
        subject = self.database.subject_hash(uid)
        query = (
            self.database.relationships.where(
                filter=FieldFilter("members", "array_contains", subject)
            )
            .where(filter=FieldFilter("status", "==", "accepted"))
            .order_by("updated_at", direction=firestore.Query.DESCENDING)
            .order_by("id")
            .limit(limit)
            .offset(offset)
        )

        def read(transaction):
            self._user(subject, transaction)
            rows = [document.to_dict() for document in query.stream(transaction=transaction)]
            profiles = self._profiles_for(rows, transaction)
            return [
                {
                    "user": public_profile(
                        profiles[
                            row["recipient_hash"]
                            if row["sender_hash"] == subject
                            else row["sender_hash"]
                        ]
                    ),
                    "since": row["updated_at"],
                }
                for row in rows
            ]

        return self.database.run(read, read_only=True, uid=uid)

    def remove_friend(self, uid: str, friend_id: str):
        subject = self.database.subject_hash(uid)

        def remove(transaction):
            self._user(subject, transaction)
            friend = self._user_by_id(friend_id, transaction)
            ref = self.database.relationships.document(
                self.database.pair_id(subject, friend["subject_hash"])
            )
            row = ref.get(transaction=transaction).to_dict()
            if row is not None and row["status"] == "accepted":
                transaction.update(ref, {"status": "removed", "updated_at": now()})

        self.database.run(remove, uid=uid)

    def _user(self, subject, transaction=None):
        row = self.database.users.document(subject).get(transaction=transaction).to_dict()
        if row is None:
            raise ApiError(404, "profile_required", "Create your social profile first.")
        pointer = self.database.user_ids.document(row["id"]).get(transaction=transaction).to_dict()
        if pointer and pointer.get("firebase_uid"):
            if (
                self.database.deletions.document(pointer["firebase_uid"])
                .get(transaction=transaction)
                .exists
            ):
                raise ApiError(404, "user_not_found", "The profile is no longer available.")
        return row

    def _user_by_id(self, user_id, transaction):
        pointer = self.database.user_ids.document(user_id).get(transaction=transaction).to_dict()
        if pointer is None:
            raise ApiError(404, "user_not_found", "The profile does not exist.")
        return self._user(pointer["subject_hash"], transaction)

    def _profiles_for(self, rows, transaction):
        subjects = {subject for row in rows for subject in row["members"]}
        if not subjects:
            return {}
        documents = self.database.client.get_all(
            [self.database.users.document(subject) for subject in sorted(subjects)],
            transaction=transaction,
        )
        return {document.id: document.to_dict() for document in documents}

    @staticmethod
    def _request_view(row, sender, recipient):
        return {
            "id": row["id"],
            "sender": public_profile(sender),
            "recipient": public_profile(recipient),
            "status": row["status"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }
