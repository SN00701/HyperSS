//! stitch 模块：重叠检测、模板匹配、拼接与导出。

pub mod template_match;

pub use template_match::{find_overlap_offset, stitch_vertical, OverlapResult};