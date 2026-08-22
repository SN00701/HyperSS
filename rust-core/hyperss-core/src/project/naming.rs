//! 命名规则：项目前缀合法性校验 + 图片文件名生成。
//!
//! 规则（开发文档 7.1）：
//! - 文件名 = `{prefix}{seq:04}`，如 `zc0001.png`
//! - 序号 1~9999 严格自增，删除不回收
//! - 前缀允许中英文/数字，过滤文件系统非法字符 `/\:*?"<>|`

use crate::error::{CoreError, CoreResult};

pub const MAX_SEQ: i64 = 9999;
pub const MAX_PREFIX_LEN: usize = 16;

/// 过滤文件系统非法字符并校验前缀合法性。
pub fn sanitize_prefix(raw: &str) -> CoreResult<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(CoreError::InvalidPrefix("前缀不能为空".into()));
    }
    if trimmed.chars().count() > MAX_PREFIX_LEN {
        return Err(CoreError::InvalidPrefix(format!(
            "前缀长度不能超过 {MAX_PREFIX_LEN} 个字符"
        )));
    }
    let illegal: &[char] = &['/', '\\', ':', '*', '?', '"', '<', '>', '|'];
    if trimmed.chars().any(|c| {
        illegal.contains(&c) || c.is_control() || c.is_whitespace()
    }) {
        return Err(CoreError::InvalidPrefix(
            "前缀不能包含空白字符或 / \\ : * ? \" < > | 等非法字符".into(),
        ));
    }
    Ok(trimmed.to_string())
}

/// 生成项目内文件名，如 `(zc, 1) -> zc0001.png`。
pub fn format_file_name(prefix: &str, seq: i64) -> String {
    format!("{prefix}{seq:04}.png")
}

/// 序号推进：9999 之后视为用尽。
pub fn next_seq_checked(current: i64) -> CoreResult<i64> {
    if current > MAX_SEQ {
        return Err(CoreError::SequenceExhausted);
    }
    Ok(current)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn valid_prefixes() {
        assert_eq!(sanitize_prefix("zc").unwrap(), "zc");
        assert_eq!(sanitize_prefix(" 资料收集 ").unwrap(), "资料收集");
        assert_eq!(sanitize_prefix("ZC2026").unwrap(), "ZC2026");
    }

    #[test]
    fn invalid_prefixes() {
        assert!(sanitize_prefix("").is_err());
        assert!(sanitize_prefix("   ").is_err());
        assert!(sanitize_prefix("a/b").is_err());
        assert!(sanitize_prefix("a\\b").is_err());
        assert!(sanitize_prefix("a:b").is_err());
        assert!(sanitize_prefix("这个前缀实在是太长了不符合长度要求").is_err());
    }

    #[test]
    fn file_names() {
        assert_eq!(format_file_name("zc", 1), "zc0001.png");
        assert_eq!(format_file_name("资料", 9999), "资料9999.png");
    }

    #[test]
    fn sequence_exhaustion() {
        assert_eq!(next_seq_checked(100).unwrap(), 100);
        assert_eq!(next_seq_checked(9999).unwrap(), 9999);
        assert!(next_seq_checked(10000).is_err());
    }
}