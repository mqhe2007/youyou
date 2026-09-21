//! 媒体格式注册表：扫描白名单、`is_video` 判定与缩略图分派的唯一事实源。
//!
//! 历史上扩展名清单散落在扫描白名单、扫描内的 `is_video` 回退与上传判定三处，
//! 导致「上传能入库、扫描却跳过」这类分裂（见 2026-09-20 索引审计）。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaKind {
    Image,
    Video,
}

/// 服务端生成元数据/缩略图时使用的解码器。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decoder {
    /// image crate 内置解码。
    Builtin,
    /// ffmpeg / ffprobe 外部解码。
    Ffmpeg,
}

#[derive(Debug, Clone, Copy)]
pub struct MediaFormat {
    pub kind: MediaKind,
    pub mime: &'static str,
    pub decoder: Decoder,
}

const fn image(mime: &'static str) -> MediaFormat {
    MediaFormat {
        kind: MediaKind::Image,
        mime,
        decoder: Decoder::Builtin,
    }
}

const fn video(mime: &'static str) -> MediaFormat {
    MediaFormat {
        kind: MediaKind::Video,
        mime,
        decoder: Decoder::Ffmpeg,
    }
}

/// 扩展名（小写）→ 格式。新增支持时只改这一张表。
const FORMATS: &[(&str, MediaFormat)] = &[
    // 图片：image crate 内置解码（Cargo feature 见 image 依赖）
    ("jpg", image("image/jpeg")),
    ("jpeg", image("image/jpeg")),
    ("png", image("image/png")),
    ("gif", image("image/gif")),
    ("webp", image("image/webp")),
    ("bmp", image("image/bmp")),
    // 视频：ffmpeg / ffprobe
    ("mp4", video("video/mp4")),
    ("m4v", video("video/x-m4v")),
    ("mov", video("video/quicktime")),
    ("mkv", video("video/x-matroska")),
    ("webm", video("video/webm")),
    ("avi", video("video/x-msvideo")),
    ("mpg", video("video/mpeg")),
    ("mpeg", video("video/mpeg")),
    ("m2v", video("video/mpeg")),
    ("wmv", video("video/x-ms-wmv")),
    ("3gp", video("video/3gpp")),
];

/// 取扩展名（不含点，小写）。文件名以点开头（隐藏/AppleDouble）时返回 `None`，
/// 与扫描的垃圾过滤保持一致。
pub fn extension(name: &str) -> Option<String> {
    let (stem, extension) = name.rsplit_once('.')?;
    if stem.is_empty() || extension.is_empty() {
        return None;
    }
    Some(extension.to_ascii_lowercase())
}

pub fn from_path(path: &str) -> Option<MediaFormat> {
    let name = path.rsplit('/').next().unwrap_or(path);
    let extension = extension(name)?;
    FORMATS
        .iter()
        .find(|(candidate, _)| *candidate == extension)
        .map(|(_, format)| *format)
}

/// 扫描白名单：注册表内的格式才进入索引。
pub fn is_supported(path: &str) -> bool {
    from_path(path).is_some()
}

pub fn is_video_path(path: &str) -> bool {
    from_path(path).is_some_and(|format| format.kind == MediaKind::Video)
}

/// 存储与响应使用的 MIME：优先注册表，未知扩展名回退 mime_guess。
pub fn mime_for_path(path: &str) -> Option<String> {
    from_path(path)
        .map(|format| format.mime.to_owned())
        .or_else(|| mime_guess::from_path(path).first_raw().map(str::to_owned))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn covers_legacy_formats_and_is_case_insensitive() {
        assert_eq!(
            from_path("a/b/井冈山游记.MPG").unwrap().kind,
            MediaKind::Video
        );
        assert_eq!(from_path("clip.WMV").unwrap().mime, "video/x-ms-wmv");
        assert_eq!(from_path("old.bmp").unwrap().kind, MediaKind::Image);
        assert_eq!(from_path("m.3GP").unwrap().mime, "video/3gpp");
        assert_eq!(from_path("v.m2v").unwrap().kind, MediaKind::Video);
        assert!(is_supported("x.jpg"));
        assert!(!is_supported("notes.txt"));
        assert!(!is_supported(".DS_Store"));
        // 隐藏文件的过滤在扫描层（is_ignored_name）；注册表只按扩展名判定。
        assert!(is_supported("._1708264084494.jpg"));
    }

    #[test]
    fn mime_prefers_registry_and_falls_back() {
        assert_eq!(mime_for_path("photo.mpg").as_deref(), Some("video/mpeg"));
        assert_eq!(mime_for_path("photo.heic").as_deref(), Some("image/heic"));
        assert_eq!(mime_for_path("no-extension").as_deref(), None);
    }
}
