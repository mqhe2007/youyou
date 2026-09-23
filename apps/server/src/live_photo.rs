//! 实况照片（iOS 成对 Live Photo / Android 单文件 Motion Photo）识别、配对与投影标记的
//! 唯一事实源。
//!
//! 事实来源：
//! - Android Motion Photo format 1.0：单文件 = 主图 + 末尾追加视频；XMP 的
//!   `Camera:MotionPhoto` 表示是否按动态照片处理，容器 XMP（`Container:Directory` /
//!   `Item:Mime` / `Item:Length` / `Item:Semantic`）给出视频字节长度与语义。
//!   <https://developer.android.com/media/platform/motion-photo-format>
//! - iOS 成对实况以共享内容标识关联：MOV 侧 `com.apple.quicktime.content.identifier`
//!   （ISO BMFF 元数据键）。文件名同基名只是辅助线索，不能单独作为判定依据。
//!
//! 判定原则：`Camera:MotionPhoto=1` 不是充分条件（编辑过的图片可能残留标记），
//! 必须同时确认「视频字节真实存在」；识别失败降级为普通媒体，不猜。
//!
//! 数据语义（`media_assets.live_*`）：
//! - `live_role = 'still'`：该静态帧有动态部分。`live_partner_id` 为空表示动态部分
//!   尚未入库（半态，见 FR-4）。
//! - `live_role = 'motion'`：动态部分；配对完成后媒体清单投影不再把它当独立媒体项。
//! - 任一侧消失即整体降级 `live_role = 'none'`（FR-8），由 tombstone / 对账 / 回填收敛。

use anyhow::Context;
use sqlx::{SqliteConnection, SqlitePool};

use crate::{db::now_millis, error::AppResult, storage::LocalFilesystemStorageDriver};

/// 探测逻辑版本；升级识别逻辑时 +1，使已索引行在下一次扫描重新探测一次。
pub const PROBE_VERSION: i64 = 1;

/// 媒体清单类查询的可见性谓词（`a` 为 media_assets 别名）。
///
/// 配对后的动态视频不是独立媒体项：它随静态帧携带的实况标记一起出现，不单独陈列。
/// 按 id 的详情、内容、缩略图、删除与恢复不受此谓词影响。
pub const VISIBLE_PREDICATE: &str = "a.live_role != 'motion'";

/// 媒体 payload 的实况片段（只依赖别名 `a`），**作为 `json_object(...)` 的最后一项**使用
/// （末尾不带逗号）。
///
/// 无实况时为 JSON null；客户端只按 `livePhoto` 判断，不解析其它实况列。
/// 所有 SQL 载荷构建点都必须用本常量拼装，避免多处漂移。
pub const PAYLOAD_FRAGMENT: &str = "'livePhoto', CASE WHEN a.live_role = 'none' THEN NULL ELSE json_object('role', a.live_role, 'embedded', CASE WHEN a.live_embedded = 1 THEN json('true') ELSE json('false') END, 'groupKey', a.live_group_key, 'partnerMediaId', a.live_partner_id, 'partnerContentHash', a.live_partner_hash, 'motionDurationMs', a.live_motion_duration_ms) END";

/// 读取文件做识别时的上限。图片只需文件头（XMP 在主图数据之前）；HEIF 的 XMP 是
/// 容器内的 item，可能落在 mdat 任意位置，放宽到整文件量级；视频只需定位 `moov`。
const IMAGE_HEAD_BYTES: u64 = 1024 * 1024;
const HEIC_HEAD_BYTES: u64 = 32 * 1024 * 1024;
const MAX_MOOV_BYTES: u64 = 32 * 1024 * 1024;
/// 判定视频字节真实存在时读取的尾部窗口上限。
const TAIL_CONFIRM_BYTES: u64 = 4096;

const XMP_MARKER: &[u8] = b"http://ns.adobe.com/xap/1.0/";
const XMP_END: &[u8] = b"</x:xmpmeta>";
/// JPEG APP1 与 HEIF 内 `Exif` item 的负载都以这个标记开头，其后是 TIFF 头。
const EXIF_MARKER: &[u8] = b"Exif\0\0";
/// Apple MakerNote 头（`Apple iOS\0` + 版本 + 自带字节序 + IFD）。
const MAKERNOTE_HEADER: &[u8] = b"Apple iOS";
/// Apple MakerNote 里内容标识的 tag（十进制 17，即 iOS 实况两侧共享的 UUID）。
const MAKERNOTE_TAG_CONTENT_IDENTIFIER: u16 = 0x0011;
/// TIFF `ExifIFD` 指针与其中的 `MakerNote`。
const TIFF_TAG_EXIF_IFD: u16 = 0x8769;
const TIFF_TAG_MAKER_NOTE: u16 = 0x927C;

/// 单文件动态照片的识别结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EmbeddedMotion {
    /// XMP 声明的追加视频字节数（含 padding 前）。
    pub video_length: u64,
    /// XMP 声明的 padding 字节数。
    pub padding: u64,
    /// XMP 声明的视频 MIME（可空）。
    pub mime: Option<String>,
}

impl EmbeddedMotion {
    /// 追加部分在文件中的起始偏移；文件长度不足时返回 `None`。
    pub fn offset_in(&self, file_size: u64) -> Option<u64> {
        let total = self.video_length.checked_add(self.padding)?;
        if self.video_length == 0 || file_size < total {
            return None;
        }
        Some(file_size - total)
    }
}

/// 一次文件探测的结果（纯识别，不做配对决策）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Probe {
    pub embedded: Option<EmbeddedMotion>,
    /// ISO BMFF 元数据键 `com.apple.quicktime.content.identifier` 归一后的分组键。
    pub content_identifier: Option<String>,
}

/// 索引时由调用方（上传协议携带的客户端本地识别结果）声明的实况线索。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeclaredLive {
    /// `"still"` 或 `"motion"`：客户端明确声明该文件属于一段实况。
    pub role: Option<String>,
    /// 两端一致的不透明分组键（客户端生成，跨设备稳定）。
    pub group_key: Option<String>,
    /// 静态帧自带内嵌动态部分（单文件动态照片）。
    pub embedded: bool,
    /// 静态帧侧声明的动态时长（毫秒）。
    pub motion_duration_ms: Option<i64>,
}

impl DeclaredLive {
    pub fn is_empty(&self) -> bool {
        self.role.is_none() && self.group_key.is_none() && !self.embedded
    }
}

/// 分组键前缀：内容标识（强）与客户端声明（上传路径）。
pub const KEY_CONTENT_IDENTIFIER: &str = "cid:";
pub const KEY_DECLARED: &str = "lp:";

fn normalize_declared_key(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed.len() > 128 {
        return None;
    }
    if trimmed.chars().any(char::is_control) {
        return None;
    }
    Some(format!("{KEY_DECLARED}{}", trimmed.to_ascii_lowercase()))
}

fn content_identifier_key(identifier: &str) -> Option<String> {
    let normalized = identifier.trim().trim_matches(['{', '}']);
    if normalized.len() < 8 || normalized.len() > 64 {
        return None;
    }
    if !normalized
        .chars()
        .all(|c| c.is_ascii_hexdigit() || c == '-')
    {
        return None;
    }
    Some(format!(
        "{KEY_CONTENT_IDENTIFIER}{}",
        normalized.to_ascii_lowercase()
    ))
}

// ── 路径辅助 ──────────────────────────────────────────────────

/// 目录与不含扩展名的主名；主名保持原始大小写（路径查找是大小写敏感的）。
pub fn split_directory_stem(path: &str) -> (String, String) {
    let (directory, name) = match path.rsplit_once('/') {
        Some((directory, name)) => (directory.to_owned(), name.to_owned()),
        None => (String::new(), path.to_owned()),
    };
    let stem = match name.rsplit_once('.') {
        Some((stem, _)) if !stem.is_empty() => stem.to_owned(),
        _ => name.to_owned(),
    };
    (directory, stem)
}

/// 主名的大小写变体（去重、保持顺序）。
fn stem_variants(stem: &str) -> Vec<String> {
    let mut variants = vec![stem.to_owned()];
    for variant in [stem.to_lowercase(), stem.to_uppercase()] {
        if !variants.contains(&variant) {
            variants.push(variant);
        }
    }
    variants
}

/// 同目录同主名的候选路径（主名与扩展名都可能大小写不一致）。
///
/// 顺序即优先级：扩展名优先（iOS 先 MOV、Android 先 MP4），其次大小写。
fn candidate_paths(directory: &str, stem: &str, extensions: &[&str]) -> Vec<String> {
    let stems = stem_variants(stem);
    let mut paths = Vec::new();
    for extension in extensions {
        for stem in &stems {
            for candidate in [extension.to_lowercase(), extension.to_ascii_uppercase()] {
                let path = sibling_path(directory, stem, &candidate);
                if !paths.contains(&path) {
                    paths.push(path);
                }
            }
        }
    }
    paths
}

/// 候选同伴路径：同目录同主名 + 给定扩展名。
pub fn sibling_path(directory: &str, stem: &str, extension: &str) -> String {
    if directory.is_empty() {
        format!("{stem}.{extension}")
    } else {
        format!("{directory}/{stem}.{extension}")
    }
}

/// 视频扩展名候选（按实况来源经验排序）：iOS 用 MOV，Android/三星用 MP4。
pub const VIDEO_EXTENSIONS: &[&str] = &["mov", "mp4", "m4v", "3gp", "mkv", "webm", "avi", "wmv"];
/// 静态帧扩展名候选：iOS 用 HEIC/JPG，Android 用 JPG/HEIC/AVIF。
pub const IMAGE_EXTENSIONS: &[&str] = &["heic", "heif", "jpg", "jpeg", "avif", "png", "webp"];

// ── 单文件动态照片（XMP）────────────────────────────────────────

/// 从图片字节里解析内嵌动态照片的 XMP 声明（尚未确认视频字节存在）。
pub fn parse_embedded_motion(bytes: &[u8], file_size: u64) -> Option<EmbeddedMotion> {
    let xmp = extract_xmp(bytes)?;
    let item = container_motion_item(&xmp)?;
    let motion = EmbeddedMotion {
        video_length: item.length,
        padding: item.padding.unwrap_or(0),
        mime: item.mime,
    };
    // 文件长度至少要装得下声明的追加部分。
    motion.offset_in(file_size)?;
    Some(motion)
}

/// 分离出的 XMP 文本。
pub fn extract_xmp(bytes: &[u8]) -> Option<String> {
    let start = find_bytes(bytes, XMP_MARKER)? + XMP_MARKER.len();
    // JPEG APP1 段在标记后有一个 NUL 终止符；HEIF 直接把 XMP 作为 item 负载，没有它。
    let start = if bytes.get(start) == Some(&0) {
        start + 1
    } else {
        start
    };
    let rest = &bytes[start..];
    let end = find_bytes(rest, XMP_END).map(|index| index + XMP_END.len())?;
    Some(String::from_utf8_lossy(&rest[..end]).into_owned())
}

/// 尾部窗口是否确实是一个视频容器盒（确认声明的视频字节真实存在）。
pub fn looks_like_video_container(window: &[u8]) -> bool {
    if window.len() < 8 {
        return false;
    }
    &window[4..8] == b"ftyp"
        || matches!(
            &window[4..8],
            b"moov" | b"mdat" | b"free" | b"skip" | b"wide" | b"styp" | b"sidx"
        )
}

/// `Camera:MotionPhoto` 是否为动态照片标记（`1` 表示按动态照片处理）。
///
/// 规范示例用属性形式（`Camera:MotionPhoto="1"`），也有写入方用元素形式
/// （`<Camera:MotionPhoto>1</Camera:MotionPhoto>`），两种都要认。
fn motion_photo_flag(xmp: &str) -> bool {
    let mut rest = xmp;
    while let Some(index) = rest.find("MotionPhoto") {
        let after = rest[index + "MotionPhoto".len()..].trim_start();
        let value = if let Some(after_eq) = after.strip_prefix('=') {
            quoted_value(after_eq.trim_start())
        } else {
            after
                .strip_prefix('>')
                .and_then(|after_gt| after_gt.split('<').next())
        };
        if value.is_some_and(|value| value.trim() == "1") {
            return true;
        }
        rest = &rest[index + "MotionPhoto".len()..];
    }
    false
}

fn quoted_value(text: &str) -> Option<&str> {
    let quote = text.chars().next()?;
    if quote != '"' && quote != '\'' {
        return None;
    }
    let value = &text[1..];
    let end = value.find(quote)?;
    Some(&value[..end])
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct MotionItem {
    length: u64,
    mime: Option<String>,
    padding: Option<u64>,
}

/// 在 `Container:Directory` 里找语义为 `MotionPhoto` 的条目；退而取视频 MIME 的条目。
fn container_motion_item(xmp: &str) -> Option<MotionItem> {
    if !motion_photo_flag(xmp) {
        return None;
    }
    let mut candidates: Vec<MotionItem> = Vec::new();
    let mut search_from = 0_usize;
    while let Some(relative) = xmp[search_from..].find("Item:Length") {
        let index = search_from + relative;
        let after = xmp[index + "Item:Length".len()..].trim_start();
        if let Some(after) = after.strip_prefix('=')
            && let Some(raw) = quoted_value(after.trim_start())
        {
            // 同一元素内的其它属性：向前不超过一个元素起点，向后最多 512 字节。
            let element_start = xmp[..index].rfind('<').unwrap_or(0);
            let window_end = (index + 512).min(xmp.len());
            let window = &xmp[element_start..window_end];
            let mime = attribute_value_from(window, "Item:Mime");
            let semantic = attribute_value_from(window, "Item:Semantic");
            let padding =
                attribute_value_from(window, "Item:Padding").and_then(|v| v.trim().parse().ok());
            let is_motion = semantic.as_deref() == Some("MotionPhoto")
                || mime.as_deref().is_some_and(|v| v.starts_with("video/"));
            if let Ok(length) = raw.trim().parse::<u64>()
                && length > 0
                && is_motion
            {
                candidates.push(MotionItem {
                    length,
                    mime,
                    padding,
                });
            }
        }
        search_from = index + "Item:Length".len();
    }
    candidates.into_iter().max_by_key(|item| item.length)
}

fn attribute_value_from(window: &str, name: &str) -> Option<String> {
    let index = window.find(name)?;
    let after = window[index + name.len()..].trim_start();
    let after = after.strip_prefix('=')?.trim_start();
    quoted_value(after).map(str::to_owned)
}

/// 从图片字节里取 iOS 实况的内容标识（归一为分组键）。
///
/// Apple 把内容标识放在 EXIF 的 Apple MakerNote tag `0x0011` 上；HEIC/HEIF 的 EXIF 是
/// 容器内的 item、负载落在 `mdat` 任意位置，因此按 `Exif\0\0` + TIFF 头扫描定位，
/// 逐个候选解析、取第一个能解析出标识的（JPEG APP1 走同一条路径）。
pub fn image_content_identifier(bytes: &[u8]) -> Option<String> {
    let mut from = 0_usize;
    while from < bytes.len() {
        let Some(relative) = find_bytes(&bytes[from..], EXIF_MARKER) else {
            break;
        };
        let index = from + relative;
        let start = index + EXIF_MARKER.len();
        if let Some(key) = tiff_apple_content_identifier(&bytes[start..]) {
            return Some(key);
        }
        from = start;
    }
    None
}

/// 从一段 TIFF 字节里取 Apple MakerNote 的内容标识（归一为分组键）。
pub fn tiff_apple_content_identifier(tiff: &[u8]) -> Option<String> {
    let order = tiff_byte_order(tiff)?;
    let entries = tiff_ifd_entries(tiff, order, order.u32(tiff.get(4..8)?)? as usize)?;
    let exif_offset = entries
        .iter()
        .find(|(tag, _, _, _)| *tag == TIFF_TAG_EXIF_IFD)
        .and_then(|(_, _, _, value)| order.u32(value))? as usize;
    let exif_entries = tiff_ifd_entries(tiff, order, exif_offset)?;
    let maker_note = exif_entries
        .iter()
        .find(|(tag, _, _, _)| *tag == TIFF_TAG_MAKER_NOTE)?
        .3
        .clone();
    apple_makernote_content_identifier(&maker_note)
}

/// Apple MakerNote：`Apple iOS\0` + 版本 + **自带字节序** + IFD（偏移相对 MakerNote 起点）。
///
/// 不认识的 MakerNote 直接返回 `None`：识别失败降级为普通媒体，不猜。
pub fn apple_makernote_content_identifier(maker_note: &[u8]) -> Option<String> {
    if !maker_note.starts_with(MAKERNOTE_HEADER) {
        return None;
    }
    let order = byte_order_from_marker(maker_note.get(12..14)?)?;
    let entries = tiff_ifd_entries(maker_note, order, 14)?;
    let value = entries
        .iter()
        .find(|(tag, kind, _, _)| *tag == MAKERNOTE_TAG_CONTENT_IDENTIFIER && *kind == 2)?
        .3
        .clone();
    let text = String::from_utf8_lossy(&value);
    content_identifier_key(text.trim_matches('\0').trim())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ByteOrder {
    Little,
    Big,
}

impl ByteOrder {
    fn u16(self, bytes: &[u8]) -> Option<u16> {
        let raw: [u8; 2] = bytes.get(0..2)?.try_into().ok()?;
        Some(match self {
            ByteOrder::Little => u16::from_le_bytes(raw),
            ByteOrder::Big => u16::from_be_bytes(raw),
        })
    }

    fn u32(self, bytes: &[u8]) -> Option<u32> {
        let raw: [u8; 4] = bytes.get(0..4)?.try_into().ok()?;
        Some(match self {
            ByteOrder::Little => u32::from_le_bytes(raw),
            ByteOrder::Big => u32::from_be_bytes(raw),
        })
    }
}

fn byte_order_from_marker(marker: &[u8]) -> Option<ByteOrder> {
    match marker.get(0..2)? {
        b"II" => Some(ByteOrder::Little),
        b"MM" => Some(ByteOrder::Big),
        _ => None,
    }
}

/// TIFF 头：字节序标记 + magic 42。
fn tiff_byte_order(tiff: &[u8]) -> Option<ByteOrder> {
    let order = byte_order_from_marker(tiff)?;
    (order.u16(tiff.get(2..4)?)? == 42).then_some(order)
}

/// 一个 TIFF IFD 条目：`(tag, type, count, value 字节)`；值不足 4 字节时内联，否则已按偏移取回。
type TiffEntry = (u16, u16, u32, Vec<u8>);

fn tiff_ifd_entries(base: &[u8], order: ByteOrder, ifd_offset: usize) -> Option<Vec<TiffEntry>> {
    let start = base.get(ifd_offset..)?;
    let count = usize::from(order.u16(start)?);
    let mut entries = Vec::with_capacity(count.min(64));
    for index in 0..count {
        let entry = base.get(ifd_offset + 2 + index * 12..)?;
        let tag = order.u16(entry)?;
        let kind = order.u16(entry.get(2..4)?)?;
        let item_count = order.u32(entry.get(4..8)?)?;
        let size = tiff_entry_size(kind, item_count)?;
        let value = if size > 4 {
            let offset = order.u32(entry.get(8..12)?)? as usize;
            base.get(offset..offset.checked_add(size)?)?.to_vec()
        } else {
            entry.get(8..8 + size)?.to_vec()
        };
        entries.push((tag, kind, item_count, value));
    }
    Some(entries)
}

/// TIFF 类型的单元字节数；未知类型返回 `None`，让调用方安全放弃而不是错位解析。
fn tiff_entry_size(kind: u16, count: u32) -> Option<usize> {
    let unit: usize = match kind {
        1 | 2 | 6 | 7 => 1,
        3 | 8 => 2,
        4 | 9 | 11 | 13 => 4,
        5 | 10 | 12 => 8,
        16..=18 => 8,
        _ => return None,
    };
    unit.checked_mul(usize::try_from(count).ok()?)
}

// ── ISO BMFF（QuickTime）内容标识 ────────────────────────────────

/// 从完整 MOV/MP4 字节里取内容标识（测试与小文件复用；大文件走盒头跳读）。
pub fn quicktime_content_identifier_in_bytes(bytes: &[u8]) -> Option<String> {
    quicktime_content_identifier(moov_body_in_bytes(bytes)?)
}

/// 在完整字节里定位顶层 `moov` 盒体。
fn moov_body_in_bytes(bytes: &[u8]) -> Option<&[u8]> {
    let mut offset = 0_usize;
    let mut guard = 0_u32;
    while offset + 8 <= bytes.len() && guard < 4096 {
        guard += 1;
        let (header_len, kind, body_len) =
            parse_top_level_header(&bytes[offset..], bytes.len() as u64, offset as u64)?;
        let header_len = usize::try_from(header_len).ok()?;
        let body_len = usize::try_from(body_len).ok()?;
        if &kind == b"moov" {
            if body_len > usize::try_from(MAX_MOOV_BYTES).ok()? {
                return None;
            }
            return bytes.get(offset + header_len..offset + header_len + body_len);
        }
        offset = offset.checked_add(header_len + body_len)?;
    }
    None
}

/// 从 `moov` 盒体里取 `com.apple.quicktime.content.identifier` 并归一为分组键。
pub fn quicktime_content_identifier(moov: &[u8]) -> Option<String> {
    for meta in meta_boxes(moov) {
        if let Some(value) = metadata_key_value(meta, "com.apple.quicktime.content.identifier")
            && let Some(key) = content_identifier_key(&value)
        {
            return Some(key);
        }
    }
    None
}

/// `meta` 可能是 `moov` 的直接子级，也可能在 `moov/udta` 下。
fn meta_boxes(moov: &[u8]) -> Vec<&[u8]> {
    let mut output: Vec<&[u8]> = Vec::new();
    let mut rest = moov;
    while let Some((header, body, next)) = next_box(rest) {
        if &header == b"meta" {
            output.push(body);
        } else if &header == b"udta" {
            let mut inner = body;
            while let Some((inner_header, inner_body, inner_next)) = next_box(inner) {
                if &inner_header == b"meta" {
                    output.push(inner_body);
                }
                inner = inner_next;
            }
        }
        rest = next;
    }
    output
}

/// 迭代一层盒结构：返回 `(类型, 盒体, 剩余字节)`。
fn next_box(data: &[u8]) -> Option<([u8; 4], &[u8], &[u8])> {
    if data.len() < 8 {
        return None;
    }
    let size = u32::from_be_bytes(data[0..4].try_into().ok()?);
    let kind: [u8; 4] = data[4..8].try_into().ok()?;
    let (header_len, body_len) = match size {
        0 => (8usize, data.len() - 8),
        1 => {
            if data.len() < 16 {
                return None;
            }
            let large = u64::from_be_bytes(data[8..16].try_into().ok()?);
            if large < 16 || large > data.len() as u64 {
                return None;
            }
            (16usize, large as usize - 16)
        }
        other => {
            let size = other as usize;
            if size < 8 || size > data.len() {
                return None;
            }
            (8usize, size - 8)
        }
    };
    let body = &data[header_len..header_len + body_len];
    Some((kind, body, &data[header_len + body_len..]))
}

fn find_child_box<'a>(data: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    let mut rest = data;
    while let Some((candidate, body, next)) = next_box(rest) {
        if &candidate == kind {
            return Some(body);
        }
        rest = next;
    }
    None
}

/// `meta` 是 FullBox：先跳过 4 字节 version/flags。
fn metadata_key_value(meta_body: &[u8], key: &str) -> Option<String> {
    // `meta` 有两种写法：ISO BMFF 是 FullBox（带 4 字节 version/flags），QuickTime 的
    // `meta` 是普通容器（子盒紧接类型之后）。真实 iPhone MOV 是后者，两种都要认。
    [4usize, 0_usize].into_iter().find_map(|skip| {
        let body = meta_body.get(skip..)?;
        let keys = find_child_box(body, b"keys")?;
        let ilst = find_child_box(body, b"ilst")?;
        let target_index = metadata_key_index(keys, key)?;
        metadata_ilst_value(ilst, target_index)
    })
}

/// `keys` 盒体：FullBox(4) + entry_count(4) + 每条 key_size(4) + key_namespace(4) + 值。
fn metadata_key_index(keys: &[u8], target: &str) -> Option<usize> {
    let body = keys.get(4..)?;
    let count = u32::from_be_bytes(body.get(0..4)?.try_into().ok()?) as usize;
    let mut offset = 4usize;
    for index in 0..count {
        let key_size = u32::from_be_bytes(body.get(offset..offset + 4)?.try_into().ok()?) as usize;
        if key_size < 8 || offset + key_size > body.len() {
            return None;
        }
        if &body[offset + 8..offset + key_size] == target.as_bytes() {
            return Some(index + 1);
        }
        offset += key_size;
    }
    None
}

/// `ilst` 子盒的 4CC 即 1 起的键序号；每个条目内是 `data` 盒。
fn metadata_ilst_value(ilst: &[u8], target_index: usize) -> Option<String> {
    let mut rest = ilst;
    while let Some((kind, body, next)) = next_box(rest) {
        if u32::from_be_bytes(kind) as usize == target_index {
            let data = find_child_box(body, b"data")?;
            // FullBox(4) + locale(4) 之后是值。
            let text = String::from_utf8_lossy(data.get(8..)?);
            let text = text.trim_matches('\0').trim();
            return (!text.is_empty()).then(|| text.to_owned());
        }
        rest = next;
    }
    None
}

// ── 文件探测 ──────────────────────────────────────────────────

/// 探测一个已落盘文件的实况线索。
///
/// 读取失败返回 `Err`：调用方按普通媒体降级并记 `failed`，下一次扫描重试；识别失败
/// 绝不猜、也不让索引失败。
pub async fn probe_path(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
    file_size: u64,
    is_video: bool,
) -> anyhow::Result<Probe> {
    if is_video {
        Ok(Probe {
            embedded: None,
            content_identifier: read_moov_content_identifier(storage, path, file_size).await?,
        })
    } else {
        probe_image(storage, path, file_size).await
    }
}

/// 读取文件的 `[offset, offset + length)` 字节。
///
/// 存储层 `read_stream` 的第二项是**闭区间结束位置**而不是长度，这里统一换算，
/// 避免各处重复踩坑。
async fn read_range(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
    offset: u64,
    length: u64,
) -> anyhow::Result<Vec<u8>> {
    if length == 0 {
        return Ok(Vec::new());
    }
    let end = offset
        .checked_add(length - 1)
        .context("live photo read range overflow")?;
    storage
        .read_all(path, Some((offset, Some(end))))
        .await
        .map_err(Into::into)
}

/// 图片侧探测：单文件动态照片（XMP）+ iOS 内容标识（EXIF MakerNote）。
async fn probe_image(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
    file_size: u64,
) -> anyhow::Result<Probe> {
    let head_cap = match crate::media_format::from_path(path) {
        Some(format) if format.decoder == crate::media_format::Decoder::Heif => HEIC_HEAD_BYTES,
        _ => IMAGE_HEAD_BYTES,
    };
    let head_len = file_size.min(head_cap);
    let head = read_range(storage, path, 0, head_len)
        .await
        .with_context(|| format!("live photo probe read failed for {path}"))?;
    let content_identifier = image_content_identifier(&head);
    let Some(motion) = parse_embedded_motion(&head, file_size) else {
        return Ok(Probe {
            embedded: None,
            content_identifier,
        });
    };
    // 必须确认声明的视频字节真实存在：读尾部窗口，检查它确实是一个视频容器盒。
    let offset = motion
        .offset_in(file_size)
        .context("declared motion part exceeds file length")?;
    let window = read_range(
        storage,
        path,
        offset,
        motion.video_length.min(TAIL_CONFIRM_BYTES),
    )
    .await
    .with_context(|| format!("live photo tail read failed for {path}"))?;
    Ok(Probe {
        embedded: looks_like_video_container(&window).then_some(motion),
        content_identifier,
    })
}

async fn read_moov_content_identifier(
    storage: &LocalFilesystemStorageDriver,
    path: &str,
    file_size: u64,
) -> anyhow::Result<Option<String>> {
    let mut offset = 0_u64;
    let mut guard = 0_u32;
    while offset + 8 <= file_size && guard < 4096 {
        guard += 1;
        let header_bytes = read_range(storage, path, offset, 16).await?;
        let Some((header_len, kind, body_len)) =
            parse_top_level_header(&header_bytes, file_size, offset)
        else {
            return Ok(None);
        };
        if &kind == b"moov" {
            if body_len > MAX_MOOV_BYTES {
                return Ok(None);
            }
            let moov = read_range(storage, path, offset + header_len, body_len).await?;
            return Ok(quicktime_content_identifier(&moov));
        }
        offset = offset
            .checked_add(header_len + body_len)
            .context("ISO BMFF box chain overflow")?;
    }
    Ok(None)
}

fn parse_top_level_header(
    bytes: &[u8],
    file_size: u64,
    offset: u64,
) -> Option<(u64, [u8; 4], u64)> {
    if bytes.len() < 8 {
        return None;
    }
    let size = u32::from_be_bytes(bytes[0..4].try_into().ok()?);
    let kind: [u8; 4] = bytes[4..8].try_into().ok()?;
    let (header_len, body_len) = match size {
        0 => (8u64, file_size.checked_sub(offset + 8)?),
        1 => {
            if bytes.len() < 16 {
                return None;
            }
            let large = u64::from_be_bytes(bytes[8..16].try_into().ok()?);
            if large < 16 {
                return None;
            }
            (16u64, large - 16)
        }
        other => {
            if (other as u64) < 8 {
                return None;
            }
            (8u64, other as u64 - 8)
        }
    };
    Some((header_len, kind, body_len))
}

// ── 配对候选 ──────────────────────────────────────────────────

/// 一个已索引的配对候选。
#[derive(Debug, Clone)]
pub struct PairCandidate {
    pub media_id: String,
    pub is_video: bool,
    pub duration_ms: Option<i64>,
    pub content_hash: Option<String>,
    pub live_role: String,
    pub live_partner_id: Option<String>,
    pub live_group_key: Option<String>,
}

type CandidateRow = (
    String,
    i64,
    Option<i64>,
    Option<String>,
    String,
    Option<String>,
    Option<String>,
);

const CANDIDATE_SQL: &str = r#"
    SELECT a.id, a.is_video, a.duration_ms, b.content_hash,
           a.live_role, a.live_partner_id, a.live_group_key
    FROM media_assets a
    {JOIN}
    LEFT JOIN content_blobs b ON b.id = a.blob_id
    WHERE a.identity_state = 'verified'
      AND a.owner_user_id IS ?1
      AND a.is_video = ?2
      {EXTRA}
    -- 优先尚未配对的候选：同一内容标识可能对应多份副本（重复导入/多次拷贝），
    -- 只取第一个可能拿到已配对的资产而误判为「无对手」。
    ORDER BY (a.live_partner_id IS NOT NULL) ASC, a.id ASC
    LIMIT 1
"#;

fn candidate_from_row(row: CandidateRow) -> PairCandidate {
    PairCandidate {
        media_id: row.0,
        is_video: row.1 == 1,
        duration_ms: row.2,
        content_hash: row.3,
        live_role: row.4,
        live_partner_id: row.5,
        live_group_key: row.6,
    }
}

/// 按分组键找类型相反的候选同伴。
pub(crate) async fn candidate_by_group_key(
    transaction: &mut SqliteConnection,
    owner_user_id: Option<i64>,
    want_video: bool,
    group_key: &str,
) -> AppResult<Option<PairCandidate>> {
    let sql = CANDIDATE_SQL
        .replace("{JOIN}", "")
        .replace("{EXTRA}", "AND a.live_group_key = ?3");
    let row = sqlx::query_as::<_, CandidateRow>(&sql)
        .bind(owner_user_id)
        .bind(i64::from(want_video))
        .bind(group_key)
        .fetch_optional(&mut *transaction)
        .await?;
    Ok(row.map(candidate_from_row))
}

/// 按「同目录同主名 + 候选扩展名」找类型相反的候选同伴（文件名只是辅助线索）。
pub(crate) async fn candidate_by_sibling_path(
    transaction: &mut SqliteConnection,
    owner_user_id: Option<i64>,
    want_video: bool,
    storage_id: &str,
    directory: &str,
    stem: &str,
    extensions: &[&str],
) -> AppResult<Option<PairCandidate>> {
    let sql = CANDIDATE_SQL
        .replace(
            "{JOIN}",
            "INNER JOIN media_locations l ON l.media_asset_id = a.id",
        )
        .replace(
            "{EXTRA}",
            "AND l.storage_id = ?3 AND l.normalized_path = ?4",
        );
    for path in candidate_paths(directory, stem, extensions) {
        let row = sqlx::query_as::<_, CandidateRow>(&sql)
            .bind(owner_user_id)
            .bind(i64::from(want_video))
            .bind(storage_id)
            .bind(&path)
            .fetch_optional(&mut *transaction)
            .await?;
        if let Some(row) = row {
            return Ok(Some(candidate_from_row(row)));
        }
    }
    Ok(None)
}

// ── 投影写入 ──────────────────────────────────────────────────

/// 一次配对写入的两侧。
struct PairWrite<'a> {
    still_id: &'a str,
    motion_id: &'a str,
    group_key: Option<&'a str>,
    motion_duration: Option<i64>,
    still_hash: Option<&'a str>,
    motion_hash: Option<&'a str>,
}

/// 一次索引探测的投影结果。
#[derive(Debug, Clone, Default)]
pub struct LiveOutcome {
    /// 本行实况语义是否变化。
    pub own_changed: bool,
    /// 配对对手（其投影也变了，调用方应为它发变更事件）。
    pub partner_id: Option<String>,
    /// 本行最终是否为实况的一部分。
    pub is_live: bool,
}

/// 索引探测结果落到 `media_assets` 并完成配对。
///
/// `probe` 为 `None` 表示探测未能完成：记 `failed` 供下一次扫描重试，本次按普通媒体
/// 处理，但保留已有的实况标记（一次读取失败不应拆散已建立的配对）。
pub(crate) async fn apply_probe(
    transaction: &mut SqliteConnection,
    media_id: &str,
    path: &str,
    is_video: bool,
    owner_user_id: Option<i64>,
    probe: Option<&Probe>,
    declared: &DeclaredLive,
) -> AppResult<LiveOutcome> {
    let Some(probe) = probe else {
        mark_probe_failed(transaction, media_id).await?;
        return Ok(LiveOutcome::default());
    };
    let storage_id = sqlx::query_scalar::<_, String>(
        "SELECT storage_id FROM media_locations WHERE media_asset_id = ?1 ORDER BY normalized_path LIMIT 1",
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?
    .unwrap_or_else(|| "local".to_owned());

    // 已声明 / 已探测的实况状态必须能被重扫保持：客户端声明的半态与分组键不会因为
    // 一次没有新线索的普通重扫而丢失。
    let current = sqlx::query_as::<_, (String, i64, Option<String>, Option<String>, Option<i64>)>(
        "SELECT live_role, live_embedded, live_group_key, live_partner_id, live_motion_duration_ms FROM media_assets WHERE id = ?1",
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?;
    let (current_role, current_embedded, current_key, current_partner, current_motion_duration) =
        current.unwrap_or_else(|| ("none".to_owned(), 0, None, None, None));
    let preserve_half_state =
        current_role == "still" && current_embedded == 0 && current_partner.is_none();

    // 内容标识优先于客户端声明的分组键：内容标识跨设备稳定，且扫描侧读到的也是它。
    // 若让声明键优先，同一段实况的两侧会落进不同键空间（一个 cid: 一个 lp:），永远配不上对。
    let own_key = probe.content_identifier.clone().or_else(|| {
        declared
            .group_key
            .as_deref()
            .and_then(normalize_declared_key)
    });
    let embedded = !is_video && (declared.embedded || probe.embedded.is_some());
    let now = now_millis();

    if embedded {
        let key = own_key.clone().or(current_key.clone());
        let changed = write_embedded_still(
            transaction,
            media_id,
            key.as_deref(),
            declared.motion_duration_ms.or(current_motion_duration),
            now,
        )
        .await?;
        return Ok(LiveOutcome {
            own_changed: changed,
            partner_id: None,
            is_live: true,
        });
    }

    // 类型相反的同伴：先按分组键（强），再按同目录同主名（辅助线索）。
    let by_key = match own_key.as_deref() {
        Some(key) => candidate_by_group_key(transaction, owner_user_id, !is_video, key).await?,
        None => None,
    };
    let partner = match by_key {
        Some(candidate) if candidate.media_id != media_id => Some(candidate),
        _ => {
            let (directory, stem) = split_directory_stem(path);
            let extensions = if is_video {
                IMAGE_EXTENSIONS
            } else {
                VIDEO_EXTENSIONS
            };
            candidate_by_sibling_path(
                transaction,
                owner_user_id,
                !is_video,
                &storage_id,
                &directory,
                &stem,
                extensions,
            )
            .await?
            .filter(|candidate| candidate.media_id != media_id)
        }
    };

    // 对手必须未被别人占用；已指向本媒体视为同一配对（重扫幂等）。
    let partner = partner.filter(|candidate| {
        candidate.live_partner_id.is_none()
            || candidate.live_partner_id.as_deref() == Some(media_id)
    });

    let Some(partner) = partner else {
        // 静态帧侧声明过「有动态部分」时保留半态标记（动态部分尚未入库，FR-4）。
        let half_declared =
            !is_video && (declared.role.as_deref() == Some("still") || preserve_half_state);
        let role = if half_declared { "still" } else { "none" };
        let key = if half_declared {
            own_key.clone().or(current_key.clone())
        } else {
            own_key.clone()
        };
        let motion_duration = if half_declared {
            declared.motion_duration_ms.or(current_motion_duration)
        } else {
            None
        };
        let changed = sqlx::query(
            r#"
            UPDATE media_assets
            SET live_role = ?1, live_embedded = 0, live_group_key = ?2,
                live_partner_id = NULL, live_partner_hash = NULL,
                live_motion_duration_ms = ?3
            WHERE id = ?4
              AND (live_role != ?1 OR live_embedded != 0 OR live_group_key IS NOT ?2
                   OR live_partner_id IS NOT NULL OR live_partner_hash IS NOT NULL
                   OR live_motion_duration_ms IS NOT ?3)
            "#,
        )
        .bind(role)
        .bind(key.as_deref())
        .bind(motion_duration)
        .bind(media_id)
        .execute(&mut *transaction)
        .await?
        .rows_affected()
            > 0;
        if changed {
            bump_version(transaction, media_id, now).await?;
        }
        record_probe_done(transaction, media_id).await?;
        return Ok(LiveOutcome {
            own_changed: changed,
            partner_id: None,
            is_live: role != "none",
        });
    };

    let (still_id, motion_id) = if is_video {
        (partner.media_id.clone(), media_id.to_owned())
    } else {
        (media_id.to_owned(), partner.media_id.clone())
    };
    let own_hash = query_content_hash(transaction, media_id).await?;
    let (still_hash, motion_hash) = if is_video {
        (partner.content_hash.clone(), own_hash)
    } else {
        (own_hash, partner.content_hash.clone())
    };
    let motion_duration = if is_video {
        query_duration(transaction, media_id).await?
    } else {
        partner.duration_ms
    }
    .or(declared.motion_duration_ms);
    let group_key = own_key
        .clone()
        .or_else(|| partner.live_group_key.clone())
        .or(current_key.clone());

    let changed = write_pair(
        transaction,
        &PairWrite {
            still_id: &still_id,
            motion_id: &motion_id,
            group_key: group_key.as_deref(),
            motion_duration,
            still_hash: still_hash.as_deref(),
            motion_hash: motion_hash.as_deref(),
        },
        now,
    )
    .await?;
    Ok(LiveOutcome {
        own_changed: changed > 0,
        partner_id: Some(partner.media_id),
        is_live: true,
    })
}

/// 探测记账：`live_probe_*` 不进投影、不占版本号，因此不触发版本自增与变更事件。
/// 只有实况语义列（进 `livePhoto` 载荷）变化才算可观测变化。
async fn record_probe_done(transaction: &mut SqliteConnection, media_id: &str) -> AppResult<()> {
    sqlx::query(
        "UPDATE media_assets SET live_probe_state = 'done', live_probe_version = ?1
         WHERE id = ?2 AND (live_probe_state != 'done' OR live_probe_version != ?1)",
    )
    .bind(PROBE_VERSION)
    .bind(media_id)
    .execute(&mut *transaction)
    .await?;
    Ok(())
}

/// 单文件动态照片：静态帧即完整的实况，不需要对手。
async fn write_embedded_still(
    transaction: &mut SqliteConnection,
    media_id: &str,
    group_key: Option<&str>,
    motion_duration_ms: Option<i64>,
    now: i64,
) -> AppResult<bool> {
    let changed = sqlx::query(
        r#"
        UPDATE media_assets
        SET live_role = 'still', live_embedded = 1, live_group_key = ?1,
            live_partner_id = NULL, live_partner_hash = NULL,
            live_motion_duration_ms = ?2
        WHERE id = ?3
          AND (live_role != 'still' OR live_embedded != 1 OR live_group_key IS NOT ?1
               OR live_partner_id IS NOT NULL OR live_partner_hash IS NOT NULL
               OR live_motion_duration_ms IS NOT ?2)
        "#,
    )
    .bind(group_key)
    .bind(motion_duration_ms)
    .bind(media_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected()
        > 0;
    if changed {
        bump_version(transaction, media_id, now).await?;
    }
    record_probe_done(transaction, media_id).await?;
    Ok(changed)
}

/// 两侧同时写入配对标记；返回发生变化的行数。
async fn write_pair(
    transaction: &mut SqliteConnection,
    pair: &PairWrite<'_>,
    now: i64,
) -> AppResult<usize> {
    let mut changed = 0_usize;
    if write_paired_row(
        transaction,
        pair.still_id,
        "still",
        pair.motion_id,
        pair.group_key,
        pair.motion_duration,
        pair.motion_hash,
        now,
    )
    .await?
    {
        changed += 1;
    }
    if write_paired_row(
        transaction,
        pair.motion_id,
        "motion",
        pair.still_id,
        pair.group_key,
        None,
        pair.still_hash,
        now,
    )
    .await?
    {
        changed += 1;
    }
    Ok(changed)
}

#[allow(clippy::too_many_arguments)]
async fn write_paired_row(
    transaction: &mut SqliteConnection,
    media_id: &str,
    role: &str,
    partner_id: &str,
    group_key: Option<&str>,
    motion_duration: Option<i64>,
    partner_hash: Option<&str>,
    now: i64,
) -> AppResult<bool> {
    let changed = sqlx::query(
        r#"
        UPDATE media_assets
        SET live_role = ?1, live_embedded = 0, live_group_key = ?2,
            live_partner_id = ?3, live_partner_hash = ?4, live_motion_duration_ms = ?5
        WHERE id = ?6
          AND (live_role != ?1 OR live_embedded != 0 OR live_group_key IS NOT ?2
               OR live_partner_id IS NOT ?3 OR live_partner_hash IS NOT ?4
               OR live_motion_duration_ms IS NOT ?5)
        "#,
    )
    .bind(role)
    .bind(group_key)
    .bind(partner_id)
    .bind(partner_hash)
    .bind(motion_duration)
    .bind(media_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected()
        > 0;
    if changed {
        bump_version(transaction, media_id, now).await?;
    }
    record_probe_done(transaction, media_id).await?;
    Ok(changed)
}

async fn query_content_hash(
    transaction: &mut SqliteConnection,
    media_id: &str,
) -> AppResult<Option<String>> {
    Ok(sqlx::query_scalar::<_, Option<String>>(
        "SELECT b.content_hash FROM media_assets a LEFT JOIN content_blobs b ON b.id = a.blob_id WHERE a.id = ?1",
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?
    .flatten())
}

async fn query_duration(
    transaction: &mut SqliteConnection,
    media_id: &str,
) -> AppResult<Option<i64>> {
    Ok(
        sqlx::query_scalar::<_, Option<i64>>("SELECT duration_ms FROM media_assets WHERE id = ?1")
            .bind(media_id)
            .fetch_optional(&mut *transaction)
            .await?
            .flatten(),
    )
}

async fn bump_version(
    transaction: &mut SqliteConnection,
    media_id: &str,
    now: i64,
) -> AppResult<()> {
    sqlx::query("UPDATE media_assets SET version = version + 1, updated_at = ?1 WHERE id = ?2")
        .bind(now)
        .bind(media_id)
        .execute(&mut *transaction)
        .await?;
    Ok(())
}

/// 重新尝试建立实况配对（恢复、重新入库等场景）。
///
/// 只做库内可判定的配对，不读文件、不覆盖已有分组键与半态；已有配对时保持不动。
pub(crate) async fn repair_pairing_tx(
    transaction: &mut SqliteConnection,
    media_id: &str,
    revision: i64,
    now: i64,
) -> AppResult<bool> {
    let row = sqlx::query_as::<_, (i64, String, Option<i64>)>(
        r#"
        SELECT a.is_video, l.normalized_path, a.owner_user_id
        FROM media_assets a
        INNER JOIN media_locations l ON l.media_asset_id = a.id AND l.hash_state = 'verified'
        WHERE a.id = ?1 AND a.identity_state = 'verified'
        ORDER BY l.normalized_path
        LIMIT 1
        "#,
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?;
    let Some((is_video, path, owner_user_id)) = row else {
        return Ok(false);
    };
    let outcome = apply_probe(
        transaction,
        media_id,
        &path,
        is_video == 1,
        owner_user_id,
        Some(&Probe::default()),
        &DeclaredLive::default(),
    )
    .await?;
    if let Some(partner_id) = outcome.partner_id.as_deref() {
        emit(transaction, revision, partner_id, now).await?;
    }
    Ok(outcome.own_changed || outcome.partner_id.is_some())
}

/// 记录探测失败（识别失败降级为普通媒体，`failed` 状态在下一次扫描可重试）。
pub(crate) async fn mark_probe_failed(
    transaction: &mut SqliteConnection,
    media_id: &str,
) -> AppResult<()> {
    sqlx::query(
        "UPDATE media_assets SET live_probe_state = 'failed', live_probe_version = ?1 WHERE id = ?2",
    )
    .bind(PROBE_VERSION)
    .bind(media_id)
    .execute(&mut *transaction)
    .await?;
    Ok(())
}

// ── 降级与收敛 ────────────────────────────────────────────────

/// 清空一段实况的标记（自身与对手），用于任一侧消失后的降级（FR-8）。
///
/// 只改实况列，不动身份、时间与其它元数据；标签、收藏、备份映射因此不受影响。
/// 返回实际发生变化的 media id。
pub(crate) async fn degrade_pair_tx(
    transaction: &mut SqliteConnection,
    media_id: &str,
    now: i64,
) -> AppResult<Vec<String>> {
    let row = sqlx::query_as::<_, (String, Option<String>)>(
        "SELECT live_role, live_partner_id FROM media_assets WHERE id = ?1",
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?;
    let Some((role, partner_id)) = row else {
        return Ok(Vec::new());
    };
    if role == "none" && partner_id.is_none() {
        return Ok(Vec::new());
    }
    let mut changed = Vec::new();
    if clear_live_columns(transaction, media_id, now).await? {
        changed.push(media_id.to_owned());
    }
    // 对手也可能反向指向自己；一并清空，避免留下半配对的孤儿标记。
    if let Some(partner_id) = partner_id
        && clear_live_columns(transaction, &partner_id, now).await?
    {
        changed.push(partner_id);
    }
    Ok(changed)
}

async fn clear_live_columns(
    transaction: &mut SqliteConnection,
    media_id: &str,
    now: i64,
) -> AppResult<bool> {
    let changed = sqlx::query(
        r#"
        UPDATE media_assets
        SET live_role = 'none', live_embedded = 0, live_group_key = NULL,
            live_partner_id = NULL, live_partner_hash = NULL, live_motion_duration_ms = NULL,
            live_probe_version = 0
        WHERE id = ?1
          AND (live_role != 'none' OR live_embedded != 0 OR live_group_key IS NOT NULL
               OR live_partner_id IS NOT NULL OR live_partner_hash IS NOT NULL
               OR live_motion_duration_ms IS NOT NULL)
        "#,
    )
    .bind(media_id)
    .execute(&mut *transaction)
    .await?
    .rows_affected()
        > 0;
    if changed {
        bump_version(transaction, media_id, now).await?;
    }
    Ok(changed)
}

/// 一次性历史收敛（FR-7）：为已分别索引的成对文件补配对，并清理失效配对。
///
/// 只做路径与库内数据可判定的部分（同目录同主名）；单文件动态照片与内容标识判定
/// 需要读文件，由扫描探测按 `live_probe_version` 增量完成。有界、幂等、可续跑。
pub async fn backfill_pairs(pool: &SqlitePool) -> anyhow::Result<usize> {
    let mut total = 0_usize;
    loop {
        let batch = sweep_degraded(pool).await?;
        if batch == 0 {
            break;
        }
        total += batch;
    }
    // 先让动态部分认到静态帧，剩余未配对的静态帧再自己找动态部分；两侧都会写，顺序不影响结果。
    for want_video in [true, false] {
        loop {
            let batch = pair_batch(pool, want_video).await?;
            if batch == 0 {
                break;
            }
            total += batch;
        }
    }
    Ok(total)
}

/// 清理指向不存在 / 未验证 / 未回指的实况标记。
async fn sweep_degraded(pool: &SqlitePool) -> anyhow::Result<usize> {
    let rows = sqlx::query_as::<_, (String, Option<String>)>(
        r#"
        SELECT a.id, a.live_partner_id
        FROM media_assets a
        LEFT JOIN media_assets p ON p.id = a.live_partner_id
        WHERE a.live_role != 'none'
          AND (a.live_partner_id IS NULL
               OR p.id IS NULL
               OR p.identity_state != 'verified'
               OR p.live_partner_id IS NOT a.id)
        LIMIT 200
        "#,
    )
    .fetch_all(pool)
    .await?;
    if rows.is_empty() {
        return Ok(0);
    }
    let now = now_millis();
    let mut transaction = crate::db::begin_write(pool).await?;
    let revision = crate::sync::allocate_revision(&mut transaction).await?;
    let mut touched = 0_usize;
    for (media_id, _) in &rows {
        for id in degrade_pair_tx(&mut transaction, media_id, now).await? {
            emit(&mut transaction, revision, &id, now).await?;
            touched += 1;
        }
    }
    transaction.commit().await.context("live photo sweep")?;
    Ok(touched)
}

/// 为一侧未配对的媒体按「同目录同主名」补配对。
async fn pair_batch(pool: &SqlitePool, want_video: bool) -> anyhow::Result<usize> {
    let rows = sqlx::query_as::<_, (String, String, String, Option<i64>)>(
        r#"
        SELECT a.id, l.storage_id, l.normalized_path, a.owner_user_id
        FROM media_assets a
        INNER JOIN media_locations l ON l.media_asset_id = a.id
        WHERE a.identity_state = 'verified' AND a.is_video = ?1
          AND a.live_role = 'none' AND a.live_probe_version < ?2
          AND l.hash_state = 'verified'
        ORDER BY a.id
        LIMIT 200
        "#,
    )
    .bind(i64::from(want_video))
    .bind(PROBE_VERSION)
    .fetch_all(pool)
    .await?;
    if rows.is_empty() {
        return Ok(0);
    }
    // 当前行是动态部分时，候选同伴是静态帧，反之亦然。
    let partner_is_video = !want_video;
    let extensions = if partner_is_video {
        VIDEO_EXTENSIONS
    } else {
        IMAGE_EXTENSIONS
    };
    let now = now_millis();
    let mut transaction = crate::db::begin_write(pool).await?;
    let revision = crate::sync::allocate_revision(&mut transaction).await?;
    let mut touched = 0_usize;
    for (media_id, storage_id, path, owner_user_id) in &rows {
        let partner = {
            let (directory, stem) = split_directory_stem(path);
            candidate_by_sibling_path(
                &mut transaction,
                *owner_user_id,
                partner_is_video,
                storage_id,
                &directory,
                &stem,
                extensions,
            )
            .await?
        }
        .filter(|candidate| {
            candidate.media_id != *media_id
                && candidate.live_partner_id.is_none()
                && candidate.live_role == "none"
        });
        let Some(partner) = partner else {
            sqlx::query(
                "UPDATE media_assets SET live_probe_version = ?1 WHERE id = ?2 AND live_probe_version < ?1",
            )
            .bind(PROBE_VERSION)
            .bind(media_id)
            .execute(&mut *transaction)
            .await?;
            continue;
        };
        let (still_id, motion_id) = if want_video {
            (partner.media_id.clone(), media_id.to_owned())
        } else {
            (media_id.to_owned(), partner.media_id.clone())
        };
        let own_hash = query_content_hash(&mut transaction, media_id).await?;
        let (still_hash, motion_hash) = if want_video {
            (partner.content_hash.clone(), own_hash)
        } else {
            (own_hash, partner.content_hash.clone())
        };
        let motion_duration = if want_video {
            query_duration(&mut transaction, media_id).await?
        } else {
            partner.duration_ms
        };
        let changed = write_pair(
            &mut transaction,
            &PairWrite {
                still_id: &still_id,
                motion_id: &motion_id,
                group_key: None,
                motion_duration,
                still_hash: still_hash.as_deref(),
                motion_hash: motion_hash.as_deref(),
            },
            now,
        )
        .await?;
        if changed > 0 {
            touched += changed;
            emit(&mut transaction, revision, &still_id, now).await?;
            emit(&mut transaction, revision, &motion_id, now).await?;
        }
        sqlx::query(
            "UPDATE media_assets SET live_probe_version = ?1 WHERE id = ?2 AND live_probe_version < ?1",
        )
        .bind(PROBE_VERSION)
        .bind(media_id)
        .execute(&mut *transaction)
        .await?;
    }
    transaction
        .commit()
        .await
        .context("live photo pair batch")?;
    Ok(touched)
}

pub(crate) async fn emit(
    transaction: &mut SqliteConnection,
    revision: i64,
    media_id: &str,
    now: i64,
) -> AppResult<()> {
    let owner = sqlx::query_scalar::<_, Option<i64>>(
        "SELECT owner_user_id FROM media_assets WHERE id = ?1",
    )
    .bind(media_id)
    .fetch_one(&mut *transaction)
    .await?;
    crate::users::append_media_upsert_change(transaction, revision, media_id, owner, now).await
}

/// 该媒体若是实况静态帧，返回其配对动态部分的媒体 id（删除级联用）。
///
/// 配对是服务端的派生态，整体性由服务端保证，客户端无需知道动态部分的媒体 id。
pub async fn paired_motion_id(
    pool: &SqlitePool,
    media_id: &str,
    owner_user_id: Option<i64>,
) -> anyhow::Result<Option<String>> {
    let row = sqlx::query_as::<_, (String, Option<String>)>(
        "SELECT live_role, live_partner_id FROM media_assets WHERE id = ?1 AND owner_user_id IS ?2",
    )
    .bind(media_id)
    .bind(owner_user_id)
    .fetch_optional(pool)
    .await?;
    Ok(row.and_then(|(role, partner)| (role == "still").then_some(partner).flatten()))
}

/// 单行实况载荷（`None` 表示普通媒体）。
pub(crate) async fn payload_json(
    transaction: &mut SqliteConnection,
    media_id: &str,
) -> AppResult<Option<serde_json::Value>> {
    let row = sqlx::query_as::<
        _,
        (
            String,
            i64,
            Option<String>,
            Option<String>,
            Option<String>,
            Option<i64>,
        ),
    >(
        "SELECT live_role, live_embedded, live_group_key, live_partner_id, live_partner_hash, live_motion_duration_ms FROM media_assets WHERE id = ?1",
    )
    .bind(media_id)
    .fetch_optional(&mut *transaction)
    .await?;
    let Some((role, embedded, group_key, partner_id, partner_hash, duration)) = row else {
        return Ok(None);
    };
    if role == "none" {
        return Ok(None);
    }
    Ok(Some(serde_json::json!({
        "role": role,
        "embedded": embedded == 1,
        "groupKey": group_key,
        "partnerMediaId": partner_id,
        "partnerContentHash": partner_hash,
        "motionDurationMs": duration,
    })))
}

fn find_bytes(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || haystack.len() < needle.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 最小合法 MP4 头部：探测只确认尾部是视频容器盒，不解码。
    fn minimal_mp4() -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.extend_from_slice(&24u32.to_be_bytes());
        bytes.extend_from_slice(b"ftyp");
        bytes.extend_from_slice(b"isom");
        bytes.extend_from_slice(&512u32.to_be_bytes());
        bytes.extend_from_slice(b"isomiso2");
        bytes.extend_from_slice(&8u32.to_be_bytes());
        bytes.extend_from_slice(b"moov");
        bytes
    }

    fn xmp_packet(image_len: usize, video_len: usize, flag: &str) -> String {
        format!(
            r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" Camera:MotionPhoto="{flag}" Camera:MotionPhotoVersion="1"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Length="{image_len}" Item:Semantic="Primary" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Length="{video_len}" Item:Semantic="MotionPhoto" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"#
        )
    }

    /// 元素形式的 MotionPhoto 标记（部分写入方使用）。
    fn xmp_packet_element_form(image_len: usize, video_len: usize) -> String {
        format!(
            r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Camera:MotionPhoto>1</Camera:MotionPhoto><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Length="{image_len}" Item:Semantic="Primary" Item:Padding="0"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Length="{video_len}" Item:Semantic="MotionPhoto" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"#
        )
    }

    fn tiny_jpeg() -> Vec<u8> {
        let image = image::RgbImage::from_pixel(4, 3, image::Rgb([10, 20, 30]));
        let mut bytes = Vec::new();
        image::DynamicImage::ImageRgb8(image)
            .write_to(
                &mut std::io::Cursor::new(&mut bytes),
                image::ImageFormat::Jpeg,
            )
            .expect("encode jpeg");
        bytes
    }

    /// 在 SOI 之后插入 XMP APP1 段（JPEG 的动态照片元数据就在这里）。
    fn insert_app1(jpeg: &[u8], xmp: &str) -> Vec<u8> {
        let mut payload = b"http://ns.adobe.com/xap/1.0/".to_vec();
        payload.push(0);
        payload.extend_from_slice(xmp.as_bytes());
        let mut out = Vec::new();
        out.extend_from_slice(&jpeg[..2]);
        out.push(0xFF);
        out.push(0xE1);
        out.extend_from_slice(&u16::try_from(payload.len() + 2).unwrap().to_be_bytes());
        out.extend_from_slice(&payload);
        out.extend_from_slice(&jpeg[2..]);
        out
    }

    fn embedded_fixture(flag: &str, declared_video_len: Option<usize>, video: &[u8]) -> Vec<u8> {
        let jpeg = tiny_jpeg();
        let declared = declared_video_len.unwrap_or(video.len());
        let xmp = xmp_packet(jpeg.len(), declared, flag);
        let mut file = insert_app1(&jpeg, &xmp);
        file.extend_from_slice(video);
        file
    }

    fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&u32::try_from(body.len() + 8).unwrap().to_be_bytes());
        out.extend_from_slice(kind);
        out.extend_from_slice(body);
        out
    }

    fn meta_keys_value(key: &str, value: &str) -> Vec<u8> {
        let mut keys_body = vec![0, 0, 0, 0];
        keys_body.extend_from_slice(&1u32.to_be_bytes());
        keys_body.extend_from_slice(&u32::try_from(key.len() + 8).unwrap().to_be_bytes());
        keys_body.extend_from_slice(&0u32.to_be_bytes());
        keys_body.extend_from_slice(key.as_bytes());
        let keys = boxed(b"keys", &keys_body);

        let mut data_body = vec![0, 0, 0, 1];
        data_body.extend_from_slice(&[0, 0, 0, 0]);
        data_body.extend_from_slice(value.as_bytes());
        let data = boxed(b"data", &data_body);
        let ilst = boxed(b"ilst", &boxed(&1u32.to_be_bytes(), &data));

        let mut meta_body = vec![0, 0, 0, 0];
        meta_body.extend_from_slice(&keys);
        meta_body.extend_from_slice(&ilst);
        // `quicktime_content_identifier` 接收的是 moov 的盒体（`read_moov_content_identifier`
        // 按盒头跳读后传进来），所以这里返回盒体而不是完整盒。
        boxed(b"meta", &meta_body)
    }

    #[test]
    fn detects_embedded_motion_photo() {
        let video = minimal_mp4();
        let file = embedded_fixture("1", None, &video);
        let motion = parse_embedded_motion(&file, file.len() as u64).expect("应识别为动态照片");
        assert_eq!(motion.video_length, video.len() as u64);
        assert_eq!(motion.mime.as_deref(), Some("video/mp4"));
        let offset = motion.offset_in(file.len() as u64).expect("偏移可定位");
        assert_eq!(offset as usize, file.len() - video.len());
        assert!(looks_like_video_container(&file[offset as usize..]));
        // 同样要能通过真实文件路径探测。
        assert!(
            super::extract_xmp(&file).is_some(),
            "XMP 必须能从文件头分离出来"
        );
    }

    #[test]
    fn supports_element_form_motion_flag() {
        let video = minimal_mp4();
        let jpeg = tiny_jpeg();
        let mut file = insert_app1(&jpeg, &xmp_packet_element_form(jpeg.len(), video.len()));
        file.extend_from_slice(&video);
        assert!(parse_embedded_motion(&file, file.len() as u64).is_some());
    }

    #[test]
    fn rejects_motion_flag_without_real_video() {
        let video = minimal_mp4();
        // 标记为 0：即使尾部有视频字节也不按动态照片处理。
        let flagged_off = embedded_fixture("0", None, &video);
        assert!(parse_embedded_motion(&flagged_off, flagged_off.len() as u64).is_none());

        // 声明长度超出文件：说明视频实际不存在。
        let oversize = embedded_fixture("1", Some(video.len() * 100), &video);
        assert!(parse_embedded_motion(&oversize, oversize.len() as u64).is_none());

        // 尾部不是视频容器盒：不认。
        let garbage = embedded_fixture("1", None, &[0x11_u8; 64]);
        let motion = parse_embedded_motion(&garbage, garbage.len() as u64).expect("声明可解析");
        let offset = motion.offset_in(garbage.len() as u64).unwrap() as usize;
        assert!(!looks_like_video_container(&garbage[offset..]));
    }

    #[test]
    fn reads_quicktime_content_identifier() {
        let moov = meta_keys_value(
            "com.apple.quicktime.content.identifier",
            "A1B2C3D4-1111-2222-3333-444455556666",
        );
        assert_eq!(
            quicktime_content_identifier(&moov).as_deref(),
            Some("cid:a1b2c3d4-1111-2222-3333-444455556666")
        );

        let other = meta_keys_value("com.apple.quicktime.make", "Apple");
        assert_eq!(quicktime_content_identifier(&other), None);
        assert_eq!(quicktime_content_identifier(&minimal_mp4()), None);
    }

    #[test]
    fn splits_directory_and_stem_preserving_case() {
        // 路径查找大小写敏感，主名保持原始大小写；大小写差异由 stem_variants 覆盖。
        assert_eq!(
            split_directory_stem("library/uploads/2024/01/IMG_1234.HEIC"),
            ("library/uploads/2024/01".to_owned(), "IMG_1234".to_owned())
        );
        assert_eq!(
            split_directory_stem("IMG_1234.MOV"),
            (String::new(), "IMG_1234".to_owned())
        );
        assert_eq!(
            split_directory_stem("library/无扩展名"),
            ("library".to_owned(), "无扩展名".to_owned())
        );
        assert_eq!(
            sibling_path("library/a", "IMG_1", "mov"),
            "library/a/IMG_1.mov"
        );
        assert_eq!(sibling_path("", "img_1", "mov"), "img_1.mov");
        assert_eq!(stem_variants("IMG_1234"), vec!["IMG_1234", "img_1234"]);
        assert_eq!(stem_variants("mixed_1"), vec!["mixed_1", "MIXED_1"]);
    }

    /// 合成 Apple MakerNote（与真实 iPhone 12 样本逐字节同构：`Apple iOS\0` + 版本 +
    /// 自带字节序 + IFD，值偏移相对 MakerNote 起点）。
    fn apple_makernote(identifier: &str) -> Vec<u8> {
        let mut blob = b"Apple iOS\0".to_vec();
        blob.extend_from_slice(&[0x00, 0x01]);
        blob.extend_from_slice(b"MM");
        let mut value = identifier.as_bytes().to_vec();
        value.push(0);
        let value_offset = 14 + 2 + 12 + 4;
        blob.extend_from_slice(&1u16.to_be_bytes());
        blob.extend_from_slice(&MAKERNOTE_TAG_CONTENT_IDENTIFIER.to_be_bytes());
        blob.extend_from_slice(&2u16.to_be_bytes());
        blob.extend_from_slice(&u32::try_from(value.len()).unwrap().to_be_bytes());
        blob.extend_from_slice(&u32::try_from(value_offset).unwrap().to_be_bytes());
        blob.extend_from_slice(&0u32.to_be_bytes());
        blob.extend_from_slice(&value);
        blob
    }

    /// 把 Apple MakerNote 包进最小 TIFF（IFD0 -> ExifIFD -> MakerNote，MM 字节序）。
    fn apple_exif_tiff(identifier: &str) -> Vec<u8> {
        let maker_note = apple_makernote(identifier);
        let mut tiff = Vec::new();
        tiff.extend_from_slice(b"MM");
        tiff.extend_from_slice(&42u16.to_be_bytes());
        tiff.extend_from_slice(&8u32.to_be_bytes());
        // IFD0：1 个条目（ExifIFD 指针），ExifIFD 起点 = 8 + 2 + 12 + 4 = 26
        tiff.extend_from_slice(&1u16.to_be_bytes());
        tiff.extend_from_slice(&TIFF_TAG_EXIF_IFD.to_be_bytes());
        tiff.extend_from_slice(&4u16.to_be_bytes());
        tiff.extend_from_slice(&1u32.to_be_bytes());
        tiff.extend_from_slice(&26u32.to_be_bytes());
        tiff.extend_from_slice(&0u32.to_be_bytes());
        // ExifIFD：1 个条目（MakerNote），MakerNote 起点 = 26 + 2 + 12 + 4 = 44
        tiff.extend_from_slice(&1u16.to_be_bytes());
        tiff.extend_from_slice(&TIFF_TAG_MAKER_NOTE.to_be_bytes());
        tiff.extend_from_slice(&7u16.to_be_bytes());
        tiff.extend_from_slice(&u32::try_from(maker_note.len()).unwrap().to_be_bytes());
        tiff.extend_from_slice(&44u32.to_be_bytes());
        tiff.extend_from_slice(&0u32.to_be_bytes());
        tiff.extend_from_slice(&maker_note);
        tiff
    }

    #[test]
    fn reads_apple_makernote_content_identifier() {
        let mut file = vec![0u8; 8];
        file.extend_from_slice(b"Exif\0\0");
        file.extend_from_slice(&apple_exif_tiff("3E11E513-3BE9-49A1-8ADA-C64B2C2EEEC7"));
        assert_eq!(
            image_content_identifier(&file).as_deref(),
            Some("cid:3e11e513-3be9-49a1-8ada-c64b2c2eeec7")
        );
        // 无 Exif、无 MakerNote、非 Apple MakerNote 都不猜。
        assert_eq!(image_content_identifier(b"plain image bytes"), None);
        let mut non_apple = b"Exif\0\0".to_vec();
        non_apple.extend_from_slice(b"MM\0*\0\0\0\x08");
        assert_eq!(image_content_identifier(&non_apple), None);
    }

    /// 用真实 iPhone 实况样本复核两侧内容标识一致（样本不入库：含个人位置 EXIF）。
    #[test]
    fn real_iphone_live_pair_shares_content_identifier() {
        let sample = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../tmp");
        let heic = std::fs::read(sample.join("IMG_0440.HEIC"));
        let mov = std::fs::read(sample.join("IMG_0440.MOV"));
        let (Ok(heic), Ok(mov)) = (heic, mov) else {
            eprintln!("跳过：tmp/ 下没有真实 iPhone 实况样本（HEIC + MOV 对）");
            return;
        };
        let from_still = image_content_identifier(&heic).expect("HEIC 侧应能解析出内容标识");
        let from_motion =
            quicktime_content_identifier_in_bytes(&mov).expect("MOV 侧应能解析出内容标识");
        assert_eq!(
            from_still, from_motion,
            "iOS 成对实况两侧必须解析出同一个内容标识"
        );
        assert!(from_still.starts_with(KEY_CONTENT_IDENTIFIER));
    }

    #[test]
    fn normalizes_declared_group_keys() {
        assert_eq!(
            normalize_declared_key("  ABC-123  ").as_deref(),
            Some("lp:abc-123")
        );
        assert_eq!(normalize_declared_key(""), None);
        assert_eq!(normalize_declared_key("   "), None);
        assert_eq!(normalize_declared_key(&"a".repeat(129)), None);
        assert_eq!(normalize_declared_key("bad\nkey"), None);
    }
}
