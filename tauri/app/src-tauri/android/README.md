# Tauri Android 端（M4）

> 2026-10-31 更新：APK 构建流水线已在本机**实跑打通**（`scripts/tauri-android-setup.sh` +
> `tauri android build --apk`），V1 APK 直接复用 Rust 核心层监管器跑引擎；
> 前台服务（后台存活）为阶段 2，见文末。

## 架构

- **UI**：同一套 Vue（`app/ui`），Tauri 2 的 Android WebView 承载，无需改动。
- **Rust 核心层**：model / toml / validate / admin / logs / metrics / supervisor 全量复用。
- **frpc 引擎**：`libfrpc.so` 随 jniLibs 分发（W^X 合规：`nativeLibraryDir` 可执行），
  Rust 启动器通过 JNI 读 `ApplicationInfo.nativeLibraryDir` 定位引擎
  （`lib.rs::android_native_lib_dir`），数据目录用 `app_data_dir`（`state.rs::AppPaths::android`）。
- **桌面专属功能**（托盘 / 单实例 / 开机自启插件）已用 `#[cfg(desktop)]` 门控，
  Android 构建不编译。

## 本机构建步骤（已验证）

前置：JDK 17、Android SDK（platform 34+/build-tools/NDK 27.2）、
rustup target `aarch64-linux-android`。

```bash
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"     # Windows Git Bash 示例
export NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"

bash scripts/tauri-android-setup.sh                 # init + 阿里云镜像 + jniLibs 注入
cd tauri/app && ui/node_modules/.bin/tauri android build --apk --target aarch64
```

产物：`gen/android/app/build/outputs/apk/universal/release/app-release-unsigned.apk`。

### Windows 非管理员注意事项（已实测踩坑）

未开启"开发者模式"时 `tauri android build` 会在符号链接一步失败，且**该步骤会清空
jniLibs**。绕过流程（`scripts/tauri-android-setup.sh` 末尾的 NOTE 有完整命令，
脚本也会自动打上全部 gradle 补丁）：

1. 先跑 CLI 到符号链接报错（Rust 产物已编译好）；
2. 手工把 cargo 产物 `libfrpc_app_lib.so` 复制进
   `gen/android/app/src/main/jniLibs/arm64-v8a/`
   （**引擎不用复制**——gradle 已声明式引入 `androidApp/src/main/jniLibs` 作为引擎源目录）；
3. `TAURI_SKIP_RUST_BUILD=1 ./gradlew :app:assembleArm64Release` 直接构建
   （若全局 `~/.gradle/gradle.properties` 有过期代理配置，加
   `-Dhttp.proxyHost= -Dhttps.proxyHost=` 覆盖为直连）；
4. zipalign + apksigner 签名。
5. 根治方案：开启 Windows 开发者模式后可直接 `tauri android build`。

## 体积说明（26MB → 11MB 的优化记录）

Tauri 生成的默认配置用"现代打包"（native 库不压缩、页对齐直读 + 保留调试符号），
首次产出 26MB。已按旧版 `androidApp` 同款策略调整（补丁固化在 setup 脚本）：

- `packaging { jniLibs.useLegacyPackaging = true }`：引擎 17.4MB → 压缩存储 ~6.2MB；
- 删除模板的 `keepDebugSymbols`；Rust 库 7.6MB → 压缩 ~2.9MB；
- 引擎改由 `androidApp/src/main/jniLibs` 声明式提供。

最终 **arm64 APK ≈ 11MB**。与旧版 7.6MB 的差距 = Rust 应用库（~2.9MB）：
它内嵌了整套前端 UI 与 Tauri 运行时，换来的是 Android 与桌面共用同一份 UI 代码。

## 签名（release APK 必须签名才能安装）

密钥在 `src-tauri/android-signing/release.keystore`（不入库；密码与别名见下方命令，
正式分发前请换成自己的密钥）：

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/zipalign" -f -p 4 app-release-unsigned.apk app-release-aligned.apk
"$BT/apksigner" sign \
  --ks ../../../../../android-signing/release.keystore \
  --ks-pass pass:frpc-desktop-multi --key-pass pass:frpc-desktop-multi \
  --ks-key-alias frpc \
  --out frpc-desktop-multi-0.1.0-arm64.apk app-release-aligned.apk
```

## 阶段 2：前台服务（后台存活）

V1 APK 的 frpc 跑在应用进程里，**应用被系统回收时隧道会断**——与 Kotlin 版
"前台服务保活" 的行为差异。阶段 2 计划（源码已备好）：

- 复用 `androidApp/src/main/java/com/frpc/android/service/` 六个文件
  （`FrpcSupervisor.kt` / `FrpcService.kt` / `FrpcBinaryManager.kt` / `BootReceiver.kt` /
  `Notifications.kt` / `LogModels.kt`，约 800 行，逻辑已在线上验证），
  包成 Tauri Android 插件（参考 `FrpcTauriPlugin.kt` 参考实现 + `AndroidManifest-snippet.xml`）。
- Rust 侧 `#[cfg(target_os = "android")]` 把 start/stop/reload 分流到插件命令。

## 真机验证清单（对应迁移方案 M4 验收）

- [ ] `libfrpc.so` 在 arm64 真机可执行、隧道建立
- [ ] 前台服务通知合规（阶段 2 后：Android 8+ channel）
- [ ] 切后台 / 杀进程后隧道存活（阶段 2 后）
- [ ] 开机自启（BootReceiver + `RECEIVE_BOOT_COMPLETED`，阶段 2）
- [ ] 低版本系统 WebView（Chrome 内核 < 90）页面渲染冒烟
