use sqlx::SqlitePool;

use crate::auth::Principal;

pub const SUCCESS: &str = "success";
pub const FAILURE: &str = "failure";

pub fn actor(principal: &Principal) -> &'static str {
    match principal {
        Principal::Admin => "admin",
        Principal::Device(_) => "device",
    }
}

pub async fn record(
    pool: &SqlitePool,
    actor: &str,
    action: &str,
    target: &str,
    result: &str,
) -> Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO audit_log (actor, action, target, result, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
    )
    .bind(actor)
    .bind(action)
    .bind(target)
    .bind(result)
    .bind(crate::db::now_millis())
    .execute(pool)
    .await?;
    Ok(())
}
