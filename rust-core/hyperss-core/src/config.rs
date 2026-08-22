//! config 模块：语言 / 主题 / 版本号等配置的持久化。
//!
//! 使用根目录下 `config.json` 存储键值对，原子写落盘。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use crate::error::{CoreError, CoreResult};

pub const KEY_LANGUAGE: &str = "language"; // "system" | "zh" | "en"
pub const KEY_THEME: &str = "theme";       // "system" | "light" | "dark"
pub const KEY_LAST_PROJECT_ID: &str = "last_project_id";
pub const KEY_WAIT_MS: &str = "scroll_wait_ms";      // 滚动后等待动画稳定时间
pub const KEY_STEP_DP: &str = "fixed_step_dp";       // 模式③步长
pub const KEY_AUTO_SCROLL_RATIO: &str = "auto_scroll_ratio"; // 模式①半屏滑动比例

#[derive(Debug, Serialize, Deserialize, Default)]
struct ConfigFile {
    values: HashMap<String, String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ConfigEntry {
    pub key: String,
    pub value: String,
}

/// 配置存储。
#[derive(uniffi::Object)]
pub struct ConfigStore {
    inner: Mutex<ConfigInner>,
}

struct ConfigInner {
    data: ConfigFile,
    path: PathBuf,
}

#[uniffi::export]
impl ConfigStore {
    #[uniffi::constructor]
    pub fn open(root_dir: String) -> CoreResult<ConfigStore> {
        let path = PathBuf::from(&root_dir).join("config.json");
        let data = if path.exists() {
            let raw = std::fs::read_to_string(&path)
                .map_err(|e| CoreError::Config(format!("读取配置失败: {e}")))?;
            serde_json::from_str(&raw)
                .map_err(|e| CoreError::Config(format!("配置解析失败: {e}")))?
        } else {
            ConfigFile::default()
        };
        Ok(ConfigStore {
            inner: Mutex::new(ConfigInner { data, path }),
        })
    }

    pub fn get(&self, key: String) -> Option<String> {
        self.inner.lock().unwrap().data.values.get(&key).cloned()
    }

    /// 读取，带默认值。
    pub fn get_or(&self, key: String, default: String) -> String {
        self.get(key).unwrap_or(default)
    }

    pub fn set(&self, key: String, value: String) -> CoreResult<()> {
        let mut inner = self.inner.lock().unwrap();
        inner.data.values.insert(key, value);
        Self::persist(&inner)
    }

    pub fn get_all(&self) -> Vec<ConfigEntry> {
        let inner = self.inner.lock().unwrap();
        inner
            .data
            .values
            .iter()
            .map(|(k, v)| ConfigEntry {
                key: k.clone(),
                value: v.clone(),
            })
            .collect()
    }
}

impl ConfigStore {
    fn persist(inner: &ConfigInner) -> CoreResult<()> {
        let json = serde_json::to_string_pretty(&inner.data)
            .map_err(|e| CoreError::Config(format!("配置序列化失败: {e}")))?;
        if let Some(parent) = inner.path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let tmp = inner.path.with_extension("json.tmp");
        std::fs::write(&tmp, json)?;
        std::fs::rename(&tmp, &inner.path)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn config_roundtrip() {
        let dir = std::env::temp_dir().join(format!("hyperss-cfg-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();

        let c = ConfigStore::open(dir.to_string_lossy().into_owned()).unwrap();
        assert_eq!(c.get("theme".into()), None);
        c.set("theme".into(), "dark".into()).unwrap();
        c.set("language".into(), "zh".into()).unwrap();

        let c2 = ConfigStore::open(dir.to_string_lossy().into_owned()).unwrap();
        assert_eq!(c2.get("theme".into()).as_deref(), Some("dark"));
        assert_eq!(c2.get_or("missing".into(), "def".into()), "def");
        assert_eq!(c2.get_all().len(), 2);
        let _ = fs::remove_dir_all(&dir);
    }
}