//! Stable media time. Filename wall clocks use UTC, matching the legacy server convention.
use sqlx::SqlitePool;
#[derive(Clone, Debug, PartialEq)]
pub struct MediaTime {
    pub at: Option<i64>,
    pub source: String,
}
pub fn valid(at: Option<i64>) -> Option<i64> {
    at.filter(|v| *v >= 31_536_000_000 && *v <= crate::db::now_millis() + 86_400_000)
}
pub fn rank(source: &str) -> u8 {
    match source {
        "capture" => 4,
        "filename" => 3,
        "added" => 2,
        "modified" | "legacy" => 1,
        _ => 0,
    }
}
pub fn filename(name: &str) -> Option<i64> {
    let (y, m, d, h, mi, s, ms) = crate::uploads::parse_rule_name(name)?;
    let y = i64::from(y) - if m <= 2 { 1 } else { 0 };
    let era = y.div_euclid(400);
    let yo = y - era * 400;
    let mp = i64::from(m) + if m > 2 { -3 } else { 9 };
    let days = era * 146097 + yo * 365 + yo / 4 - yo / 100 + (153 * mp + 2) / 5 + i64::from(d)
        - 1
        - 719468;
    valid(Some(
        days * 86400000
            + i64::from(h) * 3600000
            + i64::from(mi) * 60000
            + i64::from(s) * 1000
            + i64::from(ms),
    ))
}
pub fn resolve(name: &str, capture: Option<i64>, fallback: Option<i64>) -> MediaTime {
    if let Some(at) = valid(capture) {
        return MediaTime {
            at: Some(at),
            source: "capture".into(),
        };
    }
    if let Some(at) = filename(name) {
        return MediaTime {
            at: Some(at),
            source: "filename".into(),
        };
    }
    MediaTime {
        at: valid(fallback),
        source: if valid(fallback).is_some() {
            "modified"
        } else {
            "unknown"
        }
        .into(),
    }
}
pub fn choose(old: MediaTime, new: MediaTime) -> MediaTime {
    if valid(old.at).is_some() && rank(&old.source) >= rank(&new.source) {
        old
    } else if valid(new.at).is_some() {
        new
    } else {
        MediaTime {
            at: None,
            source: "unknown".into(),
        }
    }
}
/// One backfill batch: next assets still on the legacy time policy, plus whether any of
/// their locations lives under a legacy `uploads/` path (those had their original name
/// replaced by a generated one, so the current name must not be trusted as the original).
const BATCH_SELECT_SQL: &str = "SELECT a.id,a.name,a.sort_at,EXISTS(SELECT 1 FROM media_locations l WHERE l.media_asset_id=a.id AND l.normalized_path LIKE '%/uploads/%') FROM media_assets a WHERE a.time_version=0 ORDER BY a.id LIMIT 256";
const BACKFILL_UPDATE_SQL: &str = "UPDATE media_assets SET sort_at=?1,sort_source=?2,original_name=?4,time_version=-1,version=version+1 WHERE id=?3 AND time_version=0";
fn emit_sql() -> String {
    format!(
        r#"INSERT INTO change_log(revision,event_id,entity,operation,entity_id,version,payload,created_at,owner_user_id)
      SELECT ?1,?2,'media','upsert',a.id,a.version,json_object(
      'id',a.id,'name',a.name,'path',l.normalized_path,'size',l.size,'contentHash',b.content_hash,
      'mimeType',a.mime_type,'isVideo',json(CASE WHEN a.is_video=1 THEN 'true' ELSE 'false' END),
      'storageId',l.storage_id,'identityState',a.identity_state,'hashState',l.hash_state,
      'width',a.width,'height',a.height,'durationMs',a.duration_ms,'takenAt',a.taken_at,
      'sortAt',a.sort_at,'sortSource',a.sort_source,'timeVersion',a.time_version,'originalName',a.original_name,
      {live}),?3,a.owner_user_id
      FROM media_assets a JOIN media_locations l ON l.media_asset_id=a.id LEFT JOIN content_blobs b ON b.id=a.blob_id
      WHERE a.id=?4 ORDER BY l.normalized_path LIMIT 1"#,
        live = crate::live_photo::PAYLOAD_FRAGMENT,
    )
}

/// Bounded, restartable historical correction, with ordinary change events for existing clients.
pub async fn backfill(pool: &SqlitePool) -> anyhow::Result<()> {
    let count = run_backfill(pool, |_, _| {}).await?;
    if count > 0 {
        tracing::info!(count, "media time history normalized");
    }
    Ok(())
}

/// Backfill batch loop. `on_batch` receives `(rows, elapsed_ms)` after every committed
/// batch; the scale probe (`backfill_scale_probe`) uses it to time the real path instead
/// of duplicating the SQL, while production ([`backfill`]) passes a no-op observer.
async fn run_backfill(
    pool: &SqlitePool,
    mut on_batch: impl FnMut(usize, u128),
) -> anyhow::Result<usize> {
    let mut count = 0;
    loop {
        let batch_started = std::time::Instant::now();
        let rows = sqlx::query_as::<_, (String, String, Option<i64>, i64)>(BATCH_SELECT_SQL)
            .fetch_all(pool)
            .await?;
        if rows.is_empty() {
            break;
        }
        let batch_rows = rows.len();
        let mut tx = pool.begin().await?;
        let revision = crate::sync::allocate_revision(&mut tx).await?;
        for (id, name, old, legacy_upload) in rows {
            // Legacy taken_at may actually be Android DATE_ADDED; do not promote it to capture.
            let original = if legacy_upload == 1 { "" } else { &name };
            let time = resolve(original, None, old);
            sqlx::query(BACKFILL_UPDATE_SQL)
                .bind(time.at)
                .bind(&time.source)
                .bind(&id)
                .bind(original)
                .execute(&mut *tx)
                .await?;
            emit(&mut tx, &id, revision).await?;
        }
        tx.commit().await?;
        count += batch_rows;
        on_batch(batch_rows, batch_started.elapsed().as_millis());
    }
    // Also recover a crash between the last backfill batch and scheduling verification.
    sqlx::query("INSERT INTO jobs(id,kind,status,created_at,updated_at) SELECT ?1,'scan','queued',?2,?2 WHERE EXISTS(SELECT 1 FROM media_assets WHERE time_version=-1) AND NOT EXISTS(SELECT 1 FROM jobs WHERE kind='scan' AND status IN ('queued','running'))")
        .bind(uuid::Uuid::new_v4().to_string()).bind(crate::db::now_millis()).execute(pool).await?;
    Ok(count)
}
pub async fn emit(tx: &mut sqlx::SqliteConnection, id: &str, revision: i64) -> anyhow::Result<()> {
    sqlx::query(&emit_sql())
        .bind(revision)
        .bind(uuid::Uuid::new_v4().to_string())
        .bind(crate::db::now_millis())
        .bind(id)
        .execute(&mut *tx)
        .await?;
    Ok(())
}

/// Strict offset and ISO creation_time parsing; no dependence on server timezone.
pub fn offset_minutes(offset: &str) -> Option<i64> {
    if offset == "Z" {
        return Some(0);
    }
    let b = offset.as_bytes();
    if b.len() != 6 || !matches!(b[0], b'+' | b'-') || b[3] != b':' {
        return None;
    }
    let h = offset.get(1..3)?.parse::<i64>().ok()?;
    let m = offset.get(4..6)?.parse::<i64>().ok()?;
    if h > 23 || m > 59 {
        return None;
    }
    Some((h * 60 + m) * if b[0] == b'-' { -1 } else { 1 })
}
pub fn iso_capture(value: &str) -> Option<i64> {
    let date = value.get(..10)?;
    let time = value.get(11..19)?;
    if value.get(10..11)? != "T" {
        return None;
    }
    let rest = value.get(19..)?;
    let (ms, zone) = if let Some(frac) = rest.strip_prefix('.') {
        let n = frac.bytes().take_while(u8::is_ascii_digit).count();
        if n == 0 {
            return None;
        }
        (
            format!("{:0<3}", &frac[..n])[..3].parse::<i64>().ok()?,
            &frac[n..],
        )
    } else {
        (0, rest)
    };
    let at = filename(&format!(
        "IMG_{}_{}",
        date.replace('-', ""),
        time.replace(':', "")
    ))?;
    valid(Some(at + ms - offset_minutes(zone)? * 60000))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn strict_filename_and_timezone() {
        assert_eq!(filename("IMG_20240101_000000.jpg"), Some(1704067200000));
        assert_eq!(
            filename("VID_20240101_000000_123_habc123.mp4"),
            Some(1704067200123)
        );
        assert_eq!(
            filename("IMG_20240101_000000_habc.jpg"),
            Some(1704067200000)
        );
        for name in [
            "IMG_20230229_120000.jpg",
            "IMG_20240101_250000.jpg",
            "IMG_20240101_0000.jpg",
            "IMG_nodate_habc.jpg",
            "IMG_20240101_000000_junk.jpg",
            "IMG_20990101_000000.jpg",
        ] {
            assert_eq!(filename(name), None, "{name}");
        }
        assert!(filename("IMG_20240229_120000.jpg").is_some());
        assert_eq!(
            iso_capture("2024-01-01T08:00:00.123+08:00"),
            Some(1704067200123)
        );
        assert_eq!(iso_capture("2024-01-01T00:00:00Z"), Some(1704067200000));
    }
    #[test]
    fn provenance_and_freezing() {
        let old = MediaTime {
            at: Some(1609459200000),
            source: "modified".into(),
        };
        assert_eq!(
            choose(
                old.clone(),
                resolve("plain.jpg", None, Some(crate::db::now_millis()))
            ),
            old
        );
        assert_eq!(
            choose(old, resolve("IMG_20240101_000000.jpg", None, None)).source,
            "filename"
        );
        assert_eq!(
            resolve("IMG_20240101_000000.jpg", Some(1609459200000), None).source,
            "capture"
        );
        assert_eq!(valid(Some(1704067200)), None);
        assert_eq!(valid(Some(i64::MAX)), None);
        assert_eq!(resolve("unknown", None, None).at, None);
    }
    #[tokio::test]
    async fn history_batches_are_idempotent_and_emit_changes() -> anyhow::Result<()> {
        let dir = tempfile::tempdir()?;
        let pool = crate::db::connect(dir.path()).await?;
        // Real file-backed SQLite; old DATE_ADDED must not become trusted capture.
        sqlx::query("INSERT INTO media_assets(id,name,identity_state,taken_at,sort_at,created_at,updated_at) VALUES ('old','IMG_20240101_000000.jpg','pending',?1,?1,?1,?1)").bind(crate::db::now_millis()).execute(&pool).await?;
        backfill(&pool).await?;
        let row = sqlx::query_as::<_, (Option<i64>, String, i64)>(
            "SELECT sort_at,sort_source,version FROM media_assets WHERE id='old'",
        )
        .fetch_one(&pool)
        .await?;
        assert_eq!(row.0, Some(1704067200000));
        assert_eq!(row.1, "filename");
        backfill(&pool).await?;
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT version FROM media_assets WHERE id='old'")
                .fetch_one(&pool)
                .await?,
            row.2
        );
        pool.close().await;
        Ok(())
    }

    // ------------------------------------------------------------------
    // 规模探针与语义等价用例（回填 SQL 优化，需求 9fcjh6SbvcRZ）
    // ------------------------------------------------------------------

    use std::path::PathBuf;
    use std::time::{Duration, Instant};

    /// 优化索引以 migration 0026 为准：探针直接执行同一份 SQL，避免两处漂移。
    const PROBE_INDEX_SQL: &str =
        include_str!("../migrations/0026_media_locations_asset_index.sql");
    const OPTIMIZED_INDEX_NAME: &str = "media_locations_asset_path_idx";
    /// 种子数据的固定时间基准：保证两个数据集逐字节同构（不依赖 now）。
    const PROBE_EPOCH_MS: i64 = 1_700_000_000_000;

    fn sql_text(value: &str) -> String {
        format!("'{}'", value.replace('\'', "''"))
    }

    fn env_usize(key: &str, default: usize) -> usize {
        std::env::var(key)
            .ok()
            .and_then(|value| value.parse().ok())
            .unwrap_or(default)
    }

    /// 探针与等价用例共用的确定性种子数据集。
    /// - 每 10 条一条 legacy uploads 位置（`%/uploads/%`），回填时原名不可恢复；
    /// - 每 5 条一条第二位置（非 uploads、hash_state=stale）；
    /// - 位置 hash_state 混合 verified/pending，覆盖 0021 部分索引吃不到的行；
    /// - 名称混合可解析（`IMG_YYYYMMDD_HHmmss`）与不可解析，sort_at 混合有效/为空。
    async fn seed_bulk_media(pool: &SqlitePool, count: usize) -> anyhow::Result<()> {
        sqlx::query("INSERT INTO storages(id,name,root_path,read_only,created_at,updated_at) VALUES ('local','Local','/probe',0,1,1)")
            .execute(pool)
            .await?;
        sqlx::query("INSERT INTO users(id,name,created_at,updated_at) VALUES (1,'probe',1,1)")
            .execute(pool)
            .await?;
        for start in (0..count).step_by(100) {
            let end = (start + 100).min(count);
            let mut blobs = Vec::new();
            let mut assets = Vec::new();
            let mut locations = Vec::new();
            for index in start..end {
                let suffix = format!("{index:06}");
                let timestamp = PROBE_EPOCH_MS - i64::try_from(index).unwrap_or_default() * 1000;
                let sort_at = if index % 17 == 0 {
                    "NULL".to_string()
                } else {
                    timestamp.to_string()
                };
                let name = if index % 3 == 0 {
                    format!(
                        "IMG_2024{:02}{:02}_{:02}{:02}00.jpg",
                        1 + index % 12,
                        1 + index % 28,
                        index % 24,
                        index % 60
                    )
                } else {
                    format!("clip-{suffix}.mp4")
                };
                let hash_state = if index % 7 == 0 {
                    "pending"
                } else {
                    "verified"
                };
                blobs.push(format!(
                    "('blob-{suffix}','sha256','digest-{suffix}',1,{PROBE_EPOCH_MS})"
                ));
                assets.push(format!(
                    "('media-{suffix}','blob-{suffix}','verified',{},'image/jpeg',0,1,{PROBE_EPOCH_MS},{PROBE_EPOCH_MS},{sort_at},1)",
                    sql_text(&name)
                ));
                let path = if index % 10 == 0 {
                    format!("users/1/uploads/{suffix}.jpg")
                } else {
                    format!("users/1/library/{suffix}.jpg")
                };
                locations.push(format!(
                    "('loc-{suffix}','media-{suffix}','local',{},'{}',1,{PROBE_EPOCH_MS},'{hash_state}',1,{PROBE_EPOCH_MS},{PROBE_EPOCH_MS},{PROBE_EPOCH_MS})",
                    sql_text(&path), suffix
                ));
                if index % 5 == 0 {
                    locations.push(format!(
                        "('loc2-{suffix}','media-{suffix}','local','users/1/other/{suffix}.jpg','{}',1,{PROBE_EPOCH_MS},'stale',1,{PROBE_EPOCH_MS},{PROBE_EPOCH_MS},{PROBE_EPOCH_MS})",
                        suffix
                    ));
                }
            }
            sqlx::query(&format!(
                "INSERT INTO content_blobs(id,hash_algorithm,content_hash,size,created_at) VALUES {}",
                blobs.join(",")
            ))
            .execute(pool)
            .await?;
            sqlx::query(&format!(
                "INSERT INTO media_assets(id,blob_id,identity_state,name,mime_type,is_video,version,created_at,updated_at,sort_at,owner_user_id) VALUES {}",
                assets.join(",")
            ))
            .execute(pool)
            .await?;
            sqlx::query(&format!(
                "INSERT INTO media_locations(id,media_asset_id,storage_id,normalized_path,file_name,size,modified_at,hash_state,observed_size,observed_mtime,created_at,updated_at) VALUES {}",
                locations.join(",")
            ))
            .execute(pool)
            .await?;
        }
        Ok(())
    }

    /// 边界夹具：uploads 归属、两个位置、无位置、pending 位置、无效时间与非法文件名。
    async fn seed_edge_cases(pool: &SqlitePool) -> anyhow::Result<()> {
        let assets = [
            // legacy upload：原名不可恢复
            (
                "edge-upload-legacy",
                "'IMG_20240101_000000.jpg'",
                "1700000000000",
            ),
            // 文件名可解析且 sort_at 更晚：filename 等级胜出
            (
                "edge-filename",
                "'IMG_20240102_030405.jpg'",
                "1800000000000",
            ),
            // 非法日期（2023-02-29）不得解析
            (
                "edge-bad-date",
                "'IMG_20230229_120000.jpg'",
                "1600000000000",
            ),
            // 无位置：行仍更新，但不发变更事件
            ("edge-no-location", "'plain.jpg'", "1600000000000"),
            // 两个位置（uploads + library）：原名不可恢复，事件取字典序首个位置
            ("edge-two-locations", "'MVI_0001.MOV'", "1600000000000"),
            // hash_state=pending 的位置：0021 部分索引吃不到
            ("edge-pending-location", "'clip.mp4'", "1600000000000"),
            // sort_at 为空且名字不可解析：unknown
            ("edge-null-sort", "'plain.mov'", "NULL"),
            // sort_at 无效（低于 1971 下界的两倍）
            ("edge-zero-sort", "'plain.mov'", "0"),
            // taken_at 是旧 Android DATE_ADDED，回填不得把它当 capture
            ("edge-legacy-taken", "'plain.mov'", "0"),
            // 未来时间（超上界）无效
            ("edge-future-sort", "'plain.mov'", "9223372036854775807"),
        ];
        let rows = assets
            .iter()
            .map(|(id, name, sort_at)| {
                format!(
                    "('{id}',NULL,'verified',{name},'image/jpeg',0,1,{PROBE_EPOCH_MS},{PROBE_EPOCH_MS},{sort_at},NULL)"
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        sqlx::query(&format!(
            "INSERT INTO media_assets(id,blob_id,identity_state,name,mime_type,is_video,version,created_at,updated_at,sort_at,owner_user_id) VALUES {rows}"
        ))
        .execute(pool)
        .await?;
        let locations = [
            (
                "edge-upload-legacy",
                "users/1/uploads/edge-upload-legacy.jpg",
                "verified",
            ),
            (
                "edge-filename",
                "users/1/library/edge-filename.jpg",
                "verified",
            ),
            (
                "edge-bad-date",
                "users/1/library/edge-bad-date.jpg",
                "verified",
            ),
            (
                "edge-two-locations",
                "users/1/uploads/edge-two-locations.mov",
                "verified",
            ),
            (
                "edge-two-locations",
                "users/1/library/edge-two-locations.mov",
                "verified",
            ),
            (
                "edge-pending-location",
                "users/1/library/edge-pending-location.mp4",
                "pending",
            ),
            (
                "edge-null-sort",
                "users/1/library/edge-null-sort.mov",
                "verified",
            ),
            (
                "edge-zero-sort",
                "users/1/library/edge-zero-sort.mov",
                "verified",
            ),
            (
                "edge-legacy-taken",
                "users/1/library/edge-legacy-taken.mov",
                "verified",
            ),
            (
                "edge-future-sort",
                "users/1/library/edge-future-sort.mov",
                "verified",
            ),
        ];
        let rows = locations
            .iter()
            .enumerate()
            .map(|(index, (id, path, hash_state))| {
                format!(
                    "('edge-loc-{index}','{id}','local',{},'x.bin',1,{PROBE_EPOCH_MS},'{hash_state}',1,{PROBE_EPOCH_MS},{PROBE_EPOCH_MS},{PROBE_EPOCH_MS})",
                    sql_text(path)
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        sqlx::query(&format!(
            "INSERT INTO media_locations(id,media_asset_id,storage_id,normalized_path,file_name,size,modified_at,hash_state,observed_size,observed_mtime,created_at,updated_at) VALUES {rows}"
        ))
        .execute(pool)
        .await?;
        sqlx::query("UPDATE media_assets SET taken_at=?1 WHERE id='edge-legacy-taken'")
            .bind(PROBE_EPOCH_MS)
            .execute(pool)
            .await?;
        Ok(())
    }

    type MediaRow = (String, Option<i64>, String, Option<String>, i64, i64);
    type Snapshot = (Vec<MediaRow>, Vec<(String, i64, String)>);

    /// 回填语义快照：资产时间字段逐行 + 变更事件（条数、版本与完整载荷）。
    async fn snapshot(pool: &SqlitePool) -> anyhow::Result<Snapshot> {
        let assets = sqlx::query_as::<_, MediaRow>(
            "SELECT id,sort_at,sort_source,original_name,time_version,version FROM media_assets ORDER BY id",
        )
        .fetch_all(pool)
        .await?;
        let events = sqlx::query_as::<_, (String, i64, String)>(
            "SELECT entity_id,version,payload FROM change_log ORDER BY entity_id,revision",
        )
        .fetch_all(pool)
        .await?;
        Ok((assets, events))
    }

    /// 同一数据集在「优化前形态（无 0026 索引）」与「优化后」各回填一次。
    async fn backfilled_snapshots() -> anyhow::Result<(Snapshot, Snapshot)> {
        let with_index = async {
            let dir = tempfile::tempdir()?;
            let database = dir.path().join("youyou.db");
            let pool = crate::db::connect(dir.path()).await?;
            seed_bulk_media(&pool, 512).await?;
            seed_edge_cases(&pool).await?;
            let plan = explain(&database, BATCH_SELECT_SQL).await?;
            assert!(
                plan.contains(OPTIMIZED_INDEX_NAME),
                "optimized dataset must plan the batch query through {OPTIMIZED_INDEX_NAME}:\n{plan}"
            );
            let before = snapshot(&pool).await?;
            backfill(&pool).await?;
            let after = snapshot(&pool).await?;
            pool.close().await;
            anyhow::Ok((before, after))
        };
        let without_index = async {
            let dir = tempfile::tempdir()?;
            let database = dir.path().join("youyou.db");
            let pool = crate::db::connect(dir.path()).await?;
            seed_bulk_media(&pool, 512).await?;
            seed_edge_cases(&pool).await?;
            sqlx::query(&format!("DROP INDEX IF EXISTS {OPTIMIZED_INDEX_NAME}"))
                .execute(&pool)
                .await?;
            let plan = explain(&database, BATCH_SELECT_SQL).await?;
            assert!(
                !plan.contains(OPTIMIZED_INDEX_NAME),
                "baseline dataset must not plan the batch query through {OPTIMIZED_INDEX_NAME}:\n{plan}"
            );
            let before = snapshot(&pool).await?;
            backfill(&pool).await?;
            let after = snapshot(&pool).await?;
            pool.close().await;
            anyhow::Ok((before, after))
        };
        let (optimized, legacy) = tokio::try_join!(with_index, without_index)?;
        assert_eq!(
            optimized.0, legacy.0,
            "the two seed datasets must be byte-identical before backfill"
        );
        Ok((legacy.1, optimized.1))
    }

    #[tokio::test]
    async fn backfill_results_do_not_depend_on_location_index() -> anyhow::Result<()> {
        // 验收 3：优化前后同一数据集全量回填逐行等价（sort_at/sort_source/original_name/
        // time_version/version）且变更事件条数与载荷一致。
        let (legacy, optimized) = backfilled_snapshots().await?;
        assert!(!legacy.0.is_empty(), "expected media rows after backfill");
        assert!(
            !legacy.1.is_empty(),
            "expected change events after backfill"
        );
        assert_eq!(
            legacy, optimized,
            "backfill semantics changed with the optimized media_locations index"
        );
        Ok(())
    }

    #[tokio::test]
    async fn backfill_edge_cases_follow_media_time_rules() -> anyhow::Result<()> {
        // 验收 3 的绝对语义锚点：uploads 归属 / 两个位置 / 无位置 / 无效时间等边界，
        // 优化前后都不得改变这些结论。
        let dir = tempfile::tempdir()?;
        let pool = crate::db::connect(dir.path()).await?;
        seed_bulk_media(&pool, 0).await?;
        seed_edge_cases(&pool).await?;
        backfill(&pool).await?;

        let rows = sqlx::query_as::<_, MediaRow>(
            "SELECT id,sort_at,sort_source,original_name,time_version,version FROM media_assets ORDER BY id",
        )
        .fetch_all(&pool)
        .await?;
        let expected: Vec<MediaRow> = vec![
            // 非法日期不得解析：回退到 modified 的旧值
            (
                "edge-bad-date".into(),
                Some(1600000000000),
                "modified".into(),
                Some("IMG_20230229_120000.jpg".into()),
                -1,
                2,
            ),
            // 文件名可解析且优先于回退值
            (
                "edge-filename".into(),
                Some(1704164645000),
                "filename".into(),
                Some("IMG_20240102_030405.jpg".into()),
                -1,
                2,
            ),
            // 未来时间（超上界）无效：unknown
            (
                "edge-future-sort".into(),
                None,
                "unknown".into(),
                Some("plain.mov".into()),
                -1,
                2,
            ),
            // 旧 Android DATE_ADDED 不得升级为 capture
            (
                "edge-legacy-taken".into(),
                None,
                "unknown".into(),
                Some("plain.mov".into()),
                -1,
                2,
            ),
            // 无位置：行仍更新
            (
                "edge-no-location".into(),
                Some(1600000000000),
                "modified".into(),
                Some("plain.jpg".into()),
                -1,
                2,
            ),
            // sort_at 为空：unknown
            (
                "edge-null-sort".into(),
                None,
                "unknown".into(),
                Some("plain.mov".into()),
                -1,
                2,
            ),
            // hash_state=pending 的位置同样参与 uploads 判定
            (
                "edge-pending-location".into(),
                Some(1600000000000),
                "modified".into(),
                Some("clip.mp4".into()),
                -1,
                2,
            ),
            // 存在 uploads 位置：原名不可恢复（即使名字本身可解析）
            (
                "edge-two-locations".into(),
                Some(1600000000000),
                "modified".into(),
                Some("".into()),
                -1,
                2,
            ),
            // legacy upload：原名不可恢复
            (
                "edge-upload-legacy".into(),
                Some(1700000000000),
                "modified".into(),
                Some("".into()),
                -1,
                2,
            ),
            // 无效下界（0）：unknown
            (
                "edge-zero-sort".into(),
                None,
                "unknown".into(),
                Some("plain.mov".into()),
                -1,
                2,
            ),
        ];
        assert_eq!(rows, expected);

        // 变更事件：除无位置的资产外每条资产一条，版本与载荷取回填后的值。
        let events = sqlx::query_as::<_, (String, i64, String)>(
            "SELECT entity_id,version,payload FROM change_log ORDER BY entity_id",
        )
        .fetch_all(&pool)
        .await?;
        assert_eq!(events.len(), 9, "one event per located asset");
        let two_locations = events
            .iter()
            .find(|(id, _, _)| id == "edge-two-locations")
            .expect("edge-two-locations event");
        assert_eq!(two_locations.1, 2);
        let payload: serde_json::Value = serde_json::from_str(&two_locations.2)?;
        assert_eq!(payload["sortSource"], "modified");
        assert_eq!(payload["timeVersion"], -1);
        assert_eq!(payload["originalName"], "");
        // emit 取字典序首个位置：library 先于 uploads。
        assert_eq!(payload["path"], "users/1/library/edge-two-locations.mov");
        assert!(
            !events.iter().any(|(id, _, _)| id == "edge-no-location"),
            "assets without locations must not emit change events"
        );
        pool.close().await;
        Ok(())
    }

    fn percentile(values: &[u128], rank: f64) -> f64 {
        if values.is_empty() {
            return 0.0;
        }
        let mut sorted = values.to_vec();
        sorted.sort_unstable();
        let position = (sorted.len() - 1) as f64 * rank;
        let lower = position.floor() as usize;
        let upper = (lower + 1).min(sorted.len() - 1);
        let fraction = position - lower as f64;
        sorted[lower] as f64 + (sorted[upper] as f64 - sorted[lower] as f64) * fraction
    }

    /// 用一条全新连接执行 EXPLAIN：sqlx 的连接级语句缓存会让 DDL 之后同一连接上的
    /// 重复 EXPLAIN 复用旧计划（实测 DROP INDEX 后缓存连接仍报该索引可用），因此计划
    /// 校验不能走池里的复用连接。
    async fn explain(database: &std::path::Path, sql: &str) -> anyhow::Result<String> {
        use sqlx::Connection;
        let options = sqlx::sqlite::SqliteConnectOptions::new().filename(database);
        let mut connection = sqlx::SqliteConnection::connect_with(&options).await?;
        let rows =
            sqlx::query_as::<_, (i64, i64, i64, String)>(&format!("EXPLAIN QUERY PLAN {sql}"))
                .fetch_all(&mut connection)
                .await?;
        connection.close().await?;
        Ok(rows
            .into_iter()
            .map(|(_, _, _, detail)| detail)
            .collect::<Vec<_>>()
            .join("\n"))
    }

    async fn query_plans(database: &std::path::Path) -> anyhow::Result<serde_json::Value> {
        let mut plans = serde_json::Map::new();
        for (label, sql) in [
            ("batch", BATCH_SELECT_SQL.to_owned()),
            ("emit", emit_sql()),
            ("update", BACKFILL_UPDATE_SQL.to_owned()),
        ] {
            plans.insert(
                label.to_string(),
                serde_json::json!(explain(database, &sql).await?.lines().collect::<Vec<_>>()),
            );
        }
        Ok(serde_json::Value::Object(plans))
    }

    /// EXISTS（batch SELECT）与 emit 的分语句计时：都在真实语句上跑，事务回滚不留痕。
    async fn statement_profile(
        pool: &SqlitePool,
        select_batches: usize,
        emit_sample: usize,
    ) -> anyhow::Result<serde_json::Value> {
        let mut select_total = Duration::ZERO;
        let mut update_total = Duration::ZERO;
        let mut batches = 0_usize;
        let mut rows_total = 0_usize;
        let mut tx = crate::db::begin_write(pool).await?;
        while batches < select_batches {
            let started = Instant::now();
            let rows = sqlx::query_as::<_, (String, String, Option<i64>, i64)>(BATCH_SELECT_SQL)
                .fetch_all(&mut *tx)
                .await?;
            select_total += started.elapsed();
            if rows.is_empty() {
                break;
            }
            batches += 1;
            rows_total += rows.len();
            for (id, name, old, legacy_upload) in rows {
                let original = if legacy_upload == 1 { "" } else { &name };
                let time = resolve(original, None, old);
                let started = Instant::now();
                sqlx::query(BACKFILL_UPDATE_SQL)
                    .bind(time.at)
                    .bind(&time.source)
                    .bind(&id)
                    .bind(original)
                    .execute(&mut *tx)
                    .await?;
                update_total += started.elapsed();
            }
        }
        tx.rollback().await?;

        let ids =
            sqlx::query_scalar::<_, String>("SELECT id FROM media_assets ORDER BY id LIMIT ?1")
                .bind(i64::try_from(emit_sample).unwrap_or(i64::MAX))
                .fetch_all(pool)
                .await?;
        let mut emit_total = Duration::ZERO;
        let mut tx = crate::db::begin_write(pool).await?;
        for id in &ids {
            let started = Instant::now();
            emit(&mut tx, id, 1).await?;
            emit_total += started.elapsed();
        }
        tx.rollback().await?;

        let per_batch_rows = if batches == 0 {
            0.0
        } else {
            rows_total as f64 / batches as f64
        };
        let emit_per_statement_ms = if ids.is_empty() {
            0.0
        } else {
            emit_total.as_secs_f64() * 1000.0 / ids.len() as f64
        };
        Ok(serde_json::json!({
            "selectBatches": batches,
            "selectRows": rows_total,
            "selectTotalMs": select_total.as_secs_f64() * 1000.0,
            "selectPerBatchMs": if batches == 0 { 0.0 } else { select_total.as_secs_f64() * 1000.0 / batches as f64 },
            "updateTotalMs": update_total.as_secs_f64() * 1000.0,
            "emitSample": ids.len(),
            "emitTotalMs": emit_total.as_secs_f64() * 1000.0,
            "emitPerStatementMs": emit_per_statement_ms,
            "emitPerBatchMs": emit_per_statement_ms * per_batch_rows,
            "perBatchRows": per_batch_rows,
        }))
    }

    async fn fingerprint(pool: &SqlitePool) -> anyhow::Result<serde_json::Value> {
        let (assets, locations, uploads, sort_sum) = sqlx::query_as::<_, (i64, i64, i64, i64)>(
            "SELECT (SELECT COUNT(*) FROM media_assets),(SELECT COUNT(*) FROM media_locations),(SELECT COUNT(*) FROM media_locations WHERE normalized_path LIKE '%/uploads/%'),(SELECT COALESCE(SUM(COALESCE(sort_at,0)),0) FROM media_assets)",
        )
        .fetch_one(pool)
        .await?;
        Ok(serde_json::json!({
            "assets": assets,
            "locations": locations,
            "uploadsLocations": uploads,
            "sortAtSum": sort_sum,
        }))
    }

    /// 规模探针（默认 10 万条，`make server-test` 不跑）：
    /// 单批 256 行耗时 p50/p95、全量回填总时长，以及 EXPLAIN QUERY PLAN 与 EXISTS/emit 拆分。
    ///
    /// ```text
    /// cargo test --release --manifest-path apps/server/Cargo.toml \
    ///   media_time::tests::backfill_scale_probe -- --ignored --nocapture
    /// ```
    ///
    /// 环境变量：
    /// - `YOUYOU_BACKFILL_PROBE_MEDIA` 数据集条数（默认 100000）
    /// - `YOUYOU_BACKFILL_PROBE_DIR` 数据目录（默认临时目录；目录内已有数据则复用）
    /// - `YOUYOU_BACKFILL_PROBE_DROP_INDEX=1` 先删除 0026 索引，复现优化前基线
    /// - `YOUYOU_BACKFILL_PROBE_SELECT_BATCHES` EXISTS 计时的采样批数（默认 20）
    /// - `YOUYOU_BACKFILL_PROBE_EMIT_SAMPLE` emit 计时条数（默认 1024）
    /// - `YOUYOU_BACKFILL_PROBE_OUTPUT` 结果 JSON 落盘路径（可选）
    #[tokio::test]
    #[ignore = "100k-row scale probe; run explicitly with --ignored"]
    async fn backfill_scale_probe() -> anyhow::Result<()> {
        let media = env_usize("YOUYOU_BACKFILL_PROBE_MEDIA", 100_000);
        let select_batches = env_usize("YOUYOU_BACKFILL_PROBE_SELECT_BATCHES", 20);
        let emit_sample = env_usize("YOUYOU_BACKFILL_PROBE_EMIT_SAMPLE", 1_024);
        let drop_index = std::env::var("YOUYOU_BACKFILL_PROBE_DROP_INDEX").as_deref() == Ok("1");
        let probe_dir = std::env::var("YOUYOU_BACKFILL_PROBE_DIR")
            .ok()
            .filter(|value| !value.trim().is_empty());
        let temporary = if probe_dir.is_none() {
            Some(tempfile::tempdir()?)
        } else {
            None
        };
        let data_dir: PathBuf = match probe_dir {
            Some(path) => PathBuf::from(path),
            None => temporary
                .as_ref()
                .expect("temporary probe dir")
                .path()
                .to_path_buf(),
        };

        let pool = crate::db::connect(&data_dir).await?;
        let existing = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM media_assets")
            .fetch_one(&pool)
            .await?;
        if existing == 0 {
            let started = Instant::now();
            seed_bulk_media(&pool, media).await?;
            println!(
                "seeded {media} media in {}ms",
                started.elapsed().as_millis()
            );
        }
        if drop_index {
            sqlx::query(&format!("DROP INDEX IF EXISTS {OPTIMIZED_INDEX_NAME}"))
                .execute(&pool)
                .await?;
        } else {
            sqlx::query(PROBE_INDEX_SQL).execute(&pool).await?;
        }

        let fingerprint = fingerprint(&pool).await?;
        let plans = query_plans(&data_dir.join("youyou.db")).await?;
        let profile = statement_profile(&pool, select_batches, emit_sample).await?;

        let started = Instant::now();
        let mut batch_ms = Vec::new();
        let rows = run_backfill(&pool, |_, elapsed| batch_ms.push(elapsed)).await?;
        let total_ms = started.elapsed().as_millis();
        let report = serde_json::json!({
            "mediaCount": media,
            "optimizedIndex": !drop_index,
            "fingerprint": fingerprint,
            "plans": plans,
            "statementProfile": profile,
            "backfill": {
                "rows": rows,
                "batches": batch_ms.len(),
                "totalMs": total_ms,
                "batchP50Ms": percentile(&batch_ms, 0.5),
                "batchP95Ms": percentile(&batch_ms, 0.95),
                "batchMaxMs": batch_ms.iter().copied().max().unwrap_or_default() as f64,
            },
        });
        let encoded = serde_json::to_string_pretty(&report)?;
        println!(
            "backfill: {} rows / {} batches / total {}ms / batch p50 {:.1}ms p95 {:.1}ms / optimizedIndex={}",
            rows,
            batch_ms.len(),
            total_ms,
            percentile(&batch_ms, 0.5),
            percentile(&batch_ms, 0.95),
            !drop_index
        );
        println!("{encoded}");
        if let Ok(output) = std::env::var("YOUYOU_BACKFILL_PROBE_OUTPUT") {
            tokio::fs::write(output, format!("{encoded}\n")).await?;
        }
        pool.close().await;
        Ok(())
    }
}
