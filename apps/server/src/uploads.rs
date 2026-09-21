use std::{io, path::Path};

use axum::body::Body;
use futures_util::StreamExt;
use sha2::{Digest, Sha256};
use sqlx::SqlitePool;
use tokio::{
    fs::{self, File},
    io::AsyncWriteExt,
};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{
    error::{AppError, AppResult},
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageEntry, StorageError},
    users::USER_UPLOAD_SUBDIRECTORY,
};

pub const MAX_UPLOAD_BYTES: u64 = 50 * 1024 * 1024 * 1024;

/// 流式上传响应。
#[derive(Debug, serde::Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct StreamUploadResponse {
    pub media_id: String,
    pub path: String,
    pub size: u64,
    pub sha256: String,
}

struct UploadRow {
    id: String,
    expected_size: i64,
    expected_sha256: String,
    mime_type: Option<String>,
    file_name: String,
    taken_at: Option<i64>,
}

struct UploadTarget {
    path: String,
    already_exists: bool,
}

#[derive(Debug, PartialEq, Eq)]
enum TargetProbe {
    Missing,
    SameContent,
    DifferentContent,
}

enum UploadDestination {
    /// 客户端上传：固定落到用户媒体库的 uploads/YYYY/MM 子目录。
    UserLibrary(String),
    /// 管理端上传：指定目录（None 表示存储根）。
    AdminDirectory(Option<String>),
}

/// 客户端流式上传：写入所属用户媒体库目录，归属由目录前缀判定。
#[allow(clippy::too_many_arguments)]
pub async fn stream_upload_for_user(
    pool: &SqlitePool,
    tmp_dir: &Path,
    storage: &LocalFilesystemStorageDriver,
    library_root: &str,
    expected_size: u64,
    expected_sha256: &str,
    file_name: &str,
    mime_type: Option<&str>,
    taken_at: Option<i64>,
    timeline: Option<crate::media_time::MediaTime>,
    original_name: Option<&str>,
    body: Body,
) -> AppResult<StreamUploadResponse> {
    stream_upload_with_destination(
        pool,
        tmp_dir,
        storage,
        expected_size,
        expected_sha256,
        file_name,
        mime_type,
        taken_at,
        timeline,
        original_name,
        UploadDestination::UserLibrary(library_root.to_owned()),
        body,
    )
    .await
}

/// 管理端上传：写入指定相对目录（可为空表示媒体根），而不是强制 `uploads/YYYY/MM`。
#[allow(clippy::too_many_arguments)]
pub async fn stream_upload_to_directory(
    pool: &SqlitePool,
    tmp_dir: &Path,
    storage: &LocalFilesystemStorageDriver,
    directory: &str,
    expected_size: u64,
    expected_sha256: &str,
    file_name: &str,
    mime_type: Option<&str>,
    taken_at: Option<i64>,
    timeline: Option<crate::media_time::MediaTime>,
    original_name: Option<&str>,
    body: Body,
) -> AppResult<StreamUploadResponse> {
    let directory = LocalFilesystemStorageDriver::normalize_relative(directory)?;
    stream_upload_with_destination(
        pool,
        tmp_dir,
        storage,
        expected_size,
        expected_sha256,
        file_name,
        mime_type,
        taken_at,
        timeline,
        original_name,
        UploadDestination::AdminDirectory(Some(directory)),
        body,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn stream_upload_with_destination(
    pool: &SqlitePool,
    tmp_dir: &Path,
    storage: &LocalFilesystemStorageDriver,
    expected_size: u64,
    expected_sha256: &str,
    file_name: &str,
    mime_type: Option<&str>,
    taken_at: Option<i64>,
    timeline: Option<crate::media_time::MediaTime>,
    original_name: Option<&str>,
    destination: UploadDestination,
    body: Body,
) -> AppResult<StreamUploadResponse> {
    let expected_sha256 = normalize_sha256(expected_sha256)?;
    validate_file_name(file_name)?;
    if let Some(name) = original_name.filter(|name| !name.is_empty()) {
        validate_file_name(name)?;
    }
    if expected_size == 0 || expected_size > MAX_UPLOAD_BYTES {
        return Err(AppError::InvalidUpload(
            "stream upload: invalid expected size".to_owned(),
        ));
    }

    // 构造一个最小 UploadRow，复用 resolve_upload_target / canonical_upload_name 等逻辑。
    let upload = UploadRow {
        id: Uuid::new_v4().to_string(),
        expected_size: expected_size as i64,
        expected_sha256: expected_sha256.clone(),
        mime_type: mime_type.map(|s| s.to_owned()),
        file_name: file_name.to_owned(),
        taken_at,
    };

    let staged_path = tmp_dir.join(format!("{}.stream", upload.id));

    // 边接收边写临时文件、边算 SHA-256。
    let mut file = File::create(&staged_path).await.map_err(map_io_error)?;
    let mut hasher = Sha256::new();
    let mut actual_size: u64 = 0;
    let mut body_stream = body.into_data_stream();
    while let Some(chunk) = body_stream.next().await {
        let bytes = chunk.map_err(|e| AppError::Internal(e.into()))?;
        hasher.update(&bytes);
        file.write_all(&bytes).await.map_err(map_io_error)?;
        actual_size += bytes.len() as u64;
        if actual_size > expected_size {
            drop(file);
            let _ = fs::remove_file(&staged_path).await;
            return Err(AppError::InvalidUpload(format!(
                "stream upload: exceeded expected size {expected_size}"
            )));
        }
    }
    file.flush().await.map_err(map_io_error)?;
    drop(file);

    let actual_sha256 = format!("{:x}", hasher.finalize());

    // 校验大小和 hash。
    if actual_size != expected_size {
        let _ = fs::remove_file(&staged_path).await;
        return Err(AppError::InvalidUpload(format!(
            "stream upload size mismatch: expected {expected_size}, got {actual_size}"
        )));
    }
    if actual_sha256 != expected_sha256 {
        let _ = fs::remove_file(&staged_path).await;
        return Err(AppError::InvalidUpload(format!(
            "stream upload checksum mismatch: expected {expected_sha256}, got {actual_sha256}"
        )));
    }

    // 确定目标路径（用户媒体库的 uploads/YYYY/MM，或管理端指定目录）。
    let target = match &destination {
        UploadDestination::UserLibrary(root) => {
            resolve_upload_target(storage, &upload, Some(root)).await?
        }
        UploadDestination::AdminDirectory(directory) => {
            resolve_directory_upload_target(storage, directory.as_deref().unwrap_or(""), &upload)
                .await?
        }
    };
    let target_path = target.path.clone();

    // 写入存储（已存在相同内容则跳过）。
    if !target.already_exists {
        let staged_file = File::open(&staged_path).await.map_err(map_io_error)?;
        if let Err(error) = storage
            .write_atomic(&target_path, Box::new(staged_file))
            .await
        {
            let _ = fs::remove_file(&staged_path).await;
            return Err(error.into());
        }
    }

    // 索引媒体（写入 media_assets / media_locations）。
    let stat = storage.stat(&target_path).await?;
    let entry = StorageEntry {
        name: target_path
            .rsplit('/')
            .next()
            .unwrap_or(file_name)
            .to_owned(),
        path: target_path.clone(),
        is_directory: false,
        size: Some(stat.size),
        modified_at: stat.modified_at,
    };
    if let Err(error) = crate::scan::index_media_with_time(
        pool,
        storage,
        &entry,
        upload.mime_type.as_deref().map(Some),
        upload.taken_at.map(Some),
        timeline,
        Some(original_name.unwrap_or(file_name)),
    )
    .await
    {
        let _ = fs::remove_file(&staged_path).await;
        return Err(AppError::Internal(error));
    }

    let media_id = sqlx::query_scalar::<_, String>(
        "SELECT media_asset_id FROM media_locations WHERE storage_id = 'local' AND normalized_path = ?1",
    )
    .bind(&target_path)
    .fetch_one(pool)
    .await?;

    // 清理临时文件。
    let _ = fs::remove_file(&staged_path).await;

    Ok(StreamUploadResponse {
        media_id,
        path: target_path,
        size: actual_size,
        sha256: actual_sha256,
    })
}

// ── 目标路径解析 ──────────────────────────────────────────────

async fn resolve_upload_target(
    storage: &LocalFilesystemStorageDriver,
    upload: &UploadRow,
    library_root: Option<&str>,
) -> AppResult<UploadTarget> {
    let canonical_name = canonical_upload_name(upload);
    let date_directory = upload_date_directory(&canonical_name, upload.taken_at);
    let base_directory = match library_root {
        Some(root) => format!("{root}/{USER_UPLOAD_SUBDIRECTORY}/{date_directory}"),
        None => format!("{USER_UPLOAD_SUBDIRECTORY}/{date_directory}"),
    };
    let base_path = format!("{base_directory}/{canonical_name}");
    resolve_collision_path(
        storage,
        upload,
        &base_path,
        &base_directory,
        &canonical_name,
    )
    .await
}

async fn resolve_directory_upload_target(
    storage: &LocalFilesystemStorageDriver,
    directory: &str,
    upload: &UploadRow,
) -> AppResult<UploadTarget> {
    validate_file_name(&upload.file_name)?;
    let file_name = upload.file_name.clone();
    let base_path = if directory.is_empty() {
        file_name.clone()
    } else {
        format!("{directory}/{file_name}")
    };
    match probe_upload_target(storage, &base_path, upload).await? {
        TargetProbe::Missing => Ok(UploadTarget {
            path: base_path,
            already_exists: false,
        }),
        TargetProbe::SameContent => Ok(UploadTarget {
            path: base_path,
            already_exists: true,
        }),
        TargetProbe::DifferentContent => {
            let stem = file_name
                .rsplit_once('.')
                .map(|(stem, _)| stem)
                .unwrap_or(&file_name);
            let extension = file_name
                .rsplit_once('.')
                .map(|(_, ext)| format!(".{ext}"))
                .unwrap_or_default();
            let hashed_name = format!(
                "{stem}_h{hash_prefix}{extension}",
                hash_prefix = &upload.expected_sha256[..8.min(upload.expected_sha256.len())],
            );
            let hash_path = if directory.is_empty() {
                hashed_name
            } else {
                format!("{directory}/{hashed_name}")
            };
            match probe_upload_target(storage, &hash_path, upload).await? {
                TargetProbe::Missing => Ok(UploadTarget {
                    path: hash_path,
                    already_exists: false,
                }),
                TargetProbe::SameContent => Ok(UploadTarget {
                    path: hash_path,
                    already_exists: true,
                }),
                TargetProbe::DifferentContent => Err(AppError::Conflict(
                    "the upload name is occupied by different content".to_owned(),
                )),
            }
        }
    }
}

async fn resolve_collision_path(
    storage: &LocalFilesystemStorageDriver,
    upload: &UploadRow,
    base_path: &str,
    base_directory: &str,
    canonical_name: &str,
) -> AppResult<UploadTarget> {
    match probe_upload_target(storage, base_path, upload).await? {
        TargetProbe::Missing => Ok(UploadTarget {
            path: base_path.to_owned(),
            already_exists: false,
        }),
        TargetProbe::SameContent => Ok(UploadTarget {
            path: base_path.to_owned(),
            already_exists: true,
        }),
        TargetProbe::DifferentContent => {
            let hash_path = format!(
                "{base_directory}/{}",
                append_upload_content_hash(canonical_name, &upload.expected_sha256, 8),
            );
            match probe_upload_target(storage, &hash_path, upload).await? {
                TargetProbe::Missing => Ok(UploadTarget {
                    path: hash_path,
                    already_exists: false,
                }),
                TargetProbe::SameContent => Ok(UploadTarget {
                    path: hash_path,
                    already_exists: true,
                }),
                TargetProbe::DifferentContent => {
                    let full_hash_path = format!(
                        "{base_directory}/{}",
                        append_upload_content_hash(
                            canonical_name,
                            &upload.expected_sha256,
                            upload.expected_sha256.len(),
                        ),
                    );
                    match probe_upload_target(storage, &full_hash_path, upload).await? {
                        TargetProbe::Missing => Ok(UploadTarget {
                            path: full_hash_path,
                            already_exists: false,
                        }),
                        TargetProbe::SameContent => Ok(UploadTarget {
                            path: full_hash_path,
                            already_exists: true,
                        }),
                        TargetProbe::DifferentContent => Err(AppError::Conflict(
                            "the canonical upload name is occupied by different content".to_owned(),
                        )),
                    }
                }
            }
        }
    }
}

async fn probe_upload_target(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
    upload: &UploadRow,
) -> Result<TargetProbe, StorageError> {
    let stat = match storage.stat(path).await {
        Ok(stat) => stat,
        Err(StorageError::NotFound(_)) => return Ok(TargetProbe::Missing),
        Err(error) => return Err(error),
    };
    if stat.is_directory || stat.size != u64::try_from(upload.expected_size).unwrap_or(u64::MAX) {
        return Ok(TargetProbe::DifferentContent);
    }
    let (hash, size) = storage.hash_sha256(path).await?;
    if size == u64::try_from(upload.expected_size).unwrap_or(u64::MAX)
        && hash == upload.expected_sha256
    {
        Ok(TargetProbe::SameContent)
    } else {
        Ok(TargetProbe::DifferentContent)
    }
}

// ── 命名规则 ──────────────────────────────────────────────────

fn canonical_upload_name(upload: &UploadRow) -> String {
    if parse_rule_name(&upload.file_name).is_some() {
        return upload.file_name.clone();
    }
    let extension = upload_file_extension(&upload.file_name);
    let prefix = if is_video_upload(upload) {
        "VID_"
    } else {
        "IMG_"
    };
    if let Some((year, month, day, hour, minute, second, millisecond)) =
        upload_timestamp_components(upload.taken_at)
    {
        let stamp = if millisecond > 0 {
            format!("{year:04}{month:02}{day:02}_{hour:02}{minute:02}{second:02}_{millisecond:03}")
        } else {
            format!("{year:04}{month:02}{day:02}_{hour:02}{minute:02}{second:02}")
        };
        return format!("{prefix}{stamp}{extension}");
    }
    format!(
        "{prefix}nodate_h{}{}",
        &upload.expected_sha256[..8],
        extension
    )
}

fn upload_date_directory(file_name: &str, taken_at: Option<i64>) -> String {
    parse_rule_name(file_name)
        .map(|parts| format!("{:04}/{:02}", parts.0, parts.1))
        .or_else(|| {
            upload_timestamp_components(taken_at)
                .map(|parts| format!("{:04}/{:02}", parts.0, parts.1))
        })
        .unwrap_or_else(|| "unknown".to_owned())
}

fn append_upload_content_hash(file_name: &str, hash: &str, length: usize) -> String {
    let extension = upload_file_extension(file_name);
    let stem = file_name.strip_suffix(&extension).unwrap_or(file_name);
    let stem = strip_upload_hash_suffix(stem);
    format!("{stem}_h{}{}", &hash[..length.min(hash.len())], extension)
}

fn strip_upload_hash_suffix(stem: &str) -> &str {
    let Some(index) = stem.rfind("_h") else {
        return stem;
    };
    let suffix = &stem[index + 2..];
    if !suffix.is_empty() && suffix.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        &stem[..index]
    } else {
        stem
    }
}

fn upload_file_extension(file_name: &str) -> String {
    let Some(index) = file_name.rfind('.') else {
        return String::new();
    };
    if index == 0 || index + 1 >= file_name.len() {
        return String::new();
    }
    file_name[index..].to_owned()
}

fn is_video_upload(upload: &UploadRow) -> bool {
    upload
        .mime_type
        .as_deref()
        .is_some_and(|mime| mime.starts_with("video/"))
        || crate::media_format::is_video_path(&upload.file_name)
}

pub(crate) fn parse_rule_name(file_name: &str) -> Option<(i32, u32, u32, u32, u32, u32, u32)> {
    let bytes = file_name.as_bytes();
    if bytes.len() < 19
        || !(file_name.starts_with("IMG_") || file_name.starts_with("VID_"))
        || bytes[12] != b'_'
    {
        return None;
    }
    let year = parse_ascii_digits(bytes, 4, 8)?;
    let month = parse_ascii_digits(bytes, 8, 10)?;
    let day = parse_ascii_digits(bytes, 10, 12)?;
    let hour = parse_ascii_digits(bytes, 13, 15)?;
    let minute = parse_ascii_digits(bytes, 15, 17)?;
    let second = parse_ascii_digits(bytes, 17, 19)?;
    if !(1971..=9999).contains(&year)
        || !(1..=12).contains(&month)
        || !(1..=days_in_month(year, month)).contains(&day)
        || hour > 23
        || minute > 59
        || second > 59
    {
        return None;
    }
    let mut tail = &file_name[19..];
    let mut millisecond = 0;
    if tail.starts_with('_') && tail.as_bytes().get(1).is_some_and(u8::is_ascii_digit) {
        if tail.len() < 4 {
            return None;
        }
        millisecond = parse_ascii_digits(tail.as_bytes(), 1, 4)?;
        tail = &tail[4..];
    }
    if let Some(hash) = tail.strip_prefix("_h") {
        let len = hash.bytes().take_while(u8::is_ascii_hexdigit).count();
        if len == 0 || len > 64 {
            return None;
        }
        tail = &hash[len..];
    }
    if !tail.is_empty()
        && !tail
            .strip_prefix('.')
            .is_some_and(|ext| !ext.is_empty() && ext.bytes().all(|b| b.is_ascii_alphanumeric()))
    {
        return None;
    }
    Some((
        year,
        month as u32,
        day as u32,
        hour as u32,
        minute as u32,
        second as u32,
        millisecond as u32,
    ))
}

fn parse_ascii_digits(bytes: &[u8], start: usize, end: usize) -> Option<i32> {
    if end > bytes.len() || start >= end {
        return None;
    }
    let mut value = 0_i32;
    for byte in &bytes[start..end] {
        if !byte.is_ascii_digit() {
            return None;
        }
        value = value * 10 + i32::from(byte - b'0');
    }
    Some(value)
}

fn upload_timestamp_components(
    timestamp_ms: Option<i64>,
) -> Option<(i32, u32, u32, u32, u32, u32, u32)> {
    let timestamp_ms = timestamp_ms?;
    if timestamp_ms <= 0 {
        return None;
    }
    let days = timestamp_ms.div_euclid(86_400_000);
    let day_millis = timestamp_ms.rem_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    if !(1971..=9999).contains(&year) {
        return None;
    }
    let hour = day_millis / 3_600_000;
    let minute = (day_millis % 3_600_000) / 60_000;
    let second = (day_millis % 60_000) / 1_000;
    let millisecond = day_millis % 1_000;
    Some((
        year,
        month,
        day,
        hour as u32,
        minute as u32,
        second as u32,
        millisecond as u32,
    ))
}

fn civil_from_days(days_since_unix_epoch: i64) -> (i32, u32, u32) {
    let z = days_since_unix_epoch + 719_468;
    let era = if z >= 0 {
        z / 146_097
    } else {
        (z - 146_096) / 146_097
    };
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_part = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_part + 2) / 5 + 1;
    let month = month_part + if month_part < 10 { 3 } else { -9 };
    let year = year + if month <= 2 { 1 } else { 0 };
    (year as i32, month as u32, day as u32)
}

fn days_in_month(year: i32, month: i32) -> i32 {
    match month {
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

// ── 校验与工具 ────────────────────────────────────────────────

fn validate_file_name(file_name: &str) -> AppResult<()> {
    if file_name.is_empty()
        || file_name == "."
        || file_name == ".."
        || file_name.len() > 255
        || file_name.contains('/')
        || file_name.contains('\\')
        || file_name.contains('\0')
        || file_name.chars().any(char::is_control)
    {
        return Err(AppError::InvalidUpload(
            "fileName must be one safe file name without path separators".to_owned(),
        ));
    }
    Ok(())
}

fn normalize_sha256(value: &str) -> AppResult<String> {
    if value.len() != 64 || hex::decode(value).is_err() {
        return Err(AppError::InvalidUpload(
            "SHA-256 must be a 64-character hexadecimal value".to_owned(),
        ));
    }
    Ok(value.to_ascii_lowercase())
}

fn map_io_error(error: io::Error) -> AppError {
    if error.kind() == io::ErrorKind::StorageFull {
        AppError::DiskFull
    } else {
        AppError::Internal(anyhow::Error::new(error))
    }
}
