import os
from dataclasses import dataclass
from typing import Protocol
from uuid import uuid4

from social_api.errors import ApiError


@dataclass(frozen=True)
class Identity:
    uid: str
    auth_time: int | None = None


class TokenVerifier(Protocol):
    def verify(self, token: str) -> Identity: ...


class FirebaseTokenVerifier:
    """Only Firebase ID tokens issued for this app's project are accepted."""

    def __init__(self, project_id: str):
        # The production entry point must never silently accept unsigned emulator tokens.
        if os.environ.get("FIREBASE_AUTH_EMULATOR_HOST"):
            raise ValueError("Remove FIREBASE_AUTH_EMULATOR_HOST before starting the social API.")
        import firebase_admin
        from firebase_admin import auth, credentials, exceptions
        from google.auth.exceptions import DefaultCredentialsError

        self._auth = auth
        self._exceptions = exceptions
        credential = credentials.ApplicationDefault()
        try:
            credential.get_credential()
        except DefaultCredentialsError as error:
            raise ValueError(
                "Configure Firebase Admin Application Default Credentials first."
            ) from error
        self._app = firebase_admin.initialize_app(
            credential=credential,
            options={"projectId": project_id, "httpTimeout": 10},
            name=f"social-{uuid4()}",
        )

    def verify(self, token: str) -> Identity:
        try:
            claims = self._auth.verify_id_token(token, app=self._app, check_revoked=True)
        except (
            ValueError,
            self._auth.InvalidIdTokenError,
            self._auth.RevokedIdTokenError,
            self._auth.UserDisabledError,
            self._auth.UserNotFoundError,
        ) as error:
            raise ApiError(401, "invalid_token", "Sign in again to continue.") from error
        except self._exceptions.FirebaseError as error:
            raise ApiError(
                503, "auth_unavailable", "Sign-in verification is unavailable."
            ) from error
        uid = claims.get("uid")
        if not isinstance(uid, str) or not uid:
            raise ApiError(401, "invalid_token", "Sign in again to continue.")
        return Identity(uid=uid, auth_time=claims.get("auth_time"))

    def close(self):
        import firebase_admin

        firebase_admin.delete_app(self._app)

    @property
    def firebase_app(self):
        return self._app
