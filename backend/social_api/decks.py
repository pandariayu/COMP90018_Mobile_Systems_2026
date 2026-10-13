import json
from datetime import UTC, datetime
from uuid import uuid4

from google.cloud.firestore_v1 import FieldFilter, Query
from pydantic import ValidationError

from social_api.database import Database
from social_api.errors import ApiError
from social_api.models import CardContent, DeckCopyInput
from social_api.service import now

MAX_COPY_CARDS = 400
MAX_COPY_CONTENT_BYTES = 4 * 1024 * 1024


class DeckService:
    """Authorize Admin SDK reads/writes against the same policy as firestore.rules."""

    def __init__(self, database: Database):
        self.database = database
        self.decks = database.client.collection("decks")

    def share(self, uid: str, deck_id: str, recipient_id: str):
        deck_ref = self.decks.document(deck_id)
        owner_hash = self.database.subject_hash(uid)

        def grant(transaction):
            self._owned_deck(deck_ref, uid, transaction)
            if not self.database.users.document(owner_hash).get(transaction=transaction).exists:
                raise ApiError(404, "profile_required", "Create your social profile first.")
            recipient = self._recipient(recipient_id, transaction)
            recipient_hash = recipient["subject_hash"]
            if recipient_hash == owner_hash:
                raise ApiError(422, "self_share", "You already own this deck.")
            pair_id = self.database.pair_id(owner_hash, recipient_hash)
            friendship = (
                self.database.relationships.document(pair_id).get(transaction=transaction).to_dict()
            )
            if friendship is None or friendship["status"] != "accepted":
                raise ApiError(403, "friendship_required", "Share with an accepted friend.")
            ref = deck_ref.collection("shares").document(recipient["firebase_uid"])
            existing = ref.get(transaction=transaction).to_dict()
            row = {
                "owner_id": uid,
                "recipient_id": recipient_id,
                "namespace": self.database.root.id,
                "pair_id": pair_id,
                "relationship_id": friendship["id"],
                "owner_hash": owner_hash,
                "recipient_hash": recipient_hash,
            }
            # Retrying the same share is idempotent; a new friendship needs a new grant.
            row["created_at"] = (
                existing["created_at"]
                if existing and all(existing.get(key) == value for key, value in row.items())
                else now()
            )
            transaction.set(ref, row)
            return {
                "deck_id": deck_id,
                "recipient_id": recipient_id,
                "created_at": row["created_at"],
            }

        return self.database.run(grant, uid=uid)

    def revoke_share(self, uid: str, deck_id: str, recipient_id: str):
        deck_ref = self.decks.document(deck_id)

        def revoke(transaction):
            self._owned_deck(deck_ref, uid, transaction)
            recipient = self._recipient(recipient_id, transaction)
            # Revocation remains possible after unfriending and is safe to retry.
            transaction.delete(deck_ref.collection("shares").document(recipient["firebase_uid"]))

        self.database.run(revoke, uid=uid)

    def set_published(self, uid: str, deck_id: str, published: bool):
        ref = self.decks.document(deck_id)
        visibility = "public" if published else "private"

        def update(transaction):
            deck = self._owned_deck(ref, uid, transaction)
            if deck.get("visibility") != visibility:
                changed = {"visibility": visibility, "updatedAt": datetime.now(UTC)}
                transaction.update(ref, changed)
                deck = {**deck, **changed}
            return {**deck, "deckId": deck_id}

        return self.database.run(update, uid=uid)

    def save_copy(self, uid: str, deck_id: str, body: DeckCopyInput):
        source_ref = self.decks.document(deck_id)
        copy_ref = self.decks.document(str(uuid4()))
        request_ref = self.database.copy_requests.document(
            f"{self.database.subject_hash(uid)}_{body.request_id}"
        )

        def copy(transaction):
            previous = request_ref.get(transaction=transaction).to_dict()
            if previous is not None:
                if previous["source_deck_id"] != deck_id or previous["name"] != body.name:
                    raise ApiError(
                        409, "copy_request_conflict", "Use a new request ID for a different copy."
                    )
                saved = (
                    self.decks.document(previous["deck_id"]).get(transaction=transaction).to_dict()
                )
                if (
                    saved is None
                    or saved.get("ownerId") != uid
                    or saved.get("copiedFrom") != previous["copied_from"]
                ):
                    raise ApiError(410, "copy_deleted", "This copy was deleted. Start a new copy.")
                # A completed copy belongs to its recipient even after source access is revoked.
                return {**saved, "deckId": previous["deck_id"]}

            source = self._readable_deck(source_ref, uid, transaction)
            if not isinstance(source.get("name"), str):
                raise ApiError(409, "invalid_source_deck", "The source deck needs a valid name.")
            timestamp = datetime.now(UTC)
            copied_from = {
                "deckId": deck_id,
                "ownerId": source["ownerId"],
                "copiedAt": timestamp,
            }
            deck = {
                "name": body.name if body.name is not None else source["name"],
                "ownerId": uid,
                "visibility": "private",
                "updatedAt": timestamp,
                "copiedFrom": copied_from,
            }
            content_bytes = len(json.dumps(deck, default=str, ensure_ascii=False).encode("utf-8"))
            cards = []
            # Query all cards, including malformed rows without a position. Ordering by
            # position here would silently omit those rows instead of rejecting the copy.
            query = source_ref.collection("cards").order_by("__name__").limit(MAX_COPY_CARDS + 1)
            for document in query.stream(transaction=transaction):
                if len(cards) >= MAX_COPY_CARDS:
                    raise ApiError(413, "deck_too_large", "Copy supports up to 400 cards per deck.")
                try:
                    card = CardContent.model_validate(document.to_dict()).model_dump(
                        exclude_unset=True
                    )
                except ValidationError as error:
                    raise ApiError(
                        409, "invalid_source_deck", "Fix invalid cards in the source deck first."
                    ) from error
                content_bytes += len(json.dumps(card, ensure_ascii=False).encode("utf-8"))
                if content_bytes > MAX_COPY_CONTENT_BYTES:
                    raise ApiError(413, "deck_too_large", "Copied content must fit within 4 MiB.")
                cards.append(card)
            if content_bytes > MAX_COPY_CONTENT_BYTES:
                raise ApiError(413, "deck_too_large", "Copied content must fit within 4 MiB.")
            deck["cardCount"] = len(cards)
            # Access, source cards, destination, and retry receipt share one transaction.
            # No progress, grants, unknown fields, or partial deck can escape this commit.
            transaction.create(copy_ref, deck)
            for card in cards:
                transaction.create(copy_ref.collection("cards").document(str(uuid4())), card)
            transaction.create(
                request_ref,
                {
                    "source_deck_id": deck_id,
                    "name": body.name,
                    "deck_id": copy_ref.id,
                    "copied_from": copied_from,
                },
            )
            return {**deck, "deckId": copy_ref.id}

        return self.database.run(copy, uid=uid)

    def get_deck(self, uid: str, deck_id: str):
        def read(transaction):
            deck = self._readable_deck(self.decks.document(deck_id), uid, transaction)
            return {
                **deck,
                "deckId": deck_id,
                "ownerDisplayName": self._owner_display_name(deck["ownerId"], transaction),
            }

        return self.database.run(read, read_only=True, uid=uid)

    def list_public_decks(self, uid: str):
        def read(transaction):
            query = self.decks.where(filter=FieldFilter("visibility", "==", "public")).order_by(
                "updatedAt", direction=Query.DESCENDING
            )
            creators = {}
            deleting = set()
            rows = []
            for document in query.stream(transaction=transaction):
                deck = document.to_dict()
                owner_id = deck["ownerId"]
                if owner_id not in creators and owner_id not in deleting:
                    if (
                        self.database.deletions.document(owner_id)
                        .get(transaction=transaction)
                        .exists
                    ):
                        deleting.add(owner_id)
                    else:
                        creators[owner_id] = self._owner_display_name(owner_id, transaction)
                if owner_id not in deleting:
                    rows.append(
                        {**deck, "deckId": document.id, "ownerDisplayName": creators[owner_id]}
                    )
            return rows

        return self.database.run(read, read_only=True, uid=uid)

    def _owner_display_name(self, owner_id: str, transaction):
        owner = (
            self.database.users.document(self.database.subject_hash(owner_id))
            .get(transaction=transaction)
            .to_dict()
        )
        return owner.get("display_name") if owner else None

    def list_cards(self, uid: str, deck_id: str, limit: int, offset: int):
        deck_ref = self.decks.document(deck_id)

        def read(transaction):
            self._readable_deck(deck_ref, uid, transaction)
            query = (
                deck_ref.collection("cards")
                .order_by("position")
                .order_by("__name__")
                .limit(limit)
                .offset(offset)
            )
            return [
                {**card.to_dict(), "cardId": card.id}
                for card in query.stream(transaction=transaction)
            ]

        return self.database.run(read, read_only=True, uid=uid)

    @staticmethod
    def _deck(ref, transaction):
        deck = ref.get(transaction=transaction).to_dict()
        if deck is None:
            raise ApiError(404, "deck_not_found", "The deck does not exist or is inaccessible.")
        return deck

    def _owned_deck(self, ref, uid, transaction):
        deck = self._deck(ref, transaction)
        if deck.get("ownerId") != uid:
            raise ApiError(403, "owner_required", "Only the deck owner can perform this action.")
        return deck

    def _recipient(self, recipient_id, transaction):
        recipient = (
            self.database.user_ids.document(recipient_id).get(transaction=transaction).to_dict()
        )
        if recipient is None:
            raise ApiError(404, "user_not_found", "The profile does not exist.")
        if not recipient.get("firebase_uid"):
            raise ApiError(
                409,
                "recipient_profile_refresh_required",
                "Ask your friend to open their social profile once before sharing.",
            )
        if (
            self.database.deletions.document(recipient["firebase_uid"])
            .get(transaction=transaction)
            .exists
        ):
            raise ApiError(404, "user_not_found", "The profile is no longer available.")
        return recipient

    def _readable_deck(self, ref, uid, transaction):
        deck = self._deck(ref, transaction)
        if self.database.deletions.document(deck["ownerId"]).get(transaction=transaction).exists:
            raise ApiError(404, "deck_not_found", "The deck does not exist or is inaccessible.")
        if deck.get("ownerId") == uid or deck.get("visibility") == "public":
            return deck
        share = ref.collection("shares").document(uid).get(transaction=transaction).to_dict()
        if share and share.get("owner_id") == deck.get("ownerId"):
            friendship = (
                self.database.client.collection("social_backends")
                .document(share["namespace"])
                .collection("relationships")
                .document(share["pair_id"])
                .get(transaction=transaction)
                .to_dict()
            )
            if (
                friendship
                and friendship.get("status") == "accepted"
                and friendship.get("id") == share.get("relationship_id")
                and share["owner_hash"] != share["recipient_hash"]
                and {share["owner_hash"], share["recipient_hash"]}
                <= set(friendship.get("members", []))
            ):
                return deck
        raise ApiError(404, "deck_not_found", "The deck does not exist or is inaccessible.")
