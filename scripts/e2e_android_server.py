#!/usr/bin/env python3
"""Run the Android transfer acceptance against an isolated real server."""

import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zlib

from e2e_http import PASSWORD, ROOT, assert_status, png_chunk, poll_job, start_server, stop_server


def png_for(index: int, salt: int = 0) -> bytes:
    pixels = bytes((index * 17 % 256, index * 29 % 256, index * 43 % 256,
                    salt & 255, (salt >> 8) & 255, index))
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(b"\x00" + pixels))
        + png_chunk(b"IEND", b"")
    )


def main() -> None:
    serial = os.environ.get("ANDROID_SERIAL", "emulator-5554")
    adb = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb"
    model = subprocess.check_output([str(adb), "-s", serial, "shell", "getprop", "ro.product.model"], text=True).strip()
    if not model.startswith("sdk_gphone"):
        raise RuntimeError(f"isolated test requires an emulator, got {model}")

    with tempfile.TemporaryDirectory(prefix="youyou-android-server-") as directory:
        root = Path(directory)
        library = root / "media" / "nested"
        library.mkdir(parents=True)
        other_library = root / "media" / "other"
        other_library.mkdir(parents=True)
        (other_library / "other-account.png").write_bytes(png_for(240))
        (root / "media" / "empty").mkdir(parents=True)
        marketing_library = root / "media" / "marketing"
        marketing_library.mkdir(parents=True)
        for name in ("hero-photo.jpg", "home-server.jpg", "backup-transfer.jpg", "qr-scan.jpg"):
            shutil.copyfile(ROOT / "apps/site/public/images" / name, marketing_library / name)
        process, client = start_server(root, root / "server.log")
        port = int(client.base_url.rsplit(":", 1)[1])
        remote_prefix = f"e2e-{port}-remote-"
        try:
            for index in range(10):
                (library / f"{remote_prefix}{index:02d}.png").write_bytes(png_for(index, port))
            token_path = root / "data/bootstrap/setup-token"
            for _ in range(100):
                if token_path.exists():
                    break
                time.sleep(0.05)
            assert_status(client.json("POST", "/api/v1/admin/setup", {
                "setupToken": token_path.read_text().strip(), "password": PASSWORD,
            }), 204, "setup")
            admin = assert_status(client.json("POST", "/api/v1/admin/session", {"password": PASSWORD}), 200, "admin login")
            user = assert_status(client.json("POST", "/api/v1/admin/users", {
                "name": "android-e2e", "libraryName": "nested",
            }, bearer=admin["token"], csrf=admin["csrfToken"]), 201, "create user")
            pairing = assert_status(client.json("POST", f"/api/v1/admin/users/{user['id']}/pairing-codes", {},
                bearer=admin["token"], csrf=admin["csrfToken"]), 200, "pairing code")
            other_user = assert_status(client.json("POST", "/api/v1/admin/users", {
                "name": "android-e2e-other", "libraryName": "other",
            }, bearer=admin["token"], csrf=admin["csrfToken"]), 201, "create second user")
            other_pairing = assert_status(client.json("POST", f"/api/v1/admin/users/{other_user['id']}/pairing-codes", {},
                bearer=admin["token"], csrf=admin["csrfToken"]), 200, "second pairing code")
            empty_user = assert_status(client.json("POST", "/api/v1/admin/users", {
                "name": "android-e2e-empty", "libraryName": "empty",
            }, bearer=admin["token"], csrf=admin["csrfToken"]), 201, "create empty user")
            empty_pairing = assert_status(client.json("POST", f"/api/v1/admin/users/{empty_user['id']}/pairing-codes", {},
                bearer=admin["token"], csrf=admin["csrfToken"]), 200, "empty pairing code")
            marketing_user = assert_status(client.json("POST", "/api/v1/admin/users", {
                "name": "android-e2e-marketing", "libraryName": "marketing",
            }, bearer=admin["token"], csrf=admin["csrfToken"]), 201, "create marketing user")
            marketing_pairing = assert_status(client.json("POST", f"/api/v1/admin/users/{marketing_user['id']}/pairing-codes", {},
                bearer=admin["token"], csrf=admin["csrfToken"]), 200, "marketing pairing code")
            scan = assert_status(client.json("POST", "/api/v1/admin/jobs/scan",
                bearer=admin["token"], csrf=admin["csrfToken"]), 202, "scan")
            poll_job(client, scan["id"], bearer=admin["token"], admin=True)

            subprocess.run([str(adb), "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
            env = dict(os.environ, ANDROID_SERIAL=serial)
            subprocess.run([str(ROOT / "apps/android/gradlew"), ":app:installDebug", ":app:installDebugAndroidTest"],
                cwd=ROOT / "apps/android", env=env, check=True)
            fixture_name = f"youyou-permission-{port}.png"
            fixture_path = root / fixture_name
            fixture_path.write_bytes(png_for(241, port))
            device_path = f"/sdcard/Pictures/{fixture_name}"
            subprocess.run([str(adb), "-s", serial, "push", str(fixture_path), device_path],
                check=True, stdout=subprocess.DEVNULL)
            subprocess.run([str(adb), "-s", serial, "shell", "am", "broadcast",
                "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{device_path}"],
                check=True, stdout=subprocess.DEVNULL)
            external_uri = None
            for _ in range(30):
                rows = subprocess.check_output([str(adb), "-s", serial, "shell", "content", "query",
                    "--uri", "content://media/external/images/media", "--projection", "_id:_display_name"], text=True)
                match = re.search(rf"_id=(\d+), _display_name={re.escape(fixture_name)}", rows)
                if match:
                    external_uri = f"content://media/external/images/media/{match.group(1)}"
                    break
                time.sleep(0.2)
            if external_uri is None:
                raise RuntimeError("system-owned media fixture was not indexed")
            subprocess.run([str(adb), "-s", serial, "shell", "pm", "grant",
                "com.mengqinghe.youyou.debug", "android.permission.READ_MEDIA_IMAGES"], check=True)
            subprocess.run([str(adb), "-s", serial, "shell", "am", "start", "-n",
                "com.mengqinghe.youyou.debug/com.example.youyou_album.MainActivity"], check=True)
            time.sleep(1)
            subprocess.run([str(adb), "-s", serial, "shell", "uiautomator", "dump", "/sdcard/window.xml"],
                check=True, stdout=subprocess.DEVNULL)
            hierarchy = subprocess.check_output([str(adb), "-s", serial, "shell", "cat", "/sdcard/window.xml"])
            for node in ET.fromstring(hierarchy).iter("node"):
                if node.attrib.get("text") == "Don't Show Again":
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                    subprocess.run([str(adb), "-s", serial, "shell", "input", "tap",
                        str((x1 + x2) // 2), str((y1 + y2) // 2)], check=True)
                    break
            command = [str(adb), "-s", serial, "shell", "am", "instrument", "-w",
                "-e", "class", "com.example.youyou_album.server.RealServerTransferE2ETest",
                "-e", "youyou.baseUrl", client.base_url,
                "-e", "youyou.pairingCode", pairing["code"],
                "-e", "youyou.otherPairingCode", other_pairing["code"],
                "-e", "youyou.emptyPairingCode", empty_pairing["code"],
                "-e", "youyou.remotePrefix", remote_prefix,
                "-e", "youyou.externalUri", external_uri,
                "com.mengqinghe.youyou.debug.test/androidx.test.runner.AndroidJUnitRunner"]
            result = subprocess.run(command, text=True, capture_output=True, check=True)
            print(result.stdout)
            if "OK (1 test)" not in result.stdout:
                raise RuntimeError("Android instrumentation failed: " + result.stdout[-5000:])
            remaining_originals = list(library.glob(f"{remote_prefix}*.png"))
            if len(remaining_originals) != 7:
                raise RuntimeError(f"old account source changed across account switch: {len(remaining_originals)} originals remain")
            screenshot_dir = ROOT / "artifacts" / "acceptance" / "9e7vosKHBEvX"
            screenshot_dir.mkdir(parents=True, exist_ok=True)
            with (screenshot_dir / "first-connect.png").open("wb") as output:
                subprocess.run([str(adb), "-s", serial, "exec-out", "run-as", "com.mengqinghe.youyou.debug",
                    "cat", "files/acceptance/first-connect.png"], stdout=output, check=True)
            with (screenshot_dir / "task-center.png").open("wb") as output:
                subprocess.run([str(adb), "-s", serial, "exec-out", "run-as", "com.mengqinghe.youyou.debug",
                    "cat", "files/acceptance/task-center.png"], stdout=output, check=True)
            subprocess.run([str(adb), "-s", serial, "shell", "am", "force-stop",
                "com.mengqinghe.youyou.debug"], check=True)
            subprocess.run([str(adb), "-s", serial, "shell", "am", "start", "-n",
                "com.mengqinghe.youyou.debug/com.example.youyou_album.MainActivity"], check=True,
                stdout=subprocess.DEVNULL)
            time.sleep(1)
            with (screenshot_dir / "unbound-home.png").open("wb") as output:
                subprocess.run([str(adb), "-s", serial, "exec-out", "screencap", "-p"],
                    stdout=output, check=True)
            subprocess.run([str(adb), "-s", serial, "shell", "pm", "revoke",
                "com.mengqinghe.youyou.debug", "android.permission.READ_MEDIA_IMAGES"], check=True)
            revoked = subprocess.run([str(adb), "-s", serial, "shell", "am", "instrument", "-w",
                "-e", "class", "com.example.youyou_album.server.RevokedMediaPermissionE2ETest",
                "-e", "youyou.externalUri", external_uri,
                "com.mengqinghe.youyou.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                text=True, capture_output=True, check=True)
            print(revoked.stdout)
            if "OK (1 test)" not in revoked.stdout:
                raise RuntimeError("revoked-media instrumentation failed: " + revoked.stdout[-5000:])
            subprocess.run([str(adb), "-s", serial, "shell", "pm", "clear",
                "com.mengqinghe.youyou.debug"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run([str(adb), "-s", serial, "shell", "am", "start", "-n",
                "com.mengqinghe.youyou.debug/com.example.youyou_album.MainActivity"], check=True,
                stdout=subprocess.DEVNULL)
            time.sleep(1)
            subprocess.run([str(adb), "-s", serial, "shell", "uiautomator", "dump", "/sdcard/window.xml"],
                check=True, stdout=subprocess.DEVNULL)
            hierarchy = subprocess.check_output([str(adb), "-s", serial, "shell", "cat", "/sdcard/window.xml"])
            for node in ET.fromstring(hierarchy).iter("node"):
                if node.attrib.get("text") == "Don't Show Again":
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                    subprocess.run([str(adb), "-s", serial, "shell", "input", "tap",
                        str((x1 + x2) // 2), str((y1 + y2) // 2)], check=True)
                    break
            subprocess.run([str(adb), "-s", serial, "shell", "am", "force-stop",
                "com.mengqinghe.youyou.debug"], check=True)
            marketing = subprocess.run([str(adb), "-s", serial, "shell", "am", "instrument", "-w",
                "-e", "class", "com.example.youyou_album.server.MarketingScreenshotE2ETest",
                "-e", "youyou.baseUrl", client.base_url,
                "-e", "youyou.pairingCode", marketing_pairing["code"],
                "com.mengqinghe.youyou.debug.test/androidx.test.runner.AndroidJUnitRunner"],
                text=True, capture_output=True, check=True)
            print(marketing.stdout)
            if "OK (1 test)" not in marketing.stdout:
                raise RuntimeError("marketing screenshot instrumentation failed: " + marketing.stdout[-5000:])
            with (screenshot_dir / "marketing-first-connect.png").open("wb") as output:
                subprocess.run([str(adb), "-s", serial, "exec-out", "run-as", "com.mengqinghe.youyou.debug",
                    "cat", "files/acceptance/marketing-first-connect.png"], stdout=output, check=True)
            print("Android + real server E2E passed: first sync, 10 uploads, 10 downloads, retry")
        finally:
            subprocess.run([str(adb), "-s", serial, "shell", "appops", "set",
                "com.mengqinghe.youyou.debug", "READ_MEDIA_IMAGES", "default"], check=False)
            subprocess.run([str(adb), "-s", serial, "shell", "pm", "revoke",
                "com.mengqinghe.youyou.debug", "android.permission.READ_MEDIA_IMAGES"], check=False)
            if "external_uri" in locals() and external_uri:
                subprocess.run([str(adb), "-s", serial, "shell", "content", "delete",
                    "--uri", external_uri], check=False, stdout=subprocess.DEVNULL)
            subprocess.run([str(adb), "-s", serial, "reverse", "--remove", f"tcp:{port}"], check=False)
            stop_server(process)


if __name__ == "__main__":
    main()
