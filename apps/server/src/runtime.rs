use std::{
    io,
    path::{Path, PathBuf},
    process,
};

use anyhow::Context;
use tokio::{
    fs::{self, OpenOptions},
    io::AsyncWriteExt,
};

const LOCK_FILE: &str = "server.lock";

pub struct ServerLock {
    path: PathBuf,
}

impl ServerLock {
    pub async fn acquire(data_dir: &Path) -> anyhow::Result<Self> {
        fs::create_dir_all(data_dir)
            .await
            .with_context(|| format!("create data directory {}", data_dir.display()))?;
        let path = data_dir.join(LOCK_FILE);

        for _ in 0..2 {
            match OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&path)
                .await
            {
                Ok(mut file) => {
                    file.write_all(process::id().to_string().as_bytes())
                        .await
                        .context("write server lock")?;
                    file.write_all(b"\n").await.context("finish server lock")?;
                    return Ok(Self { path });
                }
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
                    let owner = fs::read_to_string(&path).await.unwrap_or_default();
                    if owner
                        .trim()
                        .parse::<u32>()
                        .ok()
                        .is_some_and(process_is_running)
                    {
                        anyhow::bail!(
                            "another youyou-server process is using {}",
                            data_dir.display()
                        );
                    }
                    let _ = fs::remove_file(&path).await;
                }
                Err(error) => {
                    return Err(error)
                        .with_context(|| format!("create server lock {}", path.display()));
                }
            }
        }

        anyhow::bail!("could not acquire server lock {}", path.display());
    }
}

impl Drop for ServerLock {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

#[cfg(unix)]
fn process_is_running(pid: u32) -> bool {
    let Ok(pid) = libc::pid_t::try_from(pid) else {
        return false;
    };
    // kill(pid, 0) probes process existence without sending a signal.
    let result = unsafe { libc::kill(pid, 0) };
    result == 0 || io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(not(unix))]
fn process_is_running(_pid: u32) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn prevents_two_local_processes_from_mutating_the_same_data_dir() {
        let data_dir = tempdir().expect("data directory");
        let first = ServerLock::acquire(data_dir.path())
            .await
            .expect("first lock");
        let second = ServerLock::acquire(data_dir.path()).await;
        assert!(second.is_err());

        drop(first);
        let _third = ServerLock::acquire(data_dir.path())
            .await
            .expect("lock after release");
    }
}
