//! storage 模块：SQLite 本地索引 (rusqlite) + 文件系统封装。
//!
//! - `projects/`：正式项目图片目录
//! - `.drafts/`：进行中/中断会话的检查点草稿
//! - 数据库文件位于根目录 `hyperss.db`
//!
//! 所有写文件采用「先写临时文件 → fsync → 原子 rename」避免中断损坏。

use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;

use crate::error::CoreResult;

pub const DRAFTS_DIR: &str = ".drafts";
pub const PROJECTS_DIR: &str = "projects";

/// 全局存储句柄。Kotlin 侧在应用启动时调用 `Storage.open()` 创建并持有。
#[derive(uniffi::Object)]
pub struct Storage {
    conn: Mutex<Connection>,
    root: PathBuf,
}

impl Storage {
    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn conn(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap()
    }

    pub fn projects_dir(&self) -> PathBuf {
        self.root.join(PROJECTS_DIR)
    }

    pub fn drafts_dir(&self) -> PathBuf {
        self.root.join(DRAFTS_DIR)
    }

    /// 某项目的图片目录：`projects/{prefix}`。
    pub fn project_dir(&self, prefix: &str) -> PathBuf {
        self.projects_dir().join(prefix)
    }

    /// 项目内图片完整路径：`projects/{prefix}/{file_name}`。
    pub fn image_path(&self, prefix: &str, file_name: &str) -> PathBuf {
        self.project_dir(prefix).join(file_name)
    }

    /// 会话草稿路径：`.drafts/session_{id}.partial.png`。
    pub fn draft_path(&self, session_id: i64) -> PathBuf {
        self.drafts_dir().join(format!("session_{session_id}.partial.png"))
    }

    pub fn now_secs() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)
    }

    /// 原子写文件：写临时文件 → fsync → rename 覆盖。
    pub fn write_atomic(&self, path: &Path, bytes: &[u8]) -> CoreResult<()> {
        let tmp = path.with_extension("tmp");
        let mut f = File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        fs::rename(&tmp, path)?;
        Ok(())
    }

    fn ensure_dirs(&self) -> CoreResult<()> {
        fs::create_dir_all(self.root())?;
        fs::create_dir_all(self.projects_dir())?;
        fs::create_dir_all(self.drafts_dir())?;
        Ok(())
    }

    fn migrate(conn: &Connection) -> CoreResult<()> {
        conn.execute_batch(
            r#"
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;

            CREATE TABLE IF NOT EXISTS projects (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                prefix        TEXT NOT NULL UNIQUE,
                display_name  TEXT NOT NULL,
                next_seq      INTEGER NOT NULL DEFAULT 1,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS images (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id    INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                seq           INTEGER NOT NULL,
                file_name     TEXT NOT NULL,
                file_path     TEXT NOT NULL,
                alias         TEXT,
                width_px      INTEGER NOT NULL,
                height_px     INTEGER NOT NULL,
                capture_mode  INTEGER NOT NULL,
                session_id    INTEGER REFERENCES capture_sessions(id),
                created_at    INTEGER NOT NULL,
                UNIQUE(project_id, seq)
            );

            CREATE TABLE IF NOT EXISTS capture_sessions (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id        INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                mode              INTEGER NOT NULL,
                status            TEXT NOT NULL,
                interrupt_reason  TEXT,
                frame_count       INTEGER NOT NULL DEFAULT 0,
                draft_path        TEXT,
                started_at        INTEGER NOT NULL,
                updated_at        INTEGER NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_images_project ON images(project_id);
            CREATE INDEX IF NOT EXISTS idx_sessions_status ON capture_sessions(status);
            "#,
        )?;
        Ok(())
    }
}

#[uniffi::export]
impl Storage {
    #[uniffi::constructor]
    /// 打开（或初始化）位于 `root_dir` 的存储。
    pub fn open(root_dir: String) -> CoreResult<Storage> {
        let root = PathBuf::from(&root_dir);
        // 先建目录，再打开数据库（SQLite 无法在父目录不存在时创建文件）。
        fs::create_dir_all(root.join(PROJECTS_DIR))?;
        fs::create_dir_all(root.join(DRAFTS_DIR))?;
        let storage = Storage {
            conn: Mutex::new(Connection::open(root.join("hyperss.db"))?),
            root,
        };
        Storage::migrate(&storage.conn())?;
        Ok(storage)
    }

    /// 根目录绝对路径（供 Kotlin 层展示 / 调试）。
    pub fn root_dir(&self) -> String {
        self.root().to_string_lossy().into_owned()
    }

    /// 存储概况：项目数 / 图片数 / 草稿数。
    pub fn stats(&self) -> CoreResult<StorageStats> {
        let conn = self.conn();
        let project_count = conn.query_row(
            "SELECT COUNT(*) FROM projects",
            [],
            |r| r.get::<_, i64>(0),
        )?;
        let image_count = conn.query_row(
            "SELECT COUNT(*) FROM images",
            [],
            |r| r.get::<_, i64>(0),
        )?;
        let draft_count = conn.query_row(
            "SELECT COUNT(*) FROM capture_sessions WHERE status NOT IN ('completed','discarded')",
            [],
            |r| r.get::<_, i64>(0),
        )?;
        Ok(StorageStats {
            project_count,
            image_count,
            draft_count,
        })
    }

    /// 初始化本地环境：确保目录与数据库就绪（幂等，可在启动时调用）。
    pub fn prepare(&self) -> CoreResult<()> {
        self.ensure_dirs()?;
        Storage::migrate(&self.conn())?;
        Ok(())
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StorageStats {
    pub project_count: i64,
    pub image_count: i64,
    pub draft_count: i64,
}

// 便于内部测试。
impl Storage {
    pub fn remove_test_artifact(&self) {
        let _ = fs::remove_dir_all(self.root());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_storage(name: &str) -> Storage {
        let dir = std::env::temp_dir().join(format!("hyperss-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        Storage::open(dir.to_string_lossy().into_owned()).unwrap()
    }

    #[test]
    fn open_creates_layout() {
        let s = temp_storage("open");
        assert!(s.root().join("hyperss.db").exists());
        assert!(s.projects_dir().exists());
        assert!(s.drafts_dir().exists());
        s.remove_test_artifact();
    }

    #[test]
    fn write_atomic_roundtrip() {
        let s = temp_storage("atomic");
        let p = s.drafts_dir().join("probe.txt");
        s.write_atomic(&p, b"hello").unwrap();
        assert_eq!(fs::read(&p).unwrap(), b"hello");
        s.remove_test_artifact();
    }
}