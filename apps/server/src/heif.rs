//! HEIC/HEIF 支持：容器解析（尺寸）与外部解码器（缩略图）。
//!
//! 部署镜像内的 FFmpeg 5.1 不支持 HEIF（FFmpeg 7.0 起才支持），因此服务端解码
//! 走部署环境自带的能力：Linux 用 libheif 的 `heif-convert`，macOS 本地开发
//! 回退系统自带 `sips`。两者都缺失时缩略图降级为占位图，索引本身不受影响。
//! 尺寸不依赖外部工具，直接从 ISOBMFF 容器解析（`ispe`）。

use std::{
    path::{Path, PathBuf},
    process::Command as StdCommand,
    sync::OnceLock,
};

use anyhow::Context;
use tokio::{
    process::Command,
    time::{Duration, timeout},
};
use uuid::Uuid;

/// 单个 HEIC 解码的超时（NAS 上大图较慢）。
const DECODE_TIMEOUT: Duration = Duration::from_secs(20);

const HEIF_BRANDS: &[&[u8; 4]] = &[b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1"];

/// 是否为 HEIF 容器（`ftyp` 主 brand 或兼容 brand 命中）。
pub fn is_heif(bytes: &[u8]) -> bool {
    if bytes.len() < 12 || &bytes[4..8] != b"ftyp" {
        return false;
    }
    let mut brands: Vec<&[u8]> = vec![&bytes[8..12]];
    // 兼容 brand 从 ftyp 第 16 字节起，每 4 字节一个。
    let mut offset = 16;
    while offset + 4 <= bytes.len().min(64) {
        brands.push(&bytes[offset..offset + 4]);
        offset += 4;
    }
    if brands
        .iter()
        .any(|brand| *brand == b"avif" || *brand == b"avis")
    {
        return false;
    }
    brands
        .iter()
        .any(|brand| HEIF_BRANDS.iter().any(|known| *known == brand))
}

/// 主图尺寸：返回容器内面积最大的 `ispe`（缩略图 item 更小，不会被选中）。
pub fn dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    let mut best: Option<(u32, u32)> = None;
    collect_ispe(bytes, &mut best);
    best
}

fn collect_ispe(data: &[u8], best: &mut Option<(u32, u32)>) {
    let mut offset = 0_usize;
    while offset + 8 <= data.len() {
        let size =
            u32::from_be_bytes(data[offset..offset + 4].try_into().expect("4 bytes")) as usize;
        let kind: [u8; 4] = data[offset + 4..offset + 8].try_into().expect("4 bytes");
        let (header_len, box_len) = match size {
            0 => (8, data.len() - offset),
            1 => {
                if offset + 16 > data.len() {
                    return;
                }
                let large =
                    u64::from_be_bytes(data[offset + 8..offset + 16].try_into().expect("8 bytes"))
                        as usize;
                (16, large)
            }
            other => (8, other),
        };
        if box_len < header_len || offset + box_len > data.len() {
            return;
        }
        let body = &data[offset + header_len..offset + box_len];
        match &kind {
            b"ispe"
                // FullBox：version/flags(4) + width(4) + height(4)
                if body.len() >= 12 => {
                    let width = u32::from_be_bytes(body[4..8].try_into().expect("4 bytes"));
                    let height = u32::from_be_bytes(body[8..12].try_into().expect("4 bytes"));
                    if width > 0 && height > 0 {
                        let area = u64::from(width) * u64::from(height);
                        let replace = best
                            .map(|(w, h)| area > u64::from(w) * u64::from(h))
                            .unwrap_or(true);
                        if replace {
                            *best = Some((width, height));
                        }
                    }
                }
            b"meta"
                // FullBox 之后才是子 box
                if body.len() > 4 => {
                    collect_ispe(&body[4..], best);
                }
            b"iprp" | b"ipco" => collect_ispe(body, best),
            _ => {}
        }
        offset += box_len;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HeifTool {
    HeifConvert,
    Sips,
}

/// 探测可用的 HEIC 解码器（进程内只探测一次）。
///
/// `YOUYOU_HEIF_DECODER` 可显式指定：`none` 关闭解码（缩略图返回 503，客户端可
/// 回退到原图本地解码）、`heif-convert` / `sips` 指定工具、缺省或 `auto` 自动探测。
pub fn tool() -> Option<HeifTool> {
    static TOOL: OnceLock<Option<HeifTool>> = OnceLock::new();
    *TOOL.get_or_init(|| {
        match std::env::var("YOUYOU_HEIF_DECODER").as_deref() {
            Ok("none") => return None,
            Ok("heif-convert") => return Some(HeifTool::HeifConvert),
            Ok("sips") => return Some(HeifTool::Sips),
            _ => {}
        }
        for (tool, command, args) in [
            (HeifTool::HeifConvert, "heif-convert", vec!["+h"]),
            (HeifTool::Sips, "sips", vec!["--version"]),
        ] {
            // 能启动（不要求退出码为 0）即视为可用；NotFound 才算缺失。
            if StdCommand::new(command).args(&args).output().is_ok() {
                return Some(tool);
            }
        }
        None
    })
}

/// 把 HEIC 解码为 JPEG 字节（最长边不超过 `size`）。解码器缺失时返回错误，
/// 调用方降级为占位图。
pub async fn decode_thumbnail(source: &Path, size: u32) -> anyhow::Result<Vec<u8>> {
    let tool = tool().context("HEIC 解码器不可用（heif-convert/sips）")?;
    let temporary = temp_path();
    let result: anyhow::Result<()> = match tool {
        HeifTool::HeifConvert => {
            let output = timeout(
                DECODE_TIMEOUT,
                Command::new("heif-convert")
                    .arg("-q")
                    .arg("85")
                    .arg(source)
                    .arg(&temporary)
                    .output(),
            )
            .await
            .context("heif-convert 超时")??;
            if !output.status.success() {
                anyhow::bail!(
                    "heif-convert 失败：{}",
                    String::from_utf8_lossy(&output.stderr).trim()
                );
            }
            Ok(())
        }
        HeifTool::Sips => {
            let output = timeout(
                DECODE_TIMEOUT,
                Command::new("sips")
                    .args(["-s", "format", "jpeg", "-Z", &size.to_string()])
                    .arg(source)
                    .arg("--out")
                    .arg(&temporary)
                    .output(),
            )
            .await
            .context("sips 超时")??;
            if !output.status.success() {
                anyhow::bail!(
                    "sips 失败：{}",
                    String::from_utf8_lossy(&output.stderr).trim()
                );
            }
            Ok(())
        }
    };
    let bytes = match result {
        Ok(()) => tokio::fs::read(&temporary).await.map_err(Into::into),
        Err(error) => Err(error),
    };
    let _ = tokio::fs::remove_file(&temporary).await;
    let bytes = bytes?;
    if bytes.is_empty() {
        anyhow::bail!("HEIC 解码结果为空");
    }
    Ok(bytes)
}

fn temp_path() -> PathBuf {
    std::env::temp_dir().join(format!("youyou-heic-{}.jpg", Uuid::new_v4()))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 用 macOS sips 生成一个真实 HEIC 夹具（环境无 sips 时跳过该用例）。
    fn heic_fixture() -> Option<Vec<u8>> {
        let dir = tempfile::tempdir().ok()?;
        let png = dir.path().join("src.png");
        let heic = dir.path().join("src.heic");
        let image = image::RgbImage::from_pixel(8, 6, image::Rgb([200, 30, 90]));
        image::DynamicImage::ImageRgb8(image)
            .save_with_format(&png, image::ImageFormat::Png)
            .ok()?;
        let status = StdCommand::new("sips")
            .args(["-s", "format", "heic"])
            .arg(&png)
            .arg("--out")
            .arg(&heic)
            .output()
            .ok()?;
        if !status.status.success() {
            return None;
        }
        std::fs::read(&heic).ok()
    }

    #[test]
    fn parses_dimensions_from_real_heic() {
        let Some(bytes) = heic_fixture() else {
            eprintln!("跳过：当前环境没有 sips 生成 HEIC 夹具");
            return;
        };
        assert!(is_heif(&bytes), "sips 产物应被识别为 HEIF");
        assert_eq!(dimensions(&bytes), Some((8, 6)));
        assert!(!is_heif(b"not an image"));
        assert!(!is_heif(b"\0\0\0\x18ftypavif\0\0\0\0mif1avif"));
    }
}
