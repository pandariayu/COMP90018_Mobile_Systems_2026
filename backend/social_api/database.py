import hashlib
import hmac
import json
import os
import random
import time
from collections.abc import Callable
from typing import TypeVar

from google.api_core.exceptions import Aborted
from google.cloud import firestore

from social_api.config import Settings
from social_api.errors import ApiError

T = TypeVar("T")


class Database:
    """Firestore documents scoped to this service, with atomic read/modify/write operations."""

    def __init__(self, settings: Settings):
        if os.environ.get("K_SERVICE") and os.environ.get("FIRESTORE_EMULATOR_HOST"):
            raise ValueError("FIRESTORE_EMULATOR_HOST must not be set on Cloud Run.")
        self._key = settings.identity_hmac_key
        self._project = settings.firebase_project_id
        self.client = firestore.Client(
            project=settings.firebase_project_id, database=settings.firestore_database
        )
        self.root = self.client.collection("social_backends").document(settings.firestore_namespace)
        self.users = self.root.collection("users")
        self.user_ids = self.root.collection("user_ids")
        self.usernames = self.root.collection("usernames")
        self.relationships = self.root.collection("relationships")
        self.request_ids = self.root.collection("request_ids")
        self.copy_requests = self.root.collection("copy_requests")
        self.notification_devices = self.root.collection("notification_devices")
        self.config = self.root.collection("metadata").document("config")
        self.deletions = self.client.collection("account_deletions")

    def subject_hash(self, uid: str) -> str:
        # Preserve the identity mapping from the original local prototype.
        payload = json.dumps(["firebase-uid-v1", self._project, uid]).encode()
        return hmac.new(self._key, payload, hashlib.sha256).hexdigest()

    @staticmethod
    def pair_id(first: str, second: str) -> str:
        return "_".join(sorted((first, second)))

    def username_ref(self, username: str):
        # Firestore reserves IDs matching __.*__; these are still valid app usernames.
        return self.usernames.document(f"u_{username}")

    def ensure_active(self, uid: str, transaction=None):
        if self.deletions.document(uid).get(transaction=transaction).exists:
            raise ApiError(
                409, "account_deleting", "Finish deleting this account from Account settings."
            )

    def run(self, operation: Callable[..., T], *, read_only=False, uid: str | None = None) -> T:
        def guarded(transaction):
            if uid is not None:
                self.ensure_active(uid, transaction)
            return operation(transaction)

        for attempt in range(5):
            try:
                # Retry whole transactions with jitter, including conflicts during reads.
                # Immediate retries can repeatedly contend for the same username/pair.
                transaction = self.client.transaction(read_only=read_only, max_attempts=1)
                return firestore.transactional(guarded)(transaction)
            except Aborted as error:
                conflict = error
            except ValueError as error:
                # The SDK wraps exhausted commit retries in ValueError.
                if not isinstance(error.__cause__, Aborted):
                    raise
                conflict = error
            if attempt < 4:
                time.sleep(random.uniform(0.05, 0.15) * (2**attempt))
        raise ApiError(503, "storage_busy", "Please retry the social action.") from conflict

    def initialize(self):
        def initialize_config(transaction):
            saved = self.config.get(transaction=transaction).to_dict()
            fingerprint = self.subject_hash("configuration-check")
            if saved is not None:
                if saved.get("schema_version") != 1:
                    raise RuntimeError("Unsupported social database schema version.")
                if not hmac.compare_digest(saved.get("identity_config", ""), fingerprint):
                    raise RuntimeError(
                        "The identity key or Firebase project changed. "
                        "Restore the original settings."
                    )
            else:
                transaction.create(
                    self.config, {"schema_version": 1, "identity_config": fingerprint}
                )

        self.run(initialize_config)

    def close(self):
        self.client.close()
