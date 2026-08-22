//! hyperss-core：HyperSS Rust 核心库。
//!
//! 模块划分：
//! - `capture`：截图帧接收、裁剪、编解码（`RawImage`）
//! - `stitch`：重叠检测、模板匹配 (NCC)、拼接、导出
//! - `project`：项目 / 图片元数据、命名规则
//! - `session`：截图会话状态机、草稿检查点、恢复扫描
//! - `storage`：SQLite (rusqlite) 本地索引 + 文件系统封装
//! - `config`：语言 / 主题 / 版本等配置持久化
//!
//! 通过 UniFFI 生成 Kotlin 绑定供 Android 层调用（`#[uniffi::export]`）。

pub mod capture;
pub mod config;
pub mod error;
pub mod project;
pub mod session;
pub mod stitch;
pub mod storage;

pub use config::{ConfigEntry, ConfigStore};
pub use error::{CoreError, CoreResult};
pub use project::{CaptureMode, ImageInfo, ProjectInfo};
pub use session::{
    SessionInfo, SessionManager, SessionProgress, SessionResumeAction, SessionRunner,
    SessionStatus,
};
pub use storage::{Storage, StorageStats};

pub const APP_NAME: &str = "HyperSS";

/// 当前核心库版本（与 `version.properties` 由发布流水线统一维护，
/// 此处暴露 crate 版本供 UI「关于」页展示）。
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// 双点取距标定（模式④ / 自定义步长标定）：
/// 对用户分别在起点、终点截取的两帧，求实际滚动像素距离。
///
/// 起点帧底部与终点帧顶部存在重叠，重叠高度决定用户滚动了多少，
/// 该值可直接作为固定步长模式的拼接偏移（`append_frame_fixed`）。
#[uniffi::export]
pub fn calibrate_offset(
    start_png: Vec<u8>,
    end_png: Vec<u8>,
    min_overlap: u32,
    max_overlap: u32,
) -> CoreResult<u32> {
    use crate::capture::RawImage;
    let start = RawImage::decode_png(&start_png)?;
    let end = RawImage::decode_png(&end_png)?;
    if start.width != end.width {
        return Err(CoreError::WidthMismatch {
            a: start.width,
            b: end.width,
        });
    }
    let overlap = crate::stitch::find_overlap_offset(&start, &end, min_overlap, max_overlap)
        .ok_or(CoreError::StitchLowConfidence { confidence: 0.0 })?;
    let scroll = start.height.saturating_sub(overlap.offset_px);
    if scroll < 1 {
        return Err(CoreError::Image("标定无效：起点与终点画面几乎一致，请先滚动".into()));
    }
    if scroll >= start.height {
        return Err(CoreError::Image("标定无效：两帧之间几乎无重叠，步长过大".into()));
    }
    Ok(scroll)
}

uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::RawImage;

    /// 二维伪随机图案大图，与 stitch 测试一致。
    fn stripe_image(w: u32, h: u32) -> RawImage {
        let hash2d = |y: u32, x: u32| -> u8 {
            let mut h = y.wrapping_mul(0x9E37_79B1).wrapping_add(x.wrapping_mul(0x85EB_CA6B));
            h ^= h >> 16;
            h = h.wrapping_mul(0x7FEB_352D);
            h ^= h >> 15;
            (h & 0xFF) as u8
        };
        let mut data = Vec::with_capacity((w * h * 4) as usize);
        for y in 0..h {
            for x in 0..w {
                let v = hash2d(y, x);
                data.extend_from_slice(&[v, v, v, 255]);
            }
        }
        RawImage::from_rgba(w, h, data).unwrap()
    }

    #[test]
    fn calibrate_measures_scroll_distance() {
        let big = stripe_image(64, 300);
        let start = big.crop(0, 0, 64, 100);
        let end = big.crop(0, 30, 64, 100);
        let scroll = calibrate_offset(
            start.encode_png().unwrap(),
            end.encode_png().unwrap(),
            5,
            95,
        )
        .unwrap();
        assert_eq!(scroll, 30);
    }

    #[test]
    fn calibrate_rejects_no_scroll() {
        let img = stripe_image(64, 100);
        let png = img.encode_png().unwrap();
        assert!(calibrate_offset(png.clone(), png, 5, 95).is_err());
    }
}