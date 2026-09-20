//! 备份恢复的版本闸：向前兼容 —— 新版可以恢复旧备份，更新的备份、低于兼容下限的
//! 备份、以及版本号不可解析的备份都被拒绝。

use std::path::{Path, PathBuf};

use tempfile::tempdir;
use youyou_server::backup;

async fn backup_claiming_version(data_dir: &Path, version: &str) -> PathBuf {
    let backup_dir = backup::create(data_dir, &data_dir.join("backups"))
        .await
        .expect("create backup");
    let manifest_path = backup_dir.join("manifest.json");
    let mut manifest: serde_json::Value = serde_json::from_slice(
        &tokio::fs::read(&manifest_path)
            .await
            .expect("read manifest"),
    )
    .expect("parse manifest");
    manifest["serverVersion"] = serde_json::Value::String(version.to_owned());
    tokio::fs::write(
        &manifest_path,
        serde_json::to_vec_pretty(&manifest).expect("serialize manifest"),
    )
    .await
    .expect("write manifest");
    backup_dir
}

#[tokio::test]
async fn restore_allows_older_backups_and_rejects_newer_or_unsupported_ones() {
    let temp = tempdir().expect("create temp dir");
    let data_dir = temp.path().join("data");
    tokio::fs::create_dir_all(&data_dir)
        .await
        .expect("create data dir");

    let older = backup_claiming_version(&data_dir, "0.1.0").await;
    backup::restore(&data_dir, &older)
        .await
        .expect("an older backup restores");

    let newer = backup_claiming_version(&data_dir, "99.0.0").await;
    let error = backup::restore(&data_dir, &newer)
        .await
        .expect_err("a newer backup is rejected");
    assert!(
        error.to_string().contains("newer than the current binary"),
        "{error}"
    );

    let ancient = backup_claiming_version(&data_dir, "0.0.9").await;
    let error = backup::restore(&data_dir, &ancient)
        .await
        .expect_err("a backup below the compatibility floor is rejected");
    assert!(error.to_string().contains("oldest restorable"), "{error}");

    let unlabelled = backup_claiming_version(&data_dir, "unknown").await;
    let error = backup::restore(&data_dir, &unlabelled)
        .await
        .expect_err("an unparsable version is rejected");
    assert!(
        error.to_string().contains("unusable server version"),
        "{error}"
    );
}
