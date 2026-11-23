#!/usr/bin/env python3
"""One-shot, loopback-only Amlak P1 smoke. Synthetic owner/data; no production input.

Runs exact checkout code behind an ephemeral 127.0.0.1 HTTPServer and private
temporary SQLite + enrollment. All resources are closed and deleted on exit.
This is a Python contract simulation of buyer filters, NOT actual Android E2E.
"""
import io
import json
import math
import os
from pathlib import Path
import secrets
import tempfile
import threading
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import HTTPServer

from PIL import Image
from mac_pilot_ocr_http import Handler, MemoryPilot
from pilot_workflow import PilotStore, digest
from workflow_http import WorkflowRoutes


def require(value, label):
    if not value:
        raise AssertionError(label)


def approx_km(lat1, lon1, lat2, lon2):
    a1, a2 = math.radians(lat1), math.radians(lat2)
    da = a2 - a1
    db = math.radians(lon2 - lon1)
    h = math.sin(da / 2) ** 2 + math.cos(a1) * math.cos(a2) * math.sin(db / 2) ** 2
    return 6371.0088 * 2 * math.asin(min(1.0, math.sqrt(h)))


@contextmanager
def isolated_server():
    with tempfile.TemporaryDirectory(prefix="amlak-p1-synthetic-") as folder:
        root = Path(folder)
        root.chmod(0o700)
        device_a, device_b = "A" * 43, "B" * 43
        enroll = root / "devices.json"
        enroll.write_text(json.dumps({
            "+21623456789": digest(device_a),
            "+21698765432": digest(device_b),
        }), encoding="utf-8")
        enroll.chmod(0o600)
        previous = {k: os.environ.get(k) for k in (
            "MALAK_PILOT_DEVICE_ENROLLMENT", "MALAK_DIRECT_PUBLISHING",
            "MALAK_ATLAS_READER_TOKEN", "MALAK_PILOT_PARCEL_ORIGIN",
            "MALAK_PILOT_PARCEL_TOKEN",
        )}
        try:
            os.environ["MALAK_PILOT_DEVICE_ENROLLMENT"] = str(enroll)
            os.environ["MALAK_DIRECT_PUBLISHING"] = "1"
            for k in ("MALAK_ATLAS_READER_TOKEN", "MALAK_PILOT_PARCEL_ORIGIN",
                      "MALAK_PILOT_PARCEL_TOKEN"):
                os.environ.pop(k, None)
            store = PilotStore(root / "db")
            pilot = MemoryPilot(None, [], None)
            pilot.store = store
            class UnusedPrivateMatcher:
                def compare(self, *_args, **_kwargs):
                    raise AssertionError("P1 smoke must not invoke OCR or a private matcher")
            workflow = WorkflowRoutes(store, UnusedPrivateMatcher())
            atlas_token = secrets.token_urlsafe(32)
            require(len(atlas_token) == 43, "atlas staging token size")
            workflow.atlas_reader_token = atlas_token
            server = HTTPServer(("127.0.0.1", 0), Handler)
            server.pilot, server.workflow = pilot, workflow
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                yield store, server.server_port, atlas_token, device_a, device_b
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=4)
                require(not thread.is_alive(), "staging listener not stopped")
        finally:
            for k, v in previous.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v


def call(port, path, body=None, bearer=None, binary=False):
    method = "GET" if body is None else "POST"
    headers = {"Accept": "application/json"}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    if isinstance(body, dict):
        body = json.dumps(body, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    if binary:
        headers["Content-Type"] = "application/octet-stream"
    conn = HTTPConnection("127.0.0.1", port, timeout=6)
    try:
        conn.request(method, path, body=body, headers=headers)
        result = conn.getresponse()
        raw = result.read(512 * 1024)
        status = result.status
        payload = (json.loads(raw) if result.getheader("content-type", "").startswith(
            "application/json") else raw)
        return status, payload
    finally:
        conn.close()


def main():
    steps = []
    with isolated_server() as (store, port, atlas_token, device_a, device_b):
        require(port > 0, "no loopback port")
        owner = call(port, "/internal/v1/pilot/login",
                     {"phone": "+21623456789", "code": "1234", "deviceKey": device_a})
        require(owner[0] == 200, "owner fixture login")
        bearer = owner[1]["bearer"]
        stranger = call(port, "/internal/v1/pilot/login",
                        {"phone": "+21698765432", "code": "1234", "deviceKey": device_b})
        require(stranger[0] == 200, "stranger fixture login")
        intruder = stranger[1]["bearer"]
        caps = call(port, "/api/public/capabilities")
        require(caps[0] == 200 and caps[1]["directPublishing"] is True,
                "isolated direct publish capability")
        steps.append("local_owner_login_and_capability")

        source = {
            "title": "SYNTHETIC Nabeul land", "kind": "LAND", "region": "Nabeul",
            "area": "300", "price": "80000", "description": "LOCAL_QA_ONLY",
            "latitude": "36.879784", "longitude": "11.004884",
            "requestKey": "qa-" + "p" * 32,
        }
        initial = call(port, "/api/pilot/direct-listings", source, bearer)
        require(initial[0] == 200, "draft creation")
        original_id = initial[1]["listing"]["id"]
        require(initial[1]["listing"]["status"] == "DRAFT", "draft status")
        require(call(port, "/api/public/listings")[1]["listings"] == [],
                "draft leaked into buyer feed")
        blocked = call(port, f"/api/pilot/direct-listings/{original_id}/publish",
                       {}, intruder)
        require(blocked[0] in (401, 403, 404), "other owner can publish draft")
        steps.append("draft_hidden_and_owner_isolation")

        picture = io.BytesIO()
        Image.new("RGB", (128, 128), "blue").save(picture, format="JPEG")
        photo = call(port,
            f"/api/pilot/reviews/{initial[1]['listing']['reviewId']}/photos",
            picture.getvalue(), bearer, binary=True)
        require(photo[0] == 200, "synthetic photo upload")
        photo_id = photo[1]["photoId"]
        require(call(port, f"/api/public/photos/{photo_id}")[0] == 404,
                "draft photo leaked")
        published = call(port, f"/api/pilot/direct-listings/{original_id}/publish",
                         {"photoIds": [photo_id]}, bearer)
        require(published[0] == 200 and published[1]["listing"]["id"] == original_id,
                "initial publication / identity")
        require(published[1]["listing"]["status"] == "PUBLISHED", "publish status")
        public = call(port, "/api/public/listings")
        require(public[0] == 200 and len(public[1]["listings"]) == 1,
                "published listing not visible")
        card = public[1]["listings"][0]
        require(card["id"] == original_id, "buyer listing id changed")
        require(card["kind"] == "LAND" and card["ownershipVerified"] is False,
                "type or ownership fabricated")
        require(card["locationAccuracy"] == "APPROXIMATE",
                "missing approximate marker")
        lat, lon = float(card["latitude"]), float(card["longitude"])
        require(29 <= lat <= 39 and 7 <= lon <= 13, "bad public Tunisia point")
        require(len(card["latitude"].split(".")[1]) <= 2 and
                len(card["longitude"].split(".")[1]) <= 2,
                "raw private location sent to buyer")
        require(card["latitude"] != source["latitude"] and
                card["longitude"] != source["longitude"], "private coordinate leak")
        for private_key in ("reviewId", "phone", "customerName", "identityFields",
                            "ownerId", "privateEvidence", "cinFull"):
            require(private_key not in card, "private field in public feed: " + private_key)
        require(card["photos"] == [photo_id] and
                call(port, f"/api/public/photos/{photo_id}")[0] == 200,
                "synthetic public property photo not gated")
        steps.append("published_coarse_public_card_photo_no_ownership_badge")

        # Buyer-contract simulation only. Actual Android list/map/radar on
        # a connected device remains its own acceptance gate.
        buyer_list = [item for item in public[1]["listings"] if
                      item["kind"] == "LAND" and float(item["price"]) <= 90000
                      and float(item["area"]) >= 250]
        require([item["id"] for item in buyer_list] == [original_id], "buyer list filter")
        map_ids = [item["id"] for item in buyer_list if
                   item["locationAccuracy"] == "APPROXIMATE"
                   and 29 <= float(item["latitude"]) <= 39
                   and 7 <= float(item["longitude"]) <= 13]
        radar_ids = [item["id"] for item in buyer_list if
                     approx_km(36.88, 11.00, float(item["latitude"]),
                               float(item["longitude"])) <= 10]
        require(map_ids == radar_ids == [original_id], "buyer contract parity")
        atlas = call(port, "/api/atlas/listings", bearer=atlas_token)
        require(atlas[0] == 200 and len(atlas[1]["features"]) == 1,
                "Atlas public projection")
        feature = atlas[1]["features"][0]
        require(feature["id"] == original_id and
                feature["geometry"]["coordinates"] == [lon, lat],
                "Atlas used a different/raw location")
        steps.append("buyer_filter_map_radar_contract_and_atlas_public_projection")

        owner_id = store.authenticate(bearer)["id"]
        private_review = store.record_review(
            owner_id,
            [("IDENTITY", "image/jpeg", b"SYNTHETIC_FAKE_ID"),
             ("DOCUMENT", "application/pdf", b"%PDF-1.4 SYNTHETIC_FAKE_TITLE")],
            {"assessment": "NAME_ONLY_REVIEW",
             "parcelPlanScans": [{"page": 2}]},
        )
        reviewed = call(port, f"/api/pilot/direct-listings/{original_id}/verification",
                        {"reviewId": private_review}, bearer)
        require(reviewed[0] == 200 and
                reviewed[1]["listing"]["id"] == original_id and
                reviewed[1]["listing"]["verificationStatus"] == "UNDER_REVIEW",
                "post-publication private review changed id")
        live = call(port, "/api/public/listings")[1]["listings"][0]
        require(live["id"] == original_id and
                live["ownershipVerified"] is False and
                "SYNTHETIC_FAKE_ID" not in json.dumps(live),
                "verification leaked private data or badge")
        steps.append("private_review_same_id_unverified_public")

        paused = call(port, f"/api/pilot/direct-listings/{original_id}/pause", {}, bearer)
        require(paused[0] == 200 and paused[1]["listing"]["status"] == "PAUSED",
                "owner pause")
        require(call(port, "/api/public/listings")[1]["listings"] == [],
                "withdrawal not reflected in buyer feed")
        require(call(port, "/api/atlas/listings", bearer=atlas_token)[1]["features"] == [],
                "withdrawal not reflected in Atlas")
        require(call(port, f"/api/public/photos/{photo_id}")[0] == 404,
                "paused photo still public")
        steps.append("pause_revokes_buyer_atlas_photo")

        resumed = call(port, f"/api/pilot/direct-listings/{original_id}/publish",
                       {}, bearer)
        require(resumed[0] == 200 and resumed[1]["listing"]["id"] == original_id,
                "resume created new id")
        require(len(call(port, "/api/public/listings")[1]["listings"]) == 1,
                "resume invisible")
        steps.append("resume_same_id")

        store.bootstrap_admin("qa-admin@example.test", "synthetic-admin-password")
        admin_token = store.admin_login("qa-admin@example.test",
                                        "synthetic-admin-password")["bearer"]
        current = resumed[1]["listing"]
        decision = call(port, f"/api/admin/submissions/{original_id}/decision",
                        {"status": "PAUSED", "listing": source,
                         "note": "Synthetic QA moderation", "version": current["version"]},
                        admin_token)
        require(decision[0] == 200, "moderation pause")
        require(call(port, "/api/public/listings")[1]["listings"] == [],
                "moderation pause buyer leak")
        require(call(port, f"/api/pilot/direct-listings/{original_id}/publish",
                     {}, bearer)[0] == 409,
                "owner bypassed moderation pause")
        steps.append("moderation_cannot_be_overridden_by_owner")

    # Do not report secrets, IDs, personal data, exact coordinates, or QA port.
    print(json.dumps({
        "status": "PASS", "synthetic_only": True, "bound": "127.0.0.1:ephemeral",
        "db": "temporary SQLite removed", "listener": "closed",
        "production_changed": False, "steps": steps, "count": len(steps),
        "limits": ["buyer map/radar is a Python contract simulation, not Android",
                   "private OCR is synthetic, not a real Mac model run"],
    }, sort_keys=True))


if __name__ == "__main__":
    main()
