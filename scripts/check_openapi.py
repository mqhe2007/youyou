#!/usr/bin/env python3
"""Contract sanity check for the code-generated OpenAPI document.

Usage:
    python3 scripts/check_openapi.py <openapi.json>

The OpenAPI JSON is produced at compile time by utoipa from handler
annotations and ToSchema derives. This script verifies that the generated
document contains the expected paths and component schemas, catching
accidental removal of endpoints or response types.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


REQUIRED_PATHS = (
    "/api/v1/health",
    "/api/v1/admin/setup",
    "/api/v1/admin/session",
    "/api/v1/admin/status",
    "/api/v1/admin/devices",
    "/api/v1/admin/devices/{id}/revoke",
    "/api/v1/admin/devices/{id}/rotate",
    "/api/v1/admin/storage",
    "/api/v1/admin/storage/test",
    "/api/v1/admin/users",
    "/api/v1/admin/users/{user_id}",
        "/api/v1/admin/users/{user_id}/pairing-codes",
    "/api/v1/admin/jobs",
    "/api/v1/admin/jobs/scan",
    "/api/v1/admin/backups",
    "/api/v1/admin/backups/{id}",
    "/api/v1/admin/audit-log",
    "/api/v1/admin/diagnostics",
    "/api/v1/pairing",
    "/api/v1/devices/me/revoke",
    "/api/v1/server",
    "/api/v1/sync/bootstrap",
    "/api/v1/sync/bootstrap/{snapshot_id}",
    "/api/v1/sync/bootstrap/{snapshot_id}/{entity}",
    "/api/v1/media",
    "/api/v1/media/folders",
    "/api/v1/media/{id}",
    "/api/v1/media/{id}/content",
    "/api/v1/media/{id}/thumbnail",
    "/api/v1/media/{id}/favorite",
    "/api/v1/admin/users/{user_id}/favorites",
    "/api/v1/media/upload",
    "/api/v1/tags",
    "/api/v1/tags/{id}",
    "/api/v1/tags/{id}/media/{media_id}",
    "/api/v1/changes",
    "/api/v1/jobs/{id}",
    "/api/v1/jobs/{id}/cancel",
    "/api/v1/jobs/{id}/retry",
)

REQUIRED_SCHEMAS = (
    "FavoriteUpdateRequest",
    "HealthResponse",
    "SetupStatusResponse",
    "SetupRequest",
    "LoginRequest",
    "PairingRequest",
    "ServerResponse",
    "AdminStorageResponse",
    "StorageUpdateRequest",
    "StorageTestResponse",
    "BootstrapStartResponse",
    "BootstrapStatusResponse",
    "MediaItem",
    "MediaFolder",
    "MetadataNameRequest",
    "ChangeItem",
    "ChangesResponse",
    "JobResponse",
    "BackupResponse",
    "AuditLogEntry",
    "DiagnosticsResponse",
    "SessionTokens",
    "PairingCodeResponse",
    "DeviceTokens",
    "DeviceSummary",
    "Tag",
    "Relation",
    "StreamUploadResponse",
)


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check_openapi.py <openapi.json>", file=sys.stderr)
        return 2

    contract_path = Path(sys.argv[1])
    if not contract_path.is_file():
        print(f"OpenAPI document not found: {contract_path}", file=sys.stderr)
        return 1

    document = json.loads(contract_path.read_text(encoding="utf-8"))
    paths = document.get("paths", {})
    schemas = document.get("components", {}).get("schemas", {})

    missing_paths = [path for path in REQUIRED_PATHS if path not in paths]
    missing_schemas = [schema for schema in REQUIRED_SCHEMAS if schema not in schemas]

    if missing_paths:
        print("OpenAPI contract missing paths:", ", ".join(missing_paths), file=sys.stderr)
    if missing_schemas:
        print("OpenAPI contract missing schemas:", ", ".join(missing_schemas), file=sys.stderr)

    if missing_paths or missing_schemas:
        return 1

    print(
        f"OpenAPI contract sanity check passed: {len(paths)} paths, "
        f"{len(schemas)} schemas ({contract_path})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
