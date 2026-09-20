//! Admin media library: browse indexed media as a folder grid and perform
//! basic file operations (mkdir / move / delete / upload / download).

use std::{collections::BTreeMap, io::ErrorKind};

use axum::{
    Json, Router,
    body::Body,
    extract::{Path, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::Response,
    routing::{get, post},
};
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{FromRow, SqlitePool};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{
    api::AppState,
    audit, auth,
    db::now_millis,
    error::{AppError, AppResult},
    storage::{LocalFilesystemStorageDriver, StorageDriver, StorageError},
    sync, uploads,
};

pub fn routes() -> Router<AppState> {
    Router::new()
        .route("/api/v1/admin/media-library", get(list_library))
        .route("/api/v1/admin/media-library/mkdir", post(mkdir_library))
        .route("/api/v1/admin/media-library/move", post(move_library))
        .route(
            "/api/v1/admin/media-library/entries",
            axum::routing::delete(delete_library_entry),
        )
        .route(
            "/api/v1/admin/media-library/media/{id}/content",
            get(library_media_content),
        )
        .route(
            "/api/v1/admin/media-library/media/{id}/thumbnail",
            get(library_media_thumbnail),
        )
        .route(
            "/api/v1/admin/media-library/media/{id}/info",
            get(library_media_info),
        )
}

/// Upload routes are mounted without the global short request timeout.
pub fn upload_routes() -> Router<AppState> {
    Router::new().route("/api/v1/admin/media-library/upload", post(upload_library))
}

#[derive(Debug, Deserialize)]
pub(crate) struct LibraryPathQuery {
    path: Option<String>,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MkdirRequest {
    path: String,
}

#[derive(Debug, Deserialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub(crate) struct MoveRequest {
    from: String,
    to: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct DeleteQuery {
    /// `folder` or `media`
    kind: String,
    /// Folder relative path, or media asset id when kind=media
    target: String,
}

#[derive(Debug, Deserialize)]
pub(crate) struct UploadQuery {
    path: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ThumbnailQuery {
    size: Option<u32>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LibraryListResponse {
    pub path: String,
    pub folders: Vec<LibraryFolder>,
    pub media: Vec<LibraryMedia>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LibraryFolder {
    pub path: String,
    pub name: String,
    pub media_count: u64,
    /// First media inside this folder tree, in the same order the media grid
    /// renders (`sort_at` descending, ties broken by id ascending). Used as the
    /// folder tile thumbnail. `None` when the folder has no indexed media.
    pub thumbnail_media_id: Option<String>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LibraryMedia {
    pub id: String,
    pub name: String,
    pub path: String,
    pub size: u64,
    pub mime_type: Option<String>,
    pub is_video: bool,
    pub width: Option<u64>,
    pub height: Option<u64>,
    pub taken_at: Option<i64>,
    pub sort_at: Option<i64>,
}

/// Full metadata for one media asset, mirroring the fields the Android client
/// shows in its "详细信息" sheet so both surfaces tell the same story.
#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LibraryMediaInfo {
    pub id: String,
    pub name: String,
    pub original_name: Option<String>,
    /// EXIF capture time in milliseconds.
    pub taken_at: Option<i64>,
    /// Fallback ordering time in milliseconds.
    pub sort_at: Option<i64>,
    /// Where `sort_at` came from: `exif`, `filename` or `unknown`.
    pub sort_source: String,
    /// Filesystem modification time in milliseconds.
    pub modified_at: Option<i64>,
    pub width: Option<u64>,
    pub height: Option<u64>,
    pub size: u64,
    pub mime_type: Option<String>,
    pub is_video: bool,
    pub duration_ms: Option<i64>,
    pub video_codec: Option<String>,
    /// Media library directory name, i.e. the first path segment.
    pub library: String,
    /// Path relative to the media root.
    pub path: String,
    pub content_hash: Option<String>,
    /// Camera parameters, read from the file on demand. Images only.
    pub exif: Option<LibraryExif>,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct LibraryExif {
    pub camera_model: Option<String>,
    pub iso: Option<String>,
    pub aperture: Option<String>,
    pub focal_length: Option<String>,
    pub exposure_time: Option<String>,
}

#[derive(Debug, FromRow)]
struct IndexedLocationRow {
    media_id: String,
    name: String,
    normalized_path: String,
    size: i64,
    mime_type: Option<String>,
    is_video: i64,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
}

/// Roll-up of the indexed media nested under one direct child folder.
#[derive(Debug, Default)]
struct FolderAggregate {
    media_count: u64,
    /// `(sort_at, media_id)` of the entry that renders first when entering the
    /// folder. Kept in sync with the grid ordering applied to `media`.
    thumbnail: Option<(Option<i64>, String)>,
}

/// Mirrors the media grid ordering (`sort_at` descending, ties broken by id
/// ascending) so a folder tile previews exactly what the folder opens with.
fn is_preferred_thumbnail(
    current: Option<&(Option<i64>, String)>,
    sort_at: Option<i64>,
    media_id: &str,
) -> bool {
    let Some((current_sort_at, current_id)) = current else {
        return true;
    };
    match sort_at.cmp(current_sort_at) {
        std::cmp::Ordering::Greater => true,
        std::cmp::Ordering::Less => false,
        std::cmp::Ordering::Equal => media_id < current_id.as_str(),
    }
}

#[derive(Debug, FromRow)]
struct MediaServeRow {
    id: String,
    normalized_path: String,
    mime_type: Option<String>,
    content_hash: Option<String>,
    version: i64,
    is_video: i64,
}

#[derive(Debug, FromRow)]
struct MediaInfoRow {
    id: String,
    name: String,
    original_name: Option<String>,
    mime_type: Option<String>,
    is_video: i64,
    duration_ms: Option<i64>,
    video_codec: Option<String>,
    width: Option<i64>,
    height: Option<i64>,
    taken_at: Option<i64>,
    sort_at: Option<i64>,
    sort_source: String,
    normalized_path: String,
    size: i64,
    modified_at: Option<i64>,
    content_hash: Option<String>,
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/media-library",
    tag = "administration",
    params(
        ("path" = Option<String>, Query, description = "Current folder relative path")
    ),
    responses(
        (status = 200, description = "Library listing", body = LibraryListResponse),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn list_library(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<LibraryPathQuery>,
) -> AppResult<Json<LibraryListResponse>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let path = normalize_library_path(query.path.as_deref().unwrap_or(""))?;
    let listing = build_listing(&state.db, state.storage.snapshot().await.as_ref(), &path).await?;
    Ok(Json(listing))
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/media-library/mkdir",
    tag = "administration",
    request_body = MkdirRequest,
    responses(
        (status = 204, description = "Folder created"),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn mkdir_library(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<MkdirRequest>,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, true).await?;
    let path = normalize_library_path(&request.path)?;
    if path.is_empty() {
        return Err(AppError::BadRequest(
            "folder path must not be empty".to_owned(),
        ));
    }
    if path
        .rsplit('/')
        .next()
        .is_some_and(|name| name.is_empty() || name == "." || name == "..")
    {
        return Err(AppError::BadRequest("invalid folder name".to_owned()));
    }
    let storage = state.storage.snapshot().await;
    storage.mkdir(&path).await?;
    let _ = audit::record(
        &state.db,
        "admin",
        "media_library.mkdir",
        &path,
        audit::SUCCESS,
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/media-library/move",
    tag = "administration",
    request_body = MoveRequest,
    responses(
        (status = 204, description = "Moved"),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn move_library(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<MoveRequest>,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, true).await?;
    let from = normalize_library_path(&request.from)?;
    let to = normalize_library_path(&request.to)?;
    if from.is_empty() || to.is_empty() {
        return Err(AppError::BadRequest(
            "from and to paths are required".to_owned(),
        ));
    }
    if from == to {
        return Ok(StatusCode::NO_CONTENT);
    }
    if to == from || to.starts_with(&(from.to_owned() + "/")) {
        return Err(AppError::BadRequest(
            "cannot move a folder into itself".to_owned(),
        ));
    }

    let storage = state.storage.snapshot().await;
    match storage.stat(&to).await {
        Ok(_) => {
            return Err(AppError::Conflict("target path already exists".to_owned()));
        }
        Err(StorageError::NotFound(_)) => {}
        Err(error) => return Err(error.into()),
    }

    let source_stat = storage.stat(&from).await?;
    storage
        .move_path(&from, &to)
        .await
        .map_err(|error| match error {
            StorageError::Io(io) if io.kind() == ErrorKind::AlreadyExists => {
                AppError::Conflict("target path already exists".to_owned())
            }
            other => other.into(),
        })?;

    if source_stat.is_directory {
        rewrite_folder_locations(&state.db, &from, &to).await?;
    } else {
        rewrite_media_location(&state.db, &from, &to).await?;
    }

    let _ = audit::record(
        &state.db,
        "admin",
        "media_library.move",
        &format!("{from}->{to}"),
        audit::SUCCESS,
    )
    .await;
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    delete,
    path = "/api/v1/admin/media-library/entries",
    tag = "administration",
    params(
        ("kind" = String, Query, description = "folder or media"),
        ("target" = String, Query, description = "folder path or media id")
    ),
    responses(
        (status = 204, description = "Deleted"),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Conflict")
    )
)]
pub(crate) async fn delete_library_entry(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<DeleteQuery>,
) -> AppResult<StatusCode> {
    auth::require_admin(&state.db, &headers, true).await?;
    let storage = state.storage.snapshot().await;
    match query.kind.as_str() {
        "media" => {
            delete_media(&state.db, storage.as_ref(), &state.trash, &query.target).await?;
            let _ = audit::record(
                &state.db,
                "admin",
                "media_library.delete_media",
                &query.target,
                audit::SUCCESS,
            )
            .await;
        }
        "folder" => {
            let path = normalize_library_path(&query.target)?;
            delete_empty_folder(&state.db, storage.as_ref(), &path).await?;
            let _ = audit::record(
                &state.db,
                "admin",
                "media_library.delete_folder",
                &path,
                audit::SUCCESS,
            )
            .await;
        }
        _ => {
            return Err(AppError::BadRequest(
                "kind must be folder or media".to_owned(),
            ));
        }
    }
    Ok(StatusCode::NO_CONTENT)
}

#[utoipa::path(
    post,
    path = "/api/v1/admin/media-library/upload",
    tag = "administration",
    params(
        ("path" = Option<String>, Query, description = "Target folder path")
    ),
    responses(
        (status = 200, description = "Uploaded", body = uploads::StreamUploadResponse),
        (status = 400, description = "Bad request"),
        (status = 401, description = "Unauthorized")
    )
)]
pub(crate) async fn upload_library(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<UploadQuery>,
    body: Body,
) -> AppResult<Json<uploads::StreamUploadResponse>> {
    auth::require_admin(&state.db, &headers, true).await?;
    let directory = normalize_library_path(query.path.as_deref().unwrap_or(""))?;
    let expected_size = header_u64(&headers, "x-expected-size")?;
    let expected_sha256 = header_str(&headers, "x-expected-sha256")?;
    let file_name = header_str(&headers, "x-file-name")?;
    let mime_type = headers.get("x-mime-type").and_then(|v| v.to_str().ok());
    let taken_at = headers
        .get("x-taken-at")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<i64>().ok());

    let storage = state.storage.snapshot().await;
    if !directory.is_empty() {
        // Ensure parent folder exists (mkdir is idempotent via create_dir_all).
        storage.mkdir(&directory).await?;
    }

    let result = uploads::stream_upload_to_directory(
        &state.db,
        &state.tmp_dir,
        storage.as_ref(),
        &directory,
        expected_size,
        expected_sha256,
        file_name,
        mime_type,
        taken_at,
        None,
        None,
        body,
    )
    .await?;

    let _ = audit::record(
        &state.db,
        "admin",
        "media_library.upload",
        &result.path,
        audit::SUCCESS,
    )
    .await;
    Ok(Json(result))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/media-library/media/{id}/content",
    tag = "administration",
    params(("id" = String, Path, description = "Media ID")),
    responses(
        (status = 200, description = "Media content", content_type = "application/octet-stream", body = [u8]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found")
    )
)]
pub(crate) async fn library_media_content(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Response> {
    auth::require_admin(&state.db, &headers, false).await?;
    let row = find_verified_media(&state.db, &id).await?;
    let (stat, stream) = state
        .storage
        .read_stream(&row.normalized_path, None)
        .await?;
    let body_stream =
        stream.map(|chunk| chunk.map_err(|error| std::io::Error::other(error.to_string())));
    let mut response = Response::new(Body::from_stream(body_stream));
    *response.status_mut() = StatusCode::OK;
    let content_type = row
        .mime_type
        .or_else(|| {
            mime_guess::from_path(&row.normalized_path)
                .first_raw()
                .map(str::to_owned)
        })
        .unwrap_or_else(|| "application/octet-stream".to_owned());
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_str(&content_type)
            .unwrap_or_else(|_| HeaderValue::from_static("application/octet-stream")),
    );
    response.headers_mut().insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(&stat.size.to_string())
            .expect("content length is always an ASCII integer"),
    );
    let file_name = row
        .normalized_path
        .rsplit('/')
        .next()
        .unwrap_or("download")
        .to_owned();
    if let Ok(value) = HeaderValue::from_str(&format!(
        "attachment; filename=\"{}\"",
        file_name.replace('"', "")
    )) {
        response
            .headers_mut()
            .insert(header::CONTENT_DISPOSITION, value);
    }
    Ok(response)
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/media-library/media/{id}/thumbnail",
    tag = "administration",
    params(
        ("id" = String, Path, description = "Media ID"),
        ("size" = Option<u32>, Query, description = "Thumbnail size")
    ),
    responses(
        (status = 200, description = "Thumbnail", content_type = "image/jpeg", body = [u8]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found")
    )
)]
pub(crate) async fn library_media_thumbnail(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
    Query(query): Query<ThumbnailQuery>,
) -> AppResult<Response> {
    auth::require_admin(&state.db, &headers, false).await?;
    let row = find_verified_media(&state.db, &id).await?;
    let size = query.size.unwrap_or(256).clamp(64, 1024);
    let cache_dir = state.data_dir.join("thumbnails");
    let cache_key = Sha256::digest(
        format!(
            "{}:{}:{}:{}",
            row.id,
            row.version,
            row.content_hash.as_deref().unwrap_or(""),
            size
        )
        .as_bytes(),
    );
    let cache_path = cache_dir.join(format!("{}.jpg", hex::encode(cache_key)));
    if let Ok(bytes) = tokio::fs::read(&cache_path).await
        && !bytes.is_empty()
    {
        return Ok(jpeg_response(bytes));
    }

    let storage = state.storage.snapshot().await;
    let bytes = if row.is_video == 1 {
        generate_video_thumbnail(storage.as_ref(), &row, size).await?
    } else {
        let source = storage.read_all(&row.normalized_path, None).await?;
        let decoded = image::load_from_memory(&source).map_err(|error| {
            AppError::Internal(anyhow::anyhow!("decode thumbnail source: {error}"))
        })?;
        let thumbnail = decoded.thumbnail(size, size);
        let mut encoded = std::io::Cursor::new(Vec::new());
        thumbnail
            .write_to(&mut encoded, image::ImageFormat::Jpeg)
            .map_err(|error| AppError::Internal(anyhow::anyhow!("encode thumbnail: {error}")))?;
        encoded.into_inner()
    };

    tokio::fs::create_dir_all(&cache_dir)
        .await
        .map_err(|error| AppError::Internal(error.into()))?;
    let temporary = cache_dir.join(format!(".{}.tmp-{}", row.id, Uuid::new_v4()));
    tokio::fs::write(&temporary, &bytes)
        .await
        .map_err(|error| AppError::Internal(error.into()))?;
    if let Err(error) = tokio::fs::rename(&temporary, &cache_path).await {
        let _ = tokio::fs::remove_file(&temporary).await;
        return Err(AppError::Internal(error.into()));
    }
    Ok(jpeg_response(bytes))
}

#[utoipa::path(
    get,
    path = "/api/v1/admin/media-library/media/{id}/info",
    tag = "administration",
    params(("id" = String, Path, description = "Media ID")),
    responses(
        (status = 200, description = "Media metadata", body = LibraryMediaInfo),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Not found")
    )
)]
pub(crate) async fn library_media_info(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> AppResult<Json<LibraryMediaInfo>> {
    auth::require_admin(&state.db, &headers, false).await?;
    let row = find_media_info(&state.db, &id).await?;

    // Camera parameters are not persisted by the scanner; read them on demand
    // so the admin sheet can show what the client shows.
    let exif = if row.is_video == 0 {
        let storage = state.storage.snapshot().await;
        match storage.read_all(&row.normalized_path, None).await {
            Ok(bytes) => read_exif_info(&bytes),
            Err(_) => None,
        }
    } else {
        None
    };

    let library = row
        .normalized_path
        .split('/')
        .next()
        .unwrap_or_default()
        .to_owned();

    Ok(Json(LibraryMediaInfo {
        id: row.id,
        name: row.name,
        original_name: row.original_name,
        taken_at: row.taken_at,
        sort_at: row.sort_at,
        sort_source: row.sort_source,
        modified_at: row.modified_at,
        width: row.width.and_then(|value| u64::try_from(value).ok()),
        height: row.height.and_then(|value| u64::try_from(value).ok()),
        size: u64::try_from(row.size).unwrap_or(0),
        mime_type: row.mime_type,
        is_video: row.is_video != 0,
        duration_ms: row.duration_ms,
        video_codec: row.video_codec,
        library,
        path: row.normalized_path,
        content_hash: row.content_hash,
        exif,
    }))
}

/// Mirrors the camera fields the Android client reads with `ExifInterface`.
/// Returns `None` when the file carries no usable camera metadata.
fn read_exif_info(bytes: &[u8]) -> Option<LibraryExif> {
    let mut cursor = std::io::Cursor::new(bytes);
    let exif = exif::Reader::new().read_from_container(&mut cursor).ok()?;

    let text = |tag: exif::Tag| -> Option<String> {
        let value = exif_field(&exif, tag)?.display_value().to_string();
        let value = value.trim().trim_matches('"').to_owned();
        (!value.is_empty()).then_some(value)
    };
    let rational =
        |tag: exif::Tag| -> Option<f64> { exif_rational(&exif_field(&exif, tag)?.value, 0) };

    let info = LibraryExif {
        camera_model: text(exif::Tag::Model),
        iso: exif_field(&exif, exif::Tag::PhotographicSensitivity)
            .and_then(|field| field.value.get_uint(0))
            .map(|value| value.to_string()),
        aperture: rational(exif::Tag::FNumber).map(|value| format!("f/{value:.1}")),
        focal_length: rational(exif::Tag::FocalLength).map(|value| format!("{value:.1}mm")),
        exposure_time: rational(exif::Tag::ExposureTime).map(|value| {
            if value > 0.0 && value < 1.0 {
                format!("1/{}s", (1.0 / value).round() as i64)
            } else {
                format!("{value:.1}s")
            }
        }),
    };

    let empty = info.camera_model.is_none()
        && info.iso.is_none()
        && info.aperture.is_none()
        && info.focal_length.is_none()
        && info.exposure_time.is_none();
    (!empty).then_some(info)
}

/// Looks a tag up across every IFD. Camera parameters such as aperture and
/// shutter speed live in the Exif sub-IFD, so `In::PRIMARY` alone would miss
/// nearly everything a real camera writes.
fn exif_field(exif: &exif::Exif, tag: exif::Tag) -> Option<&exif::Field> {
    exif.fields().find(|field| field.tag == tag)
}

/// EXIF rationals arrive as numerator/denominator pairs; the client formats
/// the derived float, so do the same here.
fn exif_rational(value: &exif::Value, index: usize) -> Option<f64> {
    match value {
        exif::Value::Rational(values) => values.get(index).map(exif::Rational::to_f64),
        exif::Value::SRational(values) => values.get(index).map(exif::SRational::to_f64),
        _ => None,
    }
}

async fn generate_video_thumbnail(
    storage: &LocalFilesystemStorageDriver,
    row: &MediaServeRow,
    size: u32,
) -> AppResult<Vec<u8>> {
    let filter = format!("scale={size}:{size}:force_original_aspect_ratio=decrease");
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(15),
        tokio::process::Command::new("ffmpeg")
            .args(["-v", "error", "-ss", "0", "-i"])
            .arg(storage.root().join(&row.normalized_path))
            .args([
                "-frames:v",
                "1",
                "-vf",
                &filter,
                "-f",
                "image2pipe",
                "-vcodec",
                "mjpeg",
                "pipe:1",
            ])
            .output(),
    )
    .await
    .map_err(|_| AppError::Internal(anyhow::anyhow!("video thumbnail generation timed out")))?
    .map_err(|error| AppError::Internal(anyhow::anyhow!("start ffmpeg: {error}")))?;
    if !output.status.success() || output.stdout.is_empty() {
        return Err(AppError::Internal(anyhow::anyhow!(
            "video thumbnail generation failed"
        )));
    }
    Ok(output.stdout)
}

fn jpeg_response(bytes: Vec<u8>) -> Response {
    let mut response = Response::new(Body::from(bytes));
    response
        .headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static("image/jpeg"));
    response.headers_mut().insert(
        header::CACHE_CONTROL,
        HeaderValue::from_static("private, max-age=31536000, immutable"),
    );
    response
}

async fn build_listing(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    path: &str,
) -> AppResult<LibraryListResponse> {
    let rows = sqlx::query_as::<_, IndexedLocationRow>(
        r#"
        SELECT
            a.id AS media_id,
            a.name,
            l.normalized_path,
            l.size,
            a.mime_type,
            a.is_video,
            a.width,
            a.height,
            a.taken_at,
            a.sort_at
        FROM media_assets a
        INNER JOIN media_locations l ON l.media_asset_id = a.id
        WHERE a.identity_state = 'verified'
          AND l.hash_state = 'verified'
          AND l.storage_id = 'local'
        "#,
    )
    .fetch_all(pool)
    .await?;

    let mut folder_aggregates = BTreeMap::<String, FolderAggregate>::new();
    let mut media = Vec::new();
    for row in rows {
        let relative = match relative_child(&row.normalized_path, path) {
            Some(value) => value,
            None => continue,
        };
        if let Some((folder_name, _)) = relative.split_once('/') {
            let aggregate = folder_aggregates.entry(folder_name.to_owned()).or_default();
            aggregate.media_count += 1;
            if is_preferred_thumbnail(aggregate.thumbnail.as_ref(), row.sort_at, &row.media_id) {
                aggregate.thumbnail = Some((row.sort_at, row.media_id));
            }
            continue;
        }
        media.push(LibraryMedia {
            id: row.media_id,
            name: row.name,
            path: row.normalized_path,
            size: u64::try_from(row.size).unwrap_or(0),
            mime_type: row.mime_type,
            is_video: row.is_video != 0,
            width: row.width.and_then(|v| u64::try_from(v).ok()),
            height: row.height.and_then(|v| u64::try_from(v).ok()),
            taken_at: row.taken_at,
            sort_at: row.sort_at,
        });
    }

    // Include empty on-disk directories so freshly created folders are visible.
    if let Ok(mut stream) = storage.list(path).await {
        use futures_util::TryStreamExt;
        while let Some(entry) = stream.try_next().await? {
            if entry.is_directory {
                folder_aggregates.entry(entry.name).or_default();
            }
        }
    }

    let folders = folder_aggregates
        .into_iter()
        .map(|(name, aggregate)| LibraryFolder {
            path: if path.is_empty() {
                name.clone()
            } else {
                format!("{path}/{name}")
            },
            name,
            media_count: aggregate.media_count,
            thumbnail_media_id: aggregate.thumbnail.map(|(_, media_id)| media_id),
        })
        .collect::<Vec<_>>();

    media.sort_by(|a, b| b.sort_at.cmp(&a.sort_at).then_with(|| a.id.cmp(&b.id)));

    Ok(LibraryListResponse {
        path: path.to_owned(),
        folders,
        media,
    })
}

fn relative_child<'a>(full_path: &'a str, current: &str) -> Option<&'a str> {
    if current.is_empty() {
        return Some(full_path);
    }
    let prefix = format!("{current}/");
    full_path.strip_prefix(&prefix)
}

fn normalize_library_path(path: &str) -> AppResult<String> {
    LocalFilesystemStorageDriver::normalize_relative(path.trim())
        .map_err(|error| AppError::BadRequest(error.to_string()))
}

/// 管理端删除：走回收站（原件移入服务端回收站 30 天，可恢复），
/// 不再沿用「先删文件再裸提交数据库」的失败窗口。
async fn delete_media(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    trash: &crate::trash::TrashRuntime,
    media_id: &str,
) -> AppResult<()> {
    let operation_id = Uuid::new_v4().to_string();
    let outcome = crate::trash::delete_media(
        pool,
        storage,
        trash,
        media_id,
        None,
        "admin_deleted",
        "admin",
        &operation_id,
        None,
        None,
    )
    .await?;
    match outcome.state.as_str() {
        "succeeded" => Ok(()),
        "not_found" => Err(AppError::NotFound("media not found".to_owned())),
        _ => Err(AppError::Conflict(
            outcome.message.unwrap_or_else(|| "删除媒体失败".to_owned()),
        )),
    }
}

async fn delete_empty_folder(
    pool: &SqlitePool,
    storage: &LocalFilesystemStorageDriver,
    path: &str,
) -> AppResult<()> {
    if path.is_empty() {
        return Err(AppError::BadRequest(
            "cannot delete the media root".to_owned(),
        ));
    }
    let prefix = format!("{path}/");
    let indexed = sqlx::query_scalar::<_, i64>(
        r#"
        SELECT EXISTS(
            SELECT 1
            FROM media_locations l
            INNER JOIN media_assets a ON a.id = l.media_asset_id
            WHERE l.storage_id = 'local'
              AND a.identity_state = 'verified'
              AND (l.normalized_path = ?1 OR l.normalized_path LIKE ?2)
        )
        "#,
    )
    .bind(path)
    .bind(format!("{prefix}%"))
    .fetch_one(pool)
    .await?;
    if indexed == 1 {
        return Err(AppError::Conflict("folder is not empty".to_owned()));
    }
    storage.delete_empty_dir(path).await?;
    Ok(())
}

async fn rewrite_media_location(pool: &SqlitePool, from: &str, to: &str) -> AppResult<()> {
    let row = sqlx::query_as::<
        _,
        (
            String,
            String,
            i64,
            Option<String>,
            i64,
            Option<i64>,
            Option<i64>,
            Option<i64>,
            Option<i64>,
            Option<String>,
            Option<i64>,
        ),
    >(
        r#"
        SELECT
            a.id,
            a.name,
            l.size,
            a.mime_type,
            a.is_video,
            a.width,
            a.height,
            a.taken_at,
            a.sort_at,
            b.content_hash,
            a.duration_ms
        FROM media_locations l
        INNER JOIN media_assets a ON a.id = l.media_asset_id
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE l.storage_id = 'local' AND l.normalized_path = ?1
        "#,
    )
    .bind(from)
    .fetch_optional(pool)
    .await?;
    let Some((
        media_id,
        _old_name,
        size,
        mime_type,
        is_video,
        width,
        height,
        taken_at,
        sort_at,
        content_hash,
        duration_ms,
    )) = row
    else {
        // Path may be an unindexed empty-folder move; nothing to rewrite.
        return Ok(());
    };

    let file_name = to.rsplit('/').next().unwrap_or(to).to_owned();
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    sqlx::query(
        r#"
        UPDATE media_locations
        SET normalized_path = ?1, file_name = ?2, updated_at = ?3
        WHERE storage_id = 'local' AND normalized_path = ?4
        "#,
    )
    .bind(to)
    .bind(&file_name)
    .bind(now)
    .bind(from)
    .execute(&mut *transaction)
    .await?;
    sqlx::query(
        "UPDATE media_assets SET name = ?1, version = version + 1, updated_at = ?2 WHERE id = ?3",
    )
    .bind(&file_name)
    .bind(now)
    .bind(&media_id)
    .execute(&mut *transaction)
    .await?;
    let version = sqlx::query_scalar::<_, i64>("SELECT version FROM media_assets WHERE id = ?1")
        .bind(&media_id)
        .fetch_one(&mut *transaction)
        .await?;
    let mut payload = serde_json::json!({
        "id": media_id,
        "name": file_name,
        "path": to,
        "size": size,
        "contentHash": content_hash,
        "mimeType": mime_type,
        "isVideo": is_video != 0,
        "storageId": "local",
        "identityState": "verified",
        "hashState": "verified",
        "durationMs": duration_ms,
        "width": width,
        "height": height,
        "takenAt": taken_at,
        "sortAt": sort_at,
    });
    let time = sqlx::query_as::<_, (String, i64, Option<String>)>(
        "SELECT sort_source,time_version,original_name FROM media_assets WHERE id=?1",
    )
    .bind(&media_id)
    .fetch_one(&mut *transaction)
    .await?;
    payload["sortSource"] = serde_json::json!(time.0);
    payload["timeVersion"] = serde_json::json!(time.1);
    payload["originalName"] = serde_json::json!(time.2);
    sqlx::query(
        r#"
        INSERT INTO change_log
            (revision, event_id, entity, operation, entity_id, version, payload, created_at)
        VALUES (?1, ?2, 'media', 'upsert', ?3, ?4, ?5, ?6)
        "#,
    )
    .bind(revision)
    .bind(Uuid::new_v4().to_string())
    .bind(&media_id)
    .bind(version)
    .bind(serde_json::to_string(&payload).map_err(|error| AppError::Internal(error.into()))?)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    Ok(())
}

async fn rewrite_folder_locations(pool: &SqlitePool, from: &str, to: &str) -> AppResult<()> {
    let rows = sqlx::query_as::<_, (String, String)>(
        r#"
        SELECT media_asset_id, normalized_path
        FROM media_locations
        WHERE storage_id = 'local'
          AND (normalized_path = ?1 OR normalized_path LIKE ?2)
        "#,
    )
    .bind(from)
    .bind(format!("{from}/%"))
    .fetch_all(pool)
    .await?;
    if rows.is_empty() {
        return Ok(());
    }

    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let revision = sync::allocate_revision(&mut transaction).await?;
    for (media_id, old_path) in rows {
        let new_path = if old_path == from {
            to.to_owned()
        } else {
            format!("{to}{}", &old_path[from.len()..])
        };
        let file_name = new_path
            .rsplit('/')
            .next()
            .unwrap_or(new_path.as_str())
            .to_owned();
        sqlx::query(
            r#"
            UPDATE media_locations
            SET normalized_path = ?1, file_name = ?2, updated_at = ?3
            WHERE storage_id = 'local' AND normalized_path = ?4
            "#,
        )
        .bind(&new_path)
        .bind(&file_name)
        .bind(now)
        .bind(&old_path)
        .execute(&mut *transaction)
        .await?;
        sqlx::query("UPDATE media_assets SET version = version + 1, updated_at = ?1 WHERE id = ?2")
            .bind(now)
            .bind(&media_id)
            .execute(&mut *transaction)
            .await?;
        let (
            name,
            size,
            mime_type,
            is_video,
            width,
            height,
            taken_at,
            sort_at,
            content_hash,
            duration_ms,
            version,
        ) = sqlx::query_as::<
            _,
            (
                String,
                i64,
                Option<String>,
                i64,
                Option<i64>,
                Option<i64>,
                Option<i64>,
                Option<i64>,
                Option<String>,
                Option<i64>,
                i64,
            ),
        >(
            r#"
                SELECT a.name, l.size, a.mime_type, a.is_video, a.width, a.height,
                       a.taken_at, a.sort_at, b.content_hash, a.duration_ms, a.version
                FROM media_assets a
                INNER JOIN media_locations l
                    ON l.media_asset_id = a.id AND l.storage_id = 'local' AND l.normalized_path = ?1
                LEFT JOIN content_blobs b ON b.id = a.blob_id
                WHERE a.id = ?2
                "#,
        )
        .bind(&new_path)
        .bind(&media_id)
        .fetch_one(&mut *transaction)
        .await?;
        let mut payload = serde_json::json!({
            "id": media_id,
            "name": name,
            "path": new_path,
            "size": size,
            "contentHash": content_hash,
            "mimeType": mime_type,
            "isVideo": is_video != 0,
            "storageId": "local",
            "identityState": "verified",
            "hashState": "verified",
            "durationMs": duration_ms,
            "width": width,
            "height": height,
            "takenAt": taken_at,
            "sortAt": sort_at,
        });
        let time = sqlx::query_as::<_, (String, i64, Option<String>)>(
            "SELECT sort_source,time_version,original_name FROM media_assets WHERE id=?1",
        )
        .bind(&media_id)
        .fetch_one(&mut *transaction)
        .await?;
        payload["sortSource"] = serde_json::json!(time.0);
        payload["timeVersion"] = serde_json::json!(time.1);
        payload["originalName"] = serde_json::json!(time.2);
        sqlx::query(
            r#"
            INSERT INTO change_log
                (revision, event_id, entity, operation, entity_id, version, payload, created_at)
            VALUES (?1, ?2, 'media', 'upsert', ?3, ?4, ?5, ?6)
            "#,
        )
        .bind(revision)
        .bind(Uuid::new_v4().to_string())
        .bind(&media_id)
        .bind(version)
        .bind(serde_json::to_string(&payload).map_err(|error| AppError::Internal(error.into()))?)
        .bind(now)
        .execute(&mut *transaction)
        .await?;
    }
    transaction.commit().await?;
    Ok(())
}

async fn find_verified_media(pool: &SqlitePool, id: &str) -> AppResult<MediaServeRow> {
    sqlx::query_as::<_, MediaServeRow>(
        r#"
        SELECT
            a.id,
            l.normalized_path,
            a.mime_type,
            b.content_hash,
            a.version,
            a.is_video
        FROM media_assets a
        INNER JOIN media_locations l ON l.media_asset_id = a.id
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.id = ?1
          AND a.identity_state = 'verified'
          AND l.hash_state = 'verified'
          AND l.storage_id = 'local'
        ORDER BY l.normalized_path ASC
        LIMIT 1
        "#,
    )
    .bind(id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound("media not found".to_owned()))
}

async fn find_media_info(pool: &SqlitePool, id: &str) -> AppResult<MediaInfoRow> {
    sqlx::query_as::<_, MediaInfoRow>(
        r#"
        SELECT
            a.id,
            a.name,
            a.original_name,
            a.mime_type,
            a.is_video,
            a.duration_ms,
            a.video_codec,
            a.width,
            a.height,
            a.taken_at,
            a.sort_at,
            a.sort_source,
            l.normalized_path,
            l.size,
            l.modified_at,
            b.content_hash
        FROM media_assets a
        INNER JOIN media_locations l ON l.media_asset_id = a.id
        LEFT JOIN content_blobs b ON b.id = a.blob_id
        WHERE a.id = ?1
          AND a.identity_state = 'verified'
          AND l.hash_state = 'verified'
          AND l.storage_id = 'local'
        ORDER BY l.normalized_path ASC
        LIMIT 1
        "#,
    )
    .bind(id)
    .fetch_optional(pool)
    .await?
    .ok_or_else(|| AppError::NotFound("media not found".to_owned()))
}

fn header_str<'a>(headers: &'a HeaderMap, name: &str) -> AppResult<&'a str> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(|| AppError::BadRequest(format!("{name} is required")))
}

fn header_u64(headers: &HeaderMap, name: &str) -> AppResult<u64> {
    header_str(headers, name)?
        .parse::<u64>()
        .map_err(|_| AppError::BadRequest(format!("{name} must be an integer")))
}
