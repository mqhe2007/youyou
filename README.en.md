# youyou (柚柚相册)

[简体中文](README.md) · **English**

> Effortlessly manage a lifetime of photos

youyou is a self-hosted, **multi-user photo management app** made of an Android client and a
server you run yourself. The server is the source of truth for remote media and metadata; the
client holds the local index, caches and entry points; the timeline merges the two. An original
file is ever stored only once.

## Core ideas

- **Server data first** — The server is the source of truth for remote media and metadata. The
  client's Room database holds the local media index, a projection cache of remote media and the
  pending-upload queue. The timeline is a **merged grid** of the on-device index plus the server
  projection — not a mirror of a server-side listing.
- **A single source of media files** — The server indexes mounted directories in place and never
  copies originals; an uploaded file is written to disk exactly once (unlike immich's double
  storage footprint).
- **Multi-user isolation** — Each user is bound to their own media library directory, and a
  directory can be bound by only one user. Media, tags and favorites are fully isolated.
- **A minimal client** — Scan-to-login, timeline, tags, favorites, settings. Nothing of low value.
- **Privacy on your own terms** — Self-hosted, no tracking, no analytics; the client can still
  browse cached content while the server is offline.

## Features

- **QR sign-in** — The admin generates a personal QR code for each user; scanning it binds the app
  to that user's media library.
- **Timeline** — Server and on-device media shown together. Thumbnails are badged with a phone or
  cloud icon for device-only / server-only items (synced items carry no badge); filter and select
  for sync on demand, with year-month ticks for fast navigation.
- **Two-way backup** — Select one or many device-only photos to upload; download server-only
  photos to the phone.
- **Tags** — Create, apply and browse tags, isolated per user.
- **Favorites** — Dedicated favorites pages on both client and server; favorites sync across
  devices.
- **A lean server** — A single Rust service with SQLite and an embedded admin UI: manage and
  rescan library directories, per-user QR codes, runtime logs, backups.

Deliberately out of scope: logical albums, search, AI recognition, photo maps, deduplication,
sharing.

## Platform support

- Client: Android 12 (API 31) and above
- Server: self-hosted via Docker (multi-arch image)

## Deployment

For users: just pull the published image — no source code, no build toolchain. See the official
[Quick Start](https://youyou.mengqinghe.com/quickstart) for the full walkthrough, and the
[Privacy Policy](https://youyou.mengqinghe.com/privacy) and
[Terms of Service](https://youyou.mengqinghe.com/terms).

```bash
curl -O https://raw.githubusercontent.com/mqhe2007/youyou/main/deploy/docker-compose.yml
docker compose up -d
```

## Development

Requirements: JDK 17, Android SDK (platform 37, minSdk 31), Rust 1.95+, Node 24, Docker.

```bash
make server-check     # format + compile + clippy (builds the embedded admin UI first)
make server-test      # server tests
make contract-check   # OpenAPI contract
make e2e              # end to end, with a real server process
make benchmark        # baselines at 100k media items; fails when thresholds are exceeded
make client-test      # Android unit tests
make e2e-android      # Android instrumented tests, starts an emulator automatically
make acceptance       # all of the above
```

Building the client: `cd apps/android && ./gradlew assembleDebug`. A signed release requires
`release-signing/keystore.properties` in the repository root (not committed); when it is missing,
the release build fails outright so that nothing unsigned can be published by accident. Official
builds are produced by GitHub Actions on tags and published to this repository's Releases;
`workflow_dispatch` runs the same signing flow without publishing. The server and the client
release independently: `server-vX.Y.Z` tags publish the image, `app-vX.Y.Z` tags publish the APK,
and the two version numbers need not match. A tag must match the version declared in that
component's own code — `version` in `apps/server/Cargo.toml`, `versionName` in the Android build —
so bump it before tagging; CI enforces this.

Running the server locally: `cargo run --manifest-path apps/server/Cargo.toml`; the admin UI is at
`http://127.0.0.1:8989/admin`.

## License

This project is licensed under **AGPL-3.0-only**; see [LICENSE](LICENSE) for the full text.

- You are free to use, modify and distribute it, including commercially.
- But if you distribute a modified version, or offer it as a network service, you must release the
  complete source under the same license. That is the main difference between AGPL and GPL, and it
  exists precisely to prevent others from turning it into a closed-source hosted service.
- The project retains the right to license it under other terms separately (dual licensing).
