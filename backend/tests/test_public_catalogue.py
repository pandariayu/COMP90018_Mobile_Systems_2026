from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from conftest import headers, make_friends, share


@pytest.fixture
def catalogue_decks(database, deck):
    timestamp = datetime.now(UTC)
    deck.update({"visibility": "public", "updatedAt": timestamp})
    rows = {"older": deck}
    for name, owner, visibility, seconds in (
        ("newer", "alice", "public", 2),
        ("private", "alice", "private", 3),
        ("missing_profile", "charlie", "public", 1),
    ):
        ref = database.client.collection("decks").document(str(uuid4()))
        ref.set(
            {
                "name": name,
                "ownerId": f"firebase-uid-{owner}",
                "visibility": visibility,
                "updatedAt": timestamp + timedelta(seconds=seconds),
                "createdAt": timestamp,
                "cardCount": 2,
                "authorEmail": "legacy@example.com",
                "ownerDisplayName": "Outdated name",
            }
        )
        rows[name] = ref
    yield rows
    for name, ref in rows.items():
        if name != "older":
            for grant in ref.collection("shares").stream():
                grant.reference.delete()
            ref.delete()


def test_catalogue_resolves_current_names_and_excludes_private_shared_decks(
    client, profiles, catalogue_decks
):
    make_friends(client, profiles)
    assert share(client, catalogue_decks["private"], profiles).status_code == 200
    result = client.get("/v1/decks/public", headers=headers("bob"))
    assert result.status_code == 200
    ids = {ref.id for ref in catalogue_decks.values()}
    rows = [row for row in result.json() if row["deckId"] in ids]
    assert [row["deckId"] for row in rows] == [
        catalogue_decks[name].id for name in ("newer", "missing_profile", "older")
    ]
    assert [row["ownerDisplayName"] for row in rows] == ["Alice", "Charlie", "Alice"]
    assert rows[0]["createdAt"] is not None
    assert all("authorEmail" not in row for row in rows)
    # Owners also see only public decks in this catalogue.
    own_rows = client.get("/v1/decks/public", headers=headers("alice")).json()
    assert catalogue_decks["private"].id not in {row["deckId"] for row in own_rows}
    renamed = client.put(
        "/v1/me",
        headers=headers("alice"),
        json={"username": "alice", "display_name": "Jason"},
    )
    assert renamed.status_code == 200
    refreshed = client.get("/v1/decks/public", headers=headers("bob")).json()
    assert all(
        row["ownerDisplayName"] == "Jason"
        for row in refreshed
        if row["deckId"] in {catalogue_decks["newer"].id, catalogue_decks["older"].id}
    )


def test_catalogue_preserves_decks_without_profiles(client, catalogue_decks):
    response = client.get("/v1/decks/public", headers=headers("bob"))
    assert response.status_code == 200
    rows = {row["deckId"]: row for row in response.json()}
    assert rows[catalogue_decks["missing_profile"].id]["ownerDisplayName"] is None


def test_catalogue_hides_decks_of_deleting_owners(client, database, catalogue_decks):
    marker = database.deletions.document("firebase-uid-charlie")
    marker.set({"status": "pending"})
    try:
        response = client.get("/v1/decks/public", headers=headers("bob"))
        assert response.status_code == 200
        ids = {row["deckId"] for row in response.json()}
        assert catalogue_decks["missing_profile"].id not in ids
        assert catalogue_decks["older"].id in ids
    finally:
        marker.delete()


@pytest.mark.parametrize("user", [None, "invalid"])
def test_catalogue_requires_authentication(client, user):
    response = client.get("/v1/decks/public", headers=headers(user) if user else {})
    assert response.status_code == 401


def test_catalogue_rejects_deleting_viewer(client, database):
    marker = database.deletions.document("firebase-uid-bob")
    marker.set({"status": "pending"})
    try:
        result = client.get("/v1/decks/public", headers=headers("bob"))
        assert result.status_code == 409
        assert result.json()["error"]["code"] == "account_deleting"
    finally:
        marker.delete()
