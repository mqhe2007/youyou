use std::{
    net::SocketAddr,
    path::{Path, PathBuf},
};

/// Load the nearest `.env` walking up from the current working directory.
/// Existing process environment variables are not overridden.
pub fn load_dotenv() {
    let Ok(mut dir) = std::env::current_dir() else {
        return;
    };
    loop {
        let candidate = dir.join(".env");
        if candidate.is_file() {
            let _ = dotenvy::from_path(&candidate);
            return;
        }
        if !dir.pop() {
            return;
        }
    }
}

/// Default runtime directory: `$HOME/youyou-server`, or `./youyou-server` if HOME is unset.
pub fn default_server_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(|home| PathBuf::from(home).join("youyou-server"))
        .unwrap_or_else(|| PathBuf::from("youyou-server"))
}

pub fn resolve_server_dir(explicit: Option<PathBuf>) -> PathBuf {
    explicit.unwrap_or_else(default_server_dir)
}

pub fn data_dir(server_dir: &Path) -> PathBuf {
    server_dir.join("data")
}

pub fn media_root(server_dir: &Path) -> PathBuf {
    server_dir.join("media")
}

/// 从数据目录推断服务端目录：`<server_dir>/data` → `<server_dir>`。
/// 其它形态（例如测试用的临时数据目录）返回数据目录本身，避免把回收站写到
/// 共享的父目录里。
pub fn infer_server_dir(data_dir: &Path) -> PathBuf {
    let is_canonical_layout = data_dir.file_name().and_then(|name| name.to_str()) == Some("data");
    if is_canonical_layout {
        data_dir
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(|| data_dir.to_path_buf())
    } else {
        data_dir.to_path_buf()
    }
}

pub fn bind_addr(port: u16) -> SocketAddr {
    SocketAddr::from(([0, 0, 0, 0], port))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derives_data_and_media_under_server_dir() {
        let root = PathBuf::from("/tmp/youyou-server");
        assert_eq!(data_dir(&root), PathBuf::from("/tmp/youyou-server/data"));
        assert_eq!(media_root(&root), PathBuf::from("/tmp/youyou-server/media"));
    }

    #[test]
    fn bind_addr_uses_all_interfaces_and_port() {
        assert_eq!(bind_addr(5656).to_string(), "0.0.0.0:5656");
    }
}
