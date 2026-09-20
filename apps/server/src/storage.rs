use std::{
    io,
    path::{Component, Path, PathBuf},
    pin::Pin,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use async_stream::try_stream;
use async_trait::async_trait;
use bytes::Bytes;
use futures_core::Stream;
use futures_util::StreamExt;
use serde::Serialize;
use sha2::{Digest, Sha256};
use thiserror::Error;
use tokio::{
    fs::{self, File, OpenOptions},
    io::{AsyncRead, AsyncReadExt, AsyncSeekExt, SeekFrom},
    sync::RwLock,
};
use uuid::Uuid;

#[derive(Debug, Error)]
pub enum StorageError {
    #[error("invalid storage path: {0}")]
    InvalidPath(String),

    #[error("path resolves outside the configured storage root")]
    OutsideRoot,

    #[error("file not found: {0}")]
    NotFound(String),

    #[error("path is not a directory: {0}")]
    NotDirectory(String),

    #[error("configured storage directory is read-only")]
    ReadOnly,

    #[error("directory is not empty: {0}")]
    NotEmpty(String),

    #[error(transparent)]
    Io(#[from] io::Error),
}

#[derive(Debug, Clone, Serialize)]
pub struct StorageCapabilities {
    pub read_only_scan: bool,
    pub read_write_atomic: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct StorageHealth {
    pub root_path: String,
    pub read_only: bool,
    pub writable: bool,
    pub free_bytes: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct StorageEntry {
    pub name: String,
    pub path: String,
    pub is_directory: bool,
    pub size: Option<u64>,
    pub modified_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct StorageStat {
    pub path: String,
    pub is_directory: bool,
    pub size: u64,
    pub modified_at: Option<i64>,
}

pub type StorageListStream = Pin<Box<dyn Stream<Item = Result<StorageEntry, StorageError>> + Send>>;
pub type StorageByteStream = Pin<Box<dyn Stream<Item = Result<Bytes, StorageError>> + Send>>;

#[async_trait]
pub trait StorageDriver: Send + Sync {
    fn capabilities(&self) -> StorageCapabilities;

    async fn health_check(&self) -> Result<StorageHealth, StorageError>;

    async fn list(&self, path: &str) -> Result<StorageListStream, StorageError>;

    async fn stat(&self, path: &str) -> Result<StorageStat, StorageError>;

    async fn read_stream(
        &self,
        path: &str,
        range: Option<(u64, Option<u64>)>,
    ) -> Result<(StorageStat, StorageByteStream), StorageError>;

    async fn write_atomic(
        &self,
        path: &str,
        reader: Box<dyn AsyncRead + Send + Unpin>,
    ) -> Result<u64, StorageError>;

    async fn mkdir(&self, path: &str) -> Result<(), StorageError>;

    async fn move_path(&self, path: &str, target: &str) -> Result<(), StorageError>;

    async fn delete(&self, path: &str) -> Result<(), StorageError>;

    /// Remove an empty directory only. Returns an error if the path is missing,
    /// is not a directory, or still contains entries.
    async fn delete_empty_dir(&self, path: &str) -> Result<(), StorageError>;
}

#[derive(Debug, Clone)]
pub struct LocalFilesystemStorageDriver {
    root: PathBuf,
}

impl LocalFilesystemStorageDriver {
    pub fn new(root: impl AsRef<Path>) -> Result<Self, StorageError> {
        let root = std::fs::canonicalize(root.as_ref())?;
        let metadata = std::fs::metadata(&root)?;
        if !metadata.is_dir() {
            return Err(StorageError::NotDirectory(root.display().to_string()));
        }
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn normalize_relative(relative: &str) -> Result<String, StorageError> {
        if relative.is_empty() || relative == "." {
            return Ok(String::new());
        }
        if relative.contains('\0') {
            return Err(StorageError::InvalidPath("path contains NUL".to_owned()));
        }

        let mut components = Vec::new();
        for component in Path::new(relative).components() {
            match component {
                Component::Normal(value) => {
                    let value = value.to_str().ok_or_else(|| {
                        StorageError::InvalidPath("path is not valid UTF-8".to_owned())
                    })?;
                    if !value.is_empty() {
                        components.push(value.to_owned());
                    }
                }
                Component::CurDir => {}
                Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                    return Err(StorageError::InvalidPath(
                        "absolute paths and parent traversal are forbidden".to_owned(),
                    ));
                }
            }
        }
        if components.is_empty() {
            return Ok(String::new());
        }
        Ok(components.join("/"))
    }

    async fn existing_path(&self, relative: &str) -> Result<(String, PathBuf), StorageError> {
        let normalized = Self::normalize_relative(relative)?;
        let candidate = self.root.join(&normalized);
        let canonical = match fs::canonicalize(&candidate).await {
            Ok(path) => path,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                return Err(StorageError::NotFound(normalized));
            }
            Err(error) => return Err(StorageError::Io(error)),
        };
        if !canonical.starts_with(&self.root) {
            return Err(StorageError::OutsideRoot);
        }
        Ok((normalized, canonical))
    }

    async fn writable_parent(
        &self,
        relative: &str,
    ) -> Result<(String, PathBuf, PathBuf), StorageError> {
        let normalized = Self::normalize_relative(relative)?;
        if normalized.is_empty() {
            return Err(StorageError::InvalidPath(
                "a file or directory path is required".to_owned(),
            ));
        }
        let target = self.root.join(&normalized);
        let parent = target
            .parent()
            .ok_or_else(|| StorageError::InvalidPath("path has no parent".to_owned()))?;
        fs::create_dir_all(parent).await?;
        let canonical_parent = fs::canonicalize(parent).await?;
        if !canonical_parent.starts_with(&self.root) {
            return Err(StorageError::OutsideRoot);
        }
        if fs::symlink_metadata(&target).await.is_ok() {
            let canonical_target = fs::canonicalize(&target).await?;
            if !canonical_target.starts_with(&self.root) {
                return Err(StorageError::OutsideRoot);
            }
        }
        let parent_metadata = fs::metadata(&canonical_parent).await?;
        if parent_metadata.permissions().readonly() {
            return Err(StorageError::ReadOnly);
        }
        Ok((normalized, target, canonical_parent))
    }

    pub async fn hash_sha256(&self, path: &str) -> Result<(String, u64), StorageError> {
        let (_, path) = self.existing_path(path).await?;
        let metadata = fs::metadata(&path).await?;
        if !metadata.is_file() {
            return Err(StorageError::InvalidPath(
                "only files can be hashed".to_owned(),
            ));
        }

        let mut file = File::open(path).await?;
        let mut hasher = Sha256::new();
        let mut buffer = vec![0_u8; 1024 * 1024];
        let mut size = 0_u64;
        loop {
            let read = file.read(&mut buffer).await?;
            if read == 0 {
                break;
            }
            hasher.update(&buffer[..read]);
            size += read as u64;
        }
        Ok((hex::encode(hasher.finalize()), size))
    }

    pub async fn read_all(
        &self,
        path: &str,
        range: Option<(u64, Option<u64>)>,
    ) -> Result<Vec<u8>, StorageError> {
        let (_, mut stream) = self.read_stream(path, range).await?;
        let mut output = Vec::new();
        while let Some(chunk) = stream.next().await {
            output.extend_from_slice(&chunk?);
        }
        Ok(output)
    }

    pub async fn cleanup_orphan_temporary_files(&self) -> Result<(), StorageError> {
        const TEMPORARY_FILE_MAX_AGE: Duration = Duration::from_secs(60 * 60);
        let mut directories = vec![self.root.clone()];
        while let Some(directory) = directories.pop() {
            let mut entries = fs::read_dir(&directory).await?;
            while let Some(entry) = entries.next_entry().await? {
                let path = entry.path();
                let link_metadata = fs::symlink_metadata(&path).await?;
                if link_metadata.file_type().is_symlink() {
                    continue;
                }
                if link_metadata.is_dir() {
                    directories.push(path);
                    continue;
                }
                let Some(name) = path.file_name().and_then(|value| value.to_str()) else {
                    continue;
                };
                if !is_orphan_temporary_name(name) {
                    continue;
                }
                let is_old = link_metadata
                    .modified()
                    .ok()
                    .and_then(|modified| SystemTime::now().duration_since(modified).ok())
                    .is_some_and(|age| age >= TEMPORARY_FILE_MAX_AGE);
                if is_old {
                    fs::remove_file(path).await?;
                }
            }
        }
        Ok(())
    }
}

fn is_orphan_temporary_name(name: &str) -> bool {
    name.starts_with('.') && name.contains(".youyou-") && name.ends_with(".tmp")
}

#[derive(Clone)]
pub struct StorageRuntime {
    current: Arc<RwLock<Arc<LocalFilesystemStorageDriver>>>,
}

impl StorageRuntime {
    pub fn new(driver: Arc<LocalFilesystemStorageDriver>) -> Self {
        Self {
            current: Arc::new(RwLock::new(driver)),
        }
    }

    pub async fn snapshot(&self) -> Arc<LocalFilesystemStorageDriver> {
        self.current.read().await.clone()
    }

    pub async fn health_check(&self) -> Result<StorageHealth, StorageError> {
        self.snapshot().await.health_check().await
    }

    pub async fn replace_root(
        &self,
        root: impl AsRef<Path>,
    ) -> Result<(Arc<LocalFilesystemStorageDriver>, StorageHealth), StorageError> {
        let driver = Arc::new(LocalFilesystemStorageDriver::new(root)?);
        let health = driver.health_check().await?;
        let mut current = self.current.write().await;
        let previous = std::mem::replace(&mut *current, driver);
        Ok((previous, health))
    }

    pub async fn restore(&self, driver: Arc<LocalFilesystemStorageDriver>) {
        *self.current.write().await = driver;
    }
}

#[async_trait]
impl StorageDriver for StorageRuntime {
    fn capabilities(&self) -> StorageCapabilities {
        StorageCapabilities {
            read_only_scan: true,
            read_write_atomic: true,
        }
    }

    async fn health_check(&self) -> Result<StorageHealth, StorageError> {
        StorageRuntime::health_check(self).await
    }

    async fn list(&self, path: &str) -> Result<StorageListStream, StorageError> {
        self.snapshot().await.list(path).await
    }

    async fn stat(&self, path: &str) -> Result<StorageStat, StorageError> {
        self.snapshot().await.stat(path).await
    }

    async fn read_stream(
        &self,
        path: &str,
        range: Option<(u64, Option<u64>)>,
    ) -> Result<(StorageStat, StorageByteStream), StorageError> {
        self.snapshot().await.read_stream(path, range).await
    }

    async fn write_atomic(
        &self,
        path: &str,
        reader: Box<dyn AsyncRead + Send + Unpin>,
    ) -> Result<u64, StorageError> {
        self.snapshot().await.write_atomic(path, reader).await
    }

    async fn mkdir(&self, path: &str) -> Result<(), StorageError> {
        self.snapshot().await.mkdir(path).await
    }

    async fn move_path(&self, path: &str, target: &str) -> Result<(), StorageError> {
        self.snapshot().await.move_path(path, target).await
    }

    async fn delete(&self, path: &str) -> Result<(), StorageError> {
        self.snapshot().await.delete(path).await
    }

    async fn delete_empty_dir(&self, path: &str) -> Result<(), StorageError> {
        self.snapshot().await.delete_empty_dir(path).await
    }
}

#[async_trait]
impl StorageDriver for LocalFilesystemStorageDriver {
    fn capabilities(&self) -> StorageCapabilities {
        StorageCapabilities {
            read_only_scan: true,
            read_write_atomic: true,
        }
    }

    async fn health_check(&self) -> Result<StorageHealth, StorageError> {
        let metadata = fs::metadata(&self.root).await?;
        if !metadata.is_dir() {
            return Err(StorageError::NotDirectory(self.root.display().to_string()));
        }
        let read_only = metadata.permissions().readonly();
        Ok(StorageHealth {
            root_path: self.root.display().to_string(),
            read_only,
            writable: !read_only,
            free_bytes: free_space_bytes(&self.root),
        })
    }

    async fn list(&self, path: &str) -> Result<StorageListStream, StorageError> {
        let (_, directory) = self.existing_path(path).await?;
        let metadata = fs::metadata(&directory).await?;
        if !metadata.is_dir() {
            return Err(StorageError::NotDirectory(path.to_owned()));
        }

        let root = self.root.clone();
        let mut entries = fs::read_dir(directory).await?;
        let stream = try_stream! {
            while let Some(entry) = entries.next_entry().await? {
                let entry_path = entry.path();
                // 目录枚举与逐项读取之间文件可能被删除（删除/恢复正在移动原件），
                // 消失的条目直接跳过，不能让整次列举失败。
                let link_metadata = match fs::symlink_metadata(&entry_path).await {
                    Ok(metadata) => metadata,
                    Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                    Err(error) => Err(error)?,
                };
                let canonical = match fs::canonicalize(&entry_path).await {
                    Ok(path) => path,
                    Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                    Err(error) => Err(error)?,
                };
                if !canonical.starts_with(&root) {
                    Err(StorageError::OutsideRoot)?;
                }
                // Do not descend through symlinked files or directories. A
                // link inside the root can point back to an ancestor and
                // otherwise make a recursive scan loop forever.
                if link_metadata.file_type().is_symlink() {
                    continue;
                }
                let metadata = match entry.metadata().await {
                    Ok(metadata) => metadata,
                    Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                    Err(error) => Err(error)?,
                };
                let name = entry.file_name().to_str().ok_or_else(|| {
                    StorageError::InvalidPath("file name is not valid UTF-8".to_owned())
                })?.to_owned();
                let relative = entry_path.strip_prefix(&root)
                    .map_err(|_| StorageError::OutsideRoot)?;
                let relative = relative.to_str().ok_or_else(|| {
                    StorageError::InvalidPath("storage path is not valid UTF-8".to_owned())
                })?;
                let relative = LocalFilesystemStorageDriver::normalize_relative(relative)?;
                yield StorageEntry {
                    name,
                    path: relative,
                    is_directory: metadata.is_dir(),
                    size: metadata.is_file().then_some(metadata.len()),
                    modified_at: metadata.modified().ok().and_then(epoch_millis),
                };
            }
        };
        Ok(Box::pin(stream))
    }

    async fn stat(&self, path: &str) -> Result<StorageStat, StorageError> {
        let (normalized, path) = self.existing_path(path).await?;
        let metadata = fs::metadata(&path).await?;
        Ok(StorageStat {
            path: normalized,
            is_directory: metadata.is_dir(),
            size: metadata.len(),
            modified_at: metadata.modified().ok().and_then(epoch_millis),
        })
    }

    async fn read_stream(
        &self,
        path: &str,
        range: Option<(u64, Option<u64>)>,
    ) -> Result<(StorageStat, StorageByteStream), StorageError> {
        let stat = self.stat(path).await?;
        if stat.is_directory {
            return Err(StorageError::InvalidPath(
                "directories cannot be read as files".to_owned(),
            ));
        }

        let start = range.map(|(start, _)| start).unwrap_or(0);
        let end = range
            .and_then(|(_, end)| end)
            .unwrap_or_else(|| stat.size.saturating_sub(1));
        if stat.size == 0 {
            if start != 0 {
                return Err(StorageError::InvalidPath(
                    "range start is outside the empty file".to_owned(),
                ));
            }
        } else if start >= stat.size || end < start {
            return Err(StorageError::InvalidPath(
                "requested range is outside the file".to_owned(),
            ));
        }
        let end = if stat.size == 0 {
            0
        } else {
            end.min(stat.size - 1)
        };
        let length = if stat.size == 0 { 0 } else { end - start + 1 };
        let (_, path) = self.existing_path(&stat.path).await?;
        let mut file = File::open(path).await?;
        if length > 0 {
            file.seek(SeekFrom::Start(start)).await?;
        }

        let stream = try_stream! {
            let mut remaining = length;
            let mut buffer = vec![0_u8; 64 * 1024];
            while remaining > 0 {
                let wanted = remaining.min(buffer.len() as u64) as usize;
                let read = file.read(&mut buffer[..wanted]).await?;
                if read == 0 {
                    break;
                }
                remaining -= read as u64;
                yield Bytes::copy_from_slice(&buffer[..read]);
            }
        };
        Ok((stat, Box::pin(stream)))
    }

    async fn write_atomic(
        &self,
        path: &str,
        mut reader: Box<dyn AsyncRead + Send + Unpin>,
    ) -> Result<u64, StorageError> {
        let (_, target, parent) = self.writable_parent(path).await?;
        let file_name = target
            .file_name()
            .and_then(|value| value.to_str())
            .ok_or_else(|| StorageError::InvalidPath("file name is invalid".to_owned()))?;
        let temporary = parent.join(format!(".{file_name}.youyou-{}.tmp", Uuid::new_v4()));
        let result = async {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)
                .await?;
            let size = tokio::io::copy(&mut reader, &mut file).await?;
            file.sync_all().await?;
            drop(file);

            if let Ok(metadata) = fs::symlink_metadata(&target).await {
                if metadata.file_type().is_symlink() {
                    return Err(StorageError::OutsideRoot);
                }
                return Err(StorageError::Io(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "target file already exists",
                )));
            }
            fs::rename(&temporary, &target).await?;
            Ok(size)
        }
        .await;
        if result.is_err() {
            let _ = fs::remove_file(&temporary).await;
        }
        result
    }

    async fn mkdir(&self, path: &str) -> Result<(), StorageError> {
        let normalized = Self::normalize_relative(path)?;
        if normalized.is_empty() {
            return Ok(());
        }
        let (_, target, _) = self.writable_parent(&normalized).await?;
        fs::create_dir_all(&target).await?;
        let canonical = fs::canonicalize(&target).await?;
        if !canonical.starts_with(&self.root) {
            return Err(StorageError::OutsideRoot);
        }
        Ok(())
    }

    async fn move_path(&self, path: &str, target: &str) -> Result<(), StorageError> {
        let (_, source) = self.existing_path(path).await?;
        let (_, target, parent) = self.writable_parent(target).await?;
        if fs::symlink_metadata(&target).await.is_ok() {
            return Err(StorageError::Io(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "target path already exists",
            )));
        }
        if !source.starts_with(&self.root) || !parent.starts_with(&self.root) {
            return Err(StorageError::OutsideRoot);
        }
        fs::rename(source, target).await?;
        Ok(())
    }

    async fn delete(&self, path: &str) -> Result<(), StorageError> {
        let (_, path) = self.existing_path(path).await?;
        let metadata = fs::symlink_metadata(&path).await?;
        if metadata.is_dir() {
            fs::remove_dir_all(path).await?;
        } else {
            fs::remove_file(path).await?;
        }
        Ok(())
    }

    async fn delete_empty_dir(&self, path: &str) -> Result<(), StorageError> {
        let normalized = Self::normalize_relative(path)?;
        if normalized.is_empty() {
            return Err(StorageError::InvalidPath(
                "refusing to delete the storage root".to_owned(),
            ));
        }
        let (_, directory) = self.existing_path(&normalized).await?;
        let metadata = fs::symlink_metadata(&directory).await?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(StorageError::NotDirectory(normalized));
        }
        match fs::remove_dir(&directory).await {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::DirectoryNotEmpty => {
                Err(StorageError::NotEmpty(normalized))
            }
            Err(error) => Err(StorageError::Io(error)),
        }
    }
}

fn epoch_millis(value: SystemTime) -> Option<i64> {
    value
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

#[cfg(unix)]
fn free_space_bytes(path: &Path) -> Option<u64> {
    use std::os::unix::ffi::OsStrExt;

    let path = std::ffi::CString::new(path.as_os_str().as_bytes()).ok()?;
    let mut stats = std::mem::MaybeUninit::<libc::statvfs>::uninit();
    let result = unsafe { libc::statvfs(path.as_ptr(), stats.as_mut_ptr()) };
    if result != 0 {
        return None;
    }
    let stats = unsafe { stats.assume_init() };
    u64::from(stats.f_bavail).checked_mul(stats.f_frsize)
}

#[cfg(not(unix))]
fn free_space_bytes(_path: &Path) -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use std::{
        io::Cursor,
        pin::Pin,
        task::{Context, Poll},
    };

    use tempfile::tempdir;
    use tokio::io::{AsyncRead, ReadBuf};

    use super::*;

    fn reader(data: &[u8]) -> Box<dyn AsyncRead + Send + Unpin> {
        Box::new(Cursor::new(data.to_vec()))
    }

    struct FailingReader;

    impl AsyncRead for FailingReader {
        fn poll_read(
            self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
            _buf: &mut ReadBuf<'_>,
        ) -> Poll<io::Result<()>> {
            Poll::Ready(Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "simulated interrupted write",
            )))
        }
    }

    #[test]
    fn rejects_absolute_and_parent_paths() {
        assert!(LocalFilesystemStorageDriver::normalize_relative("../photo.jpg").is_err());
        assert!(LocalFilesystemStorageDriver::normalize_relative("/photo.jpg").is_err());
        assert!(LocalFilesystemStorageDriver::normalize_relative("./albums/../photo.jpg").is_err());
    }

    #[tokio::test]
    async fn writes_atomically_and_reads_ranges() {
        let directory = tempdir().expect("temp directory");
        let driver = LocalFilesystemStorageDriver::new(directory.path()).expect("driver");

        let written = driver
            .write_atomic("nested/photo.txt", reader(b"abcdef"))
            .await
            .expect("atomic write");
        assert_eq!(written, 6);
        assert_eq!(
            driver
                .read_all("nested/photo.txt", Some((1, Some(3))))
                .await
                .expect("range"),
            b"bcd"
        );
        assert!(!directory.path().join("nested/.photo.txt.youyou-").exists());
    }

    #[tokio::test]
    async fn list_skips_entries_removed_while_iterating() {
        use futures_util::StreamExt;

        let directory = tempdir().expect("temp directory");
        std::fs::write(directory.path().join("keep.txt"), b"keep").expect("keep");
        std::fs::write(directory.path().join("gone.txt"), b"gone").expect("gone");
        let driver = LocalFilesystemStorageDriver::new(directory.path()).expect("driver");

        let mut stream = driver.list("").await.expect("list stream");
        std::fs::remove_file(directory.path().join("gone.txt")).expect("remove concurrent file");
        let mut names = Vec::new();
        while let Some(entry) = stream.next().await {
            names.push(entry.expect("entry").name);
        }

        assert_eq!(names, vec!["keep.txt".to_owned()]);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn rejects_symlink_escape() {
        use std::os::unix::fs::symlink;

        let directory = tempdir().expect("temp directory");
        let outside = tempdir().expect("outside directory");
        std::fs::write(outside.path().join("secret.txt"), b"secret").expect("secret");
        symlink(outside.path(), directory.path().join("escape")).expect("symlink");
        let driver = LocalFilesystemStorageDriver::new(directory.path()).expect("driver");

        assert!(matches!(
            driver.stat("escape/secret.txt").await,
            Err(StorageError::OutsideRoot)
        ));
    }

    #[tokio::test]
    async fn removes_temporary_file_after_interrupted_write() {
        let directory = tempdir().expect("temp directory");
        let driver = LocalFilesystemStorageDriver::new(directory.path()).expect("driver");
        let result = driver
            .write_atomic("nested/interrupted.bin", Box::new(FailingReader))
            .await;
        assert!(result.is_err());
        let entries = std::fs::read_dir(directory.path().join("nested"))
            .expect("nested directory")
            .collect::<Result<Vec<_>, _>>()
            .expect("directory entries");
        assert!(entries.is_empty());
    }

    #[tokio::test]
    async fn runtime_replacement_keeps_existing_snapshots_valid() {
        let first = tempdir().expect("first storage");
        let second = tempdir().expect("second storage");
        let first_driver =
            Arc::new(LocalFilesystemStorageDriver::new(first.path()).expect("first driver"));
        let runtime = StorageRuntime::new(first_driver.clone());
        let snapshot = runtime.snapshot().await;
        let (_, health) = runtime
            .replace_root(second.path())
            .await
            .expect("replace root");
        assert_eq!(
            health.root_path,
            second.path().canonicalize().unwrap().display().to_string()
        );
        assert_eq!(snapshot.root(), first.path().canonicalize().unwrap());
        snapshot
            .write_atomic("old-job.txt", reader(b"old"))
            .await
            .expect("write through old snapshot");
        runtime
            .write_atomic("new-job.txt", reader(b"new"))
            .await
            .expect("write through replacement");
        assert!(first.path().join("old-job.txt").exists());
        assert!(second.path().join("new-job.txt").exists());
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn reports_read_only_storage_health() {
        use std::os::unix::fs::PermissionsExt;

        let directory = tempdir().expect("read-only storage");
        std::fs::set_permissions(directory.path(), std::fs::Permissions::from_mode(0o555))
            .expect("make directory read-only");
        let driver = LocalFilesystemStorageDriver::new(directory.path()).expect("driver");
        let health = driver.health_check().await.expect("health");
        assert!(health.read_only);
        assert!(!health.writable);
    }
}
