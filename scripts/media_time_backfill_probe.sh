#!/usr/bin/env bash
# 媒体时间回填 SQL 优化的可复现测量（需求 9fcjh6SbvcRZ）。
#
# 在同一份确定性数据集上先后跑两次：先「优化前基线」（删除 0026 索引），
# 再「优化后」，各自输出单批 256 行 p50/p95、全量回填总时长、EXPLAIN QUERY PLAN
# 与 EXISTS / emit / UPDATE 分语句计时。数据集指纹一致才可对比。
#
#   ./scripts/media_time_backfill_probe.sh [数据集条数，默认 100000]
#
# 产物（apps/server/target/probe/，gitignored）：
#   media-time-backfill-baseline.json   / media-time-backfill-optimized.json
#   media-time-backfill-baseline.log    / media-time-backfill-optimized.log
#
# 注意：探针跑的是真实 release 服务端代码 + 真实文件库 SQLite（无 stub）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEDIA="${1:-100000}"
OUT="$ROOT/apps/server/target/probe"
DATA="$OUT/media-time-backfill-data"
mkdir -p "$OUT"

cd "$ROOT/apps/server"
# macOS 27 + rustc 1.95：release 默认 strip=debuginfo 会产出 dyld 拒绝加载的
# proc-macro dylib（rust-lang/rust#157750），探针必须显式关闭 strip。
export CARGO_PROFILE_RELEASE_STRIP=none
export YOUYOU_BACKFILL_PROBE_MEDIA="$MEDIA"
export YOUYOU_BACKFILL_PROBE_DIR="$DATA"

rm -rf "$DATA" "$OUT/media-time-backfill-baseline.json" "$OUT/media-time-backfill-optimized.json"
YOUYOU_BACKFILL_PROBE_DROP_INDEX=1 \
YOUYOU_BACKFILL_PROBE_OUTPUT="$OUT/media-time-backfill-baseline.json" \
  cargo test --release --locked media_time::tests::backfill_scale_probe -- --ignored --nocapture \
  > "$OUT/media-time-backfill-baseline.log" 2>&1

rm -rf "$DATA"
YOUYOU_BACKFILL_PROBE_OUTPUT="$OUT/media-time-backfill-optimized.json" \
  cargo test --release --locked media_time::tests::backfill_scale_probe -- --ignored --nocapture \
  > "$OUT/media-time-backfill-optimized.log" 2>&1

python3 - "$OUT/media-time-backfill-baseline.json" "$OUT/media-time-backfill-optimized.json" <<'PY'
import json
import sys

baseline, optimized = (json.load(open(path)) for path in sys.argv[1:3])
if baseline["fingerprint"] != optimized["fingerprint"]:
    raise SystemExit(f"dataset fingerprints differ:\n{baseline['fingerprint']}\n{optimized['fingerprint']}")


def row(label, before, after, unit="ms"):
    factor = before / after if after else float("inf")
    print(f"{label:<28}{before:>12.1f}{after:>12.1f}{factor:>10.0f}x   {unit}")


print(f"dataset: {optimized['fingerprint']} media={optimized['mediaCount']}")
print(f"{'metric':<28}{'before':>12}{'after':>12}{'speedup':>11}")
row("batch 256 p50", baseline["backfill"]["batchP50Ms"], optimized["backfill"]["batchP50Ms"])
row("batch 256 p95", baseline["backfill"]["batchP95Ms"], optimized["backfill"]["batchP95Ms"])
row("full backfill total", baseline["backfill"]["totalMs"], optimized["backfill"]["totalMs"])
row("EXISTS per batch", baseline["statementProfile"]["selectPerBatchMs"], optimized["statementProfile"]["selectPerBatchMs"])
row("emit per batch", baseline["statementProfile"]["emitPerBatchMs"], optimized["statementProfile"]["emitPerBatchMs"])
print(f"baseline batches={baseline['backfill']['batches']} rows={baseline['backfill']['rows']}")
print("baseline plans:", "; ".join(baseline["plans"]["batch"]))
print("optimized plans:", "; ".join(optimized["plans"]["batch"]))
PY
