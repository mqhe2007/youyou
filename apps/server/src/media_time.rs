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
/// Bounded, restartable historical correction, with ordinary change events for existing clients.
pub async fn backfill(pool: &SqlitePool) -> anyhow::Result<()> {
    let mut count = 0;
    loop {
        let rows = sqlx::query_as::<_, (String, String, Option<i64>, i64)>(
            "SELECT a.id,a.name,a.sort_at,EXISTS(SELECT 1 FROM media_locations l WHERE l.media_asset_id=a.id AND l.normalized_path LIKE '%/uploads/%') FROM media_assets a WHERE a.time_version=0 ORDER BY a.id LIMIT 256",
        )
        .fetch_all(pool)
        .await?;
        if rows.is_empty() {
            break;
        }
        let mut tx = pool.begin().await?;
        let revision = crate::sync::allocate_revision(&mut tx).await?;
        for (id, name, old, legacy_upload) in rows {
            // Legacy taken_at may actually be Android DATE_ADDED; do not promote it to capture.
            let original = if legacy_upload == 1 { "" } else { &name };
            let time = resolve(original, None, old);
            sqlx::query("UPDATE media_assets SET sort_at=?1,sort_source=?2,original_name=?4,time_version=-1,version=version+1 WHERE id=?3 AND time_version=0")
                .bind(time.at).bind(&time.source).bind(&id).bind(original).execute(&mut *tx).await?;
            emit(&mut tx, &id, revision).await?;
            count += 1;
        }
        tx.commit().await?;
    }
    if count > 0 {
        tracing::info!(count, "media time history normalized");
    }
    // Also recover a crash between the last backfill batch and scheduling verification.
    sqlx::query("INSERT INTO jobs(id,kind,status,created_at,updated_at) SELECT ?1,'scan','queued',?2,?2 WHERE EXISTS(SELECT 1 FROM media_assets WHERE time_version=-1) AND NOT EXISTS(SELECT 1 FROM jobs WHERE kind='scan' AND status IN ('queued','running'))")
        .bind(uuid::Uuid::new_v4().to_string()).bind(crate::db::now_millis()).execute(pool).await?;
    Ok(())
}
pub async fn emit(tx: &mut sqlx::SqliteConnection, id: &str, revision: i64) -> anyhow::Result<()> {
    sqlx::query(r#"INSERT INTO change_log(revision,event_id,entity,operation,entity_id,version,payload,created_at,owner_user_id)
      SELECT ?1,?2,'media','upsert',a.id,a.version,json_object(
      'id',a.id,'name',a.name,'path',l.normalized_path,'size',l.size,'contentHash',b.content_hash,
      'mimeType',a.mime_type,'isVideo',json(CASE WHEN a.is_video=1 THEN 'true' ELSE 'false' END),
      'storageId',l.storage_id,'identityState',a.identity_state,'hashState',l.hash_state,
      'width',a.width,'height',a.height,'durationMs',a.duration_ms,'takenAt',a.taken_at,
      'sortAt',a.sort_at,'sortSource',a.sort_source,'timeVersion',a.time_version,'originalName',a.original_name),?3,a.owner_user_id
      FROM media_assets a JOIN media_locations l ON l.media_asset_id=a.id LEFT JOIN content_blobs b ON b.id=a.blob_id
      WHERE a.id=?4 ORDER BY l.normalized_path LIMIT 1"#)
      .bind(revision).bind(uuid::Uuid::new_v4().to_string()).bind(crate::db::now_millis()).bind(id).execute(&mut *tx).await?;
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
}
