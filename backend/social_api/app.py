from contextlib import asynccontextmanager
from typing import Annotated, Literal
from uuid import UUID

from fastapi import Depends, FastAPI, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from google.api_core.exceptions import GoogleAPICallError, RetryError

from social_api.accounts import AccountService
from social_api.auth import FirebaseTokenVerifier, Identity, TokenVerifier
from social_api.config import Settings
from social_api.database import Database
from social_api.decks import DeckService
from social_api.errors import ApiError
from social_api.models import (
    Card,
    Deck,
    DeckCopyInput,
    DeckShare,
    DeckShareInput,
    ErrorResponse,
    Friend,
    FriendRequest,
    FriendRequestAction,
    FriendRequestInput,
    NotificationDeviceInput,
    Profile,
    ProfileInput,
    RequestStatus,
)
from social_api.notifications import (
    DisabledPushSender,
    FirebasePushSender,
    NotificationService,
    PushSender,
)
from social_api.service import SocialService

bearer = HTTPBearer(auto_error=False)
Limit = Annotated[int, Query(ge=1, le=100)]
Offset = Annotated[int, Query(ge=0, le=10_000)]


def authenticated_user(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> Identity:
    if credentials is None:
        raise ApiError(401, "authentication_required", "Sign in to continue.")
    return request.app.state.verifier.verify(credentials.credentials)


CurrentUser = Annotated[Identity, Depends(authenticated_user)]


def create_app(
    settings: Settings | None = None,
    verifier: TokenVerifier | None = None,
    push_sender: PushSender | None = None,
) -> FastAPI:
    settings = settings or Settings.from_environment()
    database = Database(settings)
    service = SocialService(database)
    decks = DeckService(database)
    accounts = AccountService(database)
    notifications = NotificationService(database)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        active_verifier = None
        try:
            active_verifier = verifier or FirebaseTokenVerifier(settings.firebase_project_id)
            database.initialize()
            app.state.verifier = active_verifier
            app.state.push_sender = push_sender or (
                FirebasePushSender(active_verifier.firebase_app)
                if isinstance(active_verifier, FirebaseTokenVerifier)
                else DisabledPushSender()
            )
            yield
        finally:
            database.close()
            if verifier is None and active_verifier is not None:
                active_verifier.close()

    app = FastAPI(
        title="Clashcards Social API",
        version="0.4.0",
        description="Firebase-authenticated profiles, friends, sharing, publishing, and copies.",
        lifespan=lifespan,
        responses={
            code: {"model": ErrorResponse} for code in (401, 403, 404, 409, 410, 413, 422, 503)
        },
    )

    @app.exception_handler(ApiError)
    async def api_error(_request: Request, error: ApiError):
        headers = {"WWW-Authenticate": "Bearer"} if error.status == 401 else None
        return JSONResponse(
            status_code=error.status,
            content={"error": {"code": error.code, "message": error.message}},
            headers=headers,
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _error: RequestValidationError):
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "invalid_input",
                    "message": "Check the request fields and values.",
                }
            },
        )

    @app.exception_handler(GoogleAPICallError)
    @app.exception_handler(RetryError)
    async def storage_error(_request: Request, _error: Exception):
        return JSONResponse(
            status_code=503,
            content={
                "error": {
                    "code": "storage_unavailable",
                    "message": "Social storage is unavailable. Please retry.",
                }
            },
        )

    @app.middleware("http")
    async def private_responses(request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith("/v1/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/health", tags=["Health"])
    def health():
        return {"status": "ok"}

    @app.put("/v1/me", response_model=Profile, tags=["Accounts"])
    def put_profile(profile: ProfileInput, user: CurrentUser):
        return service.put_profile(user.uid, profile)

    @app.get("/v1/me", response_model=Profile, tags=["Accounts"])
    def get_profile(user: CurrentUser):
        return service.get_profile(user.uid)

    @app.delete("/v1/me", status_code=204, tags=["Accounts"])
    def delete_account_data(user: CurrentUser):
        accounts.delete_data(user)
        return Response(status_code=204)

    @app.get("/v1/users/lookup", response_model=Profile, tags=["Accounts"])
    def lookup(user: CurrentUser, username: Annotated[str, Query(min_length=3, max_length=24)]):
        return service.find_profile(user.uid, username)

    @app.post("/v1/friend-requests", response_model=FriendRequest, tags=["Friends"])
    def send_request(body: FriendRequestInput, user: CurrentUser):
        return service.send_request(
            user.uid,
            str(body.recipient_id),
            # Keep delivery inside the HTTP request so request-based Cloud Run CPU
            # stays available. Push failure never rolls back the request itself.
            on_created=lambda request: notifications.friend_request(request, app.state.push_sender),
        )

    @app.put("/v1/notification-devices/{device_id}", status_code=204, tags=["Notifications"])
    def register_device(device_id: UUID, body: NotificationDeviceInput, user: CurrentUser):
        notifications.register(user.uid, str(device_id), body.token)
        return Response(status_code=204)

    @app.delete("/v1/notification-devices/{device_id}", status_code=204, tags=["Notifications"])
    def unregister_device(device_id: UUID, user: CurrentUser):
        notifications.unregister(user.uid, str(device_id))
        return Response(status_code=204)

    @app.get("/v1/friend-requests", response_model=list[FriendRequest], tags=["Friends"])
    def list_requests(
        user: CurrentUser,
        direction: Literal["incoming", "outgoing"] = "incoming",
        status: RequestStatus = "pending",
        limit: Limit = 50,
        offset: Offset = 0,
    ):
        return service.list_requests(user.uid, direction, status, limit, offset)

    @app.post(
        "/v1/friend-requests/{request_id}/respond", response_model=FriendRequest, tags=["Friends"]
    )
    def respond(request_id: UUID, body: FriendRequestAction, user: CurrentUser):
        return service.respond(user.uid, str(request_id), body.action)

    @app.get("/v1/friends", response_model=list[Friend], tags=["Friends"])
    def friends(user: CurrentUser, limit: Limit = 50, offset: Offset = 0):
        return service.list_friends(user.uid, limit, offset)

    @app.delete("/v1/friends/{friend_id}", status_code=204, tags=["Friends"])
    def remove_friend(friend_id: UUID, user: CurrentUser):
        service.remove_friend(user.uid, str(friend_id))
        return Response(status_code=204)

    @app.post("/v1/decks/{deck_id}/shares", response_model=DeckShare, tags=["Decks"])
    def share_deck(deck_id: UUID, body: DeckShareInput, user: CurrentUser):
        return decks.share(user.uid, str(deck_id), str(body.recipient_id))

    @app.delete("/v1/decks/{deck_id}/shares/{recipient_id}", status_code=204, tags=["Decks"])
    def revoke_share(deck_id: UUID, recipient_id: UUID, user: CurrentUser):
        decks.revoke_share(user.uid, str(deck_id), str(recipient_id))
        return Response(status_code=204)

    @app.get("/v1/decks/public", response_model=list[Deck], tags=["Decks"])
    def list_public_decks(user: CurrentUser):
        return decks.list_public_decks(user.uid)

    @app.get("/v1/decks/{deck_id}", response_model=Deck, tags=["Decks"])
    def get_deck(deck_id: UUID, user: CurrentUser):
        return decks.get_deck(user.uid, str(deck_id))

    @app.post("/v1/decks/{deck_id}/publish", response_model=Deck, tags=["Decks"])
    def publish_deck(deck_id: UUID, user: CurrentUser):
        return decks.set_published(user.uid, str(deck_id), True)

    @app.delete("/v1/decks/{deck_id}/publish", response_model=Deck, tags=["Decks"])
    def unpublish_deck(deck_id: UUID, user: CurrentUser):
        return decks.set_published(user.uid, str(deck_id), False)

    @app.post("/v1/decks/{deck_id}/copies", response_model=Deck, tags=["Decks"])
    def save_copy(deck_id: UUID, body: DeckCopyInput, user: CurrentUser):
        return decks.save_copy(user.uid, str(deck_id), body)

    @app.get("/v1/decks/{deck_id}/cards", response_model=list[Card], tags=["Decks"])
    def list_cards(deck_id: UUID, user: CurrentUser, limit: Limit = 50, offset: Offset = 0):
        return decks.list_cards(user.uid, str(deck_id), limit, offset)

    return app
