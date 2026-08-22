//! project 模块：项目 / 图片 元数据管理与命名序号分配。
//!
//! 对外通过 `#[uniffi::export] impl Storage` 暴露，Kotlin 层经由
//! `Storage` 对象直接调用（见开发文档 7 章）。

pub mod naming;

use rusqlite::{params, OptionalExtension};

use crate::capture::RawImage;
use crate::error::{CoreError, CoreResult};
use crate::storage::Storage;
use crate::stitch;

pub use naming::{format_file_name, sanitize_prefix};

/// 四种截图模式（与 DB `images.capture_mode` / `capture_sessions.mode` 对应）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CaptureMode {
    /// ① 自动滚动长截图
    AutoScroll = 0,
    /// ② 手动滚动长截图
    Manual = 1,
    /// ③ 自定义步长滚动截图
    FixedStep = 2,
    /// ④ 双点取距滑动截图
    CalibratedDistance = 3,
}

impl TryFrom<i64> for CaptureMode {
    type Error = CoreError;
    fn try_from(v: i64) -> CoreResult<Self> {
        Ok(match v {
            0 => CaptureMode::AutoScroll,
            1 => CaptureMode::Manual,
            2 => CaptureMode::FixedStep,
            3 => CaptureMode::CalibratedDistance,
            _ => return Err(CoreError::Config(format!("未知截图模式枚举: {v}"))),
        })
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ProjectInfo {
    pub id: i64,
    pub prefix: String,
    pub display_name: String,
    pub next_seq: i64,
    pub image_count: i64,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImageInfo {
    pub id: i64,
    pub project_id: i64,
    pub seq: i64,
    pub file_name: String,
    /// 相对存储根目录的路径（如 `projects/zc/zc0001.png`）。
    pub file_path: String,
    pub alias: Option<String>,
    pub width_px: i64,
    pub height_px: i64,
    pub capture_mode: CaptureMode,
    pub session_id: Option<i64>,
    pub created_at: i64,
}

fn row_to_project(row: &rusqlite::Row<'_>) -> rusqlite::Result<ProjectInfo> {
    let id = row.get(0)?;
    let image_count = row.get(5)?;
    Ok(ProjectInfo {
        id,
        prefix: row.get(1)?,
        display_name: row.get(2)?,
        next_seq: row.get(3)?,
        image_count,
        created_at: row.get(4)?,
        updated_at: row.get(6)?,
    })
}

fn row_to_image(row: &rusqlite::Row<'_>) -> rusqlite::Result<ImageInfo> {
    Ok(ImageInfo {
        id: row.get(0)?,
        project_id: row.get(1)?,
        seq: row.get(2)?,
        file_name: row.get(3)?,
        file_path: row.get(4)?,
        alias: row.get(5)?,
        width_px: row.get(6)?,
        height_px: row.get(7)?,
        capture_mode: CaptureMode::try_from(row.get::<_, i64>(8)?).map_err(|e| {
            rusqlite::Error::FromSqlConversionFailure(
                8,
                rusqlite::types::Type::Integer,
                Box::new(e),
            )
        })?,
        session_id: row.get(9)?,
        created_at: row.get(10)?,
    })
}

#[uniffi::export]
impl Storage {
    /// 新建项目：前缀（唯一）+ 展示名。
    pub fn create_project(&self, prefix: String, display_name: String) -> CoreResult<ProjectInfo> {
        let prefix = sanitize_prefix(&prefix)?;
        let display_name = if display_name.trim().is_empty() {
            prefix.clone()
        } else {
            display_name.trim().to_string()
        };
        let conn = self.conn();
        let now = Storage::now_secs();
        let res = conn.execute(
            "INSERT INTO projects (prefix, display_name, next_seq, created_at, updated_at)
             VALUES (?1, ?2, 1, ?3, ?3)",
            params![prefix, display_name, now],
        );
        match res {
            Err(rusqlite::Error::SqliteFailure(e, _)) if e.code == rusqlite::ErrorCode::ConstraintViolation => {
                return Err(CoreError::PrefixExists { prefix });
            }
            Err(e) => return Err(e.into()),
            Ok(_) => {}
        }
        let id = conn.last_insert_rowid();
        drop(conn);
        Ok(ProjectInfo {
            id,
            prefix,
            display_name,
            next_seq: 1,
            image_count: 0,
            created_at: now,
            updated_at: now,
        })
    }

    pub fn list_projects(&self) -> CoreResult<Vec<ProjectInfo>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT p.id, p.prefix, p.display_name, p.next_seq, p.created_at,
                    (SELECT COUNT(*) FROM images i WHERE i.project_id = p.id) AS image_count,
                    p.updated_at
             FROM projects p
             ORDER BY p.updated_at DESC",
        )?;
        let rows = stmt.query_map([], row_to_project)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    /// 重命名项目展示名（不影响文件前缀）。
    pub fn rename_project(&self, id: i64, display_name: String) -> CoreResult<ProjectInfo> {
        let display_name = display_name.trim().to_string();
        if display_name.is_empty() {
            return Err(CoreError::Config("项目名称不能为空".into()));
        }
        let conn = self.conn();
        let now = Storage::now_secs();
        let n = conn.execute(
            "UPDATE projects SET display_name = ?1, updated_at = ?2 WHERE id = ?3",
            params![display_name, now, id],
        )?;
        if n == 0 {
            return Err(CoreError::ProjectNotFound { id });
        }
        drop(conn);
        self.get_project(id)
    }

    pub fn get_project(&self, id: i64) -> CoreResult<ProjectInfo> {
        let conn = self.conn();
        let row = conn
            .query_row(
                "SELECT p.id, p.prefix, p.display_name, p.next_seq, p.created_at,
                        (SELECT COUNT(*) FROM images i WHERE i.project_id = p.id) AS image_count,
                        p.updated_at
                 FROM projects p WHERE p.id = ?1",
                params![id],
                row_to_project,
            )
            .optional()?;
        row.ok_or(CoreError::ProjectNotFound { id })
    }

    pub fn delete_project(&self, id: i64) -> CoreResult<()> {
        let conn = self.conn();
        let prefix: Option<String> = conn
            .query_row("SELECT prefix FROM projects WHERE id = ?1", params![id], |r| {
                r.get(0)
            })
            .optional()?;
        let Some(prefix) = prefix else {
            return Err(CoreError::ProjectNotFound { id });
        };
        conn.execute("DELETE FROM projects WHERE id = ?1", params![id])?;
        // 级联已删除 images 记录，物理文件一并清理（ignore 掉 IO 失败以免阻塞）。
        let dir = self.project_dir(&prefix);
        let _ = std::fs::remove_dir_all(&dir);
        Ok(())
    }

    pub fn list_images(&self, project_id: i64) -> CoreResult<Vec<ImageInfo>> {
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT id, project_id, seq, file_name, file_path, alias, width_px, height_px,
                    capture_mode, session_id, created_at
             FROM images WHERE project_id = ?1
             ORDER BY seq ASC",
        )?;
        let rows = stmt.query_map(params![project_id], row_to_image)?;
        rows.collect::<rusqlite::Result<Vec<_>>>().map_err(Into::into)
    }

    /// 从 PNG 字节保存一张图片到项目并按规则分配序号。
    pub fn save_image(
        &self,
        project_id: i64,
        capture_mode: CaptureMode,
        alias: Option<String>,
        session_id: Option<i64>,
        png_bytes: Vec<u8>,
    ) -> CoreResult<ImageInfo> {
        let img = RawImage::decode_png(&png_bytes)?;
        let (width_px, height_px) = (img.width as i64, img.height as i64);

        // 先在短临界区里读取项目前缀与序号，释放 DB 锁后再做文件 IO，
        // 避免写盘期间阻塞其他线程的数据库访问
        let (prefix, next_seq) = {
            let conn = self.conn();
            let project: Option<(String, i64)> = conn
                .query_row(
                    "SELECT prefix, next_seq FROM projects WHERE id = ?1",
                    params![project_id],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            let Some((prefix, next_seq)) = project else {
                return Err(CoreError::ProjectNotFound { id: project_id });
            };
            (prefix, next_seq)
        };
        let seq = naming::next_seq_checked(next_seq)?;
        if seq > naming::MAX_SEQ {
            return Err(CoreError::SequenceExhausted);
        }
        let file_name = format_file_name(&prefix, seq);
        let rel_path = format!("projects/{}/{}", prefix, file_name);
        let abs_path = self.image_path(&prefix, &file_name);
        let now = Storage::now_secs();
        // 确保项目目录存在（首次保存时）。
        std::fs::create_dir_all(self.project_dir(&prefix))?;

        // 先写文件（事务外），再在事务内落库，避免 DB 提交失败残留空文件。
        self.write_atomic(&abs_path, &png_bytes)?;

        let conn = self.conn();
        let tx = conn.unchecked_transaction()?;
        tx.execute(
            "INSERT INTO images (project_id, seq, file_name, file_path, alias, width_px, height_px, capture_mode, session_id, created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            params![
                project_id,
                seq,
                file_name,
                rel_path,
                alias,
                width_px,
                height_px,
                capture_mode as i64,
                session_id,
                now
            ],
        )?;
        // 带原值校验的序号推进：若并发保存已把 next_seq 改走则本单作废回滚
        //（残留的孤儿文件符合"宁可多余文件、不让 DB 指向缺失文件"的取舍）
        let bumped = tx.execute(
            "UPDATE projects SET next_seq = ?1, updated_at = ?2 WHERE id = ?3 AND next_seq = ?4",
            params![seq + 1, now, project_id, next_seq],
        )?;
        if bumped == 0 {
            return Err(CoreError::Image(format!(
                "并发保存冲突：项目 {project_id} 的序号已被其他保存占用"
            )));
        }
        tx.commit()?;
        let image_id = conn.last_insert_rowid();
        drop(conn);
        Ok(ImageInfo {
            id: image_id,
            project_id,
            seq,
            file_name,
            file_path: rel_path,
            alias,
            width_px,
            height_px,
            capture_mode,
            session_id,
            created_at: now,
        })
    }

    pub fn rename_image_alias(&self, image_id: i64, alias: Option<String>) -> CoreResult<()> {
        let conn = self.conn();
        let changed = conn.execute(
            "UPDATE images SET alias = ?1 WHERE id = ?2",
            params![alias, image_id],
        )?;
        if changed == 0 {
            return Err(CoreError::ImageNotFound { id: image_id });
        }
        Ok(())
    }

    pub fn delete_image(&self, image_id: i64) -> CoreResult<()> {
        let conn = self.conn();
        let file_path: Option<String> = conn
            .query_row(
                "SELECT file_path FROM images WHERE id = ?1",
                params![image_id],
                |r| r.get(0),
            )
            .optional()?;
        let Some(file_path) = file_path else {
            return Err(CoreError::ImageNotFound { id: image_id });
        };
        conn.execute("DELETE FROM images WHERE id = ?1", params![image_id])?;
        let _ = std::fs::remove_file(self.root().join(&file_path));
        Ok(())
    }

    /// 批量删除，返回实际删除数量。
    pub fn delete_images(&self, image_ids: Vec<i64>) -> CoreResult<i64> {
        if image_ids.is_empty() {
            return Ok(0);
        }
        let conn = self.conn();
        let mut stmt = conn.prepare(
            "SELECT file_path FROM images WHERE id = ?1",
        )?;
        let paths: Vec<String> = {
            let mut out = Vec::new();
            for id in &image_ids {
                if let Some(p) = stmt.query_row(params![id], |r| r.get(0)).optional()? {
                    out.push(p);
                }
            }
            out
        };
        let placeholders = image_ids.iter().map(|_| "?").collect::<Vec<_>>().join(",");
        let sql = format!("DELETE FROM images WHERE id IN ({placeholders})");
        let deleted = conn.execute(&sql, rusqlite::params_from_iter(image_ids.iter()))?;
        for path in paths {
            let _ = std::fs::remove_file(self.root().join(&path));
        }
        Ok(deleted as i64)
    }

    /// 把两张已保存的图片按给定重叠偏移合并为一张新图并落盘（项目内合并）。
    /// 仅用于开发文档 5.3 提到的"合并相邻图片"辅助能力。
    ///
    /// `base_image_id` 为上方图，`lower_image_id` 为下方图：下方图的顶部
    /// `overlap_px` 行与上方图的底部重叠。
    pub fn merge_images(
        &self,
        base_image_id: i64,
        lower_image_id: i64,
        overlap_px: u32,
    ) -> CoreResult<ImageInfo> {
        let (base_rel, lower_rel, project_id, mode) = {
            let conn = self.conn();
            let base: Option<String> = conn
                .query_row("SELECT file_path FROM images WHERE id = ?1", params![base_image_id], |r| r.get(0))
                .optional()?;
            let lower: Option<String> = conn
                .query_row("SELECT file_path FROM images WHERE id = ?1", params![lower_image_id], |r| r.get(0))
                .optional()?;
            let (base, lower) = match (base, lower) {
                (None, _) => return Err(CoreError::ImageNotFound { id: base_image_id }),
                (_, None) => return Err(CoreError::ImageNotFound { id: lower_image_id }),
                (Some(a), Some(b)) => (a, b),
            };
            let row: Option<(i64, i64)> = conn
                .query_row("SELECT project_id, capture_mode FROM images WHERE id = ?1", params![base_image_id], |r| Ok((r.get(0)?, r.get(1)?)))
                .optional()?;
            let (project_id, mode) = row.ok_or(CoreError::ImageNotFound { id: base_image_id })?;
            (base, lower, project_id, mode)
        };
        let base_img = RawImage::decode_png(&std::fs::read(self.root().join(&base_rel))?)?;
        let lower_img = RawImage::decode_png(&std::fs::read(self.root().join(&lower_rel))?)?;
        let merged = stitch::stitch_vertical(&base_img, &lower_img, overlap_px)?;
        let png = merged.encode_png()?;
        self.save_image(project_id, CaptureMode::try_from(mode)?, Some("合并图".to_string()), None, png)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::Storage;
    use std::fs;

    fn temp_storage(name: &str) -> Storage {
        let dir = std::env::temp_dir().join(format!("hyperss-proj-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        Storage::open(dir.to_string_lossy().into_owned()).unwrap()
    }

    fn dummy_png(w: u32, h: u32) -> Vec<u8> {
        let img = crate::capture::RawImage::from_rgba(
            w,
            h,
            vec![255, 0, 0, 255].repeat((w * h) as usize),
        )
        .unwrap();
        img.encode_png().unwrap()
    }

    #[test]
    fn project_crud_and_seq() {
        let s = temp_storage("crud");
        let p = s.create_project("zc".into(), "取证记录".into()).unwrap();
        assert_eq!(p.prefix, "zc");
        assert_eq!(p.image_count, 0);
        assert_eq!(p.next_seq, 1);

        let img1 = s.save_image(p.id, CaptureMode::AutoScroll, None, None, dummy_png(10, 20)).unwrap();
        assert_eq!(img1.seq, 1);
        assert_eq!(img1.file_name, "zc0001.png");
        assert_eq!(img1.width_px, 10);

        let img2 = s.save_image(p.id, CaptureMode::Manual, Some("截图".into()), None, dummy_png(5, 5)).unwrap();
        assert_eq!(img2.seq, 2);

        // 删除不回收序号
        s.delete_image(img1.id).unwrap();
        let img3 = s.save_image(p.id, CaptureMode::FixedStep, None, None, dummy_png(3, 3)).unwrap();
        assert_eq!(img3.seq, 3);

        let imgs = s.list_images(p.id).unwrap();
        assert_eq!(imgs.len(), 2);
        assert_eq!(s.get_project(p.id).unwrap().next_seq, 4);

        // 重命名别名
        s.rename_image_alias(img3.id, Some("别名A".into())).unwrap();
        let renamed = s
            .list_images(p.id)
            .unwrap()
            .into_iter()
            .find(|i| i.id == img3.id)
            .unwrap();
        assert_eq!(renamed.alias.as_deref(), Some("别名A"));

        s.delete_project(p.id).unwrap();
        assert!(s.get_project(p.id).is_err());
        s.remove_test_artifact();
    }

    #[test]
    fn duplicate_prefix_rejected() {
        let s = temp_storage("dup");
        s.create_project("zc".into(), "a".into()).unwrap();
        let err = s.create_project("zc".into(), "b".into()).unwrap_err();
        assert!(matches!(err, CoreError::PrefixExists { .. }));
        s.remove_test_artifact();
    }

    #[test]
    fn seq_exhaustion() {
        let s = temp_storage("exhaust");
        let p = s.create_project("e".into(), "e".into()).unwrap();
        s.conn().execute("UPDATE projects SET next_seq = 10000 WHERE id = ?1", params![p.id]).unwrap();
        let err = s.save_image(p.id, CaptureMode::AutoScroll, None, None, dummy_png(1, 1)).unwrap_err();
        assert!(matches!(err, CoreError::SequenceExhausted));
        s.remove_test_artifact();
    }
}