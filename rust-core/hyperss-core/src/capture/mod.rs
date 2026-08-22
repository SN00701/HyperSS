//! capture 模块：截图帧的接收、裁剪与编解码。
//!
//! Kotlin 层（无障碍 `takeScreenshot()` / MediaProjection 兜底通道）把每一帧
//! 统一编码为 PNG 字节后交给 Rust 侧，Rust 侧在此解码为内存中的 `RawImage`
//! （RGBA8 行主序），并负责去除状态栏 / 导航栏等系统 UI 区域。

use std::io::Cursor;

use crate::error::{CoreError, CoreResult};

/// 一帧 RGBA8 位图，行主序，每像素 4 字节 (R,G,B,A)。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawImage {
    pub width: u32,
    pub height: u32,
    pub data: Vec<u8>,
}

impl RawImage {
    pub fn from_rgba(width: u32, height: u32, data: Vec<u8>) -> CoreResult<Self> {
        let expect = (width as usize) * (height as usize) * 4;
        if data.len() != expect {
            return Err(CoreError::Image(format!(
                "RGBA 数据长度不符: 期望 {expect} 字节, 实际 {}",
                data.len()
            )));
        }
        Ok(RawImage {
            width,
            height,
            data,
        })
    }

    /// 从 PNG 字节解码。
    pub fn decode_png(bytes: &[u8]) -> CoreResult<Self> {
        let img = image::load_from_memory(bytes)?.to_rgba8();
        let (w, h) = img.dimensions();
        RawImage::from_rgba(w, h, img.into_raw())
    }

    /// 编码为 PNG 字节（用于检查点草稿与最终导出，保证文字清晰度）。
    pub fn encode_png(&self) -> CoreResult<Vec<u8>> {
        let img = image::RgbaImage::from_raw(self.width, self.height, self.data.clone())
            .ok_or_else(|| CoreError::Image("RgbaImage 构造失败".into()))?;
        let mut buf = Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(img)
            .write_to(&mut buf, image::ImageFormat::Png)?;
        Ok(buf.into_inner())
    }

    /// 裁剪掉系统 UI 区域（状态栏 top 行、导航栏 bottom 行）。
    pub fn crop_system_ui(&mut self, top: u32, bottom: u32) {
        if top + bottom >= self.height {
            self.height = 0;
            self.data.clear();
            return;
        }
        let row_bytes = (self.width as usize) * 4;
        let start = (top as usize) * row_bytes;
        let keep = ((self.height - top - bottom) as usize) * row_bytes;
        self.data = self.data[start..start + keep].to_vec();
        self.height -= top + bottom;
    }

    /// 裁剪出子区域（不越界检查由调用方保证）。
    pub fn crop(&self, x: u32, y: u32, w: u32, h: u32) -> RawImage {
        let row_bytes = (self.width as usize) * 4;
        let mut out = Vec::with_capacity((w as usize) * (h as usize) * 4);
        for row in y..y + h {
            let start = (row as usize) * row_bytes + (x as usize) * 4;
            out.extend_from_slice(&self.data[start..start + (w as usize) * 4]);
        }
        RawImage {
            width: w,
            height: h,
            data: out,
        }
    }

    /// 获取 (row, col) 处的灰度值（ITU-R BT.601 加权），用于拼接匹配。
    pub fn gray_at(&self, row: u32, col: u32) -> f32 {
        let idx = ((row as usize) * (self.width as usize) + col as usize) * 4;
        let r = self.data[idx] as f32;
        let g = self.data[idx + 1] as f32;
        let b = self.data[idx + 2] as f32;
        0.299 * r + 0.587 * g + 0.114 * b
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid_rgba(w: u32, h: u32, r: u8, g: u8, b: u8, a: u8) -> RawImage {
        let data = vec![r, g, b, a].repeat((w as usize) * (h as usize));
        RawImage::from_rgba(w, h, data).unwrap()
    }

    #[test]
    fn png_roundtrip() {
        let img = solid_rgba(16, 8, 10, 200, 30, 255);
        let png = img.encode_png().unwrap();
        let back = RawImage::decode_png(&png).unwrap();
        assert_eq!(back, img);
    }

    #[test]
    fn crop_system_ui_removes_rows() {
        let mut img = solid_rgba(10, 100, 1, 2, 3, 255);
        img.crop_system_ui(20, 10);
        assert_eq!(img.height, 70);
    }

    #[test]
    fn crop_region() {
        let mut img = solid_rgba(10, 10, 5, 5, 5, 255);
        img.crop_system_ui(2, 2);
        let sub = img.crop(1, 1, 4, 3);
        assert_eq!(sub.width, 4);
        assert_eq!(sub.height, 3);
    }
}