#!/usr/bin/env python3
"""对账期间的写请求延迟探针（缺陷 usKyTJguvfSX）。

复现线上形态：媒体库里有 1 个真实文件 + N 条「磁盘上已消失」的位置记录；
后管触发扫描后，对账会清理这些缺失位置。探针在扫描期间持续打写请求
（标签写入 + bootstrap 启动），记录最长耗时与失败次数。

```bash
# 修复后（当前代码）
YOUYOU_RECONCILE_PROBE_LOCATIONS=100000 python3 scripts/scan_reconcile_probe.py
# 修复前基线：把 YOUYOU_SERVER_BINARY 指向修复前的二进制即可（同一数据集）
YOUYOU_SERVER_BINARY=... python3 scripts/scan_reconcile_probe.py
```

修复前（单事务对账）实测：收藏请求等待 5.2s 后 `database is locked`、
`POST /api/v1/sync/bootstrap` 500；修复后：写请求最长等待 0.7s 内、零失败。
脚本自行断言：写请求无失败且最长耗时低于 `YOUYOU_RECONCILE_PROBE_WRITE_MAX_MS`（默认 3000ms）。
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import urllib.error

from acceptance_benchmark import authenticate, wait_for_job
from e2e_http import PNG_1X1, AcceptanceError, HttpClient, assert_status, start_server, stop_server

MEDIA_COUNT = int(os.environ.get("YOUYOU_RECONCILE_PROBE_LOCATIONS", "100000"))
WRITE_MAX_MS = float(os.environ.get("YOUYOU_RECONCILE_PROBE_WRITE_MAX_MS", "3000"))
WRITE_INTERVAL_SECONDS = float(os.environ.get("YOUYOU_RECONCILE_PROBE_WRITE_INTERVAL", "0.05"))
SCAN_TIMEOUT_SECONDS = float(os.environ.get("YOUYOU_RECONCILE_PROBE_SCAN_TIMEOUT", "600"))
OUTPUT = os.environ.get("YOUYOU_RECONCILE_PROBE_OUTPUT")


def populate_missing_locations(database: Path, owner_user_id: int) -> None:
    """N 条磁盘上不存在的位置（time_version=1，避免触发历史回填）。"""
    connection = sqlite3.connect(database)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        now = int(time.time() * 1000)
        for start in range(0, MEDIA_COUNT, 2_000):
            assets, locations = [], []
            for index in range(start, min(start + 2_000, MEDIA_COUNT)):
                suffix = f"{index:06d}"
                assets.append(
                    (
                        f"probe-media-{suffix}",
                        f"synthetic-blob-{suffix}",
                        f"{suffix}.jpg",
                        now - index,
                        owner_user_id,
                    )
                )
                locations.append(
                    (
                        f"probe-location-{suffix}",
                        f"probe-media-{suffix}",
                        f"library/{suffix}.jpg",
                        f"{suffix}.jpg",
                    )
                )
            connection.executemany(
                "INSERT INTO content_blobs (id, hash_algorithm, content_hash, size, created_at) VALUES (?, 'sha256', ?, 1, ?)",
                [(f"synthetic-blob-{index:06d}", hashlib.sha256(f"probe-{index}".encode()).hexdigest(), now) for index in range(start, min(start + 2_000, MEDIA_COUNT))],
            )
            connection.executemany(
                """
                INSERT INTO media_assets
                    (id, blob_id, identity_state, name, mime_type, is_video, version,
                     created_at, updated_at, sort_at, owner_user_id, is_favorite, time_version)
                VALUES (?, ?, 'verified', ?, 'image/jpeg', 0, 1, ?, ?, ?, ?, 0, 1)
                """,
                [(row[0], row[1], row[2], row[3], row[3], row[3], row[4]) for row in assets],
            )
            connection.executemany(
                """
                INSERT INTO media_locations
                    (id, media_asset_id, storage_id, normalized_path, file_name, size,
                     modified_at, hash_state, observed_size, observed_mtime, created_at, updated_at)
                VALUES (?, ?, 'local', ?, ?, 1, ?, 'verified', 1, ?, ?, ?)
                """,
                [(row[0], row[1], row[2], row[3], now, now, now, now) for row in locations],
            )
            connection.commit()
    finally:
        connection.close()


def create_tag(client: HttpClient, token: str, index: int) -> tuple[bool, float, str]:
    """与媒体状态无关的纯写请求：对账把合成媒体墓碑后仍可继续测写延迟。"""
    started = time.perf_counter()
    try:
        status, _, body = client.json(
            "POST", "/api/v1/tags", {"name": f"probe-{index:06d}"}, bearer=token
        )
        elapsed = (time.perf_counter() - started) * 1000
        if status not in {200, 201}:
            return False, elapsed, f"tag {index}: HTTP {status} {str(body)[:120]}"
        return True, elapsed, ""
    except (urllib.error.URLError, OSError) as error:
        return False, (time.perf_counter() - started) * 1000, str(error)


def run_probe() -> dict:
    temporary = tempfile.TemporaryDirectory(prefix="youyou-reconcile-")
    if os.environ.get("YOUYOU_KEEP_PROBE") == "1":
        temporary.cleanup = lambda: None
        temporary._finalizer.detach()
        print(f"probe temporary directory: {temporary.name}", file=sys.stderr)
    with temporary:
        root = Path(temporary.name)
        data_dir = root / "data"
        media_root = root / "media"
        log_file = root / "server.log"
        library = media_root / "library"
        library.mkdir(parents=True)
        # 一个真实文件：范围内不是 0 发现，对账才会执行（否则空卷保护会跳过）。
        (library / "kept.png").write_bytes(PNG_1X1)

        process, client = start_server(root, log_file)
        try:
            admin_token, csrf, user_id, device_tokens = authenticate(client, data_dir)
        finally:
            stop_server(process)

        populate_missing_locations(data_dir / "youyou.db", user_id)
        process, client = start_server(root, log_file)
        device_token = device_tokens[0]
        try:
            scan = assert_status(
                client.json("POST", "/api/v1/admin/jobs/scan", {}, bearer=admin_token, csrf=csrf),
                202,
                "start scan",
            )
            scan_job_id = str(scan["id"])

            writes: list[float] = []
            failures: list[str] = []
            bootstrap_status = None
            deadline = time.monotonic() + SCAN_TIMEOUT_SECONDS
            index = 0
            while time.monotonic() < deadline:
                ok, elapsed, error = create_tag(client, device_token, index)
                writes.append(elapsed)
                if not ok:
                    failures.append(error)
                if index == 5:
                    # 原始事故就是在对账期间启动 bootstrap 时 500（等满 busy_timeout）。
                    status, _, body = client.json(
                        "POST", "/api/v1/sync/bootstrap", {}, bearer=device_token
                    )
                    bootstrap_status = {
                        "httpStatus": status,
                        "message": body.get("message") if isinstance(body, dict) else None,
                    }
                index += 1
                job = assert_status(
                    client.json(
                        "GET", f"/api/v1/admin/jobs/{scan_job_id}", bearer=admin_token
                    ),
                    200,
                    "poll scan",
                )
                if job["status"] not in {"queued", "running"}:
                    break
                time.sleep(WRITE_INTERVAL_SECONDS)
            else:
                raise AcceptanceError("scan did not finish within the probe window")

            scan_job = wait_for_job(client, scan_job_id, admin_token, admin=True)
            if scan_job.get("status") != "succeeded":
                raise AcceptanceError(f"scan did not succeed: {scan_job}")

            connection = sqlite3.connect(data_dir / "youyou.db")
            try:
                remaining_locations = connection.execute(
                    "SELECT COUNT(*) FROM media_locations"
                ).fetchone()[0]
                tombstoned = connection.execute(
                    "SELECT COUNT(*) FROM media_assets WHERE identity_state = 'tombstoned'"
                ).fetchone()[0]
            finally:
                connection.close()
        except Exception:
            print(log_file.read_text(errors="replace"), file=sys.stderr)
            raise
        finally:
            stop_server(process)

        writes.sort()
        report = {
            "locations": MEDIA_COUNT,
            "writeAttempts": len(writes),
            "writeFailures": failures[:5],
            "writeMaxMs": round(writes[-1], 2) if writes else 0.0,
            "writeP95Ms": round(writes[int(len(writes) * 0.95) - 1], 2) if writes else 0.0,
            "bootstrapStart": bootstrap_status,
            "scanJobStatus": scan_job.get("status"),
            "remainingLocations": remaining_locations,
            "tombstonedAssets": tombstoned,
            "thresholds": {"writeMaxMs": WRITE_MAX_MS, "enforced": True},
        }
        violations = []
        if failures:
            violations.append("write failures")
        if report["writeMaxMs"] > WRITE_MAX_MS:
            violations.append("write latency")
        if bootstrap_status and bootstrap_status["httpStatus"] >= 500:
            violations.append("bootstrap start 5xx")
        report["thresholds"]["violations"] = violations
        report["thresholds"]["passed"] = not violations
        encoded = json.dumps(report, ensure_ascii=False, indent=2)
        print(encoded)
        if OUTPUT:
            Path(OUTPUT).write_text(encoded + "\n")
        if violations:
            raise AcceptanceError("reconcile probe thresholds exceeded: " + ", ".join(violations))
        return report


def main() -> int:
    try:
        run_probe()
    except (AcceptanceError, sqlite3.Error) as error:
        print(f"reconcile probe failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
