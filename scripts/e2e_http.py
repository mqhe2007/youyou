#!/usr/bin/env python3
"""Real-process HTTP acceptance test for the local-filesystem server."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zlib


ROOT = Path(__file__).resolve().parents[1]
SERVER = Path(
    os.environ.get(
        "YOUYOU_SERVER_BINARY",
        str(ROOT / "apps" / "server" / "target" / "debug" / "youyou-server"),
    )
)
PASSWORD = "correct horse battery staple"

# A 1x1 PNG keeps the test independent of image-generation fixtures.


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


PNG_1X1 = (
    b"\x89PNG\r\n\x1a\n"
    + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    + png_chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00"))
    + png_chunk(b"IEND", b"")
)


class AcceptanceError(RuntimeError):
    pass


class HttpClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")

    def request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        bearer: str | None = None,
        csrf: str | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        request_headers = {
            "X-Youyou-Client-Version": "0.1.3",
            "Accept": "application/json",
        }
        if method in {"POST", "PUT", "PATCH", "DELETE"}:
            request_headers["Idempotency-Key"] = str(uuid.uuid4())
        if bearer:
            request_headers["Authorization"] = f"Bearer {bearer}"
        if csrf:
            request_headers["x-csrf-token"] = csrf
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=body,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, dict(response.headers.items()), response.read()
        except urllib.error.HTTPError as error:
            return error.code, dict(error.headers.items()), error.read()
        except urllib.error.URLError as error:
            raise AcceptanceError(f"{method} {path} failed: {error}") from error

    def json(
        self,
        method: str,
        path: str,
        payload: object | None = None,
        *,
        bearer: str | None = None,
        csrf: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], dict]:
        body = None
        request_headers = dict(headers or {})
        if payload is not None:
            body = json.dumps(payload).encode()
            request_headers["Content-Type"] = "application/json"
        status, response_headers, response_body = self.request(
            method,
            path,
            body=body,
            headers=request_headers,
            bearer=bearer,
            csrf=csrf,
        )
        try:
            decoded = json.loads(response_body) if response_body else {}
        except json.JSONDecodeError as error:
            raise AcceptanceError(
                f"{method} {path} returned non-JSON body: {response_body[:200]!r}"
            ) from error
        return status, response_headers, decoded


def assert_status(
    result: tuple[int, dict[str, str], object],
    expected: int,
    label: str,
) -> object:
    status, _, body = result
    if status != expected:
        raise AcceptanceError(f"{label}: expected {expected}, got {status}: {body}")
    return body


def find_free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def start_server(
    server_dir: Path,
    log_file: Path,
) -> tuple[subprocess.Popen[bytes], HttpClient]:
    port = find_free_port()
    environment = os.environ.copy()
    environment.update(
        {
            "RUST_LOG": os.environ.get("RUST_LOG", "warn"),
            "YOUYOU_SERVER_PORT": str(port),
            "YOUYOU_SERVER_DIR": str(server_dir),
        }
    )
    # Drop legacy env names so a developer shell/.env cannot override the test layout.
    for legacy in ("YOUYOU_BIND", "YOUYOU_DATA_DIR", "YOUYOU_MEDIA_ROOT"):
        environment.pop(legacy, None)
    output = log_file.open("ab")
    process = subprocess.Popen(
        [str(SERVER)],
        cwd=ROOT,
        env=environment,
        stdout=output,
        stderr=subprocess.STDOUT,
    )
    client = HttpClient(f"http://127.0.0.1:{port}")
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output.close()
            log = log_file.read_text(errors="replace")
            raise AcceptanceError(f"server exited during startup:\n{log}")
        try:
            status, _, _ = client.request("GET", "/api/v1/health")
            if status == 200:
                output.close()
                return process, client
        except AcceptanceError:
            pass
        time.sleep(0.1)
    process.terminate()
    output.close()
    raise AcceptanceError("server did not become healthy within 20 seconds")


def stop_server(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.send_signal(signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def poll_job(
    client: HttpClient,
    job_id: str,
    *,
    bearer: str,
    admin: bool = False,
    allow_failure: bool = False,
) -> dict:
    path = f"/api/v1/admin/jobs/{job_id}" if admin else f"/api/v1/jobs/{job_id}"
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        result = client.json("GET", path, bearer=bearer)
        body = assert_status(result, 200, f"poll job {job_id}")
        assert isinstance(body, dict)
        if body["status"] in {"succeeded", "failed", "cancelled"}:
            if body["status"] != "succeeded" and not allow_failure:
                raise AcceptanceError(f"job {job_id} ended as {body}")
            return body
        time.sleep(0.1)
    raise AcceptanceError(f"job {job_id} did not finish within 30 seconds")


def run_acceptance() -> None:
    if not SERVER.exists():
        raise AcceptanceError(
            f"{SERVER} is missing; run `cargo build --manifest-path apps/server/Cargo.toml --locked`"
        )

    temporary = tempfile.TemporaryDirectory(prefix="youyou-e2e-")
    if os.environ.get("YOUYOU_KEEP_E2E"):
        temporary.cleanup = lambda: None
        temporary._finalizer.detach()
        print(f"E2E temporary directory: {temporary.name}", file=sys.stderr)
    with temporary:
        root = Path(temporary.name)
        data_dir = root / "data"
        media_root = root / "media"
        outside_root = root / "outside"
        log_file = root / "server.log"
        media_root.mkdir()
        outside_root.mkdir()
        (media_root / "nested").mkdir()
        (media_root / "nested" / "photo.png").write_bytes(PNG_1X1)
        (outside_root / "secret.txt").write_text("outside")

        process, client = start_server(root, log_file)
        try:
            health = assert_status(
                client.json("GET", "/api/v1/health"),
                200,
                "health",
            )
            assert isinstance(health, dict) and health["status"] == "ok"

            setup_token_path = data_dir / "bootstrap" / "setup-token"
            deadline = time.monotonic() + 5
            while not setup_token_path.exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            if not setup_token_path.exists():
                raise AcceptanceError("server did not create the setup token")
            setup_token = setup_token_path.read_text().strip()
            assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/setup",
                    {"setupToken": setup_token, "password": PASSWORD},
                ),
                204,
                "admin setup",
            )
            login = assert_status(
                client.json("POST", "/api/v1/admin/session", {"password": PASSWORD}),
                200,
                "admin login",
            )
            assert isinstance(login, dict)
            admin_token = login["token"]
            admin_csrf = login["csrfToken"]

            user = assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/users",
                    {"name": "e2e-user", "libraryName": "nested"},
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                201,
                "create user with library",
            )
            assert isinstance(user, dict)
            assert user["libraryRoot"] == "nested"
            user_id = user["id"]
            pairing = assert_status(
                client.json(
                    "POST",
                    f"/api/v1/admin/users/{user_id}/pairing-codes",
                    {},
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                200,
                "create user pairing code",
            )
            assert isinstance(pairing, dict)
            device = assert_status(
                client.json(
                    "POST",
                    "/api/v1/pairing",
                    {"code": pairing["code"], "deviceName": "real-http-e2e"},
                ),
                200,
                "pair device",
            )
            assert isinstance(device, dict)
            device_token = device["token"]

            scan = assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/jobs/scan",
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                202,
                "start scan",
            )
            assert isinstance(scan, dict)
            poll_job(client, scan["id"], bearer=admin_token, admin=True)
            try:
                (media_root / "escape").symlink_to(outside_root, target_is_directory=True)
                escaped_scan = assert_status(
                    client.json(
                        "POST",
                        "/api/v1/admin/jobs/scan",
                        bearer=admin_token,
                        csrf=admin_csrf,
                    ),
                    202,
                    "start symlink escape scan",
                )
                assert isinstance(escaped_scan, dict)
                escaped_result = poll_job(
                    client,
                    escaped_scan["id"],
                    bearer=admin_token,
                    admin=True,
                    allow_failure=True,
                )
                if "outside" not in (escaped_result.get("lastError") or "").lower():
                    raise AcceptanceError(
                        f"symlink escape was not rejected safely: {escaped_result}"
                    )
            finally:
                (media_root / "escape").unlink(missing_ok=True)
            retry_scan = assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/jobs/scan",
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                202,
                "start scan after symlink cleanup",
            )
            assert isinstance(retry_scan, dict)
            poll_job(client, retry_scan["id"], bearer=admin_token, admin=True)

            media_page = assert_status(
                client.json("GET", "/api/v1/media?limit=10", bearer=device_token),
                200,
                "list media",
            )
            assert isinstance(media_page, dict) and media_page["items"]
            media_id = media_page["items"][0]["id"]
            media = assert_status(
                client.json("GET", f"/api/v1/media/{media_id}", bearer=device_token),
                200,
                "get media",
            )
            assert isinstance(media, dict) and media["contentHash"]
            status, headers, body = client.request(
                "GET",
                f"/api/v1/media/{media_id}/content",
                bearer=device_token,
            )
            if status != 200 or body != PNG_1X1:
                raise AcceptanceError(
                    f"read media content failed: {status}, {headers}, {body[:32]!r}"
                )
            status, headers, body = client.request(
                "GET",
                f"/api/v1/media/{media_id}/content",
                headers={"Range": "bytes=0-7"},
                bearer=device_token,
            )
            content_range = headers.get("content-range", headers.get("Content-Range", ""))
            if status != 206 or body != PNG_1X1[:8] or "bytes 0-7/" not in content_range:
                raise AcceptanceError(f"range read failed: {status}, {headers}, {body!r}")
            status, _, thumbnail = client.request(
                "GET",
                f"/api/v1/media/{media_id}/thumbnail?size=128",
                bearer=device_token,
            )
            if status != 200 or not thumbnail:
                raise AcceptanceError(f"thumbnail request failed: {status}")

            bootstrap = assert_status(
                client.json("POST", "/api/v1/sync/bootstrap", bearer=device_token),
                202,
                "start bootstrap",
            )
            assert isinstance(bootstrap, dict)
            poll_job(client, bootstrap["jobId"], bearer=device_token)
            deadline = time.monotonic() + 30
            while True:
                snapshot = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/sync/bootstrap/{bootstrap['snapshotId']}",
                        bearer=device_token,
                    ),
                    200,
                    "poll bootstrap",
                )
                assert isinstance(snapshot, dict)
                if snapshot["state"] == "ready":
                    break
                if snapshot["state"] in {"failed", "expired"}:
                    raise AcceptanceError(f"bootstrap ended as {snapshot}")
                if time.monotonic() >= deadline:
                    raise AcceptanceError("bootstrap did not become ready")
                time.sleep(0.1)
            for entity in ("media", "tags", "relations"):
                page = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/sync/bootstrap/{bootstrap['snapshotId']}/{entity}",
                        bearer=device_token,
                    ),
                    200,
                    f"list bootstrap {entity}",
                )
                assert isinstance(page, dict)
                if entity == "media" and page["items"]:
                    if "isFavorite" not in page["items"][0]["data"]:
                        raise AcceptanceError(
                            f"favorite state missing from snapshot: {page['items'][0]['data']}"
                        )

            changes = assert_status(
                client.json("GET", "/api/v1/changes?limit=100", bearer=device_token),
                200,
                "list changes",
            )
            assert isinstance(changes, dict) and changes["items"]

            favorite = assert_status(
                client.json(
                    "POST",
                    f"/api/v1/media/{media_id}/favorite",
                    {"isFavorite": True},
                    bearer=device_token,
                ),
                200,
                "favorite media",
            )
            assert isinstance(favorite, dict) and favorite["isFavorite"] is True
            tag = assert_status(
                client.json(
                    "POST",
                    "/api/v1/tags",
                    {"name": "http-e2e"},
                    bearer=device_token,
                ),
                201,
                "create tag",
            )
            assert isinstance(tag, dict)
            assert_status(
                client.json(
                    "POST",
                    f"/api/v1/tags/{tag['id']}/media/{media_id}",
                    {},
                    bearer=device_token,
                ),
                200,
                "add tag relation",
            )

            changes_after_favorite = assert_status(
                client.json("GET", "/api/v1/changes?limit=100", bearer=device_token),
                200,
                "list changes after favorite",
            )
            favorite_events = [
                item
                for item in changes_after_favorite["items"]
                if item["entity"] == "media"
                and item["entityId"] == media_id
                and item["data"].get("isFavorite") is True
            ]
            if not favorite_events:
                raise AcceptanceError("favorite upsert change was not propagated")

            upload_bytes = b"real-http-upload\n"
            upload_hash = hashlib.sha256(upload_bytes).hexdigest()
            status, _, body = client.request(
                "POST",
                "/api/v1/media/upload",
                body=upload_bytes,
                headers={
                    "Content-Type": "application/octet-stream",
                    "X-Expected-Size": str(len(upload_bytes)),
                    "X-Expected-SHA256": upload_hash,
                    "X-File-Name": "e2e.bin",
                    "X-Mime-Type": "application/octet-stream",
                },
                bearer=device_token,
            )
            if status != 200:
                raise AcceptanceError(f"stream upload failed: {status}, {body[:200]!r}")
            upload_result = json.loads(body)
            if not str(upload_result.get("path", "")).startswith("nested/uploads/"):
                raise AcceptanceError(
                    f"upload did not land in the user library: {upload_result}"
                )

            forbidden = client.json(
                "GET",
                "/api/v1/admin/storage",
                bearer=device_token,
            )
            assert_status(forbidden, 401, "device/admin isolation")
            assert_status(client.json("GET", "/api/v1/media"), 401, "unauthenticated media")

            alternate_root = root / "alternate-media"
            alternate_root.mkdir()
            assert_status(
                client.json(
                    "PATCH",
                    "/api/v1/admin/storage",
                    {"rootPath": str(alternate_root)},
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                200,
                "change storage root",
            )
            stale_media = assert_status(
                client.json("GET", "/api/v1/media?limit=10", bearer=device_token),
                200,
                "hide stale media after storage switch",
            )
            if isinstance(stale_media, dict) and stale_media["items"]:
                raise AcceptanceError(
                    "media indexed under the previous storage root remained visible"
                )
            assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/storage/test",
                    {},
                    bearer=admin_token,
                    csrf=admin_csrf,
                ),
                200,
                "test alternate storage",
            )
        finally:
            stop_server(process)

        process, client = start_server(root, log_file)
        try:
            assert_status(client.json("GET", "/api/v1/health"), 200, "health after restart")
            restarted_login = assert_status(
                client.json("POST", "/api/v1/admin/session", {"password": PASSWORD}),
                200,
                "admin login after restart",
            )
            assert isinstance(restarted_login, dict)
            restarted_admin_token = restarted_login["token"]
            restarted_admin_csrf = restarted_login["csrfToken"]
            persisted_storage = assert_status(
                client.json(
                    "GET",
                    "/api/v1/admin/storage",
                    bearer=restarted_admin_token,
                ),
                200,
                "persisted storage root after restart",
            )
            if (
                not isinstance(persisted_storage, dict)
                or persisted_storage["rootPath"] != str(alternate_root.resolve())
            ):
                raise AcceptanceError(
                    f"storage root was not persisted across restart: {persisted_storage}"
                )
            assert_status(
                client.json(
                    "PATCH",
                    "/api/v1/admin/storage",
                    {"rootPath": str(media_root)},
                    bearer=restarted_admin_token,
                    csrf=restarted_admin_csrf,
                ),
                200,
                "restore storage root",
            )
            rescan = assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/jobs/scan",
                    bearer=restarted_admin_token,
                    csrf=restarted_admin_csrf,
                ),
                202,
                "rescan restored storage root",
            )
            assert isinstance(rescan, dict)
            poll_job(client, rescan["id"], bearer=restarted_admin_token, admin=True)
            restarted_media = assert_status(
                client.json("GET", "/api/v1/media?limit=10", bearer=device_token),
                200,
                "media after restart",
            )
            assert isinstance(restarted_media, dict) and restarted_media["items"]
        finally:
            stop_server(process)

        print("real HTTP E2E passed: setup, user+library binding, per-user pairing, scan,")
        print("bootstrap, changes, media, range, thumbnail, tag relation, stream upload,")
        print("authorization, storage switch, restart")


def main() -> int:
    try:
        run_acceptance()
    except AcceptanceError as error:
        print(f"real HTTP E2E failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
