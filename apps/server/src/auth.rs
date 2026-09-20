use std::{
    collections::HashMap,
    io::Write,
    path::{Path, PathBuf},
    sync::{Arc, OnceLock},
    time::{Duration, Instant},
};

use argon2::{
    Argon2,
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
};
use axum::http::HeaderMap;
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::Serialize;
use sqlx::{FromRow, SqlitePool};
use tokio::sync::{Mutex, Semaphore};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::{
    db::now_millis,
    error::{AppError, AppResult},
};

const SETUP_TOKEN_FILE: &str = "setup-token";
const ADMIN_SESSION_TTL_MS: i64 = 24 * 60 * 60 * 1000;
const PAIRING_CODE_TTL_MS: i64 = 10 * 60 * 1000;
// 服务端接受的客户端版本下限。只在 API 不兼容时抬高，日常改动不要动 ——
// 客户端与服务端独立发版，抬它等于强制所有旧客户端升级。
pub const MIN_CLIENT_VERSION: &str = "0.1.3";
const MIN_CLIENT_VERSION_TUPLE: (u64, u64, u64) = (0, 1, 3);
pub const ADMIN_SESSION_COOKIE: &str = "youyou_admin_session";
pub const ADMIN_CSRF_COOKIE: &str = "youyou_admin_csrf";
const DEVICE_LAST_SEEN_WRITE_INTERVAL_MS: i64 = 5 * 60 * 1000;

static PASSWORD_HASH_SLOTS: OnceLock<Arc<Semaphore>> = OnceLock::new();

fn password_hash_slots() -> Arc<Semaphore> {
    PASSWORD_HASH_SLOTS
        .get_or_init(|| Arc::new(Semaphore::new(4)))
        .clone()
}

#[derive(Clone, Default)]
pub struct AuthRateLimiter {
    failures: Arc<Mutex<HashMap<String, FailureWindow>>>,
}

struct FailureWindow {
    started_at: Instant,
    failures: u32,
    reservations: u32,
}

impl AuthRateLimiter {
    pub async fn reserve(&self, key: &str, max_failures: u32, window: Duration) -> bool {
        let mut failures = self.failures.lock().await;
        let now = Instant::now();
        failures.retain(|_, entry| now.duration_since(entry.started_at) < window + window);
        let entry = failures
            .entry(key.to_owned())
            .or_insert_with(|| FailureWindow {
                started_at: now,
                failures: 0,
                reservations: 0,
            });
        if now.duration_since(entry.started_at) >= window {
            entry.started_at = now;
            entry.failures = 0;
            entry.reservations = 0;
        }
        if entry.failures.saturating_add(entry.reservations) >= max_failures {
            return false;
        }
        entry.reservations = entry.reservations.saturating_add(1);
        true
    }

    pub async fn complete(&self, key: &str, window: Duration, failed: bool) {
        let mut failures = self.failures.lock().await;
        let now = Instant::now();
        failures.retain(|_, entry| now.duration_since(entry.started_at) < window + window);
        let expired = {
            let Some(entry) = failures.get_mut(key) else {
                return;
            };
            entry.reservations = entry.reservations.saturating_sub(1);
            now.duration_since(entry.started_at) >= window
        };
        if expired || !failed {
            failures.remove(key);
        } else {
            if let Some(entry) = failures.get_mut(key) {
                entry.failures = entry.failures.saturating_add(1);
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceIdentity {
    pub device_id: String,
    /// 设备所属用户；旧设备或被解绑后可能为空，此时不允许访问任何用户数据。
    pub user_id: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Principal {
    Admin,
    Device(DeviceIdentity),
}

impl Principal {
    /// 面向用户数据的端点必须由"已绑定用户的设备"调用。
    pub fn device_user(&self) -> AppResult<i64> {
        match self {
            Principal::Device(identity) => identity.user_id.ok_or_else(|| {
                AppError::Forbidden(
                    "device is not bound to a user; re-pair with a user pairing code".to_owned(),
                )
            }),
            Principal::Admin => Err(AppError::Forbidden(
                "this endpoint requires a paired device".to_owned(),
            )),
        }
    }
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct SessionTokens {
    pub token: String,
    pub csrf_token: String,
    pub expires_at: i64,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct PairingCodeResponse {
    pub code: String,
    pub expires_at: i64,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DeviceTokens {
    pub device_id: String,
    pub token: String,
}

#[derive(Debug, Serialize, ToSchema)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSummary {
    pub id: String,
    pub name: String,
    pub user_id: Option<i64>,
    pub user_name: Option<String>,
    pub created_at: i64,
    pub last_connected_at: Option<i64>,
    pub revoked_at: Option<i64>,
}

#[derive(Debug, FromRow)]
struct DeviceRow {
    id: String,
    name: String,
    user_id: Option<i64>,
    user_name: Option<String>,
    created_at: i64,
    last_connected_at: Option<i64>,
    revoked_at: Option<i64>,
}

#[derive(Debug, FromRow)]
struct PairingCodeRow {
    id: String,
    expires_at: i64,
    max_attempts: i64,
    attempts: i64,
    used_at: Option<i64>,
    user_id: Option<i64>,
}

pub fn setup_token_path(data_dir: &Path) -> PathBuf {
    data_dir.join("bootstrap").join(SETUP_TOKEN_FILE)
}

pub async fn prepare_setup_token(data_dir: &Path, pool: &SqlitePool) -> anyhow::Result<PathBuf> {
    let path = setup_token_path(data_dir);
    if is_initialized(pool).await? {
        if tokio::fs::try_exists(&path).await.unwrap_or(false) {
            tokio::fs::remove_file(&path).await?;
        }
        return Ok(path);
    }

    if tokio::fs::try_exists(&path).await.unwrap_or(false) {
        return Ok(path);
    }

    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let token = random_token();
    write_secret_file(&path, token.as_bytes())?;
    Ok(path)
}

pub async fn is_initialized(pool: &SqlitePool) -> Result<bool, sqlx::Error> {
    let value =
        sqlx::query_scalar::<_, i64>("SELECT EXISTS(SELECT 1 FROM admin_users WHERE id = 1)")
            .fetch_one(pool)
            .await?;
    Ok(value == 1)
}

pub async fn initialize_admin(
    pool: &SqlitePool,
    token_path: &Path,
    setup_token: &str,
    password: &str,
) -> AppResult<()> {
    if password.chars().count() < 8 {
        return Err(AppError::BadRequest(
            "admin password must contain at least 8 characters".to_owned(),
        ));
    }
    let stored_token = tokio::fs::read_to_string(token_path)
        .await
        .map_err(|_| AppError::Unauthorized("setup token is invalid or expired".to_owned()))?;
    if !constant_time_equal(stored_token.trim().as_bytes(), setup_token.as_bytes()) {
        return Err(AppError::Unauthorized(
            "setup token is invalid or expired".to_owned(),
        ));
    }

    let password = password.to_owned();
    let permit = password_hash_slots()
        .acquire_owned()
        .await
        .map_err(|error| {
            AppError::Internal(anyhow::anyhow!("acquire password hash slot: {error}"))
        })?;
    let password_hash = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let salt = SaltString::encode_b64(&Uuid::new_v4().into_bytes())
            .map_err(|error| anyhow::anyhow!("create password salt: {error}"))?;
        Argon2::default()
            .hash_password(password.as_bytes(), &salt)
            .map_err(|error| anyhow::anyhow!("hash admin password: {error}"))
            .map(|hash| hash.to_string())
    })
    .await
    .map_err(|error| AppError::Internal(anyhow::anyhow!("password hash task failed: {error}")))??;
    let now = now_millis();
    let mut transaction = pool.begin().await?;
    let result = sqlx::query(
        r#"
        INSERT INTO admin_users (id, password_hash, created_at, updated_at)
        VALUES (1, ?1, ?2, ?2)
        ON CONFLICT(id) DO NOTHING
        "#,
    )
    .bind(password_hash)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    if result.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "server admin has already been initialized".to_owned(),
        ));
    }
    transaction.commit().await?;
    let _ = tokio::fs::remove_file(token_path).await;
    Ok(())
}

pub async fn create_admin_session(pool: &SqlitePool, password: &str) -> AppResult<SessionTokens> {
    let password_hash =
        sqlx::query_scalar::<_, String>("SELECT password_hash FROM admin_users WHERE id = 1")
            .fetch_optional(pool)
            .await?
            .ok_or_else(|| AppError::Unauthorized("admin setup is incomplete".to_owned()))?;
    let password = password.to_owned();
    let permit = password_hash_slots()
        .acquire_owned()
        .await
        .map_err(|error| {
            AppError::Internal(anyhow::anyhow!("acquire password hash slot: {error}"))
        })?;
    let verification = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let parsed_hash = PasswordHash::new(&password_hash)
            .map_err(|error| anyhow::anyhow!("parse password hash: {error}"))?;
        Ok::<bool, anyhow::Error>(
            Argon2::default()
                .verify_password(password.as_bytes(), &parsed_hash)
                .is_ok(),
        )
    })
    .await
    .map_err(|error| {
        AppError::Internal(anyhow::anyhow!("password verify task failed: {error}"))
    })??;
    if !verification {
        return Err(AppError::Unauthorized(
            "invalid admin credentials".to_owned(),
        ));
    }

    let token = random_token();
    let csrf_token = random_token();
    let expires_at = now_millis() + ADMIN_SESSION_TTL_MS;
    sqlx::query(
        r#"
        INSERT INTO admin_sessions
            (id, session_digest, csrf_digest, expires_at, created_at)
        VALUES (?1, ?2, ?3, ?4, ?5)
        "#,
    )
    .bind(Uuid::new_v4().to_string())
    .bind(digest(&token))
    .bind(digest(&csrf_token))
    .bind(expires_at)
    .bind(now_millis())
    .execute(pool)
    .await?;
    Ok(SessionTokens {
        token,
        csrf_token,
        expires_at,
    })
}

pub async fn require_admin(
    pool: &SqlitePool,
    headers: &HeaderMap,
    require_csrf: bool,
) -> AppResult<Principal> {
    let token = admin_session_token(headers)?;
    let session = sqlx::query_as::<_, (String, String)>(
        r#"
        SELECT session_digest, csrf_digest
        FROM admin_sessions
        WHERE session_digest = ?1
          AND revoked_at IS NULL
          AND expires_at > ?2
        "#,
    )
    .bind(digest(token))
    .bind(now_millis())
    .fetch_optional(pool)
    .await?;
    let Some((_, csrf_digest)) = session else {
        return Err(AppError::Unauthorized(
            "a valid admin bearer token is required".to_owned(),
        ));
    };
    if require_csrf {
        let csrf = headers
            .get("x-csrf-token")
            .and_then(|value| value.to_str().ok())
            .ok_or_else(|| AppError::Forbidden("CSRF token is required".to_owned()))?;
        if !constant_time_equal(csrf_digest.as_bytes(), digest(csrf).as_bytes()) {
            return Err(AppError::Forbidden("CSRF token is invalid".to_owned()));
        }
    }
    Ok(Principal::Admin)
}

pub async fn require_client(pool: &SqlitePool, headers: &HeaderMap) -> AppResult<Principal> {
    let token = bearer_token(headers)?;
    let token_digest = digest(token);
    if sqlx::query_scalar::<_, i64>(
        r#"
        SELECT 1 FROM admin_sessions
        WHERE session_digest = ?1 AND revoked_at IS NULL AND expires_at > ?2
        "#,
    )
    .bind(&token_digest)
    .bind(now_millis())
    .fetch_optional(pool)
    .await?
    .is_some()
    {
        require_client_version(headers)?;
        return Ok(Principal::Admin);
    }

    if let Some((device_id, user_id, last_seen_at)) = sqlx::query_as::<_, (String, Option<i64>, Option<i64>)>(
        "SELECT id, user_id, last_seen_at FROM devices WHERE token_digest = ?1 AND revoked_at IS NULL",
    )
    .bind(&token_digest)
    .fetch_optional(pool)
    .await?
    {
        let now = now_millis();
        if last_seen_at
            .map(|value| value < now.saturating_sub(DEVICE_LAST_SEEN_WRITE_INTERVAL_MS))
            .unwrap_or(true)
        {
            let _ = sqlx::query("UPDATE devices SET last_seen_at = ?1 WHERE id = ?2")
                .bind(now)
                .bind(&device_id)
                .execute(pool)
                .await;
        }
        require_client_version(headers)?;
        return Ok(Principal::Device(DeviceIdentity { device_id, user_id }));
    }

    Err(AppError::Unauthorized(
        "a valid device bearer token is required".to_owned(),
    ))
}

fn require_client_version(headers: &HeaderMap) -> AppResult<()> {
    let value = headers
        .get("x-youyou-client-version")
        .and_then(|value| value.to_str().ok());
    let Some(value) = value else {
        return Err(AppError::UpgradeRequired);
    };
    let mut parts = value.split('.');
    let Some(major) = parts.next().and_then(|part| part.parse::<u64>().ok()) else {
        return Err(AppError::UpgradeRequired);
    };
    let Some(minor) = parts.next().and_then(|part| part.parse::<u64>().ok()) else {
        return Err(AppError::UpgradeRequired);
    };
    let Some(patch) = parts.next().and_then(|part| part.parse::<u64>().ok()) else {
        return Err(AppError::UpgradeRequired);
    };
    if parts.next().is_some() {
        return Err(AppError::UpgradeRequired);
    }
    let version = (major, minor, patch);
    if version < MIN_CLIENT_VERSION_TUPLE {
        return Err(AppError::UpgradeRequired);
    }
    Ok(())
}

pub async fn revoke_admin_session(pool: &SqlitePool, headers: &HeaderMap) -> AppResult<()> {
    let token = admin_session_token(headers)?;
    let result = sqlx::query(
        "UPDATE admin_sessions SET revoked_at = ?1 WHERE session_digest = ?2 AND revoked_at IS NULL",
    )
    .bind(now_millis())
    .bind(digest(token))
    .execute(pool)
    .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::Unauthorized(
            "admin session is invalid".to_owned(),
        ));
    }
    Ok(())
}

pub async fn create_pairing_code(
    pool: &SqlitePool,
    user_id: i64,
) -> AppResult<PairingCodeResponse> {
    let user_exists = sqlx::query_scalar::<_, i64>("SELECT 1 FROM users WHERE id = ?1")
        .bind(user_id)
        .fetch_optional(pool)
        .await?
        .is_some();
    if !user_exists {
        return Err(AppError::NotFound("user not found".to_owned()));
    }
    let code = random_token();
    let expires_at = now_millis() + PAIRING_CODE_TTL_MS;
    sqlx::query(
        r#"
        INSERT INTO pairing_codes
            (id, code_digest, expires_at, created_at, user_id)
        VALUES (?1, ?2, ?3, ?4, ?5)
        "#,
    )
    .bind(Uuid::new_v4().to_string())
    .bind(digest(&code))
    .bind(expires_at)
    .bind(now_millis())
    .bind(user_id)
    .execute(pool)
    .await?;
    Ok(PairingCodeResponse { code, expires_at })
}

pub async fn pair_device(pool: &SqlitePool, code: &str, name: &str) -> AppResult<DeviceTokens> {
    let mut transaction = pool.begin().await?;
    let pairing = sqlx::query_as::<_, PairingCodeRow>(
        r#"
        SELECT id, expires_at, max_attempts, attempts, used_at, user_id
        FROM pairing_codes
        WHERE code_digest = ?1
        "#,
    )
    .bind(digest(code))
    .fetch_optional(&mut *transaction)
    .await?
    .ok_or_else(|| AppError::BadRequest("pairing code is invalid".to_owned()))?;
    let Some(user_id) = pairing.user_id else {
        return Err(AppError::BadRequest(
            "pairing code is not bound to a user".to_owned(),
        ));
    };
    let user_exists = sqlx::query_scalar::<_, i64>("SELECT 1 FROM users WHERE id = ?1")
        .bind(user_id)
        .fetch_optional(&mut *transaction)
        .await?
        .is_some();
    if !user_exists {
        return Err(AppError::Conflict(
            "the user bound to this pairing code no longer exists".to_owned(),
        ));
    }
    let now = now_millis();
    if pairing.used_at.is_some()
        || pairing.expires_at <= now
        || pairing.attempts >= pairing.max_attempts
    {
        return Err(AppError::Conflict(
            "pairing code is expired, used, or locked".to_owned(),
        ));
    }

    let attempt = sqlx::query(
        r#"
        UPDATE pairing_codes
        SET attempts = attempts + 1
        WHERE id = ?1
          AND used_at IS NULL
          AND expires_at > ?2
          AND attempts < max_attempts
        "#,
    )
    .bind(&pairing.id)
    .bind(now)
    .execute(&mut *transaction)
    .await?;
    if attempt.rows_affected() != 1 {
        return Err(AppError::Conflict(
            "pairing code is expired, used, or locked".to_owned(),
        ));
    }

    let name = name.trim();
    if name.is_empty() || name.chars().count() > 100 {
        transaction.commit().await?;
        return Err(AppError::BadRequest(
            "device name must contain 1-100 characters".to_owned(),
        ));
    }

    let device_id = Uuid::new_v4().to_string();
    let token = random_token();
    sqlx::query("UPDATE pairing_codes SET used_at = ?1 WHERE id = ?2")
        .bind(now)
        .bind(&pairing.id)
        .execute(&mut *transaction)
        .await?;
    sqlx::query(
        "INSERT INTO devices (id, name, token_digest, created_at, user_id) VALUES (?1, ?2, ?3, ?4, ?5)",
    )
    .bind(&device_id)
    .bind(name)
    .bind(digest(&token))
    .bind(now)
    .bind(user_id)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;
    Ok(DeviceTokens { device_id, token })
}

pub async fn list_devices(pool: &SqlitePool) -> Result<Vec<DeviceSummary>, sqlx::Error> {
    let rows = sqlx::query_as::<_, DeviceRow>(
        r#"
        SELECT d.id, d.name, d.user_id, u.name AS user_name,
               d.created_at, d.last_seen_at AS last_connected_at, d.revoked_at
        FROM devices d
        LEFT JOIN users u ON u.id = d.user_id
        ORDER BY d.created_at DESC, d.id DESC
        "#,
    )
    .fetch_all(pool)
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| DeviceSummary {
            id: row.id,
            name: row.name,
            user_id: row.user_id,
            user_name: row.user_name,
            created_at: row.created_at,
            last_connected_at: row.last_connected_at,
            revoked_at: row.revoked_at,
        })
        .collect())
}

pub async fn revoke_device(pool: &SqlitePool, id: &str) -> AppResult<()> {
    let result =
        sqlx::query("UPDATE devices SET revoked_at = ?1 WHERE id = ?2 AND revoked_at IS NULL")
            .bind(now_millis())
            .bind(id)
            .execute(pool)
            .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound(
            "device not found or already revoked".to_owned(),
        ));
    }
    Ok(())
}

pub async fn rotate_device(pool: &SqlitePool, id: &str) -> AppResult<DeviceTokens> {
    let token = random_token();
    let result =
        sqlx::query("UPDATE devices SET token_digest = ?1, revoked_at = NULL WHERE id = ?2")
            .bind(digest(&token))
            .bind(id)
            .execute(pool)
            .await?;
    if result.rows_affected() == 0 {
        return Err(AppError::NotFound("device not found".to_owned()));
    }
    Ok(DeviceTokens {
        device_id: id.to_owned(),
        token,
    })
}

fn admin_session_token(headers: &HeaderMap) -> AppResult<&str> {
    if headers.get(axum::http::header::AUTHORIZATION).is_some() {
        return bearer_token(headers);
    }
    cookie_value(headers, ADMIN_SESSION_COOKIE)
        .ok_or_else(|| AppError::Unauthorized("a valid admin session is required".to_owned()))
}

fn bearer_token(headers: &HeaderMap) -> AppResult<&str> {
    let value = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(|| {
            AppError::Unauthorized("Authorization bearer token is required".to_owned())
        })?;
    value
        .strip_prefix("Bearer ")
        .filter(|token| !token.is_empty())
        .ok_or_else(|| AppError::Unauthorized("Authorization bearer token is required".to_owned()))
}

fn cookie_value<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers
        .get(axum::http::header::COOKIE)
        .and_then(|value| value.to_str().ok())
        .and_then(|cookies| {
            cookies.split(';').find_map(|cookie| {
                let (cookie_name, cookie_value) = cookie.trim().split_once('=')?;
                (cookie_name == name && !cookie_value.is_empty()).then_some(cookie_value)
            })
        })
}

fn random_token() -> String {
    let mut bytes = [0_u8; 32];
    bytes[..16].copy_from_slice(&Uuid::new_v4().into_bytes());
    bytes[16..].copy_from_slice(&Uuid::new_v4().into_bytes());
    URL_SAFE_NO_PAD.encode(bytes)
}

fn digest(value: &str) -> String {
    use sha2::{Digest, Sha256};

    hex::encode(Sha256::digest(value.as_bytes()))
}

fn constant_time_equal(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0_u8, |difference, (left, right)| {
            difference | (left ^ right)
        })
        == 0
}

fn write_secret_file(path: &Path, content: &[u8]) -> anyhow::Result<()> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path)?;
    file.write_all(content)?;
    file.sync_all()?;
    Ok(())
}
