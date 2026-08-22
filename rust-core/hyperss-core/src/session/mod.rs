//! session 模块：截图会话状态机、草稿检查点与恢复扫描。
//!
//! 对应开发文档第 10 章。一次"录制器"任务对应 `capture_sessions` 一条记录，
//! 支持软暂停/硬中断/检查点恢复/安全上限自动结束等完整生命周期。

pub mod state_machine;

use std::sync::{Arc, Mutex};

use rusqlite::{params, OptionalExtension};

use crate::capture::RawImage;
use crate::error::{CoreError, CoreResult};
use crate::project::{CaptureMode, ImageInfo};
use crate::stitch;
use crate::storage::Storage;
use state_machine::validate_transition;

pub use state_machine::SessionStatus;

/// 安全上限（开发文档 10.2 场景⑩，可在设置中调整）。
pub const MAX_FRAMES: i64 = 300;
pub const MAX_HEIGHT_PX: i64 = 16000;
/// 进程异常判定阈值（秒）：in_progress 超过该时长未更新视为被杀死。
pub const STALE_IN_PROGRESS_SECS: i64 = 30;
/// 孤儿草稿保留天数。
pub const ORPHAN_DRAFT_DAYS: i64 = 7;

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionInfo {
    pub id: i64,
    pub project_id: i64,
    pub mode: CaptureMode,
    pub status: SessionStatus,
    pub interrupt_reason: Option<String>,
    pub frame_count: i64,
    pub draft_path: Option<String>,
    pub started_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SessionProgress {
    pub session_id: i64,
    pub frame_count: i64,
    pub composite_width: i64,
    pub composite_height: i64,
    /// 最后一次成功拼接的重叠偏移（首帧为 0）。
    pub last_append_offset: u32,
    /// 最后一次匹配的置信度（固定步长模式为 1.0）。
    pub confidence: f32,
    /// 自动模式检测到内容到底（连续 2 帧无变化）。
    pub is_content_end_detected: bool,
    /// 达到安全上限（帧数 / 拼接高度）。
    pub is_safety_limit_reached: bool,
}

/// 草稿恢复对话框的三个操作分支（开发文档 10.4）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SessionResumeAction {
    /// 以草稿为基础继续截图
    Continue,
    /// 按当前草稿内容直接保存为完成的图片
    SaveAsIs,
    /// 丢弃草稿
    Discard,
}

fn row_to_session(row: &rusqlite::Row<'_>) -> rusqlite::Result<SessionInfo> {
    let status_str: String = row.get(3)?;
    let status = SessionStatus::from_str(&status_str)
        .ok_or_else(|| rusqlite::Error::InvalidColumnType(3, "status".into(), rusqlite::types::Type::Text))?;
    let mode = CaptureMode::try_from(row.get::<_, i64>(2)?).map_err(|e| {
        rusqlite::Error::FromSqlConversionFailure(2, rusqlite::types::Type::Integer, Box::new(e))
    })?;
    Ok(SessionInfo {
        id: row.get(0)?,
        project_id: row.get(1)?,
        mode,
        status,
        interrupt_reason: row.get(4)?,
        frame_count: row.get(5)?,
        draft_path: row.get(6)?,
        started_at: row.get(7)?,
        updated_at: row.get(8)?,
    })
}

const SESSION_COLS: &str = "id, project_id, mode, status, interrupt_reason, frame_count, draft_path, started_at, updated_at";

fn read_session(conn: &rusqlite::Connection, id: i64) -> CoreResult<SessionInfo> {
    conn.query_row(
        &format!("SELECT {SESSION_COLS} FROM capture_sessions WHERE id = ?1"),
        params![id],
        row_to_session,
    )
    .optional()?
    .ok_or(CoreError::SessionNotFound { id })
}

fn update_status(
    storage: &Storage,
    session_id: i64,
    to: SessionStatus,
    reason: Option<String>,
) -> CoreResult<SessionInfo> {
    let conn = storage.conn();
    let cur = read_session(&conn, session_id)?;
    validate_transition(cur.status, to)
        .map_err(CoreError::InvalidTransition)?;
    conn.execute(
        "UPDATE capture_sessions SET status = ?1, interrupt_reason = ?2, updated_at = ?3 WHERE id = ?4",
        params![to.as_str(), reason, Storage::now_secs(), session_id],
    )?;
    read_session(&conn, session_id)
}

/// 会话管理：创建 / 查询 / 恢复 / 清理。
#[derive(uniffi::Object)]
pub struct SessionManager {
    storage: Arc<Storage>,
}

#[uniffi::export]
impl SessionManager {
    #[uniffi::constructor]
    pub fn new(storage: Arc<Storage>) -> Arc<SessionManager> {
        Arc::new(SessionManager { storage })
    }

    /// 新建录制器任务（开发文档 7.2）。
    pub fn create_session(&self, project_id: i64, mode: CaptureMode) -> CoreResult<SessionInfo> {
        let conn = self.storage.conn();
        // 校验项目存在
        let exists: Option<i64> = conn
            .query_row("SELECT id FROM projects WHERE id = ?1", params![project_id], |r| r.get(0))
            .optional()?;
        if exists.is_none() {
            return Err(CoreError::ProjectNotFound { id: project_id });
        }
        let now = Storage::now_secs();
        conn.execute(
            "INSERT INTO capture_sessions (project_id, mode, status, interrupt_reason, frame_count, draft_path, started_at, updated_at)
             VALUES (?1, ?2, 'in_progress', NULL, 0, NULL, ?3, ?3)",
            params![project_id, mode as i64, now],
        )?;
        let id = conn.last_insert_rowid();
        read_session(&conn, id)
    }

    pub fn get_session(&self, session_id: i64) -> CoreResult<SessionInfo> {
        let conn = self.storage.conn();
        read_session(&conn, session_id)
    }

    /// 通用状态迁移（仅允许开发文档 10.1 定义的合法路径）。
    pub fn transition_status(
        &self,
        session_id: i64,
        target: SessionStatus,
        reason: Option<String>,
    ) -> CoreResult<SessionInfo> {
        update_status(&self.storage, session_id, target, reason)
    }

    /// 草稿恢复对话框的三个操作分支（开发文档 10.4）。
    pub fn resume_or_finalize(
        &self,
        session_id: i64,
        action: SessionResumeAction,
    ) -> CoreResult<Option<ImageInfo>> {
        let cur = {
            let conn = self.storage.conn();
            read_session(&conn, session_id)?
        };
        if cur.status.is_terminal() {
            return Err(CoreError::InvalidTransition(format!(
                "会话已处于终态 {:?}",
                cur.status
            )));
        }
        match action {
            SessionResumeAction::Continue => {
                update_status(&self.storage, session_id, SessionStatus::InProgress, None)?;
                Ok(None)
            }
            SessionResumeAction::SaveAsIs => {
                let draft = self.storage.draft_path(session_id);
                if !draft.exists() {
                    return Err(CoreError::NoDraft);
                }
                let bytes = std::fs::read(&draft)?;
                let img = self.storage.save_image(
                    cur.project_id,
                    cur.mode,
                    Some("未完成长图".to_string()),
                    Some(session_id),
                    bytes,
                )?;
                let _ = std::fs::remove_file(&draft);
                update_status(&self.storage, session_id, SessionStatus::Completed, None)?;
                Ok(Some(img))
            }
            SessionResumeAction::Discard => {
                let draft = self.storage.draft_path(session_id);
                if draft.exists() {
                    let _ = std::fs::remove_file(&draft);
                }
                update_status(&self.storage, session_id, SessionStatus::Discarded, None)?;
                Ok(None)
            }
        }
    }

    /// 冷启动扫描：列出所有可恢复的未完成会话
    /// （软暂停 / 硬中断 / 超时的进行中）。
    pub fn list_recoverable_sessions(&self) -> CoreResult<Vec<SessionInfo>> {
        let conn = self.storage.conn();
        let now = Storage::now_secs();
        let mut stmt = conn.prepare(&format!(
            "SELECT {SESSION_COLS} FROM capture_sessions
             WHERE status IN ('soft_paused', 'hard_interrupted')
                OR (status = 'in_progress' AND ?1 - updated_at > ?2)
             ORDER BY updated_at DESC"
        ))?;
        let rows = stmt.query_map(params![now, STALE_IN_PROGRESS_SECS], row_to_session)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    /// 冷启动兜底：把超时未更新的 `in_progress` 改标为 `hard_interrupted`
    /// （对应开发文档 10.2 场景⑧：进程被杀）。返回改标数量。
    pub fn recover_orphaned_sessions(&self) -> CoreResult<i64> {
        let conn = self.storage.conn();
        let now = Storage::now_secs();
        let res = conn.execute(
            "UPDATE capture_sessions
             SET status = 'hard_interrupted', interrupt_reason = '进程异常终止，进度已保存至检查点', updated_at = ?1
             WHERE status = 'in_progress' AND ?1 - updated_at > ?2",
            params![now, STALE_IN_PROGRESS_SECS],
        )?;
        Ok(res as i64)
    }

    /// 清理超过保留期的孤儿草稿（默认 7 天未处理），返回清理数量。
    pub fn cleanup_stale_drafts(&self) -> CoreResult<i64> {
        let conn = self.storage.conn();
        let now = Storage::now_secs();
        let cutoff = now - ORPHAN_DRAFT_DAYS * 86400;
        let mut stmt = conn.prepare(&format!(
            "SELECT {SESSION_COLS} FROM capture_sessions
             WHERE status NOT IN ('completed', 'discarded') AND updated_at < ?1"
        ))?;
        let ids: Vec<i64> = stmt
            .query_map(params![cutoff], row_to_session)?
            .filter_map(|r| r.ok())
            .map(|s| s.id)
            .collect();
        for id in &ids {
            let draft = self.storage.draft_path(*id);
            if draft.exists() {
                let _ = std::fs::remove_file(&draft);
            }
            conn.execute(
                "UPDATE capture_sessions SET status = 'discarded', interrupt_reason = '孤儿草稿超期清理', updated_at = ?1 WHERE id = ?2",
                params![now, id],
            )?;
        }
        Ok(ids.len() as i64)
    }
}

/// 最近一次追加的状态快照，供 `progress()` 返回真实值（而非硬编码）。
#[derive(Debug, Default, Clone)]
struct AppendState {
    no_change_streak: u32,
    last_offset: u32,
    last_confidence: f32,
    is_content_end: bool,
}

/// 会话执行器：持有内存中的已拼接长图，负责逐帧拼接与检查点落盘。
#[derive(uniffi::Object)]
pub struct SessionRunner {
    storage: Arc<Storage>,
    session_id: i64,
    project_id: i64,
    mode: CaptureMode,
    /// UniFFI 对象方法仅接受 `&self`，故用内部可变性承载拼接状态。
    current: Mutex<Option<RawImage>>,
    append_state: Mutex<AppendState>,
}

#[uniffi::export]
impl SessionRunner {
    /// 启动（或恢复）一次会话执行。若会话处于硬中断状态需先经
    /// `SessionManager.resume_or_finalize(Continue)` 恢复为进行中。
    #[uniffi::constructor]
    pub fn start(storage: Arc<Storage>, session_id: i64) -> CoreResult<Arc<SessionRunner>> {
        let info = {
            let conn = storage.conn();
            let info = read_session(&conn, session_id)?;
            if info.status == SessionStatus::HardInterrupted {
                return Err(CoreError::InvalidTransition(
                    "会话处于硬中断状态，请先在草稿恢复对话框中选择\"继续\"".into(),
                ));
            }
            // 软暂停 → 自动恢复为进行中
            if info.status == SessionStatus::SoftPaused {
                // std Mutex 不可重入：update_status 内部会再次取 DB 锁，须先释放
                drop(conn);
                update_status(&storage, session_id, SessionStatus::InProgress, None)?;
            }
            info
        };
        // 加载已有检查点草稿
        let current = if storage.draft_path(session_id).exists() {
            let bytes = std::fs::read(storage.draft_path(session_id))?;
            Some(RawImage::decode_png(&bytes)?)
        } else {
            None
        };
        Ok(Arc::new(SessionRunner {
            storage,
            session_id,
            project_id: info.project_id,
            mode: info.mode,
            current: Mutex::new(current),
            append_state: Mutex::new(AppendState::default()),
        }))
    }

    /// 当前进度（供 Kotlin 侧展示帧数 / 尺寸 / 完成判定）。
    ///
    /// 锁顺序约定：全模块统一「先取 `current`（图像锁）、后取 DB 锁」，
    /// 或如本方法般逐个获取、绝不嵌套持有——追加路径在持有图像锁时会再取
    /// DB 锁，若此处反序嵌套（持 DB 锁再取图像锁）会构成 ABBA 死锁。
    pub fn progress(&self) -> CoreResult<SessionProgress> {
        let frame_count = {
            let conn = self.storage.conn();
            read_session(&conn, self.session_id)?.frame_count
        };
        let (w, h) = {
            let cur = self.current.lock().unwrap();
            cur.as_ref()
                .map(|i| (i.width as i64, i.height as i64))
                .unwrap_or((0, 0))
        };
        let state = self.append_state.lock().unwrap().clone();
        Ok(SessionProgress {
            session_id: self.session_id,
            frame_count,
            composite_width: w,
            composite_height: h,
            last_append_offset: state.last_offset,
            confidence: state.last_confidence,
            is_content_end_detected: state.is_content_end,
            is_safety_limit_reached: frame_count >= MAX_FRAMES || h >= MAX_HEIGHT_PX,
        })
    }

    /// 模式①②：通过重叠匹配自动定位偏移并拼接。
    pub fn append_frame_detected(
        &self,
        frame_png: Vec<u8>,
        min_overlap: u32,
        max_overlap: u32,
    ) -> CoreResult<SessionProgress> {
        let frame = RawImage::decode_png(&frame_png)?;
        let mut cur = self.current.lock().unwrap();
        if cur.is_none() {
            // 首帧：直接作为当前长图并落盘检查点。
            self.ensure_running()?;
            let png = frame.encode_png()?;
            self.storage
                .write_atomic(&self.storage.draft_path(self.session_id), &png)?;
            self.bump_frame_count()?;
            *cur = Some(frame);
            let (w, h) = {
                let img = cur.as_ref().unwrap();
                (img.width, img.height)
            };
            *self.append_state.lock().unwrap() = AppendState {
                no_change_streak: 0,
                last_offset: 0,
                last_confidence: 1.0,
                is_content_end: false,
            };
            drop(cur);
            return Ok(self.compose_progress(w, h, 0, 1.0, false));
        }

        let base = cur.as_ref().unwrap();
        let overlap = stitch::find_overlap_offset(base, &frame, min_overlap, max_overlap)
            .ok_or(CoreError::StitchLowConfidence { confidence: 0.0 })?;
        if overlap.confidence < stitch::template_match::CONFIDENCE_THRESHOLD {
            return Err(CoreError::StitchLowConfidence {
                confidence: overlap.confidence,
            });
        }
        let merged = stitch::stitch_vertical(base, &frame, overlap.offset_px)?;
        let end = overlap.offset_px >= frame.height && overlap.confidence > 0.999;
        // 检查点在图像锁内编码：追加只发生在单一捕获线程，不存在竞争，
        // 且避免 merged.clone() 带来的整图复制
        let png = merged.encode_png()?;
        let (w, h) = (merged.width, merged.height);
        *cur = Some(merged);
        drop(cur);
        self.persist_and_progress(png, w, h, overlap.offset_px, overlap.confidence, end)
    }

    /// 模式③④：按已知固定偏移直接裁剪拼接，不做重叠置信度判断。
    pub fn append_frame_fixed(
        &self,
        frame_png: Vec<u8>,
        expected_offset: u32,
    ) -> CoreResult<SessionProgress> {
        let frame = RawImage::decode_png(&frame_png)?;
        let mut cur = self.current.lock().unwrap();
        if cur.is_none() {
            self.ensure_running()?;
            let png = frame.encode_png()?;
            self.storage
                .write_atomic(&self.storage.draft_path(self.session_id), &png)?;
            self.bump_frame_count()?;
            *cur = Some(frame);
            let (w, h) = {
                let img = cur.as_ref().unwrap();
                (img.width, img.height)
            };
            *self.append_state.lock().unwrap() = AppendState {
                no_change_streak: 0,
                last_offset: 0,
                last_confidence: 1.0,
                is_content_end: false,
            };
            drop(cur);
            return Ok(self.compose_progress(w, h, 0, 1.0, false));
        }

        if expected_offset > frame.height {
            return Err(CoreError::Image(format!(
                "固定偏移 {} 超出帧高度 {}",
                expected_offset,
                frame.height
            )));
        }
        let base = cur.as_ref().unwrap();
        let merged = stitch::stitch_vertical(base, &frame, expected_offset)?;
        let png = merged.encode_png()?;
        let (w, h) = (merged.width, merged.height);
        *cur = Some(merged);
        drop(cur);
        self.persist_and_progress(png, w, h, expected_offset, 1.0, false)
    }

    /// 软暂停（切后台 / 锁屏 / 系统弹窗打断），对应场景③④⑤。
    pub fn pause(&self, reason: Option<String>) -> CoreResult<SessionInfo> {
        update_status(&self.storage, self.session_id, SessionStatus::SoftPaused, reason)
    }

    /// 硬中断（无障碍关闭 / 权限撤销 / 存储异常 / 进程被杀），对应场景⑥⑦⑨。
    pub fn interrupt(&self, reason: String) -> CoreResult<SessionInfo> {
        update_status(
            &self.storage,
            self.session_id,
            SessionStatus::HardInterrupted,
            Some(reason),
        )
    }

    /// 正常完成：生成最终长图写入项目并清理草稿（场景① / ⑩）。
    pub fn complete(&self) -> CoreResult<Option<ImageInfo>> {
        let info = self.finalize_image()?;
        update_status(&self.storage, self.session_id, SessionStatus::Completed, None)?;
        Ok(info)
    }

    /// 从硬中断/软暂停中以当前草稿内容保存为已完成图片（与 SaveAsIs 等价）。
    pub fn save_as_is(&self) -> CoreResult<Option<ImageInfo>> {
        let info = self.finalize_image()?;
        update_status(&self.storage, self.session_id, SessionStatus::Completed, None)?;
        Ok(info)
    }

    /// 丢弃：删除检查点草稿并置为已丢弃（场景② / ⑪）。
    pub fn discard(&self) -> CoreResult<()> {
        let draft = self.storage.draft_path(self.session_id);
        if draft.exists() {
            let _ = std::fs::remove_file(&draft);
        }
        update_status(&self.storage, self.session_id, SessionStatus::Discarded, None)?;
        Ok(())
    }
}

impl SessionRunner {
    fn ensure_running(&self) -> CoreResult<()> {
        let conn = self.storage.conn();
        let info = read_session(&conn, self.session_id)?;
        if info.status != SessionStatus::InProgress {
            return Err(CoreError::InvalidTransition(format!(
                "会话当前状态 {:?}，无法继续追加帧",
                info.status
            )));
        }
        Ok(())
    }

    /// 追加成功后：落盘检查点、刷新 DB 计数并返回进度。
    /// 调用方已在图像锁内完成 PNG 编码并写入 `self.current`。
    fn persist_and_progress(
        &self,
        checkpoint_png: Vec<u8>,
        w: u32,
        h: u32,
        offset: u32,
        confidence: f32,
        end: bool,
    ) -> CoreResult<SessionProgress> {
        self.storage
            .write_atomic(&self.storage.draft_path(self.session_id), &checkpoint_png)?;
        self.bump_frame_count()?;

        let end_detected = {
            let mut st = self.append_state.lock().unwrap();
            st.last_offset = offset;
            st.last_confidence = confidence;
            st.no_change_streak = if end { st.no_change_streak + 1 } else { 0 };
            st.is_content_end = st.no_change_streak >= 2;
            st.is_content_end
        };

        let frame_count = self.frame_count();
        Ok(SessionProgress {
            session_id: self.session_id,
            frame_count,
            composite_width: w as i64,
            composite_height: h as i64,
            last_append_offset: offset,
            confidence,
            is_content_end_detected: end_detected,
            is_safety_limit_reached: frame_count >= MAX_FRAMES || (h as i64) >= MAX_HEIGHT_PX,
        })
    }

    fn compose_progress(&self, w: u32, h: u32, offset: u32, confidence: f32, end: bool) -> SessionProgress {
        let frame_count = self.frame_count();
        SessionProgress {
            session_id: self.session_id,
            frame_count,
            composite_width: w as i64,
            composite_height: h as i64,
            last_append_offset: offset,
            confidence,
            is_content_end_detected: end,
            is_safety_limit_reached: frame_count >= MAX_FRAMES || (h as i64) >= MAX_HEIGHT_PX,
        }
    }

    fn frame_count(&self) -> i64 {
        let conn = self.storage.conn();
        read_session(&conn, self.session_id)
            .map(|s| s.frame_count)
            .unwrap_or(0)
    }

    fn bump_frame_count(&self) -> CoreResult<()> {
        let conn = self.storage.conn();
        conn.execute(
            "UPDATE capture_sessions SET frame_count = frame_count + 1, updated_at = ?1 WHERE id = ?2",
            params![Storage::now_secs(), self.session_id],
        )?;
        Ok(())
    }

    fn finalize_image(&self) -> CoreResult<Option<ImageInfo>> {
        let draft = self.storage.draft_path(self.session_id);
        let bytes = {
            let cur = self.current.lock().unwrap();
            match cur.as_ref() {
                Some(img) => img.encode_png()?,
                None => {
                    if draft.exists() {
                        std::fs::read(&draft)?
                    } else {
                        return Ok(None);
                    }
                }
            }
        };
        let info = self.storage.save_image(
            self.project_id,
            self.mode,
            None,
            Some(self.session_id),
            bytes,
        )?;
        if draft.exists() {
            let _ = std::fs::remove_file(&draft);
        }
        Ok(Some(info))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Storage;
    use crate::stitch::template_match::find_overlap_offset;
    use std::fs;

    fn temp_storage(name: &str) -> Arc<Storage> {
        let dir = std::env::temp_dir().join(format!("hyperss-sess-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        Arc::new(Storage::open(dir.to_string_lossy().into_owned()).unwrap())
    }

    /// 二维伪随机图案大图 + 滚动窗口，模拟真实滚动截图。
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
    fn session_full_lifecycle() {
        let storage = temp_storage("lifecycle");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("zc".into(), "测试".into()).unwrap();

        let s = mgr.create_session(p.id, CaptureMode::AutoScroll).unwrap();
        assert_eq!(s.status, SessionStatus::InProgress);
        assert_eq!(s.frame_count, 0);

        let runner = SessionRunner::start(storage.clone(), s.id).unwrap();
        let big = stripe_image(64, 500);
        let f1 = big.crop(0, 0, 64, 120);
        let f2 = big.crop(0, 40, 64, 120);
        let f3 = big.crop(0, 80, 64, 120);

        let pr1 = runner.append_frame_detected(f1.encode_png().unwrap(), 10, 110).unwrap();
        assert_eq!(pr1.frame_count, 1);
        assert_eq!(pr1.composite_height, 120);

        let pr2 = runner.append_frame_detected(f2.encode_png().unwrap(), 10, 110).unwrap();
        assert_eq!(pr2.frame_count, 2);
        assert_eq!(pr2.last_append_offset, 80);
        assert_eq!(pr2.composite_height, 160);

        // 中途切后台 → 软暂停
        let paused = runner.pause(Some("切到桌面".into())).unwrap();
        assert_eq!(paused.status, SessionStatus::SoftPaused);

        // 恢复后继续
        let runner2 = SessionRunner::start(storage.clone(), s.id).unwrap();
        let pr3 = runner2.append_frame_detected(f3.encode_png().unwrap(), 10, 110).unwrap();
        assert_eq!(pr3.frame_count, 3);
        assert_eq!(pr3.composite_height, 200);

        // 完成 → 生成图片并清理草稿
        let img = runner2.complete().unwrap().expect("应生成图片");
        assert_eq!(img.file_name, "zc0001.png");
        assert_eq!(img.width_px, 64);
        assert_eq!(img.height_px, 200);
        assert!(!storage.draft_path(s.id).exists());
        assert_eq!(mgr.get_session(s.id).unwrap().status, SessionStatus::Completed);
        storage.remove_test_artifact();
    }

    #[test]
    fn low_confidence_pauses_flow() {
        let storage = temp_storage("lowconf");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("z".into(), "z".into()).unwrap();
        let s = mgr.create_session(p.id, CaptureMode::AutoScroll).unwrap();
        let runner = SessionRunner::start(storage.clone(), s.id).unwrap();

        let noise = RawImage::from_rgba(64, 120, vec![0u8; 64 * 120 * 4]).unwrap();
        runner.append_frame_detected(noise.encode_png().unwrap(), 10, 110).unwrap();

        let other = RawImage::from_rgba(64, 120, vec![123u8; 64 * 120 * 4]).unwrap();
        let err = runner
            .append_frame_detected(other.encode_png().unwrap(), 10, 110)
            .unwrap_err();
        assert!(matches!(err, CoreError::StitchLowConfidence { .. }));

        // 保留已拼接进度
        assert_eq!(mgr.get_session(s.id).unwrap().frame_count, 1);
        assert!(storage.draft_path(s.id).exists());
        storage.remove_test_artifact();
    }

    #[test]
    fn fixed_offset_mode() {
        let storage = temp_storage("fixed");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("f".into(), "f".into()).unwrap();
        let s = mgr.create_session(p.id, CaptureMode::FixedStep).unwrap();
        let runner = SessionRunner::start(storage.clone(), s.id).unwrap();
        let big = stripe_image(64, 400);
        let f1 = big.crop(0, 0, 64, 100);
        let f2 = big.crop(0, 30, 64, 100);
        runner.append_frame_fixed(f1.encode_png().unwrap(), 0).unwrap();
        let pr = runner.append_frame_fixed(f2.encode_png().unwrap(), 70).unwrap();
        assert_eq!(pr.composite_height, 130);
        assert_eq!(pr.last_append_offset, 70);
        assert_eq!(pr.confidence, 1.0);
        storage.remove_test_artifact();
    }

    #[test]
    fn recover_orphans_and_resume() {
        let storage = temp_storage("orphan");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("o".into(), "o".into()).unwrap();
        let s = mgr.create_session(p.id, CaptureMode::Manual).unwrap();

        // 模拟进程被杀：把 updated_at 改到 1 小时前
        storage.conn().execute(
            "UPDATE capture_sessions SET updated_at = ?1 WHERE id = ?2",
            params![Storage::now_secs() - 3600, s.id],
        )
        .unwrap();

        assert_eq!(mgr.recover_orphaned_sessions().unwrap(), 1);
        let info = mgr.get_session(s.id).unwrap();
        assert_eq!(info.status, SessionStatus::HardInterrupted);
        assert!(info.interrupt_reason.is_some());

        // 硬中断恢复需先 Continue
        assert!(SessionRunner::start(storage.clone(), s.id).is_err());
        mgr.resume_or_finalize(s.id, SessionResumeAction::Continue).unwrap();
        let runner = SessionRunner::start(storage.clone(), s.id).unwrap();
        assert_eq!(mgr.get_session(s.id).unwrap().status, SessionStatus::InProgress);
        let _ = runner;

        // SaveAsIs：无草稿时应报错
        assert!(matches!(
            mgr.resume_or_finalize(s.id, SessionResumeAction::SaveAsIs).unwrap_err(),
            CoreError::NoDraft
        ));

        // Discard
        mgr.resume_or_finalize(s.id, SessionResumeAction::Discard).unwrap();
        assert_eq!(mgr.get_session(s.id).unwrap().status, SessionStatus::Discarded);
        storage.remove_test_artifact();
    }

    #[test]
    fn list_recoverable() {
        let storage = temp_storage("list");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("l".into(), "l".into()).unwrap();
        let a = mgr.create_session(p.id, CaptureMode::AutoScroll).unwrap();
        let _b = mgr.create_session(p.id, CaptureMode::Manual).unwrap();
        mgr.transition_status(a.id, SessionStatus::SoftPaused, Some("x".into())).unwrap();

        let recov = mgr.list_recoverable_sessions().unwrap();
        assert_eq!(recov.len(), 1);
        assert_eq!(recov[0].id, a.id);
        storage.remove_test_artifact();
    }

    #[test]
    fn safety_limit_auto_end_flag() {
        let storage = temp_storage("limit");
        let mgr = SessionManager::new(storage.clone());
        let p = storage.create_project("s".into(), "s".into()).unwrap();
        let s = mgr.create_session(p.id, CaptureMode::AutoScroll).unwrap();
        let runner = SessionRunner::start(storage.clone(), s.id).unwrap();
        // 伪造已到帧上限
        storage.conn().execute(
            "UPDATE capture_sessions SET frame_count = ?1 WHERE id = ?2",
            params![MAX_FRAMES - 1, s.id],
        )
        .unwrap();
        let frame = stripe_image(64, 100).crop(0, 0, 64, 100);
        let pr = runner.append_frame_detected(frame.encode_png().unwrap(), 10, 90).unwrap();
        assert!(pr.is_safety_limit_reached);
        storage.remove_test_artifact();
    }

    #[test]
    fn stitch_api_finds_overlap() {
        let big = stripe_image(64, 300);
        let a = big.crop(0, 0, 64, 100);
        let b = big.crop(0, 25, 64, 100);
        let r = find_overlap_offset(&a, &b, 5, 95).unwrap();
        assert_eq!(r.offset_px, 75);
    }
}