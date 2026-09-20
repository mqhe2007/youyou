FROM node:24-bookworm-slim AS web-builder

WORKDIR /src
COPY apps/server/web/package.json apps/server/web/package-lock.json ./
RUN npm ci
COPY apps/server/web/index.html apps/server/web/vite.config.js ./
COPY apps/server/web/src ./src
COPY apps/server/web/public ./public
RUN npm run build

FROM rust:1.95-bookworm AS builder

WORKDIR /src
COPY apps/server/Cargo.toml apps/server/Cargo.lock* ./
COPY apps/server/src ./src
COPY apps/server/migrations ./migrations
COPY --from=web-builder /src/dist ./web/dist
COPY apps/server/web/public ./web/public
COPY apps/client/assets/icons/logo_mark.png /client/assets/icons/logo_mark.png
RUN cargo build --release --locked

FROM debian:bookworm-slim

ARG FFMPEG_PACKAGE_VERSION=7:5.1.9-0+deb12u1

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates curl "ffmpeg=${FFMPEG_PACKAGE_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && test "$(dpkg-query -W -f='${Version}' ffmpeg)" = "${FFMPEG_PACKAGE_VERSION}" \
    && ffmpeg -version >/dev/null \
    && ffprobe -version >/dev/null \
    && useradd --system --uid 10001 --create-home --home-dir /nonexistent youyou

COPY --from=builder /src/target/release/youyou-server /usr/local/bin/youyou-server

RUN mkdir -p /srv/youyou/data /srv/youyou/media \
    && chown -R youyou:youyou /srv/youyou

ENV YOUYOU_SERVER_PORT=8989 \
    YOUYOU_SERVER_DIR=/srv/youyou \
    RUST_LOG=info

VOLUME ["/srv/youyou"]
EXPOSE 8989

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["sh", "-c", "curl --fail --silent http://127.0.0.1:${YOUYOU_SERVER_PORT}/api/v1/health || exit 1"]

USER youyou
ENTRYPOINT ["/usr/local/bin/youyou-server"]
