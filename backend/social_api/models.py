from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

Username = Annotated[
    str,
    StringConstraints(strip_whitespace=True, to_lower=True, min_length=3, max_length=24),
    Field(pattern=r"^[a-zA-Z0-9_]+$"),
]
RequestStatus = Literal["pending", "accepted", "declined", "cancelled", "removed"]
RequestAction = Literal["accept", "decline", "cancel"]


class ProfileInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    username: Username
    display_name: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)
    ]


class Profile(BaseModel):
    id: UUID
    username: str
    display_name: str
    created_at: str
    updated_at: str


class FriendRequestInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recipient_id: UUID


class FriendRequestAction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: RequestAction


class FriendRequest(BaseModel):
    id: UUID
    sender: Profile
    recipient: Profile
    status: RequestStatus
    created_at: str
    updated_at: str


class Friend(BaseModel):
    user: Profile
    since: str


class DeckShareInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    recipient_id: UUID


class DeckShare(BaseModel):
    deck_id: UUID
    recipient_id: UUID
    created_at: str


class DeckCopyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # The client keeps this UUID when retrying one Save-a-copy action.
    request_id: UUID
    name: (
        Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
        | None
    ) = None


class DeckSource(BaseModel):
    deckId: UUID
    ownerId: str
    copiedAt: datetime


class Deck(BaseModel):
    # Keep the Android/Firestore names and ownership convention.
    deckId: UUID
    name: str
    ownerId: str
    visibility: Literal["private", "public"]
    updatedAt: datetime
    cardCount: int
    copiedFrom: DeckSource | None = None
    ownerDisplayName: str | None = None
    createdAt: datetime | None = None


class CardContent(BaseModel):
    model_config = ConfigDict(strict=True)

    front: str
    back: str
    position: int
    frontImageUri: str | None = None
    backImageUri: str | None = None


class Card(CardContent):
    # UUIDs in HTTP responses are parsed from document ID strings.
    model_config = ConfigDict(strict=False)

    cardId: UUID


class ErrorDetail(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail
