use std::time::Duration;

use axum::{
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{Method, Request, StatusCode, header},
};
use base64::Engine as _;
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use tempfile::tempdir;
use tokio::time::sleep;
use tower::util::ServiceExt;
use uuid::Uuid;
use youyou_server::{api::build_router, initialize, metadata};

/// 可解码的最小 JPEG 夹具：图片必须能解析出尺寸才会入库。
/// `seed` 追加在 JPEG 之后，用来让不同文件的内容哈希不同（解码器忽略尾部字节）。
fn photo_bytes(seed: &[u8]) -> Vec<u8> {
    let mut buffer = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(
        2,
        2,
        image::Rgb([90, 140, 200]),
    ))
    .write_to(&mut buffer, image::ImageFormat::Jpeg)
    .expect("encode fixture jpeg");
    let mut bytes = buffer.into_inner();
    bytes.extend_from_slice(seed);
    bytes
}

async fn request(
    app: &axum::Router,
    method: Method,
    uri: &str,
    range: Option<&str>,
    bearer: Option<&str>,
    csrf: Option<&str>,
) -> axum::response::Response {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header("Idempotency-Key", Uuid::new_v4().to_string())
        .header("X-Youyou-Client-Version", "0.1.3");
    if let Some(range) = range {
        builder = builder.header(header::RANGE, range);
    }
    if let Some(bearer) = bearer {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {bearer}"));
    }
    if let Some(csrf) = csrf {
        builder = builder.header("x-csrf-token", csrf);
    }
    let mut request = builder.body(Body::empty()).expect("request");
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    app.clone().oneshot(request).await.expect("response")
}

async fn json_request(
    app: &axum::Router,
    method: Method,
    uri: &str,
    payload: serde_json::Value,
    bearer: Option<&str>,
    csrf: Option<&str>,
) -> axum::response::Response {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/json")
        .header("X-Youyou-Client-Version", "0.1.3")
        .header("Idempotency-Key", Uuid::new_v4().to_string());
    if let Some(bearer) = bearer {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {bearer}"));
    }
    if let Some(csrf) = csrf {
        builder = builder.header("x-csrf-token", csrf);
    }
    let mut request = builder
        .body(Body::from(payload.to_string()))
        .expect("request");
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    app.clone().oneshot(request).await.expect("response")
}

async fn json_request_from_peer(
    app: &axum::Router,
    method: Method,
    uri: &str,
    payload: serde_json::Value,
    peer: SocketAddr,
) -> axum::response::Response {
    let mut request = Request::builder()
        .method(method)
        .uri(uri)
        .header(header::CONTENT_TYPE, "application/json")
        .header("X-Youyou-Client-Version", "0.1.3")
        .header("Idempotency-Key", Uuid::new_v4().to_string())
        .body(Body::from(payload.to_string()))
        .expect("request");
    request.extensions_mut().insert(ConnectInfo(peer));
    app.clone().oneshot(request).await.expect("response")
}

async fn json_request_with_key(
    app: &axum::Router,
    method: Method,
    uri: &str,
    payload: serde_json::Value,
    bearer: &str,
    key: &str,
) -> axum::response::Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(uri)
                .header(header::CONTENT_TYPE, "application/json")
                .header("X-Youyou-Client-Version", "0.1.3")
                .header("Idempotency-Key", key)
                .header(header::AUTHORIZATION, format!("Bearer {bearer}"))
                .body(Body::from(payload.to_string()))
                .expect("request"),
        )
        .await
        .expect("response")
}

async fn establish_admin(app: &axum::Router, setup_token: &str) -> (String, String) {
    let setup = json_request(
        app,
        Method::POST,
        "/api/v1/admin/setup",
        serde_json::json!({
            "setupToken": setup_token,
            "password": "correct horse battery staple",
        }),
        None,
        None,
    )
    .await;
    assert_eq!(setup.status(), StatusCode::NO_CONTENT);

    let login = json_request(
        app,
        Method::POST,
        "/api/v1/admin/session",
        serde_json::json!({"password": "correct horse battery staple"}),
        None,
        None,
    )
    .await;
    assert_eq!(login.status(), StatusCode::OK);
    let session_cookies = login
        .headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .map(|value| value.to_str().expect("session cookie").to_owned())
        .collect::<Vec<_>>();
    assert_eq!(session_cookies.len(), 2);
    assert!(session_cookies.iter().any(|cookie| {
        cookie.starts_with("youyou_admin_session=")
            && cookie.contains("HttpOnly")
            && cookie.contains("SameSite=Strict")
    }));
    assert!(session_cookies.iter().any(|cookie| {
        cookie.starts_with("youyou_admin_csrf=") && cookie.contains("SameSite=Strict")
    }));
    let login_body = to_bytes(login.into_body(), usize::MAX)
        .await
        .expect("login body");
    let login_json: serde_json::Value = serde_json::from_slice(&login_body).expect("login json");
    let admin_token = login_json["token"]
        .as_str()
        .expect("admin token")
        .to_owned();
    let csrf_token = login_json["csrfToken"]
        .as_str()
        .expect("csrf token")
        .to_owned();
    (admin_token, csrf_token)
}

/// 建一个用户并返回其 ID。
async fn admin_create_user(
    app: &axum::Router,
    admin_token: &str,
    csrf_token: &str,
    name: &str,
    library_name: &str,
) -> i64 {
    let response = json_request(
        app,
        Method::POST,
        "/api/v1/admin/users",
        serde_json::json!({"name": name, "libraryName": library_name}),
        Some(admin_token),
        Some(csrf_token),
    )
    .await;
    assert_eq!(response.status(), StatusCode::CREATED);
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("user body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("user json");
    json["id"].as_i64().expect("user id")
}

/// 为用户签发配对码并把一台设备配对到该用户，返回设备令牌。
async fn pair_user_device(
    app: &axum::Router,
    admin_token: &str,
    csrf_token: &str,
    user_id: i64,
    device_name: &str,
) -> String {
    let pairing_code = request(
        app,
        Method::POST,
        &format!("/api/v1/admin/users/{user_id}/pairing-codes"),
        None,
        Some(admin_token),
        Some(csrf_token),
    )
    .await;
    assert_eq!(pairing_code.status(), StatusCode::OK);
    let pairing_body = to_bytes(pairing_code.into_body(), usize::MAX)
        .await
        .expect("pairing body");
    let pairing_json: serde_json::Value =
        serde_json::from_slice(&pairing_body).expect("pairing json");
    let code = pairing_json["code"].as_str().expect("pairing code");

    let device = json_request(
        app,
        Method::POST,
        "/api/v1/pairing",
        serde_json::json!({"code": code, "deviceName": device_name}),
        None,
        None,
    )
    .await;
    assert_eq!(device.status(), StatusCode::OK);
    let device_body = to_bytes(device.into_body(), usize::MAX)
        .await
        .expect("device body");
    let device_json: serde_json::Value = serde_json::from_slice(&device_body).expect("device json");
    device_json["token"]
        .as_str()
        .expect("device token")
        .to_owned()
}

async fn establish_device(app: &axum::Router, setup_token: &str) -> (String, String, String) {
    let (admin_token, csrf_token) = establish_admin(app, setup_token).await;
    let name = format!("owner-{}", Uuid::new_v4());
    let library = format!("lib{}", &Uuid::new_v4().simple().to_string()[..12]);
    let user_id = admin_create_user(app, &admin_token, &csrf_token, &name, &library).await;
    let device_token =
        pair_user_device(app, &admin_token, &csrf_token, user_id, "test-device").await;
    (admin_token, csrf_token, device_token)
}

/// 建用户 + 绑定媒体库 + 配对设备：设备可见媒体 = 绑定目录内的媒体。
async fn establish_user_device(
    app: &axum::Router,
    setup_token: &str,
    library_name: &str,
) -> (String, String, String) {
    let (admin_token, csrf_token) = establish_admin(app, setup_token).await;
    let name = format!("owner-{}", Uuid::new_v4());
    let user_id = admin_create_user(app, &admin_token, &csrf_token, &name, library_name).await;
    let device_token =
        pair_user_device(app, &admin_token, &csrf_token, user_id, "test-device").await;
    (admin_token, csrf_token, device_token)
}

/// 管理端轮询 job 直到终态（scan 等管理任务对设备不可见）。
async fn wait_admin_job(app: &axum::Router, admin_token: &str, job_id: &str) -> serde_json::Value {
    let mut job_json = serde_json::Value::Null;
    for _ in 0..100 {
        let job = request(
            app,
            Method::GET,
            &format!("/api/v1/admin/jobs/{job_id}"),
            None,
            Some(admin_token),
            None,
        )
        .await;
        assert_eq!(job.status(), StatusCode::OK);
        let body = to_bytes(job.into_body(), usize::MAX)
            .await
            .expect("job body");
        job_json = serde_json::from_slice(&body).expect("job json");
        if matches!(
            job_json["status"].as_str(),
            Some("succeeded" | "failed" | "cancelled")
        ) {
            break;
        }
        sleep(Duration::from_millis(10)).await;
    }
    job_json
}

/// 客户端流式上传（对应 POST /api/v1/media/upload）。
async fn stream_upload_request(
    app: &axum::Router,
    device_token: &str,
    file_name: &str,
    mime_type: Option<&str>,
    taken_at: Option<i64>,
    body: &[u8],
) -> axum::response::Response {
    let sha256 = hex::encode(Sha256::digest(body));
    let mut builder = Request::builder()
        .method(Method::POST)
        .uri("/api/v1/media/upload")
        .header("X-Youyou-Client-Version", "0.1.3")
        .header("Idempotency-Key", Uuid::new_v4().to_string())
        .header("X-Expected-Size", body.len().to_string())
        .header("X-Expected-SHA256", sha256)
        .header("X-File-Name", file_name)
        .header(header::AUTHORIZATION, format!("Bearer {device_token}"));
    if let Some(mime_type) = mime_type {
        builder = builder.header("X-Mime-Type", mime_type);
    }
    if let Some(taken_at) = taken_at {
        builder = builder.header("X-Taken-At", taken_at.to_string());
    }
    let mut request = builder
        .body(Body::from(body.to_vec()))
        .expect("upload request");
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    app.clone().oneshot(request).await.expect("upload response")
}

#[tokio::test]
async fn serves_hardened_admin_web_assets() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);

    let page = request(&app, Method::GET, "/admin", None, None, None).await;
    assert_eq!(page.status(), StatusCode::OK);
    assert_eq!(
        page.headers()[header::CONTENT_SECURITY_POLICY],
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
    );
    assert_eq!(page.headers()[header::X_FRAME_OPTIONS], "DENY");
    let body = to_bytes(page.into_body(), usize::MAX)
        .await
        .expect("admin page body");
    let body = String::from_utf8_lossy(&body);
    assert!(body.contains("柚柚相册"));
    assert!(body.contains("/admin/admin.js"));
    assert!(body.contains("/admin/admin.css"));

    let script = request(&app, Method::GET, "/admin/admin.js", None, None, None).await;
    assert_eq!(script.status(), StatusCode::OK);
    assert_eq!(
        script.headers()[header::CONTENT_TYPE],
        "text/javascript; charset=utf-8"
    );
    let styles = request(&app, Method::GET, "/admin/admin.css", None, None, None).await;
    assert_eq!(styles.status(), StatusCode::OK);
    assert_eq!(
        styles.headers()[header::CONTENT_TYPE],
        "text/css; charset=utf-8"
    );
    let styles_body = to_bytes(styles.into_body(), usize::MAX)
        .await
        .expect("admin styles body");
    assert!(String::from_utf8_lossy(&styles_body).contains(".dashboard-sidebar"));

    let logo = request(&app, Method::GET, "/admin/logo_mark.png", None, None, None).await;
    assert_eq!(logo.status(), StatusCode::OK);
    assert_eq!(logo.headers()[header::CONTENT_TYPE], "image/png");
    let logo_body = to_bytes(logo.into_body(), usize::MAX)
        .await
        .expect("admin logo body");
    assert!(!logo_body.is_empty());

    let background = request(
        &app,
        Method::GET,
        "/admin/login-background.jpg",
        None,
        None,
        None,
    )
    .await;
    assert_eq!(background.status(), StatusCode::OK);
    assert_eq!(background.headers()[header::CONTENT_TYPE], "image/jpeg");
    let background_body = to_bytes(background.into_body(), usize::MAX)
        .await
        .expect("admin login background body");
    assert!(!background_body.is_empty());

    let setup = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/setup",
        serde_json::json!({
            "setupToken": setup_token.trim(),
            "password": "correct horse battery staple",
        }),
        None,
        None,
    )
    .await;
    assert_eq!(setup.status(), StatusCode::NO_CONTENT);
    let login = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/session",
        serde_json::json!({"password": "correct horse battery staple"}),
        None,
        None,
    )
    .await;
    assert_eq!(login.status(), StatusCode::OK);
    let cookie = login
        .headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .map(|value| {
            value
                .to_str()
                .expect("session cookie")
                .split(';')
                .next()
                .expect("cookie pair")
                .to_owned()
        })
        .collect::<Vec<_>>()
        .join("; ");
    let mut session_request = Request::builder()
        .method(Method::GET)
        .uri("/api/v1/admin/session")
        .header(header::COOKIE, cookie.clone())
        .body(Body::empty())
        .expect("cookie session request");
    session_request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    let session = app
        .clone()
        .oneshot(session_request)
        .await
        .expect("admin session response");
    assert_eq!(session.status(), StatusCode::NO_CONTENT);
    let mut cookie_request = Request::builder()
        .method(Method::GET)
        .uri("/api/v1/admin/status")
        .header(header::COOKIE, cookie)
        .body(Body::empty())
        .expect("cookie request");
    cookie_request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    let status = app
        .clone()
        .oneshot(cookie_request)
        .await
        .expect("admin status response");
    assert_eq!(status.status(), StatusCode::OK);
}

#[tokio::test]
async fn admin_can_inspect_storage_jobs_backups_audit_and_diagnostics() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, _) = establish_device(&app, setup_token.trim()).await;

    let storage = request(
        &app,
        Method::GET,
        "/api/v1/admin/storage",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(storage.status(), StatusCode::OK);
    let storage_body = to_bytes(storage.into_body(), usize::MAX)
        .await
        .expect("storage body");
    let storage_json: serde_json::Value =
        serde_json::from_slice(&storage_body).expect("storage json");
    assert_eq!(storage_json["id"], "local");
    assert!(storage_json["rootPath"].as_str().is_some());
    assert!(storage_json["freeBytes"].is_number() || storage_json["freeBytes"].is_null());

    let storage_test = request(
        &app,
        Method::POST,
        "/api/v1/admin/storage/test",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(storage_test.status(), StatusCode::OK);
    let storage_test_body = to_bytes(storage_test.into_body(), usize::MAX)
        .await
        .expect("storage test body");
    let storage_test_json: serde_json::Value =
        serde_json::from_slice(&storage_test_body).expect("storage test json");
    assert_eq!(storage_test_json["passed"], true);

    let jobs = request(
        &app,
        Method::GET,
        "/api/v1/admin/jobs?limit=20",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(jobs.status(), StatusCode::OK);
    let jobs_body = to_bytes(jobs.into_body(), usize::MAX)
        .await
        .expect("jobs body");
    let jobs_json: serde_json::Value = serde_json::from_slice(&jobs_body).expect("jobs json");
    assert!(jobs_json["items"].is_array());

    let backup = request(
        &app,
        Method::POST,
        "/api/v1/admin/backups",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(backup.status(), StatusCode::ACCEPTED);
    let backup_body = to_bytes(backup.into_body(), usize::MAX)
        .await
        .expect("backup body");
    let backup_json: serde_json::Value = serde_json::from_slice(&backup_body).expect("backup json");
    let backup_id = backup_json["id"].as_str().expect("backup id");
    let backup_job_id = backup_json["jobId"].as_str().expect("backup job id");
    assert_eq!(backup_json["status"], "queued");

    let mut backup_job = serde_json::Value::Null;
    for _ in 0..100 {
        let job = request(
            &app,
            Method::GET,
            &format!("/api/v1/admin/jobs/{backup_job_id}"),
            None,
            Some(&admin_token),
            None,
        )
        .await;
        let job_status = job.status();
        if job_status != StatusCode::OK {
            let error_body = to_bytes(job.into_body(), usize::MAX)
                .await
                .expect("error body");
            panic!(
                "backup job request failed: {} {}",
                job_status,
                String::from_utf8_lossy(&error_body)
            );
        }
        let body = to_bytes(job.into_body(), usize::MAX)
            .await
            .expect("backup job body");
        backup_job = serde_json::from_slice(&body).expect("backup job json");
        if matches!(
            backup_job["status"].as_str(),
            Some("succeeded" | "failed" | "cancelled")
        ) {
            break;
        }
        sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(backup_job["status"], "succeeded");

    let backup_detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/admin/backups/{backup_id}"),
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(backup_detail.status(), StatusCode::OK);
    let backup_detail_body = to_bytes(backup_detail.into_body(), usize::MAX)
        .await
        .expect("backup detail body");
    let backup_detail_json: serde_json::Value =
        serde_json::from_slice(&backup_detail_body).expect("backup detail json");
    assert_eq!(backup_detail_json["status"], "succeeded");
    assert!(backup_detail_json["sha256"].as_str().is_some());

    let audit = request(
        &app,
        Method::GET,
        "/api/v1/admin/audit-log?limit=50",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(audit.status(), StatusCode::OK);
    let audit_body = to_bytes(audit.into_body(), usize::MAX)
        .await
        .expect("audit body");
    let audit_json: serde_json::Value = serde_json::from_slice(&audit_body).expect("audit json");
    let actions = audit_json["items"]
        .as_array()
        .expect("audit items")
        .iter()
        .filter_map(|item| item["action"].as_str())
        .collect::<Vec<_>>();
    assert!(actions.contains(&"storage.test"));
    assert!(actions.contains(&"backup.complete"));

    let diagnostics = request(
        &app,
        Method::GET,
        "/api/v1/admin/diagnostics",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(diagnostics.status(), StatusCode::OK);
    let diagnostics_body = to_bytes(diagnostics.into_body(), usize::MAX)
        .await
        .expect("diagnostics body");
    let diagnostics_json: serde_json::Value =
        serde_json::from_slice(&diagnostics_body).expect("diagnostics json");
    assert_eq!(diagnostics_json["databaseQuickCheck"], "ok");
    assert!(diagnostics_json["storage"].is_object());
}

#[tokio::test]
async fn scans_local_directory_and_serves_paginated_media() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(library.join("camera"))
        .await
        .expect("camera directory");
    tokio::fs::write(library.join("camera/photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");
    tokio::fs::write(library.join("camera/notes.txt"), b"ignore")
        .await
        .expect("notes");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let scan_body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let scan_json: serde_json::Value = serde_json::from_slice(&scan_body).expect("scan json");
    let job_id = scan_json["id"].as_str().expect("job id").to_owned();
    let job_json = wait_admin_job(&app, &admin_token, &job_id).await;
    assert_eq!(job_json["status"], "succeeded");
    assert_eq!(job_json["current"], 1);
    assert_eq!(job_json["total"], 1);
    assert_eq!(job_json["checkpoint"]["phase"], "completed");

    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=1",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(media_response.status(), StatusCode::OK);
    let media_body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let media_json: serde_json::Value = serde_json::from_slice(&media_body).expect("media json");
    assert_eq!(media_json["items"].as_array().expect("items").len(), 1);
    assert_eq!(media_json["hasMore"], false);
    assert!(media_json["nextCursor"].as_str().is_some());
    assert_eq!(media_json["items"][0]["path"], "library/camera/photo.jpg");
    assert_eq!(
        media_json["items"][0]["size"],
        photo_bytes(b"abcdef").len() as u64
    );
    assert_eq!(media_json["items"][0]["isVideo"], false);
    assert_eq!(
        media_json["items"][0]["contentHash"],
        hex::encode(Sha256::digest(photo_bytes(b"abcdef")))
    );
    let media_id = media_json["items"][0]["id"].as_str().expect("media id");
    let folders = request(
        &app,
        Method::GET,
        "/api/v1/media/folders?limit=100",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(folders.status(), StatusCode::OK);
    let folders_body = to_bytes(folders.into_body(), usize::MAX)
        .await
        .expect("folders body");
    let folders_json: serde_json::Value =
        serde_json::from_slice(&folders_body).expect("folders json");
    let camera_folder = folders_json["items"]
        .as_array()
        .expect("items")
        .iter()
        .find(|folder| folder["path"] == "library/camera")
        .expect("camera folder");
    assert_eq!(camera_folder["mediaCount"], 1);

    let bootstrap = request(
        &app,
        Method::POST,
        "/api/v1/sync/bootstrap",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(bootstrap.status(), StatusCode::ACCEPTED);
    let bootstrap_body = to_bytes(bootstrap.into_body(), usize::MAX)
        .await
        .expect("bootstrap body");
    let bootstrap_json: serde_json::Value =
        serde_json::from_slice(&bootstrap_body).expect("bootstrap json");
    let snapshot_id = bootstrap_json["snapshotId"]
        .as_str()
        .expect("snapshot id")
        .to_owned();
    let mut snapshot_status = serde_json::Value::Null;
    for _ in 0..50 {
        let response = request(
            &app,
            Method::GET,
            &format!("/api/v1/sync/bootstrap/{snapshot_id}"),
            None,
            Some(&device_token),
            None,
        )
        .await;
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("snapshot status body");
        snapshot_status = serde_json::from_slice(&body).expect("snapshot status json");
        if snapshot_status["state"] == "ready" {
            break;
        }
        sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(snapshot_status["state"], "ready");
    let changes_cursor = snapshot_status["changesCursor"]
        .as_str()
        .expect("changes cursor");
    let decoded_cursor = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(changes_cursor)
        .expect("decode changes cursor");
    assert!(
        String::from_utf8(decoded_cursor)
            .expect("changes cursor text")
            .starts_with("v2:")
    );

    let tag = json_request(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "快照之后"}),
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(tag.status(), StatusCode::CREATED);
    let changes = request(
        &app,
        Method::GET,
        &format!("/api/v1/changes?cursor={changes_cursor}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(changes.status(), StatusCode::OK);
    let changes_body = to_bytes(changes.into_body(), usize::MAX)
        .await
        .expect("changes body");
    let changes_json: serde_json::Value =
        serde_json::from_slice(&changes_body).expect("changes json");
    assert_eq!(
        changes_json["items"]
            .as_array()
            .expect("change items")
            .len(),
        1
    );
    assert_eq!(changes_json["items"][0]["entity"], "tag");
    let changes_next_cursor = changes_json["nextCursor"]
        .as_str()
        .expect("next changes cursor");
    let changes_after = request(
        &app,
        Method::GET,
        &format!("/api/v1/changes?cursor={changes_next_cursor}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(changes_after.status(), StatusCode::OK);
    let changes_after_body = to_bytes(changes_after.into_body(), usize::MAX)
        .await
        .expect("empty changes body");
    let changes_after_json: serde_json::Value =
        serde_json::from_slice(&changes_after_body).expect("empty changes json");
    assert!(
        changes_after_json["items"]
            .as_array()
            .expect("empty change items")
            .is_empty()
    );

    let snapshot_media = request(
        &app,
        Method::GET,
        &format!("/api/v1/sync/bootstrap/{snapshot_id}/media?limit=1"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(snapshot_media.status(), StatusCode::OK);
    let snapshot_media_body = to_bytes(snapshot_media.into_body(), usize::MAX)
        .await
        .expect("snapshot media body");
    let snapshot_media_json: serde_json::Value =
        serde_json::from_slice(&snapshot_media_body).expect("snapshot media json");
    assert_eq!(
        snapshot_media_json["items"]
            .as_array()
            .expect("items")
            .len(),
        1
    );
    assert_eq!(snapshot_media_json["hasMore"], false);
    assert!(snapshot_media_json["nextCursor"].as_str().is_some());
    assert_eq!(snapshot_media_json["items"][0]["data"]["isVideo"], false);
    assert_eq!(
        snapshot_media_json["items"][0]["data"]["contentHash"],
        hex::encode(Sha256::digest(photo_bytes(b"abcdef")))
    );

    let detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(detail.status(), StatusCode::OK);

    let content = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/content"),
        Some("bytes=1-3"),
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(content.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        content.headers().get(header::CONTENT_RANGE).unwrap(),
        &format!("bytes 1-3/{}", photo_bytes(b"abcdef").len())
    );
    let content_body = to_bytes(content.into_body(), usize::MAX)
        .await
        .expect("content body");
    assert_eq!(&content_body[..], &photo_bytes(b"abcdef")[1..4]);

    let changes = request(
        &app,
        Method::GET,
        "/api/v1/changes?limit=10",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(changes.status(), StatusCode::OK);
    let changes_body = to_bytes(changes.into_body(), usize::MAX)
        .await
        .expect("changes body");
    let changes_json: serde_json::Value =
        serde_json::from_slice(&changes_body).expect("changes json");
    assert_eq!(changes_json["items"].as_array().expect("items").len(), 2);
    assert!(changes_json["nextCursor"].as_str().is_some());

    // 客户端流式上传：落入用户媒体库的 uploads/YYYY/MM 目录。
    let upload_bytes = photo_bytes(b"uploaded");
    let upload = stream_upload_request(
        &app,
        &device_token,
        "uploaded.jpg",
        Some("image/jpeg"),
        Some(1704067200000),
        &upload_bytes,
    )
    .await;
    assert_eq!(upload.status(), StatusCode::OK);
    let upload_body = to_bytes(upload.into_body(), usize::MAX)
        .await
        .expect("upload body");
    let upload_json: serde_json::Value = serde_json::from_slice(&upload_body).expect("upload json");
    let upload_media_id = upload_json["mediaId"].as_str().expect("media id");
    let expected_upload_path = "library/uploads/2024/01/IMG_20240101_000000.jpg";
    assert_eq!(upload_json["path"], expected_upload_path);
    assert!(media.path().join(expected_upload_path).is_file());
    let upload_version =
        sqlx::query_scalar::<_, i64>("SELECT version FROM media_assets WHERE id = ?1")
            .bind(upload_media_id)
            .fetch_one(&state.db)
            .await
            .expect("uploaded media version");
    assert_eq!(upload_version, 2);
    let upload_change = sqlx::query_scalar::<_, String>(
        "SELECT payload FROM change_log WHERE entity = 'media' AND entity_id = ?1 ORDER BY revision DESC LIMIT 1",
    )
    .bind(upload_media_id)
    .fetch_one(&state.db)
    .await
    .expect("uploaded media change");
    let upload_change_json: serde_json::Value =
        serde_json::from_str(&upload_change).expect("uploaded media change json");
    assert_eq!(upload_change_json["mimeType"], "image/jpeg");

    // 同名同内容重复上传：命中既有文件，返回同一媒体。
    let duplicate = stream_upload_request(
        &app,
        &device_token,
        "uploaded.jpg",
        Some("image/jpeg"),
        Some(1704067200000),
        &upload_bytes,
    )
    .await;
    assert_eq!(duplicate.status(), StatusCode::OK);
    let duplicate_body = to_bytes(duplicate.into_body(), usize::MAX)
        .await
        .expect("duplicate body");
    let duplicate_json: serde_json::Value =
        serde_json::from_slice(&duplicate_body).expect("duplicate json");
    assert_eq!(duplicate_json["mediaId"], upload_json["mediaId"]);
    assert_eq!(duplicate_json["path"], expected_upload_path);

    let tag = json_request(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "精选"}),
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(tag.status(), StatusCode::CREATED);
    let tag_body = to_bytes(tag.into_body(), usize::MAX)
        .await
        .expect("tag body");
    let tag_json: serde_json::Value = serde_json::from_slice(&tag_body).expect("tag json");
    let tag_id = tag_json["id"].as_str().expect("tag id");
    let tag_relation = request(
        &app,
        Method::POST,
        &format!("/api/v1/tags/{tag_id}/media/{upload_media_id}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(tag_relation.status(), StatusCode::OK);

    let tags = request(
        &app,
        Method::GET,
        "/api/v1/tags?limit=10",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(tags.status(), StatusCode::OK);
    let tags_body = to_bytes(tags.into_body(), usize::MAX)
        .await
        .expect("tags body");
    let tags_json: serde_json::Value = serde_json::from_slice(&tags_body).expect("tags json");
    let featured = tags_json["items"]
        .as_array()
        .expect("items")
        .iter()
        .find(|tag| tag["name"] == "精选")
        .expect("featured tag")
        .clone();
    assert!(featured["id"].is_string());
    assert_eq!(tags_json["hasMore"], false);
    assert!(tags_json["nextCursor"].as_str().is_some());
}

#[tokio::test]
async fn folder_scan_reconciles_only_the_selected_subtree() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    tokio::fs::create_dir_all(media.path().join("selected/nested"))
        .await
        .expect("selected directory");
    tokio::fs::create_dir_all(media.path().join("selected-sibling"))
        .await
        .expect("sibling directory");
    tokio::fs::write(
        media.path().join("selected/nested/inside.jpg"),
        photo_bytes(b"inside"),
    )
    .await
    .expect("selected photo");
    tokio::fs::write(
        media.path().join("selected-sibling/outside.jpg"),
        photo_bytes(b"outside"),
    )
    .await
    .expect("sibling photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token) = establish_admin(&app, setup_token.trim()).await;

    let initial_scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(initial_scan.status(), StatusCode::ACCEPTED);
    let initial_body = to_bytes(initial_scan.into_body(), usize::MAX)
        .await
        .expect("initial scan body");
    let initial_job_id = serde_json::from_slice::<serde_json::Value>(&initial_body)
        .expect("initial scan json")["id"]
        .as_str()
        .expect("initial job id")
        .to_owned();
    assert_eq!(
        wait_admin_job(&app, &admin_token, &initial_job_id).await["status"],
        "succeeded"
    );

    let inside_id = sqlx::query_scalar::<_, String>(
        "SELECT media_asset_id FROM media_locations WHERE normalized_path = 'selected/nested/inside.jpg'",
    )
    .fetch_one(&state.db)
    .await
    .expect("inside media id");
    let outside_id = sqlx::query_scalar::<_, String>(
        "SELECT media_asset_id FROM media_locations WHERE normalized_path = 'selected-sibling/outside.jpg'",
    )
    .fetch_one(&state.db)
    .await
    .expect("outside media id");

    tokio::fs::remove_file(media.path().join("selected/nested/inside.jpg"))
        .await
        .expect("remove selected photo");
    tokio::fs::remove_file(media.path().join("selected-sibling/outside.jpg"))
        .await
        .expect("remove sibling photo");

    let folder_scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan?path=selected",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(folder_scan.status(), StatusCode::ACCEPTED);
    let folder_body = to_bytes(folder_scan.into_body(), usize::MAX)
        .await
        .expect("folder scan body");
    let folder_job_id = serde_json::from_slice::<serde_json::Value>(&folder_body)
        .expect("folder scan json")["id"]
        .as_str()
        .expect("folder job id")
        .to_owned();
    let folder_job = wait_admin_job(&app, &admin_token, &folder_job_id).await;
    assert_eq!(folder_job["status"], "succeeded");
    assert_eq!(folder_job["checkpoint"]["scopePath"], "selected");
    assert_eq!(folder_job["checkpoint"]["phase"], "completed");

    let inside_state =
        sqlx::query_scalar::<_, String>("SELECT identity_state FROM media_assets WHERE id = ?1")
            .bind(&inside_id)
            .fetch_one(&state.db)
            .await
            .expect("inside state");
    let outside_state =
        sqlx::query_scalar::<_, String>("SELECT identity_state FROM media_assets WHERE id = ?1")
            .bind(&outside_id)
            .fetch_one(&state.db)
            .await
            .expect("outside state");
    assert_eq!(inside_state, "tombstoned");
    assert_eq!(outside_state, "verified");
}

#[tokio::test]
async fn cancelled_scan_resumes_from_checkpoint_without_tombstoning_prior_paths() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let album = media.path().join("album");
    tokio::fs::create_dir_all(&album)
        .await
        .expect("album directory");
    tokio::fs::write(album.join("a.jpg"), photo_bytes(b"already-indexed"))
        .await
        .expect("first photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    // `a.jpg` 是取消前已经完成的条目。续跑必须从 b/c 开始，而不是重新遍历它。
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "initial-scan")
        .await
        .expect("initial scan");
    let first_media_id = sqlx::query_scalar::<_, String>(
        "SELECT a.id FROM media_assets a INNER JOIN media_locations l ON l.media_asset_id = a.id WHERE l.normalized_path = 'album/a.jpg'",
    )
    .fetch_one(&state.db)
    .await
    .expect("first media id");

    tokio::fs::write(album.join("b.jpg"), photo_bytes(b"resume-b"))
        .await
        .expect("second photo");
    tokio::fs::write(album.join("c.jpg"), photo_bytes(b"resume-c"))
        .await
        .expect("third photo");

    let source_job_id = Uuid::new_v4().to_string();
    let now = youyou_server::db::now_millis();
    let interrupted_checkpoint = serde_json::json!({
        "version": 2,
        "kind": "scan",
        "scopePath": "album",
        "retryFailed": false,
        "phase": "interrupted",
        "discovered": 1,
        "indexed": 1,
        "failed": 0,
        "skippedUnsupported": 0,
        "skippedIgnored": 0,
        "skippedFailed": 0,
        "retried": 0,
        "failures": [],
        "resume": {
            "currentDirectory": "album",
            "lastEntryName": "a.jpg",
            "pendingDirectories": []
        }
    });
    sqlx::query(
        "INSERT INTO jobs (id, kind, status, current, total, checkpoint, created_at, updated_at, finished_at) VALUES (?1, 'scan', 'cancelled', 1, 1, ?2, ?3, ?3, ?3)",
    )
    .bind(&source_job_id)
    .bind(interrupted_checkpoint.to_string())
    .bind(now)
    .execute(&state.db)
    .await
    .expect("persist cancelled scan");

    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token) = establish_admin(&app, setup_token.trim()).await;
    let response = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan?path=album",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(response.status(), StatusCode::ACCEPTED);
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("scan response body");
    let new_job_id = serde_json::from_slice::<serde_json::Value>(&body)
        .expect("scan response json")["id"]
        .as_str()
        .expect("new scan job id")
        .to_owned();

    let settled = wait_admin_job(&app, &admin_token, &new_job_id).await;
    assert_eq!(settled["status"], "succeeded");
    assert_eq!(settled["checkpoint"]["phase"], "completed");
    assert_eq!(settled["checkpoint"]["resumedFromJobId"], source_job_id);
    assert_eq!(settled["checkpoint"]["resumedAtIndexed"], 1);
    assert_eq!(settled["checkpoint"]["indexed"], 3);
    assert_eq!(settled["checkpoint"]["discovered"], 3);

    let indexed_paths = sqlx::query_scalar::<_, String>(
        "SELECT normalized_path FROM media_locations WHERE storage_id = 'local' AND hash_state = 'verified' ORDER BY normalized_path",
    )
    .fetch_all(&state.db)
    .await
    .expect("indexed paths");
    assert_eq!(
        indexed_paths,
        vec!["album/a.jpg", "album/b.jpg", "album/c.jpg"]
    );
    let first_state =
        sqlx::query_scalar::<_, String>("SELECT identity_state FROM media_assets WHERE id = ?1")
            .bind(&first_media_id)
            .fetch_one(&state.db)
            .await
            .expect("prior media state");
    assert_eq!(first_state, "verified", "续跑对账不得误墓碑取消前的路径");

    // 已完成的续跑成为最新记录后，下一次普通刷新必须从头扫描，不能反复复用
    // 更早已取消任务的游标。
    let fresh = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan?path=album",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(fresh.status(), StatusCode::ACCEPTED);
    let fresh_body = to_bytes(fresh.into_body(), usize::MAX)
        .await
        .expect("fresh scan response body");
    let fresh_job_id = serde_json::from_slice::<serde_json::Value>(&fresh_body)
        .expect("fresh scan response json")["id"]
        .as_str()
        .expect("fresh scan job id")
        .to_owned();
    let fresh_settled = wait_admin_job(&app, &admin_token, &fresh_job_id).await;
    assert_eq!(fresh_settled["status"], "succeeded");
    assert!(
        fresh_settled["checkpoint"]["resumedFromJobId"].is_null(),
        "已完成的续跑不能让后续普通扫描继续沿用过期游标"
    );
}

#[cfg(unix)]
#[tokio::test]
async fn scan_skips_internal_symlink_cycles() {
    use std::os::unix::fs::symlink;

    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(library.join("nested"))
        .await
        .expect("nested directory");
    tokio::fs::write(library.join("nested/photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");
    symlink(library.join("nested"), library.join("nested/loop")).expect("internal symlink");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, _) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    let result = wait_admin_job(&app, &admin_token, &job_id).await;
    assert_eq!(result["status"], "succeeded");
    assert_eq!(result["current"], 1);
}

#[tokio::test]
async fn resumes_persisted_jobs_after_restart_recovery() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    tokio::fs::write(media.path().join("photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let job_id = Uuid::new_v4().to_string();
    let now = youyou_server::db::now_millis();
    sqlx::query(
        "INSERT INTO jobs (id, kind, status, created_at, updated_at) VALUES (?1, 'scan', 'running', ?2, ?2)",
    )
    .bind(&job_id)
    .bind(now)
    .execute(&state.db)
    .await
    .expect("persist running job");
    youyou_server::db::recover_running_jobs(&state.db)
        .await
        .expect("recover job");

    let _app = build_router(state.clone());
    let mut status = String::new();
    for _ in 0..20 {
        status = sqlx::query_scalar::<_, String>("SELECT status FROM jobs WHERE id = ?1")
            .bind(&job_id)
            .fetch_one(&state.db)
            .await
            .expect("job status");
        if status == "succeeded" {
            break;
        }
        sleep(Duration::from_millis(50)).await;
    }
    assert_eq!(status, "succeeded");
}

#[tokio::test]
async fn resumes_persisted_bootstrap_with_its_snapshot_after_restart() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    // 建用户并绑定媒体库，使扫描出的媒体归属该用户。
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let job_id = Uuid::new_v4().to_string();
    let snapshot_id = Uuid::new_v4().to_string();
    let now = youyou_server::db::now_millis();
    sqlx::query(
        "INSERT INTO jobs (id, kind, status, user_id, created_at, updated_at) \
         VALUES (?1, 'bootstrap', 'running', 1, ?2, ?2)",
    )
    .bind(&job_id)
    .bind(now)
    .execute(&state.db)
    .await
    .expect("persist bootstrap job");
    sqlx::query(
        "INSERT INTO sync_snapshots \
         (id, job_id, state, user_id, expires_at, created_at, updated_at) \
         VALUES (?1, ?2, 'preparing', 1, ?3, ?4, ?4)",
    )
    .bind(&snapshot_id)
    .bind(&job_id)
    .bind(now + 60_000)
    .bind(now)
    .execute(&state.db)
    .await
    .expect("persist snapshot");
    youyou_server::db::recover_running_jobs(&state.db)
        .await
        .expect("recover bootstrap");

    let _app = build_router(state.clone());
    let mut status = String::new();
    for _ in 0..40 {
        status = sqlx::query_scalar::<_, String>("SELECT status FROM jobs WHERE id = ?1")
            .bind(&job_id)
            .fetch_one(&state.db)
            .await
            .expect("job status");
        if status == "succeeded" {
            break;
        }
        sleep(Duration::from_millis(25)).await;
    }
    assert_eq!(status, "succeeded");
    let stored_snapshot =
        sqlx::query_scalar::<_, String>("SELECT state FROM sync_snapshots WHERE id = ?1")
            .bind(&snapshot_id)
            .fetch_one(&state.db)
            .await
            .expect("snapshot state");
    assert_eq!(stored_snapshot, "ready");
}

#[tokio::test]
async fn full_scan_tombstones_media_removed_from_local_directory() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    let photo_path = library.join("photo.jpg");
    tokio::fs::write(&photo_path, photo_bytes(b"abcdef"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan-1")
        .await
        .expect("initial scan");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    let media_id = sqlx::query_scalar::<_, String>(
        "SELECT id FROM media_assets WHERE identity_state = 'verified'",
    )
    .fetch_one(&state.db)
    .await
    .expect("media id");
    let tag = youyou_server::metadata::create_tag(&state.db, 1, "待清理标签")
        .await
        .expect("tag");
    youyou_server::metadata::add_tag_media(&state.db, 1, &tag.id, &media_id)
        .await
        .expect("tag relation");

    tokio::fs::remove_file(photo_path)
        .await
        .expect("remove photo");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan-2")
        .await
        .expect("reconcile scan");

    let state_name =
        sqlx::query_scalar::<_, String>("SELECT identity_state FROM media_assets WHERE id = ?1")
            .bind(&media_id)
            .fetch_one(&state.db)
            .await
            .expect("media state");
    assert_eq!(state_name, "tombstoned");
    let locations = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM media_locations WHERE media_asset_id = ?1",
    )
    .bind(&media_id)
    .fetch_one(&state.db)
    .await
    .expect("location count");
    assert_eq!(locations, 0);
    let tag_relations =
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM media_tags WHERE media_asset_id = ?1")
            .bind(&media_id)
            .fetch_one(&state.db)
            .await
            .expect("tag relation count");
    assert_eq!(tag_relations, 0);
    let deleted_relation_versions = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM relation_versions WHERE deleted_at IS NOT NULL",
    )
    .fetch_one(&state.db)
    .await
    .expect("relation tombstones");
    assert_eq!(deleted_relation_versions, 1);
    let delete_events = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM change_log WHERE entity_id = ?1 AND operation = 'delete' AND owner_user_id = 1",
    )
    .bind(&media_id)
    .fetch_one(&state.db)
    .await
    .expect("delete events");
    assert_eq!(delete_events, 1);
}

#[tokio::test]
async fn stale_location_is_not_readable_through_media_endpoints() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let media_id = sqlx::query_scalar::<_, String>(
        "SELECT id FROM media_assets WHERE identity_state = 'verified' AND owner_user_id = 1",
    )
    .fetch_one(&state.db)
    .await
    .expect("media id");
    sqlx::query("UPDATE media_locations SET hash_state = 'stale' WHERE media_asset_id = ?1")
        .bind(&media_id)
        .execute(&state.db)
        .await
        .expect("stale location");

    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(detail.status(), StatusCode::NOT_FOUND);
    let content = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/content"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(content.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn metadata_writes_replay_matching_requests_and_reject_mismatches() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (_, _, device_token) = establish_device(&app, setup_token.trim()).await;

    let first = json_request_with_key(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "重复请求"}),
        &device_token,
        "tag-create-retry",
    )
    .await;
    assert_eq!(first.status(), StatusCode::CREATED);

    let replay = json_request_with_key(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "重复请求"}),
        &device_token,
        "tag-create-retry",
    )
    .await;
    assert_eq!(replay.status(), StatusCode::CREATED);

    let conflict = json_request_with_key(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "内容不同必须拒绝"}),
        &device_token,
        "tag-create-retry",
    )
    .await;
    assert_eq!(conflict.status(), StatusCode::CONFLICT);
    let conflict_body = to_bytes(conflict.into_body(), usize::MAX)
        .await
        .expect("idempotency conflict body");
    let replay_json: serde_json::Value =
        serde_json::from_slice(&conflict_body).expect("idempotency conflict json");
    assert_eq!(replay_json["code"], "conflict");

    let tag_count = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM tags")
        .fetch_one(&state.db)
        .await
        .expect("tag count");
    assert_eq!(tag_count, 1);
}

#[tokio::test]
async fn changes_cursor_requires_resync_after_retention_prunes_old_events() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (_, _, device_token) = establish_device(&app, setup_token.trim()).await;
    let now = youyou_server::db::now_millis();
    sqlx::query(
        r#"
        INSERT INTO change_log
            (event_id, entity, operation, entity_id, version, payload, created_at)
        VALUES (?1, 'media', 'upsert', 'old', 1, '{}', ?2)
        "#,
    )
    .bind(Uuid::new_v4().to_string())
    .bind(now - 31 * 24 * 60 * 60 * 1000)
    .execute(&state.db)
    .await
    .expect("old change");
    sqlx::query(
        r#"
        INSERT INTO change_log
            (event_id, entity, operation, entity_id, version, payload, created_at)
        VALUES (?1, 'media', 'upsert', 'new', 1, '{}', ?2)
        "#,
    )
    .bind(Uuid::new_v4().to_string())
    .bind(now)
    .execute(&state.db)
    .await
    .expect("recent change");

    let response = request(
        &app,
        Method::GET,
        "/api/v1/changes?cursor=djI6MDo",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("changes error body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("changes error json");
    assert_eq!(json["code"], "resync_required");

    let legacy_cursor = request(
        &app,
        Method::GET,
        "/api/v1/changes?cursor=djE6MA",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(legacy_cursor.status(), StatusCode::CONFLICT);
    let legacy_body = to_bytes(legacy_cursor.into_body(), usize::MAX)
        .await
        .expect("legacy cursor error body");
    let legacy_json: serde_json::Value =
        serde_json::from_slice(&legacy_body).expect("legacy cursor error json");
    assert_eq!(legacy_json["code"], "resync_required");

    let old_count =
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM change_log WHERE entity_id = 'old'")
            .fetch_one(&state.db)
            .await
            .expect("old change count");
    assert_eq!(old_count, 0);
}

#[tokio::test]
async fn allows_http_auth_and_rate_limits_remote_auth_failures() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let peer = SocketAddr::from(([192, 0, 2, 10], 8080));

    let remote_setup = json_request_from_peer(
        &app,
        Method::POST,
        "/api/v1/admin/setup",
        serde_json::json!({
            "setupToken": setup_token.trim(),
            "password": "correct horse battery staple",
        }),
        peer,
    )
    .await;
    assert_eq!(remote_setup.status(), StatusCode::NO_CONTENT);

    for _ in 0..10 {
        let response = json_request_from_peer(
            &app,
            Method::POST,
            "/api/v1/admin/session",
            serde_json::json!({"password": "wrong password"}),
            peer,
        )
        .await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }
    let blocked = json_request_from_peer(
        &app,
        Method::POST,
        "/api/v1/admin/session",
        serde_json::json!({"password": "wrong password"}),
        peer,
    )
    .await;
    assert_eq!(blocked.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(blocked.headers()[header::RETRY_AFTER], "900");
}

#[tokio::test]
async fn pairing_code_attempts_are_consumed_by_invalid_device_names() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token, _) = establish_device(&app, setup_token.trim()).await;

    let user_id = admin_create_user(
        &app,
        &admin_token,
        &csrf_token,
        "pairing-user",
        "pairinguser",
    )
    .await;
    let pairing_code = request(
        &app,
        Method::POST,
        &format!("/api/v1/admin/users/{user_id}/pairing-codes"),
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(pairing_code.status(), StatusCode::OK);
    let body = to_bytes(pairing_code.into_body(), usize::MAX)
        .await
        .expect("pairing code body");
    let code =
        serde_json::from_slice::<serde_json::Value>(&body).expect("pairing code json")["code"]
            .as_str()
            .expect("pairing code")
            .to_owned();

    for _ in 0..5 {
        let response = json_request(
            &app,
            Method::POST,
            "/api/v1/pairing",
            serde_json::json!({"code": code.clone(), "deviceName": ""}),
            None,
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
    let locked = json_request(
        &app,
        Method::POST,
        "/api/v1/pairing",
        serde_json::json!({"code": code, "deviceName": "valid-name"}),
        None,
        None,
    )
    .await;
    assert_eq!(locked.status(), StatusCode::CONFLICT);
    let attempts = sqlx::query_scalar::<_, i64>(
        "SELECT attempts FROM pairing_codes ORDER BY created_at DESC LIMIT 1",
    )
    .fetch_one(&state.db)
    .await
    .expect("pairing attempts");
    assert_eq!(attempts, 5);
}

/// 用 ffmpeg 生成一个极短的真实视频夹具（编码器/容器/尺寸按格式要求选择，
/// 例如 H.263 只接受 128x96 / 176x144 等标准尺寸）。
fn write_video_fixture(path: &std::path::Path, encoder: &str, format: &str, size: &str) {
    let status = std::process::Command::new("ffmpeg")
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            &format!("testsrc=size={size}:rate=25:duration=0.4"),
            "-pix_fmt",
            "yuv420p",
            "-c:v",
            encoder,
            "-f",
            format,
        ])
        .arg(path)
        .status()
        .expect("run ffmpeg for fixture");
    assert!(
        status.success(),
        "ffmpeg fixture failed for {}",
        path.display()
    );
}

/// 用 macOS sips 生成真实 HEIC 夹具（无 sips 的环境跳过相关用例）。
fn heic_fixture(rgb: [u8; 3], size: u32) -> Option<Vec<u8>> {
    let dir = tempdir().ok()?;
    let png = dir.path().join("src.png");
    let heic = dir.path().join("src.heic");
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(size, size, image::Rgb(rgb)))
        .save_with_format(&png, image::ImageFormat::Png)
        .ok()?;
    let output = std::process::Command::new("sips")
        .args(["-s", "format", "heic"])
        .arg(&png)
        .arg("--out")
        .arg(&heic)
        .output()
        .ok()?;
    output.status.success().then(|| std::fs::read(&heic).ok())?
}

#[tokio::test]
async fn indexes_heic_with_container_dimensions_and_decodes_thumbnail() {
    let Some(heic) = heic_fixture([255, 0, 255], 8) else {
        eprintln!("跳过：当前环境没有 sips 生成 HEIC 夹具");
        return;
    };
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("IMG_20240105_000000.heic"), &heic)
        .await
        .expect("heic");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    let summary = youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    assert_eq!(summary.discovered, 1);
    assert_eq!(summary.indexed, 1, "失败明细：{:?}", summary.failures);

    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=1",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    let item = &json["items"][0];
    assert_eq!(item["width"], 8, "尺寸应来自 HEIF 容器解析：{item}");
    assert_eq!(item["height"], 8);
    assert_eq!(item["mimeType"], "image/heic");
    let media_id = item["id"].as_str().expect("media id");

    let thumbnail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    if youyou_server::heif::tool().is_some() {
        assert_eq!(thumbnail.status(), StatusCode::OK);
        let bytes = to_bytes(thumbnail.into_body(), usize::MAX)
            .await
            .expect("thumbnail body");
        let decoded = image::load_from_memory(&bytes).expect("缩略图应可解码");
        let pixel = decoded.to_rgb8().get_pixel(0, 0).0;
        assert!(
            pixel[0] > 180 && pixel[2] > 180 && pixel[1] < 110,
            "缩略图应是真实解码（洋红），实际 {pixel:?}"
        );
    } else {
        // 服务端没有 HEIF 解码器：明确返回 503，客户端可回退原图本地解码。
        assert_eq!(thumbnail.status(), StatusCode::SERVICE_UNAVAILABLE);
    }
}

#[tokio::test]
async fn indexes_legacy_formats_with_metadata_and_thumbnails() {
    // 历史上被扫描白名单跳过的格式：MPG（家庭录像）、WMV、3GP、M4V 与 BMP。
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    write_video_fixture(
        &library.join("jinggangshan.mpg"),
        "mpeg1video",
        "mpeg",
        "160x120",
    );
    write_video_fixture(&library.join("college.wmv"), "wmv2", "asf", "160x120");
    write_video_fixture(&library.join("college.3gp"), "h263", "3gp", "176x144");
    write_video_fixture(&library.join("clip.m4v"), "mpeg4", "mp4", "160x120");
    let mut bmp = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(4, 3, image::Rgb([10, 20, 30])))
        .write_to(&mut bmp, image::ImageFormat::Bmp)
        .expect("encode bmp");
    tokio::fs::write(library.join("old-photo.bmp"), bmp.into_inner())
        .await
        .expect("bmp");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    let summary = youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    assert_eq!(summary.discovered, 5, "全部历史格式都应被发现");
    assert_eq!(summary.indexed, 5, "失败明细：{:?}", summary.failures);

    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;

    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=20",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    let items = json["items"].as_array().expect("items");
    assert_eq!(items.len(), 5);
    for item in items {
        let name = item["name"].as_str().expect("name").to_owned();
        let is_video = item["isVideo"].as_bool().expect("isVideo");
        assert!(
            item["width"].as_i64().is_some() && item["height"].as_i64().is_some(),
            "{name} 应解析出宽高：{item}"
        );
        assert_eq!(is_video, !name.ends_with(".bmp"), "{name} 的视频判定");
        assert!(item["sortAt"].as_i64().is_some(), "{name} 应有媒体时间");
        let sort_source = item["sortSource"].as_str().unwrap_or_default();
        assert!(
            matches!(sort_source, "filename" | "modified"),
            "{name} 的时间来源应为文件名/mtime 回退，实际 {sort_source}"
        );
        let media_id = item["id"].as_str().expect("media id");
        let thumbnail = request(
            &app,
            Method::GET,
            &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
            None,
            Some(&device_token),
            None,
        )
        .await;
        assert_eq!(thumbnail.status(), StatusCode::OK, "{name} 缩略图应可用");
        assert_eq!(thumbnail.headers()[header::CONTENT_TYPE], "image/jpeg");
        let bytes = to_bytes(thumbnail.into_body(), usize::MAX)
            .await
            .expect("thumbnail body");
        assert!(!bytes.is_empty(), "{name} 缩略图不应为空");
    }
}

#[tokio::test]
async fn thumbnail_decode_failure_serves_placeholder() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    let mut pixel = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(2, 2, image::Rgb([255, 0, 0])))
        .write_to(&mut pixel, image::ImageFormat::Png)
        .expect("encode pixel");
    tokio::fs::write(library.join("pixel.png"), pixel.into_inner())
        .await
        .expect("pixel");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=1",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    let media_id = json["items"][0]["id"]
        .as_str()
        .expect("media id")
        .to_owned();

    // 源文件在索引后损坏：解码失败应回退为占位图，而不是 500。
    tokio::fs::write(library.join("pixel.png"), b"broken-after-index")
        .await
        .expect("break source");
    let thumbnail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(thumbnail.status(), StatusCode::OK);
    assert_eq!(thumbnail.headers()[header::CONTENT_TYPE], "image/jpeg");
    let bytes = to_bytes(thumbnail.into_body(), usize::MAX)
        .await
        .expect("thumbnail body");
    assert!(!bytes.is_empty());
    let placeholder = image::load_from_memory(&bytes).expect("placeholder decodes");
    assert_eq!(placeholder.width(), 64);
    assert_eq!(placeholder.height(), 64);
}

#[tokio::test]
async fn serves_and_caches_image_thumbnail() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    let mut pixel = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(1, 1, image::Rgb([255, 0, 0])))
        .write_to(&mut pixel, image::ImageFormat::Png)
        .expect("encode pixel");
    tokio::fs::write(library.join("pixel.png"), pixel.into_inner())
        .await
        .expect("pixel");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=1",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let media_body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let media_json: serde_json::Value = serde_json::from_slice(&media_body).expect("media json");
    assert_eq!(media_json["items"][0]["width"], 1);
    assert_eq!(media_json["items"][0]["height"], 1);
    let media_id = media_json["items"][0]["id"].as_str().expect("media id");
    let thumbnail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(thumbnail.status(), StatusCode::OK);
    assert_eq!(thumbnail.headers()[header::CONTENT_TYPE], "image/jpeg");
    let thumbnail_body = to_bytes(thumbnail.into_body(), usize::MAX)
        .await
        .expect("thumbnail body");
    assert!(!thumbnail_body.is_empty());

    let cached = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(cached.status(), StatusCode::OK);
    assert_eq!(
        to_bytes(cached.into_body(), usize::MAX)
            .await
            .expect("cached thumbnail body"),
        thumbnail_body
    );
}

#[tokio::test]
async fn serves_image_thumbnails_for_videos_at_each_requested_size() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    let video_path = library.join("sample.mp4");
    let output = tokio::process::Command::new("ffmpeg")
        .args([
            "-y",
            "-v",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=blue:s=320x180:d=1",
            "-c:v",
            "mpeg4",
            "-pix_fmt",
            "yuv420p",
        ])
        .arg(&video_path)
        .output()
        .await
        .expect("start ffmpeg");
    assert!(
        output.status.success(),
        "ffmpeg failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );

    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let media_response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=1",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(media_response.status(), StatusCode::OK);
    let media_body = to_bytes(media_response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let media_json: serde_json::Value = serde_json::from_slice(&media_body).expect("media json");
    assert_eq!(media_json["items"][0]["isVideo"], true);
    assert_eq!(media_json["items"][0]["videoCodec"], "mpeg4");
    let media_id = media_json["items"][0]["id"].as_str().expect("media id");

    let small = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=64"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    let large = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}/thumbnail?size=128"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(small.status(), StatusCode::OK);
    assert_eq!(large.status(), StatusCode::OK);
    assert_eq!(small.headers()[header::CONTENT_TYPE], "image/jpeg");
    assert_eq!(large.headers()[header::CONTENT_TYPE], "image/jpeg");
    let small_body = to_bytes(small.into_body(), usize::MAX)
        .await
        .expect("small thumbnail");
    let large_body = to_bytes(large.into_body(), usize::MAX)
        .await
        .expect("large thumbnail");
    assert!(!small_body.is_empty());
    assert!(!large_body.is_empty());
    assert_ne!(small_body, large_body);
    let cached_files = std::fs::read_dir(data.path().join("thumbnails"))
        .expect("thumbnail cache")
        .count();
    assert_eq!(cached_files, 2);
}

#[tokio::test]
async fn duplicate_content_is_one_media_with_a_deterministic_location() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("a.jpg"), photo_bytes(b"same-content"))
        .await
        .expect("first photo");
    tokio::fs::write(library.join("b.jpg"), photo_bytes(b"same-content"))
        .await
        .expect("second photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let media_count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM media_assets WHERE identity_state = 'verified'",
    )
    .fetch_one(&state.db)
    .await
    .expect("media count");
    let location_count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM media_locations WHERE hash_state = 'verified'",
    )
    .fetch_one(&state.db)
    .await
    .expect("location count");
    assert_eq!(media_count, 1);
    assert_eq!(location_count, 2);
    let changes_before = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM change_log")
        .fetch_one(&state.db)
        .await
        .expect("initial change count");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan-again")
        .await
        .expect("unchanged rescan");
    let changes_after = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM change_log")
        .fetch_one(&state.db)
        .await
        .expect("rescan change count");
    assert_eq!(changes_after, changes_before);

    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token2, csrf_token2) = establish_admin(&app, setup_token.trim()).await;
    let device_token = pair_user_device(&app, &admin_token2, &csrf_token2, 1, "test-device").await;
    let response = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=100",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    assert_eq!(json["items"].as_array().expect("items").len(), 1);
    assert_eq!(json["items"][0]["path"], "library/a.jpg");

    let bootstrap = request(
        &app,
        Method::POST,
        "/api/v1/sync/bootstrap",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let bootstrap_body = to_bytes(bootstrap.into_body(), usize::MAX)
        .await
        .expect("bootstrap body");
    let bootstrap_json: serde_json::Value =
        serde_json::from_slice(&bootstrap_body).expect("bootstrap json");
    let snapshot_id = bootstrap_json["snapshotId"].as_str().expect("snapshot id");
    let mut ready = false;
    for _ in 0..40 {
        let response = request(
            &app,
            Method::GET,
            &format!("/api/v1/sync/bootstrap/{snapshot_id}"),
            None,
            Some(&device_token),
            None,
        )
        .await;
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("snapshot status body");
        let status: serde_json::Value = serde_json::from_slice(&body).expect("snapshot status");
        if status["state"] == "ready" {
            ready = true;
            break;
        }
        sleep(Duration::from_millis(10)).await;
    }
    assert!(ready);
    let response = request(
        &app,
        Method::GET,
        &format!("/api/v1/sync/bootstrap/{snapshot_id}/media?limit=100"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("snapshot media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("snapshot media json");
    assert_eq!(json["items"].as_array().expect("items").len(), 1);
}

#[tokio::test]
async fn favorite_updates_propagate_to_snapshot_and_changes() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let scan_body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&scan_body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    wait_admin_job(&app, &admin_token, &job_id).await;

    let media_page = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=10",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(media_page.status(), StatusCode::OK);
    let body = to_bytes(media_page.into_body(), usize::MAX)
        .await
        .expect("media body");
    let media_json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    let media_id = media_json["items"][0]["id"]
        .as_str()
        .expect("media id")
        .to_owned();
    assert_eq!(media_json["items"][0]["isFavorite"], false);

    // 收藏
    let favorite = json_request(
        &app,
        Method::POST,
        &format!("/api/v1/media/{media_id}/favorite"),
        serde_json::json!({"isFavorite": true}),
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(favorite.status(), StatusCode::OK);
    let favorite_body = to_bytes(favorite.into_body(), usize::MAX)
        .await
        .expect("favorite body");
    let favorite_json: serde_json::Value =
        serde_json::from_slice(&favorite_body).expect("favorite json");
    assert_eq!(favorite_json["isFavorite"], true);

    // 变更流携带收藏状态
    let changes = request(
        &app,
        Method::GET,
        "/api/v1/changes?limit=50",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(changes.status(), StatusCode::OK);
    let body = to_bytes(changes.into_body(), usize::MAX)
        .await
        .expect("changes body");
    let changes_json: serde_json::Value = serde_json::from_slice(&body).expect("changes json");
    let favorite_change = changes_json["items"]
        .as_array()
        .expect("items")
        .iter()
        .filter(|item| item["entity"] == "media" && item["entityId"] == media_id)
        .max_by_key(|item| item["revision"].as_i64().unwrap_or(0))
        .expect("favorite change");
    assert_eq!(favorite_change["data"]["isFavorite"], true);

    // bootstrap 快照携带收藏状态
    let bootstrap = request(
        &app,
        Method::POST,
        "/api/v1/sync/bootstrap",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(bootstrap.status(), StatusCode::ACCEPTED);
    let bootstrap_body = to_bytes(bootstrap.into_body(), usize::MAX)
        .await
        .expect("bootstrap body");
    let bootstrap_json: serde_json::Value =
        serde_json::from_slice(&bootstrap_body).expect("bootstrap json");
    let snapshot_id = bootstrap_json["snapshotId"].as_str().expect("snapshot id");
    let mut ready = false;
    for _ in 0..40 {
        let response = request(
            &app,
            Method::GET,
            &format!("/api/v1/sync/bootstrap/{snapshot_id}"),
            None,
            Some(&device_token),
            None,
        )
        .await;
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("snapshot body");
        if serde_json::from_slice::<serde_json::Value>(&body).expect("snapshot json")["state"]
            == "ready"
        {
            ready = true;
            break;
        }
        sleep(Duration::from_millis(10)).await;
    }
    assert!(ready);
    let snapshot_media = request(
        &app,
        Method::GET,
        &format!("/api/v1/sync/bootstrap/{snapshot_id}/media"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(snapshot_media.status(), StatusCode::OK);
    let body = to_bytes(snapshot_media.into_body(), usize::MAX)
        .await
        .expect("snapshot media body");
    let snapshot_json: serde_json::Value =
        serde_json::from_slice(&body).expect("snapshot media json");
    assert_eq!(snapshot_json["items"][0]["data"]["isFavorite"], true);

    // 其他用户不能收藏不属于自己的媒体
    let other_library = media.path().join("other");
    tokio::fs::create_dir_all(&other_library)
        .await
        .expect("other dir");
    let other_user = admin_create_user(&app, &admin_token, &csrf_token, "other", "other").await;
    let other_device = pair_user_device(&app, &admin_token, &csrf_token, other_user, "other").await;
    let cross = json_request(
        &app,
        Method::POST,
        &format!("/api/v1/media/{media_id}/favorite"),
        serde_json::json!({"isFavorite": true}),
        Some(&other_device),
        None,
    )
    .await;
    assert_eq!(cross.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn user_data_is_isolated_between_libraries() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library_a = media.path().join("alice");
    let library_b = media.path().join("bob");
    tokio::fs::create_dir_all(&library_a)
        .await
        .expect("alice dir");
    tokio::fs::create_dir_all(&library_b)
        .await
        .expect("bob dir");
    tokio::fs::write(library_a.join("alice.jpg"), photo_bytes(b"alice-content"))
        .await
        .expect("alice photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token, device_a) =
        establish_user_device(&app, setup_token.trim(), "alice").await;
    let user_b = admin_create_user(&app, &admin_token, &csrf_token, "bob", "bob").await;
    let device_b = pair_user_device(&app, &admin_token, &csrf_token, user_b, "bob-phone").await;

    // 删除结果凭据按稳定服务端实例与认证用户隔离，设备不得声明用户身份。
    let mut identities = Vec::new();
    for token in [&device_a, &device_b] {
        let response = request(&app, Method::GET, "/api/v1/server", None, Some(token), None).await;
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
        identities.push(serde_json::from_slice::<serde_json::Value>(&bytes).unwrap());
    }
    assert_eq!(
        identities[0]["serverInstanceId"],
        identities[1]["serverInstanceId"]
    );
    assert_ne!(identities[0]["userId"], identities[1]["userId"]);
    assert_eq!(identities[1]["userId"], user_b.to_string());

    // Alice 扫描后只能看到自己库里的媒体。
    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let scan_body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&scan_body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    let job = wait_admin_job(&app, &admin_token, &job_id).await;
    assert_eq!(job["status"], "succeeded");

    let alice_list = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=100",
        None,
        Some(&device_a),
        None,
    )
    .await;
    assert_eq!(alice_list.status(), StatusCode::OK);
    let body = to_bytes(alice_list.into_body(), usize::MAX)
        .await
        .expect("alice media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("alice media json");
    assert_eq!(json["items"].as_array().expect("items").len(), 1);
    let alice_media_id = json["items"][0]["id"]
        .as_str()
        .expect("media id")
        .to_owned();

    // Bob 看不到 Alice 的媒体。
    let bob_list = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=100",
        None,
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(bob_list.status(), StatusCode::OK);
    let body = to_bytes(bob_list.into_body(), usize::MAX)
        .await
        .expect("bob media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("bob media json");
    assert_eq!(json["items"].as_array().expect("items").len(), 0);

    let cross_detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{alice_media_id}"),
        None,
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(cross_detail.status(), StatusCode::NOT_FOUND);
    let cross_content = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{alice_media_id}/content"),
        None,
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(cross_content.status(), StatusCode::NOT_FOUND);
    let cross_thumbnail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{alice_media_id}/thumbnail"),
        None,
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(cross_thumbnail.status(), StatusCode::NOT_FOUND);

    // Bob 的标签互不可见；Bob 不能给 Alice 的媒体打标。
    let bob_tag = json_request(
        &app,
        Method::POST,
        "/api/v1/tags",
        serde_json::json!({"name": "bobs"}),
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(bob_tag.status(), StatusCode::CREATED);
    let bob_tag_body = to_bytes(bob_tag.into_body(), usize::MAX)
        .await
        .expect("bob tag body");
    let bob_tag_id = serde_json::from_slice::<serde_json::Value>(&bob_tag_body)
        .expect("bob tag json")["id"]
        .as_str()
        .expect("tag id")
        .to_owned();
    let cross_tag = request(
        &app,
        Method::POST,
        &format!("/api/v1/tags/{bob_tag_id}/media/{alice_media_id}"),
        None,
        Some(&device_b),
        None,
    )
    .await;
    assert_eq!(cross_tag.status(), StatusCode::NOT_FOUND);

    // Bob 上传到自己的库；Alice 看不到。
    let upload = stream_upload_request(
        &app,
        &device_b,
        "bob.jpg",
        Some("image/jpeg"),
        None,
        &photo_bytes(b"bob-upload"),
    )
    .await;
    assert_eq!(upload.status(), StatusCode::OK);
    let body = to_bytes(upload.into_body(), usize::MAX)
        .await
        .expect("bob upload body");
    let upload_json: serde_json::Value = serde_json::from_slice(&body).expect("upload json");
    assert!(
        upload_json["path"]
            .as_str()
            .expect("upload path")
            .starts_with("bob/uploads/")
    );
    let alice_after = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=100",
        None,
        Some(&device_a),
        None,
    )
    .await;
    let body = to_bytes(alice_after.into_body(), usize::MAX)
        .await
        .expect("alice after body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("alice after json");
    assert_eq!(json["items"].as_array().expect("items").len(), 1);
}

#[tokio::test]
async fn health_endpoint_is_available_before_storage_details() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let app = build_router(state);

    let response = request(&app, Method::GET, "/api/v1/health", None, None, None).await;
    assert_eq!(response.status(), StatusCode::OK);
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("health body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("health json");
    assert_eq!(json["status"], "ok");
    assert_eq!(json["service"], "youyou-server");
}

#[tokio::test]
async fn restart_reuses_the_persisted_storage_root() {
    let data = tempdir().expect("data directory");
    let configured_root = tempdir().expect("configured media directory");
    let fallback_root = tempdir().expect("fallback media directory");

    let state = initialize(data.path(), configured_root.path())
        .await
        .expect("initial state");
    let first_root = state.storage.snapshot().await.root().to_path_buf();
    drop(state);

    let restarted = initialize(data.path(), fallback_root.path())
        .await
        .expect("restarted state");
    let restarted_root = restarted.storage.snapshot().await.root().to_path_buf();
    assert_eq!(restarted_root, first_root);
    assert_ne!(restarted_root, fallback_root.path());
}

#[tokio::test]
async fn storage_root_rejects_data_directory_and_filesystem_root() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, _) = establish_device(&app, setup_token.trim()).await;

    let data_root = json_request(
        &app,
        Method::PATCH,
        "/api/v1/admin/storage",
        serde_json::json!({"rootPath": data.path()}),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(data_root.status(), StatusCode::BAD_REQUEST);

    let filesystem_root = json_request(
        &app,
        Method::PATCH,
        "/api/v1/admin/storage",
        serde_json::json!({"rootPath": "/"}),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(filesystem_root.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn cascaded_metadata_changes_share_one_transaction_revision() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library)
        .await
        .expect("library dir");
    tokio::fs::write(library.join("photo.jpg"), photo_bytes(b"abcdef"))
        .await
        .expect("photo");
    let state = initialize(data.path(), media.path()).await.expect("state");
    let now0 = youyou_server::db::now_millis();
    sqlx::query("INSERT INTO users (name, created_at, updated_at) VALUES ('owner', ?1, ?1)")
        .bind(now0)
        .execute(&state.db)
        .await
        .expect("create user");
    youyou_server::users::bind_user_library(
        &state.db,
        state.storage.snapshot().await.root(),
        1,
        "library",
    )
    .await
    .expect("bind library");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let media_id = sqlx::query_scalar::<_, String>(
        "SELECT id FROM media_assets WHERE identity_state = 'verified' AND owner_user_id = 1",
    )
    .fetch_one(&state.db)
    .await
    .expect("media id");
    let tag = metadata::create_tag(&state.db, 1, "revision tag")
        .await
        .expect("tag");
    metadata::add_tag_media(&state.db, 1, &tag.id, &media_id)
        .await
        .expect("relation");
    metadata::delete_tag(&state.db, 1, &tag.id, tag.version)
        .await
        .expect("delete tag");

    let relation_id = format!("{}:{}", tag.id, media_id);
    let (event_count, revision_count) = sqlx::query_as::<_, (i64, i64)>(
        r#"
        SELECT COUNT(*), COUNT(DISTINCT revision)
        FROM change_log
        WHERE operation = 'delete' AND entity_id IN (?1, ?2)
        "#,
    )
    .bind(&tag.id)
    .bind(&relation_id)
    .fetch_one(&state.db)
    .await
    .expect("revision counts");
    assert_eq!(event_count, 2);
    assert_eq!(revision_count, 1);
}

#[tokio::test]
async fn admin_media_library_browse_mkdir_move_upload_and_delete() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let mut pixel = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(2, 2, image::Rgb([0, 128, 255])))
        .write_to(&mut pixel, image::ImageFormat::Jpeg)
        .expect("encode pixel");
    let jpeg_bytes = pixel.into_inner();
    tokio::fs::create_dir_all(media.path().join("albums"))
        .await
        .expect("albums dir");
    tokio::fs::write(media.path().join("albums/shot.jpg"), &jpeg_bytes)
        .await
        .expect("shot");

    let state = initialize(data.path(), media.path()).await.expect("state");
    youyou_server::scan::scan_directory(&state.db, state.storage.clone(), "scan")
        .await
        .expect("scan");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, _) = establish_device(&app, setup_token.trim()).await;

    let root = request(
        &app,
        Method::GET,
        "/api/v1/admin/media-library",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(root.status(), StatusCode::OK);
    let root_body = to_bytes(root.into_body(), usize::MAX)
        .await
        .expect("root body");
    let root_json: serde_json::Value = serde_json::from_slice(&root_body).expect("root json");
    let albums_folder = root_json["folders"]
        .as_array()
        .expect("folders")
        .iter()
        .find(|folder| folder["path"] == "albums")
        .expect("albums folder");
    let albums_thumbnail_id = albums_folder["thumbnailMediaId"]
        .as_str()
        .expect("albums folder thumbnail media id")
        .to_owned();

    let mkdir = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/media-library/mkdir",
        serde_json::json!({ "path": "albums/empty" }),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(mkdir.status(), StatusCode::NO_CONTENT);

    let albums = request(
        &app,
        Method::GET,
        "/api/v1/admin/media-library?path=albums",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(albums.status(), StatusCode::OK);
    let albums_body = to_bytes(albums.into_body(), usize::MAX)
        .await
        .expect("albums body");
    let albums_json: serde_json::Value = serde_json::from_slice(&albums_body).expect("albums json");
    let empty_folder = albums_json["folders"]
        .as_array()
        .expect("folders")
        .iter()
        .find(|folder| folder["path"] == "albums/empty")
        .expect("albums/empty folder");
    assert!(
        empty_folder["thumbnailMediaId"].is_null(),
        "a folder without indexed media must not advertise a thumbnail"
    );
    assert_eq!(albums_json["media"].as_array().expect("media").len(), 1);
    let media_id = albums_json["media"][0]["id"].as_str().expect("media id");
    assert_eq!(
        albums_thumbnail_id, media_id,
        "folder tile thumbnail must point at the first media inside the folder"
    );

    let info = request(
        &app,
        Method::GET,
        &format!("/api/v1/admin/media-library/media/{media_id}/info"),
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(info.status(), StatusCode::OK);
    let info_body = to_bytes(info.into_body(), usize::MAX)
        .await
        .expect("info body");
    let info_json: serde_json::Value = serde_json::from_slice(&info_body).expect("info json");
    assert_eq!(info_json["name"], "shot.jpg");
    assert_eq!(info_json["path"], "albums/shot.jpg");
    assert_eq!(info_json["library"], "albums");
    assert_eq!(info_json["width"], 2);
    assert_eq!(info_json["height"], 2);
    assert_eq!(info_json["mimeType"], "image/jpeg");
    assert_eq!(info_json["isVideo"], false);
    assert!(info_json["size"].as_u64().is_some_and(|size| size > 0));
    assert!(
        info_json["exif"].is_null(),
        "a synthetic JPEG carries no camera metadata"
    );

    let move_media = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/media-library/move",
        serde_json::json!({
            "from": "albums/shot.jpg",
            "to": "albums/empty/shot.jpg"
        }),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(move_media.status(), StatusCode::NO_CONTENT);

    let nonempty_delete = request(
        &app,
        Method::DELETE,
        "/api/v1/admin/media-library/entries?kind=folder&target=albums%2Fempty",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(nonempty_delete.status(), StatusCode::CONFLICT);

    let upload_hash = hex::encode(Sha256::digest(&jpeg_bytes));
    let upload_builder = Request::builder()
        .method(Method::POST)
        .uri("/api/v1/admin/media-library/upload?path=albums")
        .header("X-Expected-Size", jpeg_bytes.len().to_string())
        .header("X-Expected-SHA256", &upload_hash)
        .header("X-File-Name", "upload.jpg")
        .header("X-Mime-Type", "image/jpeg")
        .header(header::AUTHORIZATION, format!("Bearer {admin_token}"))
        .header("x-csrf-token", &csrf_token)
        .header("Idempotency-Key", Uuid::new_v4().to_string());
    let mut upload_request = upload_builder
        .body(Body::from(jpeg_bytes.clone()))
        .expect("upload request");
    upload_request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    let upload = app.clone().oneshot(upload_request).await.expect("upload");
    assert_eq!(upload.status(), StatusCode::OK);

    let delete_media = request(
        &app,
        Method::DELETE,
        &format!("/api/v1/admin/media-library/entries?kind=media&target={media_id}"),
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(delete_media.status(), StatusCode::NO_CONTENT);

    let empty_delete = request(
        &app,
        Method::DELETE,
        "/api/v1/admin/media-library/entries?kind=folder&target=albums%2Fempty",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(empty_delete.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn modern_upload_preserves_original_time_provenance() {
    let data = tempdir().unwrap();
    let media = tempdir().unwrap();
    let state = initialize(data.path(), media.path()).await.unwrap();
    let token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .unwrap();
    let db = state.db.clone();
    let app = build_router(state);
    let (_, _, device) = establish_device(&app, token.trim()).await;
    let bytes = photo_bytes(b"time-provenance-contract");
    let mut req = Request::builder()
        .method(Method::POST)
        .uri("/api/v1/media/upload")
        .header(header::AUTHORIZATION, format!("Bearer {device}"))
        .header("X-Expected-Size", bytes.len())
        .header("X-Expected-SHA256", hex::encode(Sha256::digest(&bytes)))
        .header("X-File-Name", "IMG_20250101_000000.jpg")
        .header("X-Original-Name", "plain.jpg")
        .header("X-Youyou-Client-Version", "0.1.3")
        .header("X-Time-Version", "1")
        .header("X-Sort-Source", "modified")
        .header("X-Sort-At", "1609459200000")
        .body(Body::from(bytes.to_vec()))
        .unwrap();
    req.extensions_mut()
        .insert(ConnectInfo(SocketAddr::from(([127, 0, 0, 1], 0))));
    let response = app.clone().oneshot(req).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let json: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    let id = json["mediaId"].as_str().unwrap();
    let detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{id}"),
        None,
        Some(&device),
        None,
    )
    .await;
    let detail: serde_json::Value =
        serde_json::from_slice(&to_bytes(detail.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(detail["sortAt"], 1609459200000i64);
    assert_eq!(detail["sortSource"], "modified");
    assert_eq!(detail["originalName"], "plain.jpg");
    assert!(detail["takenAt"].is_null());
    let payload = sqlx::query_scalar::<_, String>(
        "SELECT payload FROM change_log WHERE entity_id=?1 ORDER BY revision DESC LIMIT 1",
    )
    .bind(id)
    .fetch_one(&db)
    .await
    .unwrap();
    let event: serde_json::Value = serde_json::from_str(&payload).unwrap();
    assert_eq!(event["sortAt"], detail["sortAt"]);
    assert_eq!(event["sortSource"], detail["sortSource"]);
    assert_eq!(event["originalName"], detail["originalName"]);
}

// ---------------------------------------------------------------------------
// 删除原件与两侧回收能力（需求 UoN5J--JHK_R）
// ---------------------------------------------------------------------------

/// 触发一次全量扫描并返回该设备可见的第一条媒体（含 `id` 与 `version`）。
async fn scan_library_and_first_media(
    app: &axum::Router,
    admin_token: &str,
    csrf_token: &str,
    device_token: &str,
) -> serde_json::Value {
    let scan = request(
        app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(admin_token),
        Some(csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    wait_admin_job(app, admin_token, &job_id).await;
    first_media(app, device_token).await
}

async fn first_media(app: &axum::Router, device_token: &str) -> serde_json::Value {
    let page = request(
        app,
        Method::GET,
        "/api/v1/media?limit=10",
        None,
        Some(device_token),
        None,
    )
    .await;
    assert_eq!(page.status(), StatusCode::OK);
    let body = to_bytes(page.into_body(), usize::MAX)
        .await
        .expect("media body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("media json");
    json["items"][0].clone()
}

async fn device_delete(
    app: &axum::Router,
    device_token: &str,
    media_id: &str,
    operation_id: &str,
    expected_version: Option<i64>,
) -> serde_json::Value {
    let response = json_request(
        app,
        Method::DELETE,
        &format!("/api/v1/media/{media_id}"),
        serde_json::json!({"operationId": operation_id, "expectedVersion": expected_version}),
        Some(device_token),
        None,
    )
    .await;
    assert_eq!(
        response.status(),
        StatusCode::OK,
        "设备侧删除应返回 200 与结构化结果"
    );
    let body = to_bytes(response.into_body(), usize::MAX)
        .await
        .expect("delete body");
    serde_json::from_slice(&body).expect("delete json")
}

fn write_jpeg(path: &std::path::Path, color: [u8; 3]) {
    let mut buffer = std::io::Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(2, 2, image::Rgb(color)))
        .write_to(&mut buffer, image::ImageFormat::Jpeg)
        .expect("encode jpeg");
    std::fs::write(path, buffer.into_inner()).expect("write jpeg");
}

#[tokio::test]
async fn device_delete_moves_original_into_trash_and_blocks_rescan() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    let original = library.join("photo.jpg");
    write_jpeg(&original, [10, 20, 30]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let db = state.db.clone();
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");
    assert!(original.exists());

    let outcome = device_delete(&app, &device_token, &media_id, "op-device-1", Some(version)).await;
    assert_eq!(outcome["state"], "succeeded");
    assert_eq!(outcome["mediaId"].as_str(), Some(media_id.as_str()));
    assert!(
        outcome["trashed"]
            .as_array()
            .expect("trashed")
            .iter()
            .any(|value| value.as_str() == Some("library/photo.jpg")),
        "反馈应列出被回收的原件路径"
    );

    // 原件离开扫描树，进入回收站。
    assert!(!original.exists(), "原件应已移出媒体库");
    let trash_path: String = sqlx::query_scalar(
        "SELECT trash_path FROM trash_entries WHERE media_id = ?1 AND state = 'active'",
    )
    .bind(&media_id)
    .fetch_one(&db)
    .await
    .expect("trash row");
    assert!(
        data.path().join("trash").join(&trash_path).exists(),
        "回收站应存在原件"
    );

    // 索引不可见，且变更流发出 media/delete。
    let detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(detail.status(), StatusCode::NOT_FOUND);
    let delete_events: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM change_log WHERE entity = 'media' AND entity_id = ?1 AND operation = 'delete'",
    )
    .bind(&media_id)
    .fetch_one(&db)
    .await
    .expect("delete events");
    assert_eq!(delete_events, 1);

    // 重扫不得复活：原件已不在扫描树，也不再产生同 id 的媒体。
    let rescan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(rescan.status(), StatusCode::ACCEPTED);
    let body = to_bytes(rescan.into_body(), usize::MAX)
        .await
        .expect("rescan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&body).expect("rescan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    wait_admin_job(&app, &admin_token, &job_id).await;
    let page = request(
        &app,
        Method::GET,
        "/api/v1/media?limit=10",
        None,
        Some(&device_token),
        None,
    )
    .await;
    let body = to_bytes(page.into_body(), usize::MAX)
        .await
        .expect("page body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("page json");
    assert!(
        json["items"]
            .as_array()
            .expect("items")
            .iter()
            .all(|item| item["id"].as_str() != Some(media_id.as_str())),
        "重扫后媒体不得复活"
    );
}

#[tokio::test]
async fn device_delete_is_idempotent_and_new_operation_is_not_found() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    write_jpeg(&library.join("photo.jpg"), [1, 2, 3]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let db = state.db.clone();
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");

    let first = device_delete(&app, &device_token, &media_id, "op-same", Some(version)).await;
    assert_eq!(first["state"], "succeeded");
    // 相同操作 ID 重放：返回原结果，不重复移文件、不产生第二条回收记录。
    let second = device_delete(&app, &device_token, &media_id, "op-same", Some(version)).await;
    assert_eq!(second["state"], "succeeded");
    let entries: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM trash_entries WHERE media_id = ?1")
        .bind(&media_id)
        .fetch_one(&db)
        .await
        .expect("entries");
    assert_eq!(entries, 1, "同一操作 ID 不得重复产生回收条目");

    // 新操作 ID 针对已删除媒体：按「不存在」返回，不泄露也不重复删除。
    let third = device_delete(&app, &device_token, &media_id, "op-new", None).await;
    assert_eq!(third["state"], "not_found");
}

#[tokio::test]
async fn device_delete_rejects_stale_version_then_accepts_fresh_one() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    let original = library.join("photo.jpg");
    write_jpeg(&original, [4, 5, 6]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");

    let stale = device_delete(
        &app,
        &device_token,
        &media_id,
        "op-stale",
        Some(version + 99),
    )
    .await;
    assert_eq!(stale["state"], "conflict");
    assert_eq!(stale["currentVersion"].as_i64(), Some(version));
    assert!(original.exists(), "版本冲突不得删除原件");

    let fresh = device_delete(&app, &device_token, &media_id, "op-fresh", Some(version)).await;
    assert_eq!(fresh["state"], "succeeded");
    assert!(!original.exists());
}

#[tokio::test]
async fn device_delete_is_isolated_between_users() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library_a = media.path().join("liba");
    let library_b = media.path().join("libb");
    tokio::fs::create_dir_all(&library_a).await.expect("liba");
    tokio::fs::create_dir_all(&library_b).await.expect("libb");
    let file_b = library_b.join("b.jpg");
    write_jpeg(&file_b, [7, 8, 9]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token) = establish_admin(&app, setup_token.trim()).await;
    let user_a = admin_create_user(&app, &admin_token, &csrf_token, "owner-a", "liba").await;
    let token_a = pair_user_device(&app, &admin_token, &csrf_token, user_a, "device-a").await;
    let user_b = admin_create_user(&app, &admin_token, &csrf_token, "owner-b", "libb").await;
    let token_b = pair_user_device(&app, &admin_token, &csrf_token, user_b, "device-b").await;

    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    wait_admin_job(&app, &admin_token, &job_id).await;

    let item_b = first_media(&app, &token_b).await;
    let media_b = item_b["id"].as_str().expect("media b").to_owned();

    // A 的设备试图删除 B 的媒体：按不存在处理，B 的原件与索引不受影响。
    let denied = device_delete(&app, &token_a, &media_b, "op-cross", None).await;
    assert_eq!(denied["state"], "not_found");
    assert!(file_b.exists(), "跨用户删除不得触碰他人原件");
    let still_there = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_b}"),
        None,
        Some(&token_b),
        None,
    )
    .await;
    assert_eq!(still_there.status(), StatusCode::OK);
}

#[tokio::test]
async fn admin_trash_restore_preserves_media_id_and_reindexes() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    let original = library.join("photo.jpg");
    write_jpeg(&original, [11, 22, 33]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let db = state.db.clone();
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");
    device_delete(&app, &device_token, &media_id, "op-trash", Some(version)).await;

    let list = request(
        &app,
        Method::GET,
        "/api/v1/admin/trash",
        None,
        Some(&admin_token),
        None,
    )
    .await;
    assert_eq!(list.status(), StatusCode::OK);
    let body = to_bytes(list.into_body(), usize::MAX)
        .await
        .expect("trash list body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("trash list json");
    assert_eq!(json["capability"]["enabled"], true);
    assert_eq!(json["capability"]["retentionDays"], 30);
    assert_eq!(json["entries"].as_array().expect("entries").len(), 1);

    let restore = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/trash/restore",
        serde_json::json!({"mediaIds": [media_id]}),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(restore.status(), StatusCode::OK);
    let body = to_bytes(restore.into_body(), usize::MAX)
        .await
        .expect("restore body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("restore json");
    assert!(
        json["failures"].as_array().expect("failures").is_empty(),
        "unexpected failures: {json}"
    );
    assert!(
        json["restored"]
            .as_array()
            .expect("restored")
            .iter()
            .any(|value| value.as_str() == Some(media_id.as_str()))
    );
    assert!(original.exists(), "恢复后原件回到原路径");

    let detail = request(
        &app,
        Method::GET,
        &format!("/api/v1/media/{media_id}"),
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(detail.status(), StatusCode::OK, "恢复保留原 media id");
    let upserts: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM change_log WHERE entity = 'media' AND entity_id = ?1 AND operation = 'upsert'",
    )
    .bind(&media_id)
    .fetch_one(&db)
    .await
    .expect("upsert events");
    assert!(upserts >= 1, "恢复应发出 upsert 变更");

    // 重复恢复：条目已恢复，明确失败而非静默。
    let again = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/trash/restore",
        serde_json::json!({"mediaIds": [media_id]}),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    let body = to_bytes(again.into_body(), usize::MAX)
        .await
        .expect("again body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("again json");
    assert_eq!(json["failures"].as_array().expect("failures").len(), 1);
}

#[tokio::test]
async fn admin_restore_renames_when_original_path_occupied() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    let original = library.join("photo.jpg");
    write_jpeg(&original, [40, 50, 60]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");
    device_delete(&app, &device_token, &media_id, "op-rename", Some(version)).await;

    // 原路径被不同内容占位。
    write_jpeg(&original, [70, 80, 90]);
    let rescan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(rescan.status(), StatusCode::ACCEPTED);
    let body = to_bytes(rescan.into_body(), usize::MAX)
        .await
        .expect("rescan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&body).expect("rescan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();
    wait_admin_job(&app, &admin_token, &job_id).await;
    let occupant = std::fs::read(&original).expect("occupant bytes");

    let restore = json_request(
        &app,
        Method::POST,
        "/api/v1/admin/trash/restore",
        serde_json::json!({"mediaIds": [media_id]}),
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    let body = to_bytes(restore.into_body(), usize::MAX)
        .await
        .expect("restore body");
    let json: serde_json::Value = serde_json::from_slice(&body).expect("restore json");
    assert!(
        json["failures"].as_array().expect("failures").is_empty(),
        "unexpected failures: {json}"
    );
    assert!(
        !json["renamed"].as_array().expect("renamed").is_empty(),
        "路径被占用时应自动生成唯一名"
    );
    // 占位文件不被覆盖。
    assert_eq!(std::fs::read(&original).expect("occupant after"), occupant);
    let renamed_path = json["renamed"][0].as_str().expect("renamed path");
    assert!(
        media.path().join(renamed_path).exists(),
        "恢复文件应落在唯一名路径"
    );
}

#[tokio::test]
async fn admin_purge_and_expiry_cleanup_remove_trashed_files() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    let library = media.path().join("library");
    tokio::fs::create_dir_all(&library).await.expect("library");
    write_jpeg(&library.join("photo.jpg"), [90, 91, 92]);

    let state = initialize(data.path(), media.path()).await.expect("state");
    let db = state.db.clone();
    let runtime = state.trash.clone();
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state);
    let (admin_token, csrf_token, device_token) =
        establish_user_device(&app, setup_token.trim(), "library").await;

    let item = scan_library_and_first_media(&app, &admin_token, &csrf_token, &device_token).await;
    let media_id = item["id"].as_str().expect("media id").to_owned();
    let version = item["version"].as_i64().expect("version");
    device_delete(&app, &device_token, &media_id, "op-purge", Some(version)).await;

    // 到期清理：把过期时间拨到过去，清理任务应物理删除。
    sqlx::query("UPDATE trash_entries SET expires_at = 0 WHERE media_id = ?1")
        .bind(&media_id)
        .execute(&db)
        .await
        .expect("expire");
    let purged = youyou_server::trash::run_expiry_cleanup(&db, &runtime)
        .await
        .expect("cleanup");
    assert_eq!(purged, 1);
    let remaining: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM trash_entries WHERE media_id = ?1 AND state = 'active'",
    )
    .bind(&media_id)
    .fetch_one(&db)
    .await
    .expect("remaining");
    assert_eq!(remaining, 0);
    let trashed_files: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM trash_entries WHERE media_id = ?1 AND state = 'purged'",
    )
    .bind(&media_id)
    .fetch_one(&db)
    .await
    .expect("purged rows");
    assert_eq!(trashed_files, 1);

    // 操作凭据不随清理删除，迟到查询仍可读。
    let op = request(
        &app,
        Method::GET,
        "/api/v1/media/operations/op-purge",
        None,
        Some(&device_token),
        None,
    )
    .await;
    assert_eq!(op.status(), StatusCode::OK);
}

#[tokio::test]
async fn trash_prepare_disables_directory_inside_storage_tree() {
    let storage = tempdir().expect("storage");
    let server_dir = storage.path().join("server");
    tokio::fs::create_dir_all(&server_dir)
        .await
        .expect("server dir");
    let config = youyou_server::trash::prepare(&server_dir, storage.path()).await;
    assert!(!config.enabled, "回收站位于扫描树内必须禁用删除能力");
    assert!(config.blocked_reason.is_some());
}

#[tokio::test]
async fn trash_prepare_enables_sibling_directory() {
    let root = tempdir().expect("root");
    let storage = root.path().join("media");
    let server_dir = root.path().join("data");
    tokio::fs::create_dir_all(&storage).await.expect("storage");
    tokio::fs::create_dir_all(&server_dir)
        .await
        .expect("server");
    let config = youyou_server::trash::prepare(&server_dir, &storage).await;
    assert!(config.enabled, "同文件系统且扫描树之外应启用删除能力");
    assert!(config.blocked_reason.is_none());
}

/// 轮询到终态为止（比 wait_admin_job 更宽容：自动恢复要等 worker 的下一轮唤醒）。
async fn wait_job_settled(
    app: &axum::Router,
    admin_token: &str,
    job_id: &str,
) -> serde_json::Value {
    for _ in 0..300 {
        let response = request(
            app,
            Method::GET,
            &format!("/api/v1/admin/jobs/{job_id}"),
            None,
            Some(admin_token),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body = to_bytes(response.into_body(), usize::MAX)
            .await
            .expect("job body");
        let job_json: serde_json::Value = serde_json::from_slice(&body).expect("job json");
        if matches!(
            job_json["status"].as_str(),
            Some("succeeded" | "failed" | "cancelled")
        ) {
            return job_json;
        }
        sleep(Duration::from_millis(20)).await;
    }
    panic!("job {job_id} never reached a terminal state");
}

/// `started_at` 的存在意义是把「排队等待」和「实际执行」分开：
/// 终态记录的耗时 = finished_at - started_at，不含排队时间。
#[tokio::test]
async fn job_records_execution_start_and_resets_it_only_on_manual_retry() {
    let data = tempdir().expect("data directory");
    let media = tempdir().expect("media directory");
    tokio::fs::create_dir_all(media.path().join("album"))
        .await
        .expect("album directory");
    tokio::fs::write(media.path().join("album/a.jpg"), photo_bytes(b"a"))
        .await
        .expect("photo");

    let state = initialize(data.path(), media.path()).await.expect("state");
    let setup_token = tokio::fs::read_to_string(&state.setup_token_path)
        .await
        .expect("setup token");
    let app = build_router(state.clone());
    let (admin_token, csrf_token) = establish_admin(&app, setup_token.trim()).await;

    let scan = request(
        &app,
        Method::POST,
        "/api/v1/admin/jobs/scan",
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(scan.status(), StatusCode::ACCEPTED);
    let scan_body = to_bytes(scan.into_body(), usize::MAX)
        .await
        .expect("scan body");
    let job_id = serde_json::from_slice::<serde_json::Value>(&scan_body).expect("scan json")["id"]
        .as_str()
        .expect("job id")
        .to_owned();

    let settled = wait_job_settled(&app, &admin_token, &job_id).await;
    assert_eq!(settled["status"], "succeeded");
    let created_at = settled["createdAt"].as_i64().expect("createdAt");
    let started_at = settled["startedAt"].as_i64().expect("startedAt");
    let finished_at = settled["finishedAt"].as_i64().expect("finishedAt");
    assert!(
        created_at <= started_at,
        "执行起点不能早于入队时间：{created_at} > {started_at}"
    );
    assert!(
        started_at <= finished_at,
        "执行起点不能晚于结束时间：{started_at} > {finished_at}"
    );

    // 租约过期后 worker 会再次接管同一个任务，此时不能改写执行起点，
    // 否则「已运行」会在恢复后倒退，「耗时」也只剩最后一轮。
    sqlx::query(
        "UPDATE jobs SET status = 'interrupted', run_after = 0, finished_at = NULL, \
         lease_owner = NULL, lease_until = NULL WHERE id = ?1",
    )
    .bind(&job_id)
    .execute(&state.db)
    .await
    .expect("mark interrupted");
    let resumed = wait_job_settled(&app, &admin_token, &job_id).await;
    assert_eq!(resumed["status"], "succeeded");
    assert_eq!(
        resumed["startedAt"].as_i64(),
        Some(started_at),
        "自动恢复不应改写执行起点"
    );

    // 停在 interrupted，人工重试应开启新的执行周期。
    sqlx::query(
        "UPDATE jobs SET status = 'interrupted', run_after = ?1, finished_at = NULL, \
         lease_owner = NULL, lease_until = NULL WHERE id = ?2",
    )
    .bind(i64::MAX / 2)
    .bind(&job_id)
    .execute(&state.db)
    .await
    .expect("park interrupted");

    let retry = request(
        &app,
        Method::POST,
        &format!("/api/v1/jobs/{job_id}/retry"),
        None,
        Some(&admin_token),
        Some(&csrf_token),
    )
    .await;
    assert_eq!(retry.status(), StatusCode::OK);
    let retry_body = to_bytes(retry.into_body(), usize::MAX)
        .await
        .expect("retry body");
    let retry_json: serde_json::Value = serde_json::from_slice(&retry_body).expect("retry json");
    assert_eq!(retry_json["status"], "queued");
    assert!(
        retry_json["startedAt"].is_null(),
        "人工重试应清空执行起点，重新计时"
    );
}
