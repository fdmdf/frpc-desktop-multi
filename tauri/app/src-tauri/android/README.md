# Tauri Android 端接入指南（M4 交付物）

> 本机无 Android SDK/NDK，本目录交付**可评审的参考实现源码 + 逐步接入指南**；
> 在具备 SDK 的环境按下面步骤编译验证后即可并入构建（预计 1~2 周含真机联调）。

## 架构（与迁移方案第 5 节一致）

- **UI**：同一套 Vue（`app/ui`），Tauri 2 的 Android WebView 承载，无需改动。
- **Rust 核心层**：`frpc-core` 的 model / toml / validate / admin / logs 照常使用。
- **进程监管**：不走 Rust `supervisor`（App 进程可能被回收），由 **Kotlin 前台服务**承担——
  直接复用 `androidApp/src/main/java/com/frpc/android/service/` 的既有实现
  （`FrpcSupervisor.kt` / `FrpcService.kt` / `FrpcBinaryManager.kt` / `BootReceiver.kt` /
  `Notifications.kt` / `LogModels.kt`，约 800 行，逻辑已在线上验证）。
- **frpc 引擎**：`libfrpc.so` 随 jniLibs 分发（W^X 合规：`nativeLibraryDir` 可执行），
  构建方式 = `scripts/build-frpc.sh android`（现有 CI 已产出）。

## 接入步骤

1. **初始化 Android 工程**（需 SDK/NDK）：
   ```bash
   cd tauri/app && ui/node_modules/.bin/tauri android init
   ```
   生成 `src-tauri/gen/android`。

2. **复制服务层**：把 `androidApp/src/main/java/com/frpc/android/service/` 六个文件
   复制到生成的工程 `gen/android/app/src/main/java/io/github/fdmdf/frpc/service/`，
   包名 `com.frpc.android.service` 全局替换为宿主包名。

3. **复制 jniLibs 与 assets**：
   ```bash
   cp -r ../../androidApp/src/main/jniLibs  gen/android/app/src/main/
   cp -r ../../androidApp/src/main/assets   gen/android/app/src/main/
   ```

4. **合并清单**：把 `AndroidManifest-snippet.xml` 的权限与组件声明合并进
   `gen/android/app/src/main/AndroidManifest.xml`。

5. **挂插件**：参考 `FrpcTauriPlugin.kt` 实现 Tauri Android 插件（`@Command`
   方法 + 事件回传），在 `MainActivity` 的 `registerPlugin` 处注册。

6. **Rust 侧分流**：`commands.rs` 的 `start_tunnel` / `stop_tunnel` / `reload_tunnel`
   在 `#[cfg(target_os = "android")]` 下改为 invoke 插件命令（配置编码仍走
   `FrpcToml::encode`，把 TOML 文本与档案 id 传给插件），桌面路径不变。

7. **真机验证清单**（对应迁移方案 M4 验收）：
   - [ ] 前台服务通知合规（Android 8+ channel）
   - [ ] 切后台 / 杀进程后隧道存活
   - [ ] 开机自启（BootReceiver + 权限 `RECEIVE_BOOT_COMPLETED`）
   - [ ] `libfrpc.so` 在 arm64 真机可执行
   - [ ] 低版本系统 WebView（Chrome 内核 < 90）页面渲染冒烟

## 已知差异（相对 Kotlin 版）

- 档案存储：桌面端用 `~/.frpcdash`；Android 端 Tauri WebView 无该目录概念，
  档案 JSON 放应用私有目录 `files_dir`，与 Kotlin 版 `ProfileBundle` 导入/导出互通。
- 前台服务的通知文案走既有 `Notifications.kt`（中文），M4 联调时接入 i18n。
