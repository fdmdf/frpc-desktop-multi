//! 应用状态与持久化：档案库（`~/.frpcdash/profiles.json`）+ 设置。
//!
//! Kotlin 来源：`ProfileStore.kt` / `SettingsStore.kt` / `AppPaths.kt`。
//! 数据目录与 Kotlin 版**完全一致**（`~/.frpcdash`），老用户的档案与设置
//! 无缝平移；JSON 字段名保持互通（camelCase）。

use std::path::PathBuf;
use std::sync::RwLock;

use frpc_core::model::FrpcConfig;
use frpc_core::store::{ImportSummary, ProfileBundle, ServerProfile, SettingsData};
use frpc_core::supervisor::Supervisor;
use frpc_core::toml::FrpcToml;

pub struct AppPaths {
    pub home: PathBuf,
    pub profiles_file: PathBuf,
    pub settings_file: PathBuf,
    pub bin_dir: PathBuf,
    pub run_dir: PathBuf,
    pub logs_dir: PathBuf,
    pub engine_file_name: String,
    /// Android：引擎所在的原生库目录（nativeLibraryDir，可执行）。
    /// 桌面端为 None（引擎在 bin_dir，随包资源解包）。
    pub engine_dir: Option<PathBuf>,
    pub is_windows: bool,
}

impl AppPaths {
    /// 与 Kotlin `AppPaths` 一致：`~/.frpcdash`，含旧目录 `~/.frpc-desktop` 迁移。
    pub fn resolve() -> Self {
        let user_home = std::env::var_os("USERPROFILE")
            .or_else(|| std::env::var_os("HOME"))
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
        let home = user_home.join(".frpcdash");
        let _ = std::fs::create_dir_all(&home);
        // 旧版数据目录迁移（无 profiles.json 且存在 legacy 目录时拷贝）
        let legacy = user_home.join(".frpc-desktop");
        if !home.join("profiles.json").exists() && legacy.is_dir() {
            for name in ["profiles.json", "settings.json"] {
                let source = legacy.join(name);
                let target = home.join(name);
                if source.is_file() && !target.exists() {
                    let _ = std::fs::copy(&source, &target);
                }
            }
        }
        let is_windows = cfg!(windows);
        Self {
            bin_dir: home.join("bin"),
            logs_dir: home.join("logs"),
            profiles_file: home.join("profiles.json"),
            run_dir: home.join("run"),
            settings_file: home.join("settings.json"),
            engine_file_name: if is_windows {
                "frpc.exe".into()
            } else {
                "frpc".into()
            },
            engine_dir: None,
            home,
            is_windows,
        }
    }

    /// Android：数据目录用应用 files 目录（`app_data_dir`），
    /// 引擎固定为 jniLibs 打包的 `libfrpc.so`（落在 nativeLibraryDir，可执行）。
    #[cfg(target_os = "android")]
    pub fn android(data_dir: PathBuf, native_lib_dir: PathBuf) -> Self {
        let _ = std::fs::create_dir_all(&data_dir);
        Self {
            bin_dir: data_dir.join("bin"),
            logs_dir: data_dir.join("logs"),
            profiles_file: data_dir.join("profiles.json"),
            run_dir: data_dir.join("run"),
            settings_file: data_dir.join("settings.json"),
            engine_file_name: "libfrpc.so".into(),
            engine_dir: Some(native_lib_dir),
            home: data_dir,
            is_windows: false,
        }
    }

    pub fn run_config_file(&self) -> PathBuf {
        self.run_dir.join("frpc.toml")
    }
}

pub struct AppState {
    pub paths: AppPaths,
    pub profiles: RwLock<Vec<ServerProfile>>,
    pub settings: RwLock<SettingsData>,
    pub supervisor: Supervisor,
    pub engine_path: RwLock<PathBuf>,
    /// 打包资源目录（内置引擎 / VERSION；dev 下为 None）
    pub resource_dir: Option<PathBuf>,
}

impl AppState {
    pub fn new(supervisor: Supervisor) -> Self {
        Self::new_with_paths(AppPaths::resolve(), supervisor)
    }

    pub fn new_with_paths(paths: AppPaths, supervisor: Supervisor) -> Self {
        for dir in [&paths.bin_dir, &paths.run_dir, &paths.logs_dir] {
            let _ = std::fs::create_dir_all(dir);
        }
        let profiles = load_profiles(&paths.profiles_file);
        let settings = load_settings(&paths.settings_file);
        let engine_default = paths
            .engine_dir
            .as_ref()
            .map(|d| d.join(&paths.engine_file_name))
            .unwrap_or_else(|| paths.bin_dir.join(&paths.engine_file_name));
        Self {
            engine_path: RwLock::new(engine_default),
            paths,
            profiles: RwLock::new(profiles),
            settings: RwLock::new(settings),
            supervisor,
            resource_dir: None,
        }
    }

    pub fn find_profile(&self, id: &str) -> Option<ServerProfile> {
        self.profiles
            .read()
            .ok()?
            .iter()
            .find(|p| p.id == id)
            .cloned()
    }

    pub fn persist_profiles(&self, list: Vec<ServerProfile>) {
        if let Ok(mut guard) = self.profiles.write() {
            *guard = list;
        }
        let list = self.profiles.read().map(|g| g.clone()).unwrap_or_default();
        write_json_file(&self.paths.profiles_file, &list);
    }

    pub fn upsert_profile(&self, mut profile: ServerProfile) -> ServerProfile {
        profile.updated_at = frpc_core::admin::now_epoch_ms();
        let mut list = self.profiles.read().map(|g| g.clone()).unwrap_or_default();
        list.retain(|p| p.id != profile.id);
        list.push(profile.clone());
        self.persist_profiles(list);
        profile
    }

    pub fn delete_profile(&self, id: &str) {
        let list = self
            .profiles
            .read()
            .map(|g| g.iter().filter(|p| p.id != id).cloned().collect::<Vec<_>>())
            .unwrap_or_default();
        self.persist_profiles(list);
    }

    pub fn duplicate_profile(&self, id: &str) -> Option<ServerProfile> {
        let source = self.find_profile(id)?;
        let now = frpc_core::admin::now_epoch_ms();
        let copy = ServerProfile {
            id: uuid_v4(),
            name: format!("{} 副本", source.name),
            created_at: now,
            updated_at: now,
            last_used_at: 0,
            ..source
        };
        Some(self.upsert_profile(copy))
    }

    pub fn mark_used(&self, id: &str) {
        if let Ok(mut guard) = self.profiles.write() {
            for p in guard.iter_mut() {
                if p.id == id {
                    p.last_used_at = frpc_core::admin::now_epoch_ms();
                }
            }
            let list = guard.clone();
            drop(guard);
            write_json_file(&self.paths.profiles_file, &list);
        }
    }

    /// Kotlin `ProfileStore.importAll`：按 id 去重合并，重名自动加后缀。
    pub fn import_all(&self, text: &str, replace: bool) -> Result<ImportSummary, String> {
        let (incoming, skipped) = parse_bundle(text)?;
        let base = if replace {
            Vec::new()
        } else {
            self.profiles.read().map(|g| g.clone()).unwrap_or_default()
        };
        let mut by_id: Vec<ServerProfile> = base.clone();
        let mut names: std::collections::HashSet<String> =
            base.iter().map(|p| p.name.clone()).collect();
        let mut added = 0usize;
        let mut updated = 0usize;
        for mut p in incoming {
            if let Some(existing) = by_id.iter_mut().find(|e| e.id == p.id) {
                *existing = p;
                updated += 1;
            } else {
                if p.name.trim().is_empty() {
                    p.name = "导入配置".into();
                }
                let mut final_name = p.name.clone();
                let mut n = 2;
                while names.contains(&final_name) {
                    final_name = format!("{} ({n})", p.name);
                    n += 1;
                }
                names.insert(final_name.clone());
                p.name = final_name;
                by_id.push(p);
                added += 1;
            }
        }
        self.persist_profiles(by_id);
        Ok(ImportSummary {
            added,
            updated,
            skipped,
        })
    }

    pub fn export_all(&self) -> String {
        let bundle = ProfileBundle {
            profiles: self.profiles.read().map(|g| g.clone()).unwrap_or_default(),
            ..ProfileBundle::default()
        };
        serde_json::to_string_pretty(&bundle).unwrap_or_else(|_| "[]".into())
    }

    pub fn save_settings(&self, settings: SettingsData) {
        write_json_file(&self.paths.settings_file, &settings);
        if let Ok(mut guard) = self.settings.write() {
            *guard = settings;
        }
    }

    pub fn settings(&self) -> SettingsData {
        self.settings.read().map(|s| s.clone()).unwrap_or_default()
    }
}

// ---------- 引擎管理（Kotlin EngineManager） ----------

pub struct EngineManager<'a> {
    pub state: &'a AppState,
}

impl EngineManager<'_> {
    /// 引擎文件：设置中的自定义路径优先；Android 用 nativeLibraryDir/libfrpc.so；
    /// 其余平台用 `bin/frpc(.exe)`。
    ///
    /// Android 上自定义路径一律忽略：10+ 禁止从可写的应用私有目录 exec，
    /// 用户即使把 frpc 拷进来也只会得到「权限不足」，设置项本身也不展示。
    pub fn engine_file(&self) -> PathBuf {
        #[cfg(not(target_os = "android"))]
        {
            let custom = self.state.settings().engine_path;
            let custom = PathBuf::from(custom.trim());
            if custom.is_file() {
                return custom;
            }
        }
        if let Some(dir) = &self.state.paths.engine_dir {
            return dir.join(&self.state.paths.engine_file_name);
        }
        self.state
            .paths
            .bin_dir
            .join(&self.state.paths.engine_file_name)
    }

    pub fn is_using_custom(&self) -> bool {
        #[cfg(target_os = "android")]
        {
            false
        }
        #[cfg(not(target_os = "android"))]
        {
            let custom = self.state.settings().engine_path;
            !custom.trim().is_empty() && PathBuf::from(custom.trim()).is_file()
        }
    }

    /// 就绪检查：优先解包内置资源（`resources/engine/`），其次自定义路径 / bin 目录。
    pub fn ensure_ready(&self) -> Result<PathBuf, String> {
        let target = self.engine_file();

        // 1) 目标已有可执行引擎
        if target.is_file() {
            #[cfg(unix)]
            self.ensure_executable(&target);
            return Ok(target);
        }

        // 2) 打包资源解包（Kotlin ensureReady 的资源拷贝逻辑）
        if let Some(res_dir) = &self.state.resource_dir {
            let bundled = res_dir
                .join("resources/engine")
                .join(&self.state.paths.engine_file_name);
            if bundled.is_file() {
                std::fs::copy(&bundled, &target).map_err(|e| format!("解包内置引擎失败：{e}"))?;
                #[cfg(unix)]
                self.ensure_executable(&target);
                return Ok(target);
            }
        }

        #[cfg(not(target_os = "android"))]
        let msg = format!(
            "未找到引擎 {}：可在设置中指定 frpc 路径，或将引擎放入 {}",
            target.display(),
            self.state.paths.bin_dir.display()
        );
        // Android 的引擎只能来自 jniLibs（APK 内的 libfrpc.so），没有「换个路径」这回事
        #[cfg(target_os = "android")]
        let msg = format!(
            "未找到内置引擎 {}：Android 只使用随安装包内置的 libfrpc.so，请重新安装应用",
            target.display()
        );
        Err(msg)
    }

    #[cfg(unix)]
    fn ensure_executable(&self, path: &std::path::Path) {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = std::fs::metadata(path) {
            if meta.permissions().mode() & 0o111 == 0 {
                let mut perm = meta.permissions();
                perm.set_mode(perm.mode() | 0o111);
                let _ = std::fs::set_permissions(path, perm);
            }
        }
    }

    /// 内置引擎版本。
    ///
    /// 链路：打包资源 `resources/engine/VERSION`（桌面）→ 构建期编进二进制的
    /// `FRPC_ENGINE_VERSION`（Android，引擎在 nativeLibraryDir 没有 VERSION 文件）
    /// → `unknown`。返回的是去掉 `v` 前缀的裸版本号（界面模板自带上下文）。
    pub fn bundled_version(&self, resource_dir: Option<&std::path::Path>) -> String {
        if let Some(dir) = resource_dir {
            if let Ok(text) = std::fs::read_to_string(dir.join("resources/engine/VERSION")) {
                let v = frpc_core::version::strip_v_prefix(&text);
                if !v.is_empty() {
                    return v;
                }
            }
        }
        if let Some(v) = option_env!("FRPC_ENGINE_VERSION") {
            let v = frpc_core::version::strip_v_prefix(v);
            if !v.is_empty() {
                return v;
            }
        }
        "unknown".into()
    }

    /// `frpc -v` 探测版本（Kotlin probeVersion）。
    ///
    /// 解析逻辑见 `frpc_core::version`：真实输出只有一行 `0.71.1`，
    /// 不能按「包含 frp 的行」来找。
    pub fn probe_version(&self) -> String {
        let engine = self.engine_file();
        if !engine.is_file() {
            return String::new();
        }
        match std::process::Command::new(&engine).arg("-v").output() {
            Ok(o) => {
                let stdout = String::from_utf8_lossy(&o.stdout);
                let parsed = frpc_core::version::parse_version_output(&stdout);
                if !parsed.is_empty() {
                    return parsed;
                }
                // 少数构建把版本打到 stderr
                frpc_core::version::parse_version_output(&String::from_utf8_lossy(&o.stderr))
            }
            Err(_) => String::new(),
        }
    }
}

/// 校验配置。端口占用检测要排除「运行中的 frpc 自己占着的 admin 端口」——
/// 否则运行中编辑并保存档案必然失败：webServer 端口被自己占着，校验报
/// `PortInUse`，前端只看到一个无从下手的错误。
pub fn validate_config_live(
    state: &AppState,
    config: &FrpcConfig,
) -> Vec<frpc_core::validate::ValidationIssue> {
    let own_admin = {
        let rx = state.supervisor.state();
        let st = rx.borrow();
        if st.phase == frpc_core::supervisor::Phase::Running {
            st.admin_port
        } else {
            0
        }
    };
    frpc_core::validate::ConfigValidator::validate(config, |port| {
        port != own_admin && frpc_core::net::PortCheck::is_in_use(port)
    })
}

pub fn import_toml_profile(name: &str, toml_text: &str) -> Result<ServerProfile, String> {
    let config = FrpcToml::decode(toml_text).map_err(|e| e.to_string())?;
    Ok(ServerProfile::new(
        name,
        config,
        frpc_core::admin::now_epoch_ms(),
    ))
}

// ---------- 文件与解析辅助 ----------

fn write_json_file<T: serde::Serialize>(path: &std::path::Path, value: &T) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match serde_json::to_string_pretty(value) {
        Ok(text) => {
            let tmp = path.with_extension("json.tmp");
            if std::fs::write(&tmp, text).is_ok() {
                if std::fs::rename(&tmp, path).is_err() {
                    let text = std::fs::read_to_string(&tmp).unwrap_or_default();
                    let _ = std::fs::write(path, text);
                    let _ = std::fs::remove_file(&tmp);
                }
            }
        }
        Err(_) => {}
    }
}

fn read_json_file<T: serde::de::DeserializeOwned>(path: &std::path::Path) -> Option<T> {
    let text = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

/// Kotlin `load()`：逐条容错解析，坏条目按名字报告。
pub fn load_profiles(path: &std::path::Path) -> Vec<ServerProfile> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    let Ok(raw_list) = serde_json::from_str::<Vec<serde_json::Value>>(&text) else {
        return Vec::new();
    };
    raw_list
        .iter()
        .filter_map(|raw| serde_json::from_value::<ServerProfile>(raw.clone()).ok())
        .collect()
}

pub fn load_settings(path: &std::path::Path) -> SettingsData {
    read_json_file(path).unwrap_or_default()
}

/// Kotlin `parseBundle`：接受 ProfileBundle 对象或纯数组。
fn parse_bundle(text: &str) -> Result<(Vec<ServerProfile>, Vec<String>), String> {
    let root: serde_json::Value =
        serde_json::from_str(text).map_err(|_| "文件不是合法 JSON".to_string())?;
    let raw_profiles: Vec<serde_json::Value> = match &root {
        serde_json::Value::Array(arr) => arr.clone(),
        serde_json::Value::Object(obj) => obj
            .get("profiles")
            .and_then(|p| p.as_array())
            .cloned()
            .unwrap_or_default(),
        _ => Vec::new(),
    };
    let mut ok = Vec::new();
    let mut bad = Vec::new();
    for (index, element) in raw_profiles.iter().enumerate() {
        match serde_json::from_value::<ServerProfile>(element.clone()) {
            Ok(p) => ok.push(p),
            Err(_) => {
                let name = element.get("name").and_then(|n| n.as_str()).unwrap_or("");
                bad.push(if name.is_empty() {
                    format!("第 {} 条", index + 1)
                } else {
                    name.to_string()
                });
            }
        }
    }
    Ok((ok, bad))
}

fn uuid_v4() -> String {
    uuid::Uuid::new_v4().to_string()
}
