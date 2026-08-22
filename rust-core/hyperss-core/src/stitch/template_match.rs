//! 重叠区域模板匹配：归一化互相关 (NCC) + 降采样粗定位 + 窗口精修。
//!
//! 手机长截图场景中相邻两帧只存在纯竖直平移，因此采用轻量的一维偏移搜索
//! 而非全景拼接 (SIFT/RANSAC)。算法流程（对应开发文档 6.2）：
//! 1. 预提取 base 底部与 new 顶部各 `max_overlap` 行的灰度条带（列 1/2 采样）；
//! 2. 在候选偏移区间内逐一计算重叠区域 NCC（窗口置于重叠带 1/4 / 1/2 / 3/4
//!    处取最大值），取最优偏移；
//! 3. 置信度低于阈值判定为拼接失败（内容跳变，如弹窗/动态内容）；
//! 4. 在最优偏移附近 ±8px 用更宽的 64 行窗口精修，消除错位误差。
//!
//! 匹配窗口必须避开屏幕顶部/底部：真实 App 的标题栏与底部导航栏固定不动、
//! 不随内容滚动，若窗口落在重叠带两端（新帧第 0 行起 / 基准帧最后一行止），
//! 固定栏内容在任何偏移下都匹配不上，会导致所有真实 App 截图拼接失败。

use crate::capture::RawImage;
use crate::error::CoreError;

pub const CONFIDENCE_THRESHOLD: f32 = 0.80;
const REFINE_RANGE: u32 = 8;

/// 拼接匹配结果。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct OverlapResult {
    /// 最佳重叠高度（像素）。
    pub offset_px: u32,
    /// 匹配置信度 (0.0 ~ 1.0)。
    pub confidence: f32,
}

/// 预提取的灰度条带（行主序，列按 `col_step` 采样，行不采样保证偏移精度为 1px）。
/// 只提取一次、供全部候选偏移复用，避免逐偏移重复做 RGBA→灰度换算。
struct GrayBand {
    /// 采样后的每行列数。
    cols: usize,
    /// 实际提取的行数。
    rows: u32,
    data: Vec<u8>,
}

/// 提取 `img` 自 `row_start` 起 `rows` 行的灰度条带（BT.601 加权）。
/// 行列均从区域起点（第 0 行 / 第 0 列）开始按步进采样，保证两个被比较
/// 条带的采样相位一致（列相位一致是 NCC 可用的前提）。
fn gray_band(img: &RawImage, row_start: u32, rows: u32, col_step: u32) -> GrayBand {
    let cols = img.width.div_ceil(col_step) as usize;
    let mut data = Vec::with_capacity(cols * rows as usize);
    let mut row = 0u32;
    while row < rows && row_start + row < img.height {
        let mut col = 0u32;
        while col < img.width {
            let g = img.gray_at(row_start + row, col);
            // BT.601 结果范围为 [0, 255]，四舍五入存 u8 足够匹配使用
            data.push((g + 0.5) as u8);
            col += col_step;
        }
        row += 1;
    }
    GrayBand { cols, rows: row, data }
}

/// 两条带对齐行块的 NCC。`a` 自 `a_row` 起、`b` 自 `b_row` 起，各取 `window` 行。
/// 单遍 f64 累加计算（n·Σab−Σa·Σb 形式），避免 f32 大数组求和的精度损失。
fn band_window_ncc(a: &GrayBand, a_row: u32, b: &GrayBand, b_row: u32, window: u32) -> f32 {
    if a.cols == 0 || a.cols != b.cols {
        return 0.0;
    }
    let cols = a.cols;
    let avail = (a.rows - a_row).min(b.rows - b_row);
    let w = window.min(avail);
    if w == 0 {
        return 0.0;
    }
    let n = (w as usize) * cols;
    let sa = &a.data[(a_row as usize) * cols..][..n];
    let sb = &b.data[(b_row as usize) * cols..][..n];
    let (mut sum_a, mut sum_b, mut aa, mut bb, mut ab) = (0f64, 0f64, 0f64, 0f64, 0f64);
    for i in 0..n {
        let x = sa[i] as f64;
        let y = sb[i] as f64;
        sum_a += x;
        sum_b += y;
        aa += x * x;
        bb += y * y;
        ab += x * y;
    }
    let nn = n as f64;
    let num = nn * ab - sum_a * sum_b;
    let da = nn * aa - sum_a * sum_a;
    let db = nn * bb - sum_b * sum_b;
    let denom = (da.max(0.0) * db.max(0.0)).sqrt();
    if denom < 1e-9 {
        0.0
    } else {
        (num / denom) as f32
    }
}

/// 在候选偏移 `offset` 下计算重叠带的多窗口 NCC。
///
/// 语义：若真实重叠高度为 `offset`，则 `base` 的 [H-offset, H) 行内容与
/// `new` 的 [0, offset) 行内容一致——但屏幕顶部/底部的固定栏除外（它们
/// 不随内容滚动，且 offset>0 时不可能对齐）。因此窗口取重叠带 1/4 / 1/2 /
/// 3/4 三个位置的最大 NCC，既避开两端的固定栏，也容忍个别窗口落在纯色
/// 低纹理区域。
///
/// `base_band` 为 base 底部 `hi` 行（条带行 0 对应屏幕行 H-hi），
/// `new_band` 为 new 顶部 `hi` 行（条带行 0 对应屏幕行 0）。
fn match_at_offset(
    base_band: &GrayBand,
    new_band: &GrayBand,
    hi: u32,
    offset: u32,
    window: u32,
) -> f32 {
    if offset == 0 || offset > hi {
        return 0.0;
    }
    let w = window.min(offset);
    let usable = offset - w;
    let mut best = f32::NEG_INFINITY;
    for i in 1..=3u32 {
        let k = usable * i / 4;
        // base 重叠带顶行 = 屏幕行 H-offset（条带内行号 hi-offset），再加 k
        let a_row = hi - offset + k;
        let score = band_window_ncc(base_band, a_row, new_band, k, w);
        if score > best {
            best = score;
        }
    }
    best
}

/// 粗定位：逐像素遍历候选偏移，水平 1/2 采样、固定窗口 16 行控制成本。
/// 逐像素遍历保证真实偏移（任意值）都会被覆盖，避免步进采样漏检。
fn coarse_search(base: &GrayBand, new: &GrayBand, hi: u32, lo: u32) -> Option<(f32, u32)> {
    let mut best: Option<(f32, u32)> = None;
    for o in lo..=hi {
        let score = match_at_offset(base, new, hi, o, 16);
        if best.map_or(true, |(s, _)| score > s) {
            best = Some((score, o));
        }
    }
    best
}

/// 精修：在 `approx` 附近 ±range 像素逐像素搜索，窗口加宽到 64 行。
/// `hi` 为条带高度（等于搜索偏移上限），精修范围不得越过它。
fn refine_search(base: &GrayBand, new: &GrayBand, hi: u32, approx: u32, range: u32) -> (f32, u32) {
    let mut best: Option<(f32, u32)> = None;
    let lo = approx.saturating_sub(range).max(1);
    let upper = (approx + range).min(hi);
    for o in lo..=upper {
        let score = match_at_offset(base, new, hi, o, 64);
        if best.map_or(true, |(s, _)| score > s) {
            best = Some((score, o));
        }
    }
    best.unwrap_or((0.0, approx))
}

/// 在 `base` 底部与 `new_frame` 顶部之间寻找最佳重叠偏移。
/// 返回 `None` 表示匹配失败（置信度不足，说明画面跳变过大）。
pub fn find_overlap_offset(
    base: &RawImage,
    new_frame: &RawImage,
    min_overlap: u32,
    max_overlap: u32,
) -> Option<OverlapResult> {
    if base.width != new_frame.width || base.height == 0 || new_frame.height == 0 {
        return None;
    }
    let hi = max_overlap.min(base.height).min(new_frame.height);
    let lo = min_overlap.min(hi);
    if hi == 0 || lo == 0 {
        return None;
    }

    // 预提取两侧灰度条带：base 底部 hi 行、new 顶部 hi 行（列 1/2 采样）。
    // 粗定位与精修共用同一对条带：行方向全采样保证偏移精度，列降采样控成本。
    let base_band = gray_band(base, base.height - hi, hi, 2);
    let new_band = gray_band(new_frame, 0, hi, 2);

    let (score, offset) = coarse_search(&base_band, &new_band, hi, lo)?;
    if score < CONFIDENCE_THRESHOLD {
        return None;
    }
    let (refined_score, refined_offset) = refine_search(&base_band, &new_band, hi, offset, REFINE_RANGE);
    if refined_score < CONFIDENCE_THRESHOLD {
        return None;
    }
    Some(OverlapResult {
        offset_px: refined_offset,
        confidence: refined_score,
    })
}

/// 垂直拼接：把 `new` 以其顶部 `offset` 行与 `base` 底部重叠，合成一张图。
/// 两图宽度必须一致。
pub fn stitch_vertical(base: &RawImage, new: &RawImage, offset: u32) -> crate::error::CoreResult<RawImage> {
    if base.width != new.width {
        return Err(CoreError::WidthMismatch {
            a: base.width,
            b: new.width,
        });
    }
    if offset > new.height {
        return Err(CoreError::Image(format!(
            "重叠偏移 {} 超出新帧高度 {}",
            offset, new.height
        )));
    }
    let out_h = base.height + new.height - offset;
    let row_bytes = (base.width as usize) * 4;
    let mut out = Vec::with_capacity((out_h as usize) * row_bytes);
    out.extend_from_slice(&base.data);
    out.extend_from_slice(&new.data[(offset as usize) * row_bytes..]);
    RawImage::from_rgba(base.width, out_h, out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capture::RawImage;

    /// 合成一张二维伪随机图案测试图：灰度由 (y, x) 经整数哈希确定。
    /// 注意不能用线性函数（如 mod 素数），否则对 NCC 是退化输入。
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

    /// 模拟滚动：截取大图从 `scroll` 行开始的窗口作为“新帧”。
    fn window_of(img: &RawImage, scroll: u32, h: u32) -> RawImage {
        img.crop(0, scroll, img.width, h)
    }

    #[test]
    fn band_ncc_perfect_match_is_high() {
        // 同一条带、同一行块的窗口应完全相关（哈希图案每行不同，
        // 行号不同则内容不同、NCC 应接近 0）
        let big = stripe_image(64, 512);
        let band = gray_band(&big, 0, 512, 2);
        let s = band_window_ncc(&band, 100, &band, 100, 16);
        assert!((s - 1.0).abs() < 1e-4, "相同窗口 NCC 应为 1.0，实际 {s}");
        let s2 = band_window_ncc(&band, 100, &band, 301, 16);
        assert!(s2.abs() < 0.5, "不同行窗口 NCC 应接近 0，实际 {s2}");
    }

    #[test]
    fn find_overlap_on_stripe() {
        let big = stripe_image(64, 512);
        let first = big.crop(0, 0, 64, 200);
        // 滚动 60px → 两帧重叠高度 = 200 - 60 = 140
        let second = window_of(&big, 60, 200);
        let res = find_overlap_offset(&first, &second, 10, 190).expect("应匹配成功");
        assert_eq!(res.offset_px, 140);
        assert!(res.confidence > 0.95, "置信度 {} 应很高", res.confidence);
    }

    /// 真实 App 场景回归测试：顶部标题栏与底部导航栏固定不动，仅中间内容
    /// 滚动。旧实现把匹配窗口固定在重叠带顶部（新帧第 0 行起），固定栏内容
    /// 在任何偏移下都无法与滚动内容对上，导致所有含固定栏的 App 拼接失败。
    #[test]
    fn find_overlap_with_fixed_header_footer() {
        let w = 64;
        let header = 30u32;
        let footer = 30u32;
        let frame_h = 200u32;
        let content_h = frame_h - header - footer;
        let doc = stripe_image(w, 600);

        // 合成一帧：固定栏用与滚动无关的横向图案，内容区取自 doc
        let frame_at = |scroll: u32| -> RawImage {
            let mut data = Vec::with_capacity((w * frame_h * 4) as usize);
            for _ in 0..header {
                for x in 0..w {
                    let v = (x * 7 % 251) as u8;
                    data.extend_from_slice(&[v, v, v, 255]);
                }
            }
            for y in 0..content_h {
                for x in 0..w {
                    let v = doc.gray_at(scroll + y, x) as u8;
                    data.extend_from_slice(&[v, v, v, 255]);
                }
            }
            for _ in 0..footer {
                for x in 0..w {
                    let v = (x * 11 % 241) as u8;
                    data.extend_from_slice(&[v, v, v, 255]);
                }
            }
            RawImage::from_rgba(w, frame_h, data).unwrap()
        };

        let first = frame_at(0);
        let second = frame_at(60);
        let res = find_overlap_offset(&first, &second, 10, 190)
            .expect("含固定栏的相邻帧应能匹配（窗口避开两端固定栏）");
        assert_eq!(res.offset_px, 140);
        assert!(res.confidence > 0.9, "置信度 {} 应很高", res.confidence);
    }

    #[test]
    fn find_overlap_rejects_unrelated() {
        let mut noise = RawImage::from_rgba(64, 200, vec![0u8; 64 * 200 * 4]).unwrap();
        for i in 0..noise.data.len() {
            noise.data[i] = ((i as u32).wrapping_mul(2654435761) >> 24) as u8; // 伪随机
        }
        let res = find_overlap_offset(&noise, &stripe_image(64, 200), 10, 190);
        assert!(res.is_none(), "内容不相关应匹配失败");
    }

    #[test]
    fn stitch_vertical_combines_correctly() {
        let big = stripe_image(64, 200);
        let a = big.crop(0, 0, 64, 100);
        let b = window_of(&big, 40, 100);
        let out = stitch_vertical(&a, &b, 60).unwrap();
        assert_eq!(out.width, 64);
        assert_eq!(out.height, 140);
        // 拼接后第 100 行应等于原图第 100 行（b 的第 60 行）
        let expect_gray = big.gray_at(100, 0);
        let actual_gray = out.gray_at(100, 0);
        assert!((expect_gray - actual_gray).abs() < 1.0);
    }

    #[test]
    fn stitch_width_mismatch_errors() {
        let a = stripe_image(64, 100);
        let b = stripe_image(80, 100);
        assert!(stitch_vertical(&a, &b, 40).is_err());
    }
}