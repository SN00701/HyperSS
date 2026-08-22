//! 会话状态机：`capture_sessions.status` 的五种状态与合法迁移路径。
//!
//! 对应开发文档 10.1 定义：
//! - `in_progress`（进行中）
//! - `soft_paused`（软暂停：切后台/锁屏/系统弹窗打断，可一键继续）
//! - `hard_interrupted`（硬中断：无障碍/悬浮窗权限被收回、进程被杀、存储异常）
//! - `completed`（已完成，草稿转为正式图片）
//! - `discarded`（已丢弃，草稿删除）
//!
//! 任何状态变更必须先经 `validate_transition` 校验，防止状态不一致。

/// 会话状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionStatus {
    InProgress,
    SoftPaused,
    HardInterrupted,
    Completed,
    Discarded,
}

impl SessionStatus {
    pub fn is_terminal(self) -> bool {
        matches!(self, SessionStatus::Completed | SessionStatus::Discarded)
    }

    pub fn as_str(self) -> &'static str {
        match self {
            SessionStatus::InProgress => "in_progress",
            SessionStatus::SoftPaused => "soft_paused",
            SessionStatus::HardInterrupted => "hard_interrupted",
            SessionStatus::Completed => "completed",
            SessionStatus::Discarded => "discarded",
        }
    }

    pub fn from_str(s: &str) -> Option<SessionStatus> {
        match s {
            "in_progress" => Some(SessionStatus::InProgress),
            "soft_paused" => Some(SessionStatus::SoftPaused),
            "hard_interrupted" => Some(SessionStatus::HardInterrupted),
            "completed" => Some(SessionStatus::Completed),
            "discarded" => Some(SessionStatus::Discarded),
            _ => None,
        }
    }
}

/// 状态机校验。非法迁移返回带上下文的错误。
pub fn validate_transition(from: SessionStatus, to: SessionStatus) -> Result<(), String> {
    use SessionStatus::*;
    let ok = match (from, to) {
        // 进行中 → 可被暂停 / 中断 / 完成 / 丢弃
        (InProgress, SoftPaused | HardInterrupted | Completed | Discarded) => true,
        // 软暂停 → 一键继续 / 硬中断（能力再次被收回）/ 完成 / 丢弃
        (SoftPaused, InProgress | HardInterrupted | Completed | Discarded) => true,
        // 硬中断 → 重新授权后恢复草稿 / 保存现状 / 丢弃
        (HardInterrupted, InProgress | Completed | Discarded) => true,
        // 终态不可再迁移
        _ => false,
    };
    if ok {
        Ok(())
    } else {
        Err(format!(
            "非法的会话状态迁移: {:?} → {:?}",
            from, to
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use SessionStatus::*;

    #[test]
    fn legal_transitions() {
        assert!(validate_transition(InProgress, SoftPaused).is_ok());
        assert!(validate_transition(InProgress, HardInterrupted).is_ok());
        assert!(validate_transition(InProgress, Completed).is_ok());
        assert!(validate_transition(InProgress, Discarded).is_ok());
        assert!(validate_transition(SoftPaused, InProgress).is_ok());
        assert!(validate_transition(SoftPaused, Completed).is_ok());
        assert!(validate_transition(SoftPaused, Discarded).is_ok());
        assert!(validate_transition(HardInterrupted, InProgress).is_ok());
        assert!(validate_transition(HardInterrupted, Completed).is_ok());
        assert!(validate_transition(HardInterrupted, Discarded).is_ok());
    }

    #[test]
    fn illegal_transitions() {
        assert!(validate_transition(Completed, InProgress).is_err());
        assert!(validate_transition(Completed, Discarded).is_err());
        assert!(validate_transition(Discarded, SoftPaused).is_err());
        assert!(validate_transition(InProgress, InProgress).is_err());
        assert!(validate_transition(SoftPaused, SoftPaused).is_err());
    }

    #[test]
    fn terminal_flags() {
        assert!(Completed.is_terminal());
        assert!(Discarded.is_terminal());
        assert!(!InProgress.is_terminal());
        assert!(!SoftPaused.is_terminal());
        assert!(!HardInterrupted.is_terminal());
    }

    #[test]
    fn status_serde() {
        for s in [InProgress, SoftPaused, HardInterrupted, Completed, Discarded] {
            assert_eq!(SessionStatus::from_str(s.as_str()), Some(s));
        }
        assert_eq!(SessionStatus::from_str("bogus"), None);
    }
}