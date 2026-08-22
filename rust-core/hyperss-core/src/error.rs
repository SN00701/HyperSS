use thiserror::Error;

/// 业务错误统一枚举，通过 UniFFI 映射为 Kotlin 侧的异常类。
/// 所有字段限定为 String / 基本类型，保证跨 FFI 边界的可转换性。
#[derive(Debug, Error, uniffi::Error)]
pub enum CoreError {
    #[error("{0}")]
    Database(String),

    #[error("{0}")]
    Io(String),

    #[error("{0}")]
    Image(String),

    #[error("{0}")]
    Config(String),

    #[error("非法项目前缀: {0}")]
    InvalidPrefix(String),

    #[error("项目不存在: {id}")]
    ProjectNotFound { id: i64 },

    #[error("项目前缀已存在: {prefix}")]
    PrefixExists { prefix: String },

    #[error("图片不存在: {id}")]
    ImageNotFound { id: i64 },

    #[error("会话不存在: {id}")]
    SessionNotFound { id: i64 },

    #[error("项目编号已用尽 (1~9999)，请新建项目")]
    SequenceExhausted,

    #[error("非法会话状态迁移: {0}")]
    InvalidTransition(String),

    #[error("拼接置信度低于阈值: {confidence}")]
    StitchLowConfidence { confidence: f32 },

    #[error("会话无可恢复的草稿")]
    NoDraft,

    #[error("拼接的图像宽度不一致: {a} vs {b}")]
    WidthMismatch { a: u32, b: u32 },
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        CoreError::Database(e.to_string())
    }
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io(e.to_string())
    }
}

impl From<image::ImageError> for CoreError {
    fn from(e: image::ImageError) -> Self {
        CoreError::Image(e.to_string())
    }
}

pub type CoreResult<T> = Result<T, CoreError>;