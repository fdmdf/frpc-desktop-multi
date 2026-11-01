# Tauri Android 端（M4）

> 2026-11-01 更新：APK 构建流水线已在本机**实跑打通**（`scripts/tauri-android-setup.sh` +
> `tauri android build --apk`），V1 APK 直接复用 Rust 核心层监管器跑引擎；
> 前台服务（后台存活）已落地，见「后台保活」一节。

## 架构

- **UI**：同一套 Vue（`app/ui`），Tauri 2 的 Android WebView 承载，无需改动。
- **Rust 核心层**：model / toml / validate / admin / logs / metrics / supervisor 全量复用。
- **frpc 引擎**：`libfrpc.so` 随 jniLibs 分发（W^X 合规：`nativeLibraryDir` 可执行），
  Rust 启动器通过 JNI 读 `ApplicationInfo.nativeLibraryDir` 定位引擎
  （`lib.rs::android_native_lib_dir`），数据目录用 `app_data_dir`（`state.rs::AppPaths::android`）。
- **桌面专属功能**（托盘 / 单实例 / 开机自启插件）已用 `#[cfg(desktop)]` 门控，
  Android 构建不编译。
- **后台保活**：`FrpcKeepAliveService.kt`（`service` 包）在隧道运行时挂一条常驻通知，
  前端在 `engine-state` 变为 RUNNING 时通过 `set_keepalive` 命令触发
  （Rust → JNI → `startForegroundService`），停止 / 失败时收掉通知。
  没有它的话，应用切后台被系统回收时 frpc 子进程会跟着死 —— 隧道静默断开。
- **系统栏避让**：`MainActivity.kt`（本目录，由 setup 脚本复制进 `gen/android`）在
  `enableEdgeToEdge()` 之后按系统栏 / 刘海高度给 `android.R.id.content` 补 padding。
  Android 15 起强制 edge-to-edge，不补的话顶部标题会被状态栏压住、底部导航被手势条
  咬掉；WebView 的 `env(safe-area-inset-*)` 依赖系统 WebView 版本，不能替代这一步。

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
APK 会到 26MB。改为压缩存储后（补丁固化在 setup 脚本）：

- `packaging { jniLibs.useLegacyPackaging = true }`：引擎 17.4MB → 压缩存储 ~6.2MB；
- 删除模板的 `keepDebugSymbols`；Rust 库 7.6MB → 压缩 ~2.9MB；
- 引擎改由 `androidApp/src/main/jniLibs` 声明式提供。

最终 **arm64 APK ≈ 11MB**。与旧版 7.6MB 的差距 = Rust 应用库（~2.9MB）：
它内嵌了整套前端 UI 与 Tauri 运行时，换来的是 Android 与桌面共用同一份 UI 代码。

## 签名（release APK 必须签名才能安装）

密钥固定为仓库内的 `tauri/android-signing/release.keystore`（**入库**，说明见
[`tauri/android-signing/README.md`](../../../android-signing/README.md)）。必须是同一把：
Android 只允许同签名的 APK 覆盖安装，每次构建换密钥就会报「签名不一致」，
用户只能卸载重装。

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/zipalign" -f -p 4 app-release-unsigned.apk app-release-aligned.apk
"$BT/apksigner" sign \
  --ks ../../../../../android-signing/release.keystore \
  --ks-pass pass:frpc-desktop-multi --key-pass pass:frpc-desktop-multi \
  --ks-key-alias frpc \
  --out frpc-desktop-multi-0.1.0-arm64.apk app-release-aligned.apk
"$BT/apksigner" verify --print-certs frpc-desktop-multi-0.1.0-arm64.apk
```

## 后台保活（前台服务）

frpc 是 `Command::spawn` 出来的子进程，父进程（应用进程）被系统回收时子进程跟着死。
Android 的进程回收按「有没有前台组件」排序：只有一个 Activity 时，切后台几分钟就可能
被 LMK 干掉；挂上前台服务 + 常驻通知后，就变成用户可见的工作，不再按空闲清理。

- `FrpcKeepAliveService.kt`：`startForeground` + `START_STICKY`，通知 `IMPORTANCE_LOW`
  （不响、不弹）；小图标用生成脚本产出的「纯白字形 + alpha」前景图
  （`res/drawable/ic_stat_frpc.png`），彩色图标会被系统渲染成白方块。
  折叠态一行摘要，展开态是多行明细（状态 / 配置 / 在线代理数 / 管理端口 / 启动时刻），
  右侧计时器交给系统（`setUsesChronometer`），不需要前端定时刷新。
- 触发链：前端 `App.vue` 监听 `engine-state` → `set_keepalive(enabled, title, text, lines, since)`
  → `android::keepalive_start/stop`（JNI）→ `startForegroundService`。
  文案与明细都在前端拼，跟随界面语言；计时器只传启动时刻。
- `MainActivity` 在 Android 13+ 申请 `POST_NOTIFICATIONS`：不申请的话前台服务通知
  会被静默丢弃（服务在跑但用户看不到）。
- 设置页「允许后台运行」调 `FrpcHost.requestIgnoreBatteryOptimizations()` 拉系统的
  电池优化白名单弹窗；跳转是异步的（系统设置是另一个 Activity，只把我们压到后台），
  前端拿到的只是「已发起」，所以另有 `battery_exemption_status` 命令查真实状态。
  **刷新策略必须是多路的**：`visibilitychange` / `pageshow` / `focus` 走快路径，
  页面可见时每 4s 轮询兜底 —— 部分 ROM 的 WebView 在被别的 Activity 覆盖时
  `visibilityState` 根本不变化，只挂 `visibilitychange` 等于没挂
  （只挂 `visibilitychange` 的表现就是「回来还是未允许」）。界面上不设手动「刷新」
  按钮：状态该自己跟，不该让用户为界面没更新负责。国产 ROM 还需要在
  「应用启动管理」里允许后台活动，这是 ROM 侧行为，应用无法代劳。

仍然做不到的：用户主动「强行停止」、部分 ROM 划掉后台即冻结。这两类只能靠白名单 +
厂商自启管理，任何应用都绕不过去。

## 返回键

返回键由**前端**接管（`App.vue` → `onBackButtonPress`，内部就是
`addPluginListener('app','back-button')` → Tauri 的 `AppPlugin.kt`）。一旦注册了监听，
`AppPlugin` 就不再自己 `webView.goBack()` 或把返回传给系统，完全由 JS 决定。

三层，逐层收：

1. 有弹窗（`ui/src/composables/backNav.ts` 里各页面注册的处理器）→ 关掉最上层那个；
2. 不在主页 → `router.replace("/")` 回主页（五个 tab 是平级，没有更细的层级）；
3. 已经在主页 → **2 秒内连按两次**才退后台：第一下只弹一条「再按一次返回退出」，
   窗口内再按一次才走 `move_to_back` 命令 → `FrpcHost.moveToBack` → `moveTaskToBack(true)`。
   这一步只能用 Activity 实例，而 Rust 侧拿到的是 Application，所以走
   `FrpcHost.currentActivity`（`MainActivity.onCreate` 登记）；
   `moveTaskToBack` 返回 false（分屏 / 悬浮窗 / 个别 ROM）时再用 `ACTION_MAIN +
   CATEGORY_HOME` 兜一次，保证「按了返回一定离开前台」。

为什么主页要两下：这是全应用唯一的「离开」入口，而且底部导航紧挨着手势条，
单次误触就退出去太轻率。计数在切页面、弹窗被关掉、超时、真的退出去之后都会清掉
（`disarmBack`），不会出现「上次按过一下，这次一按就没了」。

第 3 步**不是** `exit(0)`、也不是 `finish()`：

- `exit(0)` 会结束应用进程，而 frpc 是它的子进程 —— 进程一没，隧道直接断，
  用户只是按了个返回却把连接掐了；
- `finish()` 虽然进程还在，但 Activity 被销毁，当前 tab、滚动位置、编辑到一半的
  内容全丢，回来得从头点一遍。

`moveTaskToBack` 等价于按 Home 键：Activity 和进程都留着，前台服务通知还在，
从最近任务点回来就是原界面。真正的关闭仍然是主页上的「停止」。

## JNI 约定（踩过的坑，别再踩）

Rust `src/android.rs` 调 Kotlin 的规矩有四条，违反任一条的表现都极难自证：前两条报
**「Error invoking postMessage: Java exception was raised during method invocation」**
（完全看不出原因），后两条报 `java.lang.NoSuchMethodError: no static method …`。

1. **不要用 `JNIEnv::find_class`**。Tauri 的命令跑在 tokio 线程池上，从「不是 Java 创建的
   线程」调 JNI `FindClass` 会退化成系统 ClassLoader，永远找不到 `io.github.fdmdf.frpc.*`，
   抛 `ClassNotFoundException`。必须走
   `activity.getClassLoader().loadClass("io.github.fdmdf.frpc.FrpcHost")`。
2. **调用失败后必须清掉挂起的 Java 异常**（`exception_occurred` + `exception_clear`）。
   `call_static_method` 只返回一个 `Err`，异常本身仍挂在线程上；带着异常的线程之后每次
   JNI 调用都会立刻失败，包括 Tauri 自己回传结果的 IPC，于是真因被吞掉。
3. **被调的那一侧必须是真正的静态方法**。Kotlin `object` 的成员本身是，但
   `companion object` 里的普通函数在 JVM 里只是 `Companion` 的**实例方法**，
   `FrpcKeepAliveService.start(...)` 这个静态方法压根不存在 —— 必须加 `@JvmStatic`。
4. **release 构建必须 keep 住这两个类**。R8 看不到「用字符串按名字调方法」这种引用，
   会把它们当死代码删掉或改名：类还在、方法没了，运行时才炸。keep 规则由
   `scripts/tauri-android-setup.sh`（6b 步）注入 `app/proguard-rules.pro`，
   `scripts/verify-jni-dex.py` 在 CI 出包前直接扫 dex 核对，缺一个就拒绝发包。
   换句话说：**这类问题现在不会等到真机才发现**。

配套的 Kotlin 侧约定：

- `FrpcHost` 的每个入口都 `try/catch(Throwable)` 并返回安全默认值，**绝不向外抛**；
- `startActivity` / `Toast` 一律 `Handler(Looper.getMainLooper()).post`，
  非主线程调 Toast 会直接抛 `Can't create handler inside thread that has not called Looper.prepare()`；
- Rust 每次调用都开 `push_local_frame` / `pop_local_frame`：attach 住的线程不回 Java，
  局部引用不会自动回收，反复调用会撑满局部引用表。
- **Rust 传进来的 `Context` 是 Application，不是 Activity**。`ndk_context` 是 tao 初始化的，
  而它塞进去的是 `activity.getApplicationContext()`（见 tao 的 `ndk_glue.rs`）。
  带 `FLAG_ACTIVITY_NEW_TASK` 的 `startActivity` 用 Application 也能跑，所以这个区别很
  容易被漏掉；一旦在它上面调 Activity 专属方法，`(context as? Activity)` 恒为 null，
  再配上 `?.` 就是**静默不执行**。凡是要用 Activity 的方法（`moveTaskToBack`、
  `finishAndRemoveTask`、`requestPermissions`…），都得走 `FrpcHost.currentActivity`——
  由 `MainActivity.onCreate` 调 `attachActivity(this)` 登记、`onDestroy` 摘掉。
  写这类入口时别用 `?.` 把 null 咽掉，打一条 `Log.w` 再兜底。

## 真机验证清单（对应迁移方案 M4 验收）

- [ ] `libfrpc.so` 在 arm64 真机可执行、隧道建立
- [ ] 前台服务通知合规（Android 8+ channel、13+ 通知权限）
- [ ] 切后台后隧道存活（前台服务在，通知常驻）
- [ ] 返回键：弹窗 → 关弹窗；子页面 → 回主页；主页 → 2s 内连按两次才退后台
      （第一下出「再按一次返回退出」，隧道不断、回来画面还在）
- [x] 覆盖安装不报签名不一致（固定密钥后）
- [ ] 开机自启（未实现：需要 BootReceiver + 持久化的「启动即连」）
- [ ] 低版本系统 WebView（Chrome 内核 < 90）页面渲染冒烟
