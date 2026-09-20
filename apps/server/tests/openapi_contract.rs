//! Compile-time OpenAPI contract verification.
//!
//! Generates the OpenAPI document from utoipa annotations and runs the
//! Python sanity checker against it. This catches accidental removal of
//! endpoints or response schemas at test time, without requiring a running
//! server.

use std::process::Command;

use youyou_server::api::openapi_document;

#[test]
fn openapi_contract_passes_sanity_check() {
    let document = openapi_document();
    assert_eq!(document.info.version, env!("CARGO_PKG_VERSION"));
    let json = serde_json::to_string_pretty(&document).expect("serialize openapi");

    let mut temp_path = std::env::temp_dir();
    temp_path.push(format!("youyou-openapi-{}.json", std::process::id()));
    std::fs::write(&temp_path, &json).expect("write openapi json");

    let script =
        std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../scripts/check_openapi.py");

    let output = Command::new("python3")
        .arg(&script)
        .arg(&temp_path)
        .output()
        .expect("run check_openapi.py");

    let _ = std::fs::remove_file(&temp_path);

    if !output.status.success() {
        panic!(
            "check_openapi.py failed:\nstdout: {}\nstderr: {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
