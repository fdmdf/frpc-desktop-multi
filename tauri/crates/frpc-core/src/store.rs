//! 档案与设置的数据结构（JSON 存储契约与 Kotlin 版互通）。
//!
//! Kotlin 来源：`desktopApp/.../data/{ServerProfile,SettingsStore}.kt`。
//! 持久化由应用层的存储插件承担（tauri-plugin-store / 文件），本模块只定义
//! 数据结构与导入导出摘要 —— 保证 Kotlin 版导出的 ProfileBundle 可直接导入。

use serde::{Deserialize, Serialize};

use crate::model::FrpcConfig;

/// 单个服务器档案（Kotlin `ServerProfile`）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerProfile {
    pub id: String,
    pub name: String,
    pub config: FrpcConfig,
    pub created_at: i64,
    pub updated_at: i64,
    pub last_used_at: i64,
}

impl ServerProfile {
    pub fn new(name: &str, config: FrpcConfig, now_epoch_ms: i64) -> Self {
        Self {
            id: new_profile_id(),
            name: name.to_string(),
            config,
            created_at: now_epoch_ms,
            updated_at: now_epoch_ms,
            last_used_at: 0,
        }
    }

    pub fn server_summary(&self) -> String {
        format!("{}:{}", self.config.server_addr, self.config.server_port)
    }
}

fn new_profile_id() -> String {
    // 与 Kotlin 的 UUID.randomUUID() 形态一致（v4 随机）
    uuid::Uuid::new_v4().to_string()
}

/// 导出/导入包（Kotlin `ProfileBundle`，跨端互通）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ProfileBundle {
    pub schema_version: i32,
    pub app: String,
    pub exported_at: i64,
    pub profiles: Vec<ServerProfile>,
}

impl Default for ProfileBundle {
    fn default() -> Self {
        Self {
            schema_version: 1,
            app: "Frpc-Android-Multi".into(),
            exported_at: crate::admin::now_epoch_ms(),
            profiles: Vec::new(),
        }
    }
}

/// 导入摘要（Kotlin `ImportSummary`）。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportSummary {
    pub added: usize,
    pub updated: usize,
    pub skipped: Vec<String>,
}

/// 应用设置（Kotlin `SettingsData`）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SettingsData {
    pub theme: String,
    pub engine_path: String,
    pub show_access_logs: bool,
    pub default_profile_id: Option<String>,
    pub auto_start_on_launch: bool,
    pub close_to_tray: bool,
}

impl Default for SettingsData {
    fn default() -> Self {
        Self {
            theme: "system".into(),
            engine_path: String::new(),
            show_access_logs: false,
            default_profile_id: None,
            auto_start_on_launch: false,
            close_to_tray: true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_json_round_trip_matches_kotlin_keys() {
        let profile = ServerProfile::new(
            "测试",
            FrpcConfig {
                server_addr: "frps.example.com".into(),
                ..FrpcConfig::default()
            },
            1_700_000_000_000,
        );
        let json = serde_json::to_string(&profile).unwrap();
        assert!(json.contains("\"createdAt\""), "{json}");
        assert!(json.contains("\"serverAddr\""), "{json}");
        let back: ServerProfile = serde_json::from_str(&json).unwrap();
        assert_eq!(profile.id, back.id);
        assert_eq!(profile.config, back.config);
    }

    #[test]
    fn settings_defaults_match_kotlin() {
        let s = SettingsData::default();
        assert_eq!("system", s.theme);
        assert!(!s.show_access_logs);
        assert!(s.close_to_tray);
        let json = serde_json::to_string(&s).unwrap();
        assert!(json.contains("\"enginePath\""), "{json}");
        assert!(json.contains("\"closeToTray\""), "{json}");
    }

    #[test]
    fn bundle_defaults_match_kotlin() {
        let b = ProfileBundle::default();
        assert_eq!(1, b.schema_version);
        assert_eq!("Frpc-Android-Multi", b.app);
    }
}
