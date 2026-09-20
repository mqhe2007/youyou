#!/usr/bin/env python3
"""Synthetic scale and bootstrap acceptance checks.

The benchmark intentionally talks to a real server process over HTTP.  It is
kept as a script (rather than a unit test) so that the result can be compared
between machines and after server changes.  Set ``YOUYOU_BENCHMARK_ENFORCE=1``
to turn the documented guardrails into a non-zero exit status.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time

from e2e_http import (
    AcceptanceError,
    HttpClient,
    assert_status,
    start_server,
    stop_server,
)


MEDIA_COUNT = int(os.environ.get("YOUYOU_BENCHMARK_MEDIA_COUNT", "100000"))
BOOTSTRAP_TIMEOUT_SECONDS = float(
    os.environ.get("YOUYOU_BENCHMARK_BOOTSTRAP_TIMEOUT_SECONDS", "360")
)
BOOTSTRAP_MAX_SECONDS = float(
    os.environ.get("YOUYOU_BENCHMARK_BOOTSTRAP_MAX_SECONDS", "300")
)
BOOTSTRAP_MAX_RSS_MB = float(
    os.environ.get("YOUYOU_BENCHMARK_BOOTSTRAP_MAX_RSS_MB", "512")
)
WRITE_P95_MAX_MS = float(
    os.environ.get("YOUYOU_BENCHMARK_WRITE_P95_MAX_MS", "5000")
)
DB_MAX_MB = float(os.environ.get("YOUYOU_BENCHMARK_DB_MAX_MB", "512"))
ENFORCE_THRESHOLDS = os.environ.get("YOUYOU_BENCHMARK_ENFORCE") == "1"
CONCURRENT_WRITE_COUNT = int(
    os.environ.get("YOUYOU_BENCHMARK_CONCURRENT_WRITE_COUNT", "8")
)
CONCURRENT_WRITE_WORKERS = int(
    os.environ.get("YOUYOU_BENCHMARK_CONCURRENT_WRITE_WORKERS", "2")
)
CONCURRENT_WRITE_DELAY_SECONDS = float(
    os.environ.get("YOUYOU_BENCHMARK_CONCURRENT_WRITE_DELAY_SECONDS", "0.1")
)


class ProcessSampler:
    """Sample the server RSS without adding a runtime dependency such as psutil."""

    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.peak_rss_bytes = 0
        self.samples = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=2)

    def _run(self) -> None:
        while not self._stop.is_set():
            rss = process_rss_bytes(self.pid)
            if rss is not None:
                self.peak_rss_bytes = max(self.peak_rss_bytes, rss)
                self.samples += 1
            self._stop.wait(0.05)


def process_rss_bytes(pid: int) -> int | None:
    try:
        output = subprocess.check_output(
            ["ps", "-o", "rss=", "-p", str(pid)],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        if not output:
            return None
        # macOS and Linux both report RSS in KiB for this ps invocation.
        return int(output.splitlines()[-1].strip()) * 1024
    except (OSError, ValueError, subprocess.CalledProcessError):
        return None


def populate_media(database: Path, owner_user_id: int) -> None:
    connection = sqlite3.connect(database)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        now = int(time.time() * 1000)
        batch_size = 2_000
        for start in range(0, MEDIA_COUNT, batch_size):
            content_rows = []
            media_rows = []
            location_rows = []
            for index in range(start, min(start + batch_size, MEDIA_COUNT)):
                suffix = f"{index:06d}"
                digest = hashlib.sha256(f"synthetic-{index}".encode()).hexdigest()
                blob_id = f"synthetic-blob-{suffix}"
                media_id = f"synthetic-media-{suffix}"
                location_id = f"synthetic-location-{suffix}"
                path = f"library/{suffix}.jpg"
                modified = now - index
                content_rows.append((blob_id, digest, now))
                media_rows.append(
                    (
                        media_id,
                        blob_id,
                        f"{suffix}.jpg",
                        "image/jpeg",
                        owner_user_id,
                        modified,
                    )
                )
                location_rows.append(
                    (location_id, media_id, path, f"{suffix}.jpg", modified)
                )
            connection.executemany(
                """
                INSERT INTO content_blobs
                    (id, hash_algorithm, content_hash, size, created_at)
                VALUES (?, 'sha256', ?, 1, ?)
                """,
                content_rows,
            )
            connection.executemany(
                """
                INSERT INTO media_assets
                    (id, blob_id, identity_state, name, mime_type, is_video,
                     version, created_at, updated_at, sort_at, owner_user_id,
                     is_favorite)
                VALUES (?, ?, 'verified', ?, ?, 0, 1, ?, ?, ?, ?, 0)
                """,
                [
                    (row[0], row[1], row[2], row[3], row[5], row[5], row[5], row[4])
                    for row in media_rows
                ],
            )
            connection.executemany(
                """
                INSERT INTO media_locations
                    (id, media_asset_id, storage_id, normalized_path, file_name,
                     size, modified_at, hash_state, observed_size, observed_mtime,
                     created_at, updated_at)
                VALUES (?, ?, 'local', ?, ?, 1, ?, 'verified', 1, ?, ?, ?)
                """,
                [
                    (*row[:5], row[4], row[4], row[4])
                    for row in location_rows
                ],
            )
            connection.commit()
    finally:
        connection.close()


def authenticate(client: HttpClient, data_dir: Path) -> tuple[str, str, int, list[str]]:
    token_path = data_dir / "bootstrap" / "setup-token"
    setup_token = token_path.read_text().strip()
    assert_status(
        client.json(
            "POST",
            "/api/v1/admin/setup",
            {"setupToken": setup_token, "password": "benchmark password 123"},
        ),
        204,
        "benchmark admin setup",
    )
    login = assert_status(
        client.json(
            "POST",
            "/api/v1/admin/session",
            {"password": "benchmark password 123"},
        ),
        200,
        "benchmark admin login",
    )
    assert isinstance(login, dict)
    admin_token = login["token"]
    csrf = login["csrfToken"]
    user = assert_status(
        client.json(
            "POST",
            "/api/v1/admin/users",
            {"name": "benchmark-user", "libraryName": "library"},
            bearer=admin_token,
            csrf=csrf,
        ),
        201,
        "benchmark user creation",
    )
    assert isinstance(user, dict)
    user_id = int(user["id"])

    def pair_device(device_name: str) -> str:
        pairing = assert_status(
            client.json(
                "POST",
                f"/api/v1/admin/users/{user_id}/pairing-codes",
                {},
                bearer=admin_token,
                csrf=csrf,
            ),
            200,
            "benchmark pairing code",
        )
        assert isinstance(pairing, dict)
        device = assert_status(
            client.json(
                "POST",
                "/api/v1/pairing",
                {"code": pairing["code"], "deviceName": device_name},
            ),
            200,
            "benchmark device pairing",
        )
        assert isinstance(device, dict)
        return str(device["token"])

    device_tokens = [pair_device(f"benchmark-client-{index}") for index in range(3)]
    return admin_token, csrf, user_id, device_tokens


def start_bootstrap(client: HttpClient, device_token: str) -> dict:
    started = time.perf_counter()
    bootstrap = assert_status(
        client.json("POST", "/api/v1/sync/bootstrap", {}, bearer=device_token),
        202,
        "start bootstrap",
    )
    assert isinstance(bootstrap, dict)
    return {
        "snapshotId": str(bootstrap["snapshotId"]),
        "jobId": str(bootstrap["jobId"]),
        "startedAt": started,
    }


def wait_for_bootstrap(
    client: HttpClient,
    device_token: str,
    process_pid: int,
    bootstrap: dict,
    *,
    timeout_seconds: float = BOOTSTRAP_TIMEOUT_SECONDS,
) -> dict:
    started = float(bootstrap["startedAt"])
    snapshot_id = str(bootstrap["snapshotId"])
    job_id = str(bootstrap["jobId"])
    sampler = ProcessSampler(process_pid)
    sampler.start()
    status = None
    ready_at = None
    try:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            status = assert_status(
                client.json(
                    "GET",
                    f"/api/v1/sync/bootstrap/{snapshot_id}",
                    bearer=device_token,
                ),
                200,
                "poll bootstrap",
            )
            assert isinstance(status, dict)
            if status["state"] in {"ready", "failed", "expired"}:
                ready_at = time.perf_counter()
                break
            time.sleep(0.05)
        if not isinstance(status, dict) or status.get("state") != "ready":
            raise AcceptanceError(f"bootstrap did not become ready: {status}")
    finally:
        sampler.stop()
    elapsed_ms = ((ready_at or time.perf_counter()) - started) * 1000
    job = assert_status(
        client.json("GET", f"/api/v1/jobs/{job_id}", bearer=device_token),
        200,
        "bootstrap job",
    )
    assert isinstance(job, dict)
    if job.get("status") != "succeeded":
        raise AcceptanceError(f"bootstrap job did not succeed: {job}")
    return {
        "snapshotId": snapshot_id,
        "jobId": job_id,
        "status": status,
        "job": job,
        "elapsedMs": round(elapsed_ms, 2),
        "peakRssBytes": sampler.peak_rss_bytes,
        "rssSamples": sampler.samples,
        "startedAt": started,
        "readyAt": ready_at or time.perf_counter(),
    }


def wait_for_job(
    client: HttpClient,
    job_id: str,
    bearer: str,
    *,
    admin: bool = False,
    timeout_seconds: float = BOOTSTRAP_TIMEOUT_SECONDS,
) -> dict:
    path = f"/api/v1/admin/jobs/{job_id}" if admin else f"/api/v1/jobs/{job_id}"
    deadline = time.monotonic() + timeout_seconds
    job = None
    while time.monotonic() < deadline:
        job = assert_status(client.json("GET", path, bearer=bearer), 200, "poll job")
        assert isinstance(job, dict)
        if job.get("status") in {"succeeded", "failed", "cancelled", "interrupted"}:
            return job
        time.sleep(0.05)
    raise AcceptanceError(f"job {job_id} did not finish within {timeout_seconds}s: {job}")


def database_size_bytes(data_dir: Path) -> int:
    return sum(
        path.stat().st_size
        for path in data_dir.glob("youyou.db*")
        if path.is_file()
    )


def write_favorite(client: HttpClient, device_token: str, index: int) -> dict:
    media_id = f"synthetic-media-{index:06d}"
    started = time.perf_counter()
    result = assert_status(
        client.json(
            "POST",
            f"/api/v1/media/{media_id}/favorite",
            {"isFavorite": True},
            bearer=device_token,
        ),
        200,
        f"favorite update {media_id}",
    )
    assert isinstance(result, dict) and result.get("isFavorite") is True
    return {
        "mediaId": media_id,
        "startedAt": started,
        "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
    }


def percentile(values: list[float], rank: float) -> float:
    values = sorted(values)
    position = (len(values) - 1) * rank
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    fraction = position - lower
    return values[lower] + (values[upper] - values[lower]) * fraction


def run_benchmark() -> None:
    report = None
    temporary = tempfile.TemporaryDirectory(prefix="youyou-scale-")
    if os.environ.get("YOUYOU_KEEP_BENCHMARK") == "1":
        temporary.cleanup = lambda: None
        temporary._finalizer.detach()
        print(f"benchmark temporary directory: {temporary.name}", file=sys.stderr)
    with temporary:
        root = Path(temporary.name)
        data_dir = root / "data"
        media_root = root / "media"
        log_file = root / "server.log"
        (media_root / "library").mkdir(parents=True)

        process, client = start_server(root, log_file)
        try:
            admin_token, csrf, user_id, device_tokens = authenticate(client, data_dir)
        finally:
            stop_server(process)

        # Populate after the API has created the user/library binding.  This
        # keeps the benchmark data on the same per-user path as real scans.
        populate_media(data_dir / "youyou.db", user_id)
        process, client = start_server(root, log_file)
        primary_token = device_tokens[0]
        try:
            database_before_bootstrap = database_size_bytes(data_dir)

            # Baseline pagination is retained so the report remains comparable
            # with the previous scale benchmark.
            timings = []
            for _ in range(30):
                started = time.perf_counter()
                page = assert_status(
                    client.json(
                        "GET",
                        "/api/v1/media?limit=100",
                        bearer=primary_token,
                    ),
                    200,
                    "benchmark media page",
                )
                timings.append((time.perf_counter() - started) * 1000)
                assert isinstance(page, dict) and len(page["items"]) == min(100, MEDIA_COUNT)
            p95 = percentile(timings, 0.95)

            def concurrent_request(path: str) -> dict:
                status, _, body = client.json("GET", path, bearer=primary_token)
                value = assert_status((status, {}, body), 200, f"concurrent {path}")
                assert isinstance(value, dict)
                return value

            with ThreadPoolExecutor(max_workers=3) as executor:
                results = list(
                    executor.map(
                        concurrent_request,
                        ["/api/v1/media?limit=100"] * 3,
                    )
                )
            if any(len(result["items"]) != min(100, MEDIA_COUNT) for result in results):
                raise AcceptanceError("three concurrent clients did not receive full pages")

            # A real bootstrap, with RSS sampling around the server process.
            bootstrap_ref = start_bootstrap(client, primary_token)
            if CONCURRENT_WRITE_COUNT:
                time.sleep(CONCURRENT_WRITE_DELAY_SECONDS)
                with ThreadPoolExecutor(max_workers=CONCURRENT_WRITE_WORKERS) as executor:
                    write_futures = [
                        executor.submit(write_favorite, client, primary_token, index)
                        for index in range(CONCURRENT_WRITE_COUNT)
                    ]
                    try:
                        bootstrap_result = wait_for_bootstrap(
                            client, primary_token, process.pid, bootstrap_ref
                        )
                    except Exception:
                        print(log_file.read_text(errors="replace"), file=sys.stderr)
                        raise
                    write_results = [future.result() for future in write_futures]
            else:
                try:
                    bootstrap_result = wait_for_bootstrap(
                        client, primary_token, process.pid, bootstrap_ref
                    )
                except Exception:
                    print(log_file.read_text(errors="replace"), file=sys.stderr)
                    raise
                write_results = []
            if bootstrap_result["job"].get("total") != MEDIA_COUNT:
                raise AcceptanceError(
                    "bootstrap materialized an unexpected number of media items: "
                    f"{bootstrap_result['job'].get('total')} != {MEDIA_COUNT}"
                )
            if write_results and not any(
                result["startedAt"] < bootstrap_result["readyAt"]
                for result in write_results
            ):
                raise AcceptanceError("favorite writes did not overlap bootstrap")

            # The snapshot and change cursor must both remain usable after
            # concurrent writes.  The final media endpoint is the authoritative
            # value even when a write races the snapshot transaction.
            if write_results:
                favorite_check = assert_status(
                    client.json(
                        "GET",
                        "/api/v1/media/synthetic-media-000000",
                        bearer=primary_token,
                    ),
                    200,
                    "favorite consistency check",
                )
                assert isinstance(favorite_check, dict)
                if favorite_check.get("isFavorite") is not True:
                    raise AcceptanceError("favorite write was lost during bootstrap")

            # Multiple devices initialize from the same immutable snapshot at
            # once.  The snapshot is user-scoped (not device-scoped), so this
            # tests concurrent status/page reads without materializing three
            # redundant 100k-row copies in the database.
            def initialize_client(token: str) -> dict:
                started = time.perf_counter()
                status = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/sync/bootstrap/{bootstrap_ref['snapshotId']}",
                        bearer=token,
                    ),
                    200,
                    "multi-client bootstrap status",
                )
                page = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/sync/bootstrap/{bootstrap_ref['snapshotId']}/media?limit=100",
                        bearer=token,
                    ),
                    200,
                    "multi-client bootstrap page",
                )
                assert isinstance(status, dict) and status.get("state") == "ready"
                assert isinstance(page, dict) and len(page.get("items", [])) == 100
                return {"elapsedMs": round((time.perf_counter() - started) * 1000, 2)}

            with ThreadPoolExecutor(max_workers=len(device_tokens)) as executor:
                results = list(executor.map(initialize_client, device_tokens))

            # Cancellation is requested immediately after enqueueing.  The
            # job may already be running (or may still be queued at this
            # scale); both paths must converge to a persisted cancellation.
            cancellation_ref = start_bootstrap(client, primary_token)
            cancellation_job = assert_status(
                client.json(
                    "GET",
                    f"/api/v1/admin/jobs/{cancellation_ref['jobId']}",
                    bearer=admin_token,
                ),
                200,
                "poll cancellation job",
            )
            assert isinstance(cancellation_job, dict)
            running_seen = cancellation_job.get("status") == "running"
            cancel_response = client.json(
                "POST",
                f"/api/v1/jobs/{cancellation_ref['jobId']}/cancel",
                {},
                bearer=admin_token,
                csrf=csrf,
            )
            cancel_status = cancel_response[0]
            cancellation_job = wait_for_job(
                client,
                cancellation_ref["jobId"],
                admin_token,
                admin=True,
            )

            # Restart recovery: interrupt a running bootstrap, then let the
            # real startup recovery loop resume the persisted snapshot.
            recovery_ref = start_bootstrap(client, primary_token)
            recovery_running = False
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                recovery_job = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/admin/jobs/{recovery_ref['jobId']}",
                        bearer=admin_token,
                    ),
                    200,
                    "poll recovery job",
                )
                assert isinstance(recovery_job, dict)
                if recovery_job["status"] == "running":
                    recovery_running = True
                    break
                if recovery_job["status"] in {"succeeded", "failed", "cancelled"}:
                    break
                time.sleep(0.02)
            stop_server(process)
            process, client = start_server(root, log_file)
            if recovery_running:
                recovery_result = wait_for_bootstrap(
                    client,
                    primary_token,
                    process.pid,
                    recovery_ref,
                )
                recovery_state = "resumed-after-interruption"
            else:
                recovery_result = wait_for_job(
                    client,
                    recovery_ref["jobId"],
                    primary_token,
                )
                recovery_state = "completed-before-restart"
            if isinstance(recovery_result, dict) and recovery_result.get("job"):
                if recovery_result["job"].get("total") != MEDIA_COUNT:
                    raise AcceptanceError("restart recovery produced an incomplete snapshot")

            backup = assert_status(
                client.json(
                    "POST",
                    "/api/v1/admin/backups",
                    {},
                    bearer=admin_token,
                    csrf=csrf,
                ),
                202,
                "start scale backup",
            )
            assert isinstance(backup, dict)
            job_id = backup["jobId"]
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                job = assert_status(
                    client.json(
                        "GET",
                        f"/api/v1/admin/jobs/{job_id}",
                        bearer=admin_token,
                    ),
                    200,
                    "poll scale backup",
                )
                assert isinstance(job, dict)
                if job["status"] in {"succeeded", "failed", "cancelled"}:
                    if job["status"] != "succeeded":
                        raise AcceptanceError(f"scale backup ended as {job}")
                    break
                time.sleep(0.2)
            else:
                raise AcceptanceError("scale backup exceeded 90 seconds")
        finally:
            stop_server(process)

        db_size = database_size_bytes(data_dir)
        write_p95 = (
            percentile([item["elapsedMs"] for item in write_results], 0.95)
            if write_results
            else 0.0
        )
        report = {
            "mediaCount": MEDIA_COUNT,
            "paginationSamples": 30,
            "paginationP95Ms": round(p95, 2),
            "bootstrap": {
                "elapsedMs": bootstrap_result["elapsedMs"],
                "peakRssMb": round(
                    bootstrap_result["peakRssBytes"] / 1024 / 1024, 2
                ),
                "rssSamples": bootstrap_result["rssSamples"],
                "snapshotRevision": bootstrap_result["status"].get("snapshotRevision"),
                "changesCursor": bootstrap_result["status"].get("changesCursor"),
                "materializedItems": bootstrap_result["job"].get("total"),
            },
            "database": {
                "beforeBootstrapBytes": database_before_bootstrap,
                "afterBootstrapBytes": db_size,
                "afterBootstrapMb": round(db_size / 1024 / 1024, 2),
            },
            "concurrentWrites": {
                "count": len(write_results),
                "p95Ms": round(write_p95, 2) if write_results else None,
                "overlappedBootstrap": bool(write_results),
            },
            "multipleClients": {
                "count": len(device_tokens),
                "elapsedMs": [result["elapsedMs"] for result in results],
                "allMaterialized": True,
            },
            "cancellation": {
                "runningSeen": running_seen,
                "cancelHttpStatus": cancel_status,
                "finalStatus": cancellation_job.get("status"),
                "observed": cancellation_job.get("status") == "cancelled",
            },
            "restartRecovery": {
                "state": recovery_state,
                "elapsedMs": recovery_result.get("elapsedMs")
                if isinstance(recovery_result, dict)
                else None,
            },
            "backup": "succeeded",
            "thresholds": {
                "bootstrapMaxSeconds": BOOTSTRAP_MAX_SECONDS,
                "bootstrapMaxRssMb": BOOTSTRAP_MAX_RSS_MB,
                "concurrentWriteP95MaxMs": WRITE_P95_MAX_MS,
                "databaseMaxMb": DB_MAX_MB,
                "enforced": ENFORCE_THRESHOLDS,
            },
        }
        violations = []
        if bootstrap_result["elapsedMs"] > BOOTSTRAP_MAX_SECONDS * 1000:
            violations.append("bootstrap elapsed time")
        if bootstrap_result["peakRssBytes"] > BOOTSTRAP_MAX_RSS_MB * 1024 * 1024:
            violations.append("bootstrap peak RSS")
        if write_p95 > WRITE_P95_MAX_MS:
            violations.append("concurrent write p95")
        if db_size > DB_MAX_MB * 1024 * 1024:
            violations.append("database size")
        if running_seen and cancel_status == 200 and cancellation_job.get("status") != "cancelled":
            violations.append("bootstrap cancellation")
        report["thresholds"]["violations"] = violations
        report["thresholds"]["passed"] = not violations

        output_path = os.environ.get("YOUYOU_BENCHMARK_OUTPUT")
        encoded = json.dumps(report, ensure_ascii=False, indent=2)
        if output_path:
            Path(output_path).write_text(encoded + "\n")
        print(encoded)
        if ENFORCE_THRESHOLDS and violations:
            raise AcceptanceError("benchmark thresholds exceeded: " + ", ".join(violations))


def main() -> int:
    try:
        run_benchmark()
    except (AcceptanceError, sqlite3.Error) as error:
        print(f"scale acceptance failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
