#!/usr/bin/env python3
"""Exercise deletion and restore against a real Docker server and bind mounts."""

from __future__ import annotations

import hashlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

from e2e_http import AcceptanceError, HttpClient, PASSWORD, PNG_1X1, assert_status, find_free_port, poll_job


IMAGE = os.environ.get("YOUYOU_TRASH_TEST_IMAGE", "youyou-server:delete-regression")


def verify_layout(split_mount: bool, obsolete_env: bool = False, mount_trash: bool = False) -> None:
    with tempfile.TemporaryDirectory(prefix="youyou-trash-docker-") as temporary:
        root = Path(temporary)
        (root / "media").mkdir()
        name = f"youyou-trash-test-{uuid.uuid4().hex[:12]}"
        port = find_free_port()
        command = [
            "docker", "run", "--detach", "--rm", "--name", name,
            "--user", f"{os.getuid()}:{os.getgid()}",
            "--publish", f"127.0.0.1:{port}:8989",
            "--env", "YOUYOU_SERVER_DIR=/srv/youyou",
            "--env", "YOUYOU_SERVER_PORT=8989",
            "--env", "RUST_LOG=warn",
            "--volume", f"{root}:/srv/youyou",
        ]
        if obsolete_env:
            command += ["--env", "YOUYOU_TRASH_DIR=trash"]
        if split_mount:
            command += ["--volume", f"{root / 'media'}:/srv/youyou/media"]
        if mount_trash:
            (root / "isolated-trash").mkdir()
            command += ["--volume", f"{root / 'isolated-trash'}:/srv/youyou/media/.youyou-trash"]
        subprocess.run(command + [IMAGE], check=True, capture_output=True)
        try:
            client = HttpClient(f"http://127.0.0.1:{port}")
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    if client.request("GET", "/api/v1/health")[0] == 200:
                        break
                except (AcceptanceError, OSError, http.client.HTTPException):
                    pass
                time.sleep(0.1)
            else:
                log = subprocess.run(["docker", "logs", name], capture_output=True, text=True)
                raise AcceptanceError(f"Docker server did not become healthy: {log.stderr}")

            setup_token = (root / "data/bootstrap/setup-token").read_text().strip()
            assert_status(client.json("POST", "/api/v1/admin/setup", {
                "setupToken": setup_token, "password": PASSWORD,
            }), 204, "set up test administrator")
            session = assert_status(client.json("POST", "/api/v1/admin/session", {
                "password": PASSWORD,
            }), 200, "sign in test administrator")
            admin, csrf = session["token"], session["csrfToken"]
            user = assert_status(client.json("POST", "/api/v1/admin/users", {
                "name": "trash-regression", "libraryName": "library",
            }, bearer=admin, csrf=csrf), 201, "create test library")
            pairing = assert_status(client.json(
                "POST", f"/api/v1/admin/users/{user['id']}/pairing-codes", {},
                bearer=admin, csrf=csrf,
            ), 200, "create test pairing code")
            device = assert_status(client.json("POST", "/api/v1/pairing", {
                "code": pairing["code"], "deviceName": "trash-regression",
            }), 200, "pair test device")["token"]

            content = PNG_1X1 + b"unknown-photo-deletion-regression"
            upload = json.loads(assert_status(client.request(
                "POST", "/api/v1/media/upload", body=content, bearer=device,
                headers={
                    "X-Expected-Size": str(len(content)),
                    "X-Expected-SHA256": hashlib.sha256(content).hexdigest(),
                    "X-File-Name": "mmexport.png",
                    "X-Mime-Type": "image/png",
                },
            ), 200, "upload photo without media time"))
            assert upload["path"].startswith("library/uploads/unknown/IMG_nodate_")
            original = root / "media" / upload["path"]
            assert original.read_bytes() == content
            jobs = assert_status(client.json("GET", "/api/v1/admin/jobs", bearer=admin),
                                 200, "read jobs")
            assert not any(job["status"] in {"queued", "running", "interrupted"}
                           for job in jobs["items"])
            capability = assert_status(client.json("GET", "/api/v1/admin/trash", bearer=admin),
                                       200, "read trash capability")["capability"]
            deleted = client.request(
                "DELETE", f"/api/v1/admin/media-library/entries?kind=media&target={upload['mediaId']}",
                bearer=admin, csrf=csrf,
            )
            if mount_trash:
                error = json.loads(assert_status(deleted, 409, "reject separately mounted trash"))
                assert not capability["enabled"], capability
                assert "挂载" in error["message"], error
                assert original.read_bytes() == content
                print("Docker separately mounted trash: blocked, original preserved")
                return
            assert capability["enabled"], capability
            assert_status(deleted, 204, "delete unknown photo")
            assert not original.exists()
            scan = assert_status(client.json("POST", "/api/v1/admin/jobs/scan",
                                 bearer=admin, csrf=csrf), 202, "rescan with internal trash")
            poll_job(client, scan["id"], bearer=admin, admin=True)
            media = assert_status(client.json("GET", "/api/v1/media", bearer=device),
                                  200, "read media after rescan")
            assert not media["items"], "deleted photo was indexed from trash"
            trash = assert_status(client.json("GET", "/api/v1/admin/trash", bearer=admin),
                                  200, "read deleted photo")
            entries = [entry for entry in trash["entries"] if entry["mediaId"] == upload["mediaId"]]
            assert len(entries) == 1
            restored = assert_status(client.json("POST", "/api/v1/admin/trash/restore", {
                "mediaIds": [upload["mediaId"]],
            }, bearer=admin, csrf=csrf), 200, "restore unknown photo")
            assert restored["restored"] == [upload["mediaId"]]
            assert not restored["failures"]
            assert original.read_bytes() == content
            assert not list(root.rglob(".youyou-trash-probe-*")), "probe files were not cleaned up"
            assert (root / "media/.youyou-trash").is_dir()
            assert not (root / "trash").exists(), "obsolete setting created an external trash directory"
            print(f"Docker internal trash (split_mount={split_mount}): uploaded, deleted, restored with identical bytes")
        finally:
            subprocess.run(["docker", "rm", "--force", "--volumes", name],
                           check=False, capture_output=True)


if __name__ == "__main__":
    verify_layout(False)
    verify_layout(True)
    verify_layout(True, obsolete_env=True)
    verify_layout(False, mount_trash=True)
    print("real Docker trash E2E passed")
