# Windows 桌面端四项修复 —— 独立验证报告

- 验证人：严过关（Yan）· QA
- 日期：2026-10-29
- 仓库：`C:/Users/Liao/Desktop/frpc-desktop-multi`，分支 `dev`，HEAD = `67c38c9`（工作区含未提交改动）
- 设计文档：`docs/design/windows-desktop-fixes.md`
- 约束遵守：全程**未 commit / 未 push / 未打 tag / 未触发 Release**；**未构建任何分发产物**
  （只跑了 `compileKotlin`、`jvmTest`/`testDebugUnitTest`、`GenTrayIcons.java --check`、Python 静态校验脚本）
- 验证原则：每条结论都给出**可复现的证据**（命令输出 / 文件:行号 / 探针实测），不采信"已完成"的口头结论

---

## 1. 总览

| # | 问题 | 状态 | 一句话结论 |
|---|---|---|---|
| 1 | 冷启动缓慢（T02） | ⚠️ 基本通过，有 1 个中风险 | 6 项改动全部落地、EDT 上已无 fork/字体枚举；但 `ProcessScanner` 的"零 fork"主路径**在本机实测恒不生效**，实际仍走 powershell 回退 |
| 2 | 托盘菜单点外部不关闭（T03） | ⚠️ 机制已验证，端到端**无法自动验证** | 我独立复现了"Robot 事件进不了 AWT"，并**实测确认**锚点 `isFocusableWindow()`、`popupWindow.isFocusableWindow()`、`windowGainedFocus` 三项均已由 false→true —— 修复方向正确；但"点外面真的会关"必须真机人工验收 |
| 3 | 图标不一致（T01） | ✅ 通过 | `--check` 全 PASS；我做了跨档位像素级结构比对（16 vs 512 对称差 ≤3/256），简化分支已彻底删除，SVG 与常量同步，10px 箭尖漂移已修 |
| 4 | 安装包图标 + 中文向导（T04） | ✅ 通过（2 处已修并复检） | YAML/清单/命名/BOM/一致性**全部通过**。首轮查出 `.iss` 把换行写成 `&&`（官方是 `%n`）与 2 条不存在的 message 名；工程师已修，**我已独立复检通过**（见 §12） |

**⚠️ 但仍有一项会阻断发版的版本口径不一致**（见缺陷 D-1，第二轮复检更新）：
`androidApp` 已改为 `versionCode = 8` / `versionName = "1.3.4"`，而 `desktopApp` 兜底版本仍是 `"1.3.1"` —— release.yml 的版本校验会因两者不等而 `exit 1`。

---

## 2. A. 编译与既有测试（回归底线）

### A-1 `./gradlew :desktopApp:compileKotlin`

```
> Task :desktopApp:compileKotlin
w: Main.kt:86 ... 'val Icons.Filled.Article' is deprecated
w: ProfileStore.kt:91:9 Expression is unused
w: FrpcSupervisor.kt:357:47 Condition is always 'true'
w: Components.kt:238 / HomeScreen.kt:405 / ProfileEditScreen.kt:133 ... (均为既有 deprecation)

BUILD SUCCESSFUL in 4m 1s
4 actionable tasks: 4 executed
EXIT=0
```

✅ 通过。加 `--rerun-tasks` 后是 `4 executed`（不是 UP-TO-DATE），确认强制重跑。

### A-2 三套单元测试

```
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest --rerun-tasks
...
> Task :shared:jvmTest
> Task :shared:testDebugUnitTest
> Task :androidApp:testDebugUnitTest
BUILD SUCCESSFUL in 2m 12s
52 actionable tasks: 52 executed          <-- 全部执行，无 UP-TO-DATE
```

用 XML 报告聚合（不是看 Gradle 那句 BUILD SUCCESSFUL）：

| 测试套件 | tests | failures+errors | skipped |
|---|---|---|---|
| `:shared:jvmTest`（13 个类） | 72 | 0 | 0 |
| `:shared:testDebugUnitTest`（13 个类） | 70 | 0 | 0 |
| `:androidApp:testDebugUnitTest`（3 个类） | 25 | 0 | 0 |
| **合计** | **167** | **0** | **0** |

✅ 通过。工程师 A 的说法独立复核成立。

### A-3 `:shared` 是否被改动

`git status --porcelain` 中**没有**任何 `shared/` 条目。✅ Android 端不受本轮影响（详见 §F）。

---

## 3. B. 问题 3 —— 图标一致性

### B-1 生成器自检

```
$ java scripts/gen-tray-icons/GenTrayIcons.java . --check
  [PASS] tray-16.png ... tray-256.png        (10 张)
  [PASS] tray-mac.png / tray-mac@2x.png
  [PASS] icon-16/20/24/32/40/48/64/128/256/512.png   (10 张)
  [PASS] icon.png (512)
  [PASS] icon.svg  与几何常量同步
check: OK（全部产物 + SVG 与常量同步）
退出码 0
```

### B-2 独立验证：跨档位图形结构是否真的相同（不采信工程师结论）

我不满足于"脚本自己说 PASS"，另外做了两件事：

**(a) 像素级结构比对**：把 10 个档位全部归一化到 16×16（Lanczos），取"不透明且近白"的像素集合做对称差，以 `icon-512` 为基准：

```
icon-512 ink px: 26
  icon- 16: ink= 28  symdiff_vs_512=  2
  icon- 20: ink= 29  symdiff_vs_512=  3
  icon- 24: ink= 29  symdiff_vs_512=  3
  icon- 32: ink= 29  symdiff_vs_512=  3
  icon- 40: ink= 28  symdiff_vs_512=  2
  icon- 48: ink= 28  symdiff_vs_512=  2
  icon- 64: ink= 27  symdiff_vs_512=  1
  icon-128: ink= 26  symdiff_vs_512=  0
  icon-256: ink= 27  symdiff_vs_512=  1
  icon-512: ink= 26  symdiff_vs_512=  0
  tray-16 : ink= 28  symdiff_vs_512=  2
  16 vs 24 symdiff: 1      24 vs 48 symdiff: 1
```

256 个像素里最大只差 3 个（且都是抗锯齿边缘像素），**说明不存在"某个档位少画/多画一个元素"**。
若有残留的简化分支（删圆点 / 换三角），对称差会是 10~30 量级。

**(b) ASCII 掩码目视核对**（48×48 归一化，`#`=实心、`+`=边缘）：`tray-16` / `tray-24` / `icon-512` 三者都能看到
`拱门（两竖柱 + 顶部半圆）` + `中段横向笔画` + `箭尖三角` + `源点圆点`，形状一致，只是边缘像素多少不同。
（比对图已存到 `.build/qa-icon-structure-16.png` 与 `.build/qa-icon-structure-48.png`，被 `.gitignore` 的 `.build/` 覆盖。）

**(c) 简化分支确实被删**：

```
$ grep -n "simplified" scripts/gen-tray-icons/GenTrayIcons.java   -> (none)
$ git show HEAD:scripts/gen-tray-icons/GenTrayIcons.java | grep -n "simplified"
   137: downsampled(int size, boolean simplified, boolean template)
   224: static Area glyph(float s, boolean simplified)   <-- HEAD 里确实有两套画法
```

✅ 旧的 `size <= 24 → simplified` 分支已彻底移除。

### B-3 `icon.ico` 尺寸档位

自己解析 PE 头（不依赖生成器日志）：

```
reserved 0 type 1 count 9
entry 0: 16x16 bpp=32 png=True     entry 5: 48x48   bpp=32 png=True
entry 1: 20x20 bpp=32 png=True     entry 6: 64x64   bpp=32 png=True
entry 2: 24x24 bpp=32 png=True     entry 7: 128x128 bpp=32 png=True
entry 3: 32x32 bpp=32 png=True     entry 8: 0x0(=256) bpp=32 png=True
entry 4: 40x40 bpp=32 png=True
```

✅ 9 档 16..256 全 PNG 编码，与设计 §8(a) 一致。

### B-4 `icon.svg` 与 Java 几何常量同步

`desktopApp/icon.svg:21`：

```xml
<path d="M272 286 L312 310 L272 334 Z" fill="#FFFFFF"/>
```

反推常量：`ARROW_X1=0.559 → 286`；`tipX = round(0.078×512×0.65)=26 → 312`；`tipBack = round(0.078×512×0.35)=14 → 272`；`halfY = round(0.047×512)=24 → 286/334`。
✅ 正是设计文档第 399-400 行指出的 `282/322 → 272/312` 的 10px 漂移，已被修掉。
`--check` 的 `checkSvg` 逐字节比对也报 PASS（SVG 由 `--sync-svg` 反向生成，禁止手改）。

### B-5 接入点

- `Main.kt:309` `icon = remember { AppIcons.windowPainter() }` ✅（旧的 `appIconPainter()` 已彻底删除，`grep -rn appIconPainter desktopApp/` → none）
- `Main.kt:329-330` `val images = AppIcons.windowIconImages(); if (images.isNotEmpty()) window.iconImages = images` ✅ 多尺寸交给 AWT
- `desktopApp/src/main/resources/icon/` 10 张齐备 ✅
- `AppIcons.WINDOW_SIZES` = `{16,20,24,32,40,48,64,128,256,512}` 与 `GenTrayIcons.WINDOW_SIZES` 逐项一致 ✅
- `AppIcons.COLOR_SIZES` = `{16,20,22,24,32,40,48,64,128,256}` 与 `GenTrayIcons.COLOR_SIZES` 一致 ✅
- `AppTray.loadTrayImage()` 已委托 `AppIcons.trayImage()` ✅

### B-6 一个诚实的观察（低）

16px 档位上，源点圆点与箭头横线**视觉上粘连成一个色块**：
`DOT_CX×16 = 6.128`、`DOT_R×16×boost(1.1333) = 0.544` ⇒ 圆点右边缘 ≈ 6.67；
`ARROW_X0×16 = 6.752` ⇒ 间隙只有 **0.08px**。
结构上"同一个图形"的目标达成了，但 100% DPI 的 Windows 托盘上看不出独立的源点。这属于 16px 物理极限，不是缺陷，仅记录（见 §6 遗留风险 R-4）。

---

## 4. C. 问题 1 —— 冷启动路径

### C-1 EDT 上是否还有阻塞操作（逐项静态核查）

| 设计编号 | 改动 | 证据 | 结论 |
|---|---|---|---|
| A2 | `OsTheme` 拆分 | `Main.kt:201-213`：`remember(settings.theme) { ... OsTheme.prefersDarkCached() ?: false }`；`LaunchedEffect(settings.theme) { OsTheme.prefersDarkAsync(this) { darkMenu = it } }`。`prefersDarkCached()`（`OsTheme.kt:43-49`）在 Windows 上只返回 `cached`，绝不 fork ✅ | ✅ |
| A3 | `AppTray.warmUp()` | `Main.kt:117` `Thread({ runCatching { AppTray.warmUp() } }, "frpc-tray-warmup").apply { isDaemon = true }.start()`，在 `app()` 之前 ✅；`AppTray.kt:158-164` 只做 `installedFontFamilies()` + `AppIcons.warmUp()`，**不碰任何 AWT 组件**；macOS 直接 return（`AppTray.kt:159`）✅ | ✅ |
| A3 | 字体名单缓存 | `AppTray.kt:704-714` `@Volatile fontFamilies` + `synchronized(fontFamiliesLock)`；`MENU_FONT`（`:722-730`）从缓存取 ✅ | ✅ |
| A4 | 图标缓存 | `ui/AppIcons.kt` `@Volatile windowImages / windowPainterRef / cache`，`synchronized(lock)` ✅ | ✅ |
| A5 | `bundledVersion()` 缓存 | `EngineManager.kt:26-40` `@Volatile bundledVersionCache` ✅ | ✅（设计里"再套一层 remember"没做，见缺陷 D-6，成本≈0，低） |
| A6 | `cleanupOrphans` 条件化 + 延后 | 见 C-2 | ✅ |
| A7 | 自动连接等 `load()` | 见 C-3 | ✅ |

**EDT 路径上剩余的同步 IO**：只有 `SettingsStore` 构造期读 `settings.json`（设计明确"不建议动"）+ `CleanShutdownFlag.markStart()` 写一行 `.running`（`AppContainer.kt:32`）。
`grep -rn "ProcessBuilder|Runtime.getRuntime().exec"` 命中的 8 处全部在 `OsTheme`(IO 线程) / `EngineManager`(惰性) / `FrpcSupervisor`(IO) / `ProcessMetrics`，**没有一处落在 EDT 启动路径上** ✅

### C-2 `cleanupOrphans` 延后 + `--silent` 补一次（重点查的项）

- 有窗口：`Main.kt:335` 在 `Window { LaunchedEffect(Unit) { ... StartupTrace.mark("window ready"); container.cleanupOrphansIfNeeded() } }` 内 —— 即**首帧之后** ✅
- `--silent`（无窗口）：`Main.kt:292-297`
  ```kotlin
  LaunchedEffect(Unit) {
      AppTray.install(::handleTrayEvent)
      StartupTrace.mark("tray installed")
      // --silent（开机自启）时没有主窗口，窗口里那个「首帧之后」的钩子不会跑，这里补一次。
      if (!windowVisible) container.cleanupOrphansIfNeeded()
  }
  ```
  ✅ **这条没漏**。而且 `--silent` 之后用户再点"显示主界面"，窗口那个 effect 会再调一次，被 `orphanScanDone` 幂等拦掉 ✅
- `AppContainer.kt:51-60`：`orphanScanDone` 幂等；`!orphanScanNeeded` 时打 `cleanupOrphans skipped (last shutdown was clean)` 后直接返回，不 fork ✅
- `CleanShutdownFlag`（`core/CleanShutdownFlag.kt:32-44`）：先判 `file.exists()` 再写，顺序正确；`runCatching` 包住；`markClean()` 只在 `AppContainer.shutdown()`（`:66`）调用，被强杀时文件留下 → 下次判脏 ✅

### C-3 自动连接不再依赖 `delay(500)`

- `grep -n "delay(500" Main.kt` → 只剩第 217 行的注释，无代码 ✅
- `Main.kt:222` `container.profiles.awaitLoaded()` → `ProfileStore.awaitLoaded(timeoutMs = 5_000)`
  ```kotlin
  suspend fun awaitLoaded(timeoutMs: Long = 5_000L) {
      if (_loaded.value) return
      withTimeoutOrNull(timeoutMs) { _loaded.first { it } }
      Unit
  }
  ```
  ✅ 超时兜底正确（`withTimeoutOrNull` 不会抛异常挂死）
- **解析失败也算 loaded**：`ProfileStore.load()`（已 diff 核对）在 `!file.exists()` 与 `runCatching` 的 `onFailure` 之后都置 `_loaded.value = true` ✅ 不会永久挂住

### C-4 `ProcessScanner` / `CleanShutdownFlag` 异常处理

- `ProcessScanner.supportsCommandLine()` / `pidsByCommandLine()` / `kill()` 每个进程都 `runCatching` ✅
- `supportsCommandLine()` 结果缓存到 `@Volatile` ✅
- `CleanShutdownFlag` 全 `runCatching` ✅
- ⚠️ 但本机实测发现主路径根本不生效 —— 见缺陷 D-2

### C-5 `StartupTrace` 埋点

里程碑全 ASCII ✅（Windows GBK 控制台不会乱码）：
`main entered` / `single instance acquired` / `container created` / `container.init orphanScanNeeded=…` / `tray.warmUp` / `osTheme.probe` / `profiles loaded` / `autoConnect: no profile` / `autoConnect start` / `tray installed` / `window ready` / `cleanupOrphans skipped (…)` / `cleanupOrphans done killed=…`

`t0` 取 `RuntimeMXBean.startTime()` ✅；`marks` 用 `CopyOnWriteArrayList`（多线程安全）✅；`-Dfrpc.startupTrace=true` 才打印 ✅。
覆盖到了关键里程碑 ✅（唯一遗憾：没有"首帧渲染完成"这个点，`window ready` 是最近的近似）。

---

## 5. D. 问题 2 —— 托盘菜单（最大验收缺口）

### D-1 先独立复现"Robot 输入打不进 AWT"

我写了独立探针（`/tmp/qaprobe/TrayProbe2.java`，未进仓库），用 `java.awt.Robot` 对**三种目标**分别投事件，并挂全局 `AWTEventListener(MOUSE|KEY)` 计数：

```
--- A) Robot 点主窗口（普通 JFrame）---   presses delta = 0
--- B) Robot 点弹层内部 ---------------   presses delta = 0
--- C) Robot 按 Esc -------------------   keys delta    = 0
```

**连一个普通 `JFrame` 都收不到 Robot 的鼠标按下事件**，说明这是**环境限制**（本机该会话不在可接收合成输入的 window station / input desktop），不是弹层特有的问题。
用 `Toolkit.getAWTEventListeners()` 能正常读到 3 个已注册监听，说明 AWT 事件机制本身是活的。

✅ **工程师 A 的说法独立复现成立：本会话无法做端到端自动化。**
（同样的探针在 PowerShell 里跑也是 0，排除 Bash 沙箱因素。）

### D-2 退而求其次：机制性验证 —— **三项关键断言全部实测为真**

同一探针（`JDialog(null as Frame?, "", false, gc)` + `isUndecorated` + `Type.POPUP` + `focusableWindowState=true`，内容面板 `isFocusable=true`，与真实 `MenuPanel` 一致）：

```
PROBE old JWindow(POPUP, focusable=false).isFocusableWindow  = false
PROBE new JDialog(POPUP, undecorated, focusable=true).isFocusableWindow = true   <-- ① 由 false 变 true

popupWindow = javax.swing.Popup$HeavyWeightWindow
popupWindow.name = ###focusableSwingPopup###
popupWindow.focusableWindowState = true
popupWindow.isFocusableWindow = true                                             <-- ② 由 false 变 true
popup gainedFocus? true                                                          <-- ③ windowGainedFocus 真的来了
  panel isFocusOwner = true                                                      <-- 内容真的拿到焦点
  activeWindow = javax.swing.JDialog[dialog0,700,200,1x1,...]                     （锚点成了 active window）
```

🔎 说明：
- ① `Window.isFocusableWindow()` 的 `this is Dialog → true` 分支确实被走通；
- ② 弹层窗口被 `PopupFactory` 改名成 `###focusableSwingPopup###` 并置为可聚焦 —— **这一步依赖内容面板 `isFocusable=true`，真实 `MenuPanel`（`AppTray.kt:773` `isFocusable = true`）满足**；
  （我第一版探针用了不可聚焦的 `JPanel`，量出来是 `false`；换成可聚焦面板后是 `true`。这一点说明"弹层可聚焦"是有前提条件的，值得写进真机验收要点。）
- ③ 焦点链恢复 ⇒ `panel.requestFocusInWindow()` 现在会成功 ⇒ `WHEN_IN_FOCUSED_WINDOW` 上的 Esc 绑定能触发。

**结论：修复方向被独立证实。** 三个症状（点外部不关 / Esc 不关 / 失焦不关）的共同根因被消除。

### D-3 监听成对摘除：20 次开关是否累积

探针用 `Toolkit.getAWTEventListeners().length` 做客观计数，跑 `DismissController` 同款幂等 attach/detach 逻辑：

```
PROBE AWTEventListener baseline            = 3
PROBE after 20 attach/detach cycles        = 3   (leak=0)
```

✅ 幂等 attach/detach 本身不泄漏。再结合源码复核 `AppTray.kt`：

- `DismissController.attach()`（`AppTray.kt:598-609`）：`if (listener != null) return` 幂等 ✅
- `DismissController.detach()`（`:611-616`）：`listener = null` 后再 remove，可重复调用 ✅
- **唯一摘除点核查**：所有关闭路径都汇聚到 `handleClosed()`（`:493-501`）
  - 点外面 / Esc → `closer()` → `close(auto=true)` → `popup.isVisible=false` → `popupMenuWillBecomeInvisible` → `handleClosed`；`close()` 末尾再兜一次（`:485`，`if (!opened) return` 幂等）✅
  - 失焦/失活 → `closeOnFocusLoss()` → `close(true)` ✅
  - JDK grab 取消 → `popupMenuCanceled` → `handleClosed(auto=true)` ✅
  - 点菜单项 → `onPicked` → `close(auto=false)` ✅
  - 再点托盘 → `toggleMenu()` → `close(auto=false)` ✅
  - 退出 → `dispose()` → `close(false)` + 兜底 `dismiss.detach()` / `detachFocusCloser()` ✅
- `open()`（`:419-460`）在 `show()` 之后有个"若已同步关闭就不再挂监听"的兜底（`:452-455`）✅ —— 这条很容易漏，工程师写上了 ✅
- `invokerFor()` 换锚点时先 `removeWindowListener(anchorCloser)` 再 dispose ✅
- `detachFocusCloser()`（`:503-510`）先 remove 再置 `popupWindow = null` ✅

**未发现监听泄漏路径。**

### D-4 `DismissController` 命中判定逻辑审查

```kotlin
private fun onPressed(e: MouseEvent) {
    if (e.component == null) return                       // 托盘图标事件
    val bounds = boundsProvider() ?: return               // 还没弹出 -> 一律不关
    if (bounds.contains(e.xOnScreen, e.yOnScreen)) return // 在弹层内
    closer()                                              // 不 consume
}
```

- `e.component == null` 判据正确：`TrayIcon` 不是 `Component`，`MouseEvent.getComponent()` 对非 Component 的 source 返回 null ⇒ **点托盘图标本身不会被误关，交给 `toggleMenu` 处理** ✅
- 矩形取 `window.locationOnScreen + size` 外扩 2px（`AppTray.kt:472-479`）✅ 覆盖了 1px 边框和阴影
- 未 `consume()` ✅ 符合"关掉但把点击交给下面"的原生语义
- Esc 走 `KEY_PRESSED`（`:621-623`），**不依赖焦点** ✅
- `boundsProvider()` 返回 null（弹层未定位）时不关 ✅

**边界风险**：`OPEN_GRACE_MS=250` 只保护 `closeOnFocusLoss()`，不保护 `onPressed`。这是**正确的**（弹出瞬间用户真点外面就该立刻关）。

### D-5 三个时间常数是否打架

| 常数 | 值 | 作用 | 冲突检查 |
|---|---|---|---|
| `TOGGLE_DEDUPE_MS` | 50 | 同一次物理点击 pressed/released 去重 | 与另外两者独立 ✅ |
| `AUTO_REOPEN_GUARD_MS` | 250 | 自动关闭后 250ms 内托盘点击不重开 | ✅ |
| `OPEN_GRACE_MS` | 250 | 弹出瞬间的焦点倒手不算"点到外面" | ✅ |

推演"再点一次托盘图标"：
- 若 Windows 因点击托盘让弹层失活 → `focusCloser/anchorCloser` → `close(auto=true)`，`lastAutoCloseMs=t` → 随后 `mouseReleased` → `toggleMenu`，`now - lastAutoCloseMs ≈ 0 < 250` ⇒ **不开**，菜单保持关闭 ✅（符合验收标准"关闭且不立刻重开"）
- 若弹层未失活 → `toggleMenu` 见 `opened == true` → `close(auto=false)` ✅
两条路都正确收敛。**未发现打架。**

`windowLostFocus` 以前不触发、现在会触发的"秒关"风险，已由 `OPEN_GRACE_MS` + `anchorCloser` **只听 `windowDeactivated`**（`AppTray.kt:307-313`，注释解释了"锚点丢焦点比弹层拿到焦点早 1ms") 双重缓解 ✅

### D-6 硬约束：既有能力是否被破坏（逐项 diff 比对 HEAD）

| 项 | 比对结果 |
|---|---|
| `MENU_FONT`（`AppTray.kt:722-730`） | 候选顺序 `"Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun", "Noto Sans CJK SC", "PingFang SC"` 与 HEAD **逐字一致**；`MENU_FONT_SIZE = 13` 不变 ✅ |
| `Metrics` / `measure()` | `padX=14 / padV=6 / rowH=max(26, fm.height+10) / sep=9 / width.coerceIn(120,280)` 与 HEAD **逐项一致** ✅ |
| `paintComponent` | hairline `1f/scale`、半像素对齐、边框矩形与 HEAD 一致；只移除了 `disabled` 分支（新菜单无禁用项）和把 `right` 提到循环外（等价）✅ |
| `Palette` | `bg/fg/selBg/selFg/border/separator` 六个色值**逐值一致**；`disabled` 随禁用态一起移除 ✅ |
| `resolvePlacement` | 与 HEAD **逻辑逐行一致**（只多传了 `scale`）✅ |
| 定位数学（`menuLocation`） | `gap = (4*scale).roundToInt()`、四条边的方向、可用区 clamp 与 HEAD 一致 ✅ |
| DPI 逻辑 | 字号固定 13pt 不乘倍率、整设备像素 snap、`1/scale` 发丝线全部保留 ✅ |
| 是否退回 AWT 原生菜单 | `grep "java.awt.PopupMenu\|java.awt.MenuItem\|new PopupMenu\|MenuItem("` → **none**；外层仍是 `JPopupMenu` + 自绘 `MenuPanel` ✅（中文方框的线上问题不会复现） |
| 50ms 去重 / 平台 pressed-released 差异 | `TOGGLE_DEDUPE_MS`、`PLATFORM` 三分支、`mousePressed/mouseReleased` 与 HEAD 一致 ✅ |

✅ 硬约束全部守住。

---

## 6. E. 问题 4 —— 打包与发布配置

### E-1 YAML 可解析 + 硬约束

```
.github/workflows/release.yml: parsed OK, jobs=['version','desktop','android','release','summary']
   step names containing ": ": none
.github/workflows/ci.yml:      parsed OK, jobs=['manifest','shared','android','desktop-windows','protocols']
   step names containing ": ": [('shared','Unit tests (shared: jvm + android)')]   <-- 既有且已加引号，新 ci.yml 的 lint 会把它列为 warning
```
✅ `yaml.safe_load` 通过。`release.yml` 里 `| tee` 的块均有 `set -euo pipefail` ✅（新加的 ISCC 步骤第一行就是 `set -euo pipefail`）。

### E-2 交叉校验一致性（逐字符）

| 检查项 | 值 | 结论 |
|---|---|---|
| `release.yml:49` `PRODUCT` | `Frpc-Android-Multi` | |
| `desktopApp/build.gradle.kts` `packageName` | `Frpc-Android-Multi` | |
| `.iss:39` `#define MyAppName` | `Frpc-Android-Multi` | ✅ 三者一致 |
| `build.gradle.kts`（windows）`upgradeUuid` | `9f2b6f7c-0f4a-4a3f-9d1c-8f5b1d2a6c31` | |
| `.iss:52` `#define MyAppUUID` | `9f2b6f7c-0f4a-4a3f-9d1c-8f5b1d2a6c31` | ✅ 逐字符含大小写一致（机器级变体）；用户级 = 同值 + `-user`（`.iss:65`）✅ 符合设计 |
| `.iss:40` `MyAppExeName` | `Frpc-Android-Multi.exe` | ✅ 与 `packageName` 对应 |

### E-3 产物命名与清单一致性

```
$ python3 scripts/ci/check-release-manifest.py
=== 发布前必需清单（11 项，V=1.3.1 P=Frpc-Android-Multi）===
   ... Frpc-Android-Multi-1.3.1-windows-x64-setup-machine.exe
   ... Frpc-Android-Multi-1.3.1-windows-x64-setup.exe
必需但没有任何 job 产出 : 无
可选但没有任何 job 产出 : 无
产出但不在校验清单里    : 无
EXIT=0
```
✅ 两个新包都在清单内、都有 `take` 产出。
`take "desktopApp/build/inno/user" '*-setup.exe'` 与 `take "desktopApp/build/inno/machine" '*-setup-machine.exe'` 的 glob 不会互相误匹配，也不会抓到 app-image 里的同名 launcher ✅
`V` 已改为从 `build.gradle.kts` 现读 ✅（脚本自证：输出 `V=1.3.1`，与 `desktopApp/build.gradle.kts:27` 一致）

### E-4 BOM

```
first 8 bytes: ef bb bf 3b 20 3d 3d 3d
BOM present: True
```
✅ 首 3 字节 `EF BB BF`。（`ci.yml` 新增的 `Check Inno Setup script encoding` 步骤会在 PR 阶段守住它 ✅）

### E-5 `release.yml` 已无 WiX / packageExe

```
$ grep -n "packageExe\|WiX\|candle" .github/workflows/release.yml
222:              # 不再跑 packageExe（jpackage + WiX）：它既不写 installer 图标，   <-- 注释
646:            echo "> 旧版是 jpackage/WiX 的 MSI，两者卸载表不兼容 ..."             <-- Release notes 文案
```
✅ `Install WiX Toolset` 步骤已删（diff 显示 -14 行）、`packageExe` 调用已删、无 `candle`/`wixtoolset` 调用。仅剩注释里的字样，无害。

### E-6 `.iss` 无硬编码绝对路径

三处机器相关路径都有 `/D` 覆盖 + 相对 `.iss` 的默认：
```
#ifndef MySourceDir  "..\..\desktopApp\build\compose\binaries\main\app\" + MyAppName
#ifndef MyIconFile   "..\..\desktopApp\icon.ico"
#ifndef MyOutputDir  "..\..\desktopApp\build\inno"
```
✅ 版本走 `/DMyAppVersion`，输出子目录由变体决定（`user` / `machine`）✅

### E-7 `.iss` `[Messages]` 审查 —— **发现 2 处问题**

先把官方事实钉死（我下载了 Inno Setup 官方源码 `jrsoftware/issrc` 的 `Files/Default.isl`，6.2.2 与 main(6.5.0+) 两个版本都查了）：

```
Default.isl (main, 6.5.0+):  "&&" 出现 0 次；"%n" 出现 30 次
Default.isl (is-6_2_2):      "&&" 出现 0 次；"%n" 出现 69 次
```
官方文档 `topic_messagessection`：*"On messages with arguments, use two consecutive '%' characters to embed a single '%'. **'%n' creates a line break.**"* —— **没有任何地方说 `&&` 是换行。**

**问题 1（高）：`.iss` 用 `&&` 当换行，9 行 / 11 处**

```
line 164: WelcomeLabel2=本向导将把 %1 安装到你的电脑。&&建议在开始安装之前关闭其它正在运行的程序。
line 165: SelectDirLabel3=...&&单击“下一步”继续。...
line 167: SelectStartMenuFolderLabel3=...&&单击“下一步”继续。
line 177: PreparingLabel=...&&请稍候……
line 178: InstallingLabel=...&&请稍候……
line 180: FinishedLabel=...&&单击“完成”退出安装程序。
line 186: ExitSetupMessage=...&&你可以以后再次运行...&&确定要退出安装程序吗？
line 192: SetupAppRunningError=...&&请先关闭它的所有窗口...
line 197: UninstallingLabel=...&&请稍候……
line 199: UninstallAppRunningError=...&&请先关闭它的所有窗口...
```
这些位置**本意是分段换行**，实际会渲染成字面 `&&`（或一个 `&`）。安装向导是本次交付的核心卖点之一，"欢迎页/准备安装页"上出现 `&&` 是肉眼可见的瑕疵。

**问题 2（中）：2 条 message 名在 Inno Setup 里不存在**

用官方 `Default.isl`（6.5.0+ 与 6.2.2 两份）交叉比对 49 条 `[Messages]`：
```
UNKNOWN ids (2):
  line 177: PreparingLabel        <-- 正确名是 PreparingDesc
  line 197: UninstallingLabel     <-- 正确名是 StatusUninstalling
```
官方行为（`CompMsgs.pas`）：`Message name "%s" is not recognized by this version of Inno Setup. Ignoring.`
⇒ **不会让 ISCC 报错终止**（只是 warning），但这两条覆盖**完全失效**。
实际影响有限：这两页会回落到 `ChineseSimplified.isl` 的自带中文（`PreparingDesc=安装程序正在准备安装 [name] 到您的计算机。` / `StatusUninstalling=正在卸载 %1...`），仍然不是英文。
但会在 CI 日志里留下 2 条 warning，且是死代码。

其余 47 条 ID 全部在官方 `Default.isl` 中存在 ✅；无重复 key ✅；`%1` 占位符用法与官方一致 ✅。

### E-8 `--silent` / 自动启动路径

- `.iss:216` `[Run] ... Flags: nowait postinstall skipifsilent` ⇒ 静默安装不启动程序 ✅ 合理
- `--silent` 时应用只进托盘（`Main.kt:142`），且 `cleanupOrphansIfNeeded()` 已补（见 C-2）✅
- `autoStartOnLaunch`（`SettingsStore.kt:21`）语义是"启动后自动连接配置"，**不是**写注册表开机启动项；`--silent` 需外部（快捷方式参数）传入 ⇒ **安装目录从 `{pf}` 改成 `{autopf}` 不会破坏任何注册表启动项** ✅
- 桌面快捷方式默认不勾（`.iss:203` `Flags: unchecked`）、开始菜单快捷方式无条件建 ✅

---

## 7. F. 两端一视同仁

- `git status` 中**无** `shared/` 改动 ⇒ `:shared` 未被本轮触碰 ✅
- Android launcher 图标不引用 `icon.svg` / `icon.png`（`grep -rn "icon.svg\|icon.png" androidApp/` → none）✅ 与 `--sync-svg` 重写 `icon.svg` 无冲突
- 4 项修复均为 Windows 桌面端特有，Android 端无对应能力缺失（无"桌面端有、Android 端该有却没有"的新增缺口）✅
- ⚠️ 但 `androidApp/build.gradle.kts` 在工作区里被改了版本号，见缺陷 D-1

---

## 8. 缺陷清单（按严重级别）

### 🔴 D-1【高 · 发版阻断】androidApp 与 desktopApp 的版本号不一致

> **第二轮复检（13:31）更新**：工程师已把 `androidApp` 改成 `versionCode = 8` / `versionName = "1.3.4"`，
> 并加了"versionCode 只增不减"的注释 —— 首轮报的"回退到 5 / 1.3.1"**已解决**。
> 但 `desktopApp` 的兜底版本**没跟着改**，于是产生新的不一致，后果比首轮更直接。

- **文件**：`androidApp/build.gradle.kts:18-19`、`desktopApp/build.gradle.kts:27`
- **现状（实测 grep，用的是 release.yml 里那一模一样的命令）**：
  ```
  ANDROID_V = 1.3.4      <- grep -oE 'versionName = "[0-9][0-9.]*"' androidApp/build.gradle.kts | head -1
  DESKTOP_V = 1.3.1      <- grep -oE '"[0-9]+\.[0-9]+\.[0-9]+"' desktopApp/build.gradle.kts | tail -1
  ```
  （`desktopApp/build.gradle.kts` 里只有一个 `x.y.z`，就在第 27 行，所以 `tail -1` 取的确实是兜底版本）
- **复现**：`grep -n "versionCode\|versionName" androidApp/build.gradle.kts; grep -n '"1\.3\.' desktopApp/build.gradle.kts`
- **影响 —— 发版直接失败**：`release.yml` 的 `Resolve and validate version` 步骤里有
  ```bash
  if [ -n "$DESKTOP_V" ] && [ "$V" != "$DESKTOP_V" ]; then
      echo "::error::版本不一致 - 本次要发 $V，但 desktopApp/build.gradle.kts 的兜底版本是 $DESKTOP_V。两处必须一致。"
      exit 1
  fi
  ```
  推演：
  - tag push `v1.3.4` → `V=1.3.4`，`ANDROID_V` 通过，`DESKTOP_V=1.3.1` 不等 → **exit 1**
  - tag push `v1.3.1` → `V=1.3.1`，`ANDROID_V=1.3.4` 不等 → **exit 1**
  - workflow_dispatch 留空 → `V=ANDROID_V=1.3.4` → `DESKTOP_V` 不等 → **exit 1**
  - 唯一能过的是勾 `skip_version_check=true`（那样产物名 `1.3.4` 而 App「关于」页显示 `1.3.1`，仍是对不上的）
- **建议修复**：`desktopApp/build.gradle.kts:27` 的 `"1.3.1"` → `"1.3.4"`（一行）。
- **我不自行修改**：该文件 13:01 刚被改过、`androidApp` 13:29 还在被并行编辑，我插入编辑有冲突风险；且版本号属交付决策，交给 team-lead 转交。

### 🟠 D-2【中高】`ProcessScanner` 的"零 fork"主路径在本机恒不生效，新代码从未被真实执行

- **文件**：`runtime/ProcessScanner.kt:30-45,53-64`、`engine/FrpcSupervisor.kt:216-224`
- **证据**（两个 JDK、两种 shell 都复现）：
  ```
  JDK 17.0.20.1 (Microsoft) / JDK 21.0.13 (Corretto)，Windows 10
  SELF commandLine = <empty>          <-- 连自己都读不到
  CHILD(alive) commandLine = <empty>  <-- 自己拉起的子进程也读不到
  ALL processes=441  command.present=218  commandLine.present=0
  ⇒ supportsCommandLine() = false
  ⇒ pidsByCommandLine(TOKEN) = [] （真实子进程 pid 明明活着）
  ```
- **影响**：`killOrphans()` 在本机 100% 走 `killOrphansByShell()`（powershell CIM，1.5~4s）。
  - 好消息：回退路径保留完好，**功能无回归**；且 `CleanShutdownFlag` 让正常退出时**整段跳过**，这才是最大的那笔收益，它与 `supportsCommandLine()` 无关，**仍然生效**。
  - 坏消息：`ProcessScanner` 的 `pidsByCommandLine` / `kill` 两条新路径在本机**从未真正跑过**，其中任何 bug 都是潜伏的。
- **建议**：
  1. 真机上用 `-Dfrpc.startupTrace=true` 跑一次异常退出后的启动，看日志里 `cleanupOrphans done killed=N` 之前有没有 `ProcessScanner` 的痕迹（可临时加一行 mark 区分两条路径）；
  2. 至少让 `supportsCommandLine()` 的抽样**优先取本进程及其子进程**（一定能读到），避免"抽到的 50 个恰好都是系统进程"导致误判 false。
- **不阻断交付**（回退正确），但**设计假设未在本机成立**，必须写进遗留风险。

### 🟠 D-3【中】`.iss` `[Messages]` 把换行写成了 `&&`（11 处 / 9 行）

- **文件**：`packaging/windows/Frpc-Android-Multi.iss:164,165,167,177,178,180,186,192,197,199`
- **证据**：Inno Setup 官方 `Default.isl`（6.2.2 与 6.5.0+）中 `&&` 出现 **0** 次、`%n` 出现 30/69 次；官方文档明确 `"%n" creates a line break`。
- **影响**：安装向导这些位置显示字面 `&&`（或单个 `&`）而不是分段换行。**中文向导是本次交付的核心，这是肉眼可见的缺陷。**
- **建议修复**：把这 11 处 `&&` 全部替换成 `%n%n`（段落间）或 `%n`。例：
  `WelcomeLabel2=本向导将把 %1 安装到你的电脑。%n%n建议在开始安装之前关闭其它正在运行的程序。`
- **注意**：`ButtonNext=下一步(&N) >` 这类**单个 `&`** 是加速键标记，**不要动**。

### 🟡 D-4【中】`.iss` 有 2 条 Inno Setup 不认识的 message 名

- **文件**：`packaging/windows/Frpc-Android-Multi.iss:177`（`PreparingLabel`）、`:197`（`UninstallingLabel`）
- **证据**：官方 `Default.isl`（6.2.2 / 6.5.0+）均无这两个 ID；正确名是 `PreparingDesc` 与 `StatusUninstalling`。
- **影响**：ISCC 输出 2 条 `Message name "..." is not recognized ... Ignoring.` 警告（**不会终止编译**），这两条覆盖失效，回落到 `ChineseSimplified.isl` 的自带中文。
- **建议修复**：`PreparingLabel` → `PreparingDesc`；`UninstallingLabel` → `StatusUninstalling`。同时把这两行里的 `&&` 一并改成 `%n%n`。

### 🟡 D-5【中】`ProcessScanner.kill()` 忽略 `destroyForcibly()` 的返回值

- **文件**：`runtime/ProcessScanner.kt:67-81`
- **现象**：`ok` 恒为 `true`（只要没抛异常），`destroyForcibly()` 返回的 Boolean 被丢弃 ⇒ 可能把"没杀掉"报成"杀掉了"（`killed` 计数偏大），进而打出误导性的系统日志"已清理上次残留的 N 个 frpc 进程"。
- **建议**：`val ok = runCatching { if (handle.destroyForcibly()) { handle.onExit().get(2, SECONDS); true } else false }.getOrDefault(false)`

### 🟢 D-6【低】`supportsCommandLine()` 只抽样前 50 个进程

- **文件**：`runtime/ProcessScanner.kt:20,35-41`
- **现象**：`ProcessHandle.allProcesses()` 大致按 PID 升序，前 50 个多为系统进程。若某台机器只有用户进程可读而系统进程不可读，会误判 `false` → 白白 fork 一次 powershell。
- **建议**：抽样时把 `ProcessHandle.current()` 与其子进程优先纳入，或把 `PROBE_SAMPLE` 提到 200（多读几十个进程的开销远小于一次 powershell）。

### 🟢 D-7【低】`cleanupOrphansIfNeeded()` 的幂等标记非原子

- **文件**：`AppContainer.kt:51-58`
- **现象**：`if (orphanScanDone) return; orphanScanDone = true` 是 check-then-set，理论上两个调用点并发时会双扫（`supervisor.cleanupOrphans()` 是 `scope.launch`，双扫只多一次进程扫描，不致命）。
- **建议**：改用 `AtomicBoolean.compareAndSet(false, true)`。

### 🟢 D-8【低】`Main.kt` 未按设计给 `bundledVersion()` 再包一层 `remember`

- **文件**：`desktopApp/src/main/kotlin/com/frpc/desktop/Main.kt:363`
- **现象**：`Sidebar(version = container.engine.bundledVersion())` 每次重组仍会调用一次函数。函数内部已有 `@Volatile` 缓存，成本 ≈ 0。
- **建议**：`val engineVersion = remember { container.engine.bundledVersion() }`（可选，非必须）。

### 🟢 D-9【低】编译警告：`ProfileStore.kt:91` 多余表达式

- **文件**：`data/ProfileStore.kt:91`
- **现象**：`w: ProfileStore.kt:91:9 Expression is unused`（`awaitLoaded` 末尾的 `Unit`）。
- **建议**：删掉裸 `Unit`（suspend 函数返回 Unit 不需要显式写），或改成 `return withTimeoutOrNull(...) { ... }?.let { }`。

### 🟢 D-10【低】`smallBoost` 实际最大值与设计文档不符

- **文件**：`scripts/gen-tray-icons/GenTrayIcons.java:107,254-257`
- **现象**：`MAX_SMALL_BOOST = 1.20f`，但 `smallBoost(size) = 1 + (48-size)*0.2/48`，`size=16` 时只有 **1.133**，要到 `size=0` 才是 1.20。设计文档 §3.2 写"16px 最大 1.20x"。
- **影响**：无功能影响（1.133 已够），是文档/实现的口径不一致。
- **建议**：把文档改成"小尺寸最多放大约 1.13x"，或把公式改成以 16px 为上限插值。

### 🟢 D-11【低】CHANGELOG 描述与代码不符

- **文件**：`CHANGELOG.md`（`## [未发布]` → `移除`）
- **现象**：写着"`screenScale` 收回 `private`"，实际 `AppTray.kt:930` 是 **`internal`**（`AppIcons` 需要同一套倍率口径，代码注释里写明了原因）。
- **建议**：改成"`screenScale` 改为 `internal`（`AppIcons` 需要复用同一套倍率口径）"。

### 🟢 D-12【提示】`release.yml` 仍出现 `packageExe` / `WiX` 字样

- **文件**：`.github/workflows/release.yml:222, 646`
- **现象**：仅在**注释**与 Release notes 文案中出现，没有实际调用步骤。
- **结论**：✅ 不算缺陷，只是提醒后续 grep 时别被这两个词误导。

---

## 9. 必须真机人工验收的清单

以下是自动验证**覆盖不到**的项（我已独立证实 Robot 无法在本会话投递事件），请在 Windows 真机（建议 100% / 125% / 150% 三种缩放各一遍）上操作：

### 9.1 托盘菜单（T03）—— 最高优先级

| # | 步骤 | 期望 |
|---|---|---|
| M1 | 右键托盘图标 → 菜单弹出 → **点击主窗口**任意处 | 菜单关闭，且这一次点击**正常作用到主窗口**（没有被吞掉） |
| M2 | 菜单弹出 → 点击**桌面** | 菜单关闭 |
| M3 | 菜单弹出 → 点击**其他应用窗口 / 任务栏** | 菜单关闭 |
| M4 | 菜单弹出 → 按 **Esc** | 菜单关闭 |
| M5 | 菜单弹出 → 再点一次托盘图标 | 关闭且**不立刻重开**；再等 300ms 以上点一次应能正常打开 |
| M6 | **连续开关 20 次**（右键开 → Esc 关，重复） | 每次都正常；结束后 `Toolkit.getAWTEventListeners()` 数量应回到初始（可用 `-Dfrpc.startupTrace=true` + 临时日志确认，或感觉上不应越来越卡） |
| M7 | 点"设置" / "关于" | 菜单先关、主窗口弹出、**不被菜单压住** |
| M8 | 菜单项文字 | 中文**不是方框**（"显示主界面 / 设置 / 关于 / 退出"） |
| M9 | 悬停高亮、分隔线、边框 | 三种缩放下都是**清晰发丝线**，不糊不断 |
| M10 | 托盘图标 vs 主窗口/任务栏图标 | 图案一致；**标题栏与任务栏小图标不发虚** |
| M11 | 深浅色 | 系统切深色/浅色后，托盘菜单配色跟随（首帧可能先浅色，1 秒内刷新为深色，属预期） |
| M12 | 主窗口标题栏 | 菜单弹出瞬间主窗口标题栏可能短暂变灰（焦点被锚点 Dialog 取走），点别处后立即恢复 —— 确认观感可接受 |

> M6 的"20 次"如果不想装工具，可以用一个简易办法：在 `AppTray.handleClosed()` 临时加一行 `println("hc")`，在 `DismissController.attach()` 加 `println("at")`，跑完 20 次后 `at` 与 `hc` 的次数应相等。

### 9.2 冷启动（T02）

| # | 步骤 | 期望 |
|---|---|---|
| S1 | 加 `-Dfrpc.startupTrace=true` 启动，看 stdout 里程碑 | `window ready` 之前**不应出现** `osTheme.probe` 或 `tray.warmUp` 的大额耗时；`osTheme.probe` 应出现在 `window ready` **之后** |
| S2 | **正常退出**（托盘 → 退出）后再次启动 | 日志里出现 `cleanupOrphans skipped (last shutdown was clean)`，**没有** powershell / CIM 相关痕迹 |
| S3 | **任务管理器强杀**进程后再次启动 | 日志里出现 `cleanupOrphans done killed=N`；确认残留的 frpc 被清掉 |
| S4 | 有默认配置时启动 | `profiles loaded` 之后才出现 `autoConnect start`；不应再有"开机自启了但没连上" |
| S5 | 冷启动体感 | 双击 → 窗口可见应明显快于改动前（目标 ≤1.5s）。**用 S1 的日志量化，不要靠感觉** |
| S6 | `--silent`（开机自启）启动 | 不弹窗、托盘图标出现；且日志里同样能找到 `cleanupOrphans` 的结论（证明 C-2 那条补跑生效） |
| S7 | **（D-2 相关）** 异常退出后的启动 | 确认 `ProcessScanner` 的纯 Java 路径到底有没有被走到；若仍是 powershell，记录实际耗时，据此决定是否值得继续优化 |

### 9.3 安装包（T04）

| # | 步骤 | 期望 |
|---|---|---|
| P1 | CI 首次出包后，右键 `…-setup.exe` → 属性 | **有图标**（这是问题 4a 的直接验收） |
| P2 | 双击运行 | 向导**全程简体中文**，重点看第 164/165/167/177/178/180/186/192/197/199 行对应的那些页面 —— **确认没有出现字面 `&&`** |
| P3 | 用户级包安装 | 免 UAC，装到 `%LOCALAPPDATA%\Programs\Frpc-Android-Multi`，开始菜单有快捷方式，桌面快捷方式默认不建 |
| P4 | 机器级包安装 | 弹 UAC，装到 `C:\Program Files\Frpc-Android-Multi` |
| P5 | 两个包先后安装 | 卸载表里出现**两条独立条目**，互不覆盖 |
| P6 | 控制面板卸载 | 卸载向导中文、卸载图标正常 |
| P7 | 覆盖安装（装新版） | 提示关闭正在运行的程序（`CloseApplications`），装完不重启程序（`RestartApplications=no`） |
| P8 | 装完的 exe | 自带 JRE 能跑起来，图标与托盘一致 |

---

## 10. 遗留风险

| ID | 风险 | 影响 | 缓解 / 状态 |
|---|---|---|---|
| R-1 | **`ProcessScanner` 零 fork 路径在本机不生效**（D-2） | 异常退出后的启动仍要付 powershell 的 1.5~4s | 回退路径正确；正常退出时整段跳过（最大收益仍成立）。**需真机 S7 确认** |
| R-2 | **托盘菜单端到端行为未自动验证**（D-1 节：Robot 在本会话不可用） | 若"点外面关"在真机上仍不生效，要等下次迭代 | 机制三断言已实测为真；**必须走 §9.1 的 M1-M12** |
| R-3 | `.iss` `[Messages]` 的 `&&` 与 2 条未知 ID（D-3 / D-4） | 向导文案瑕疵 + 2 条覆盖失效 | **建议发版前修掉**（改动很小） |
| R-4 | 16px 上源点圆点与箭头横线**视觉粘连**（间隙仅 0.08px） | 100% DPI 托盘上看不出独立圆点 | 结构一致性已达标（B-2）；属物理极限，若介意需重新设计图形（超出本轮范围） |
| R-5 | `icon.ico` 是 PNG 内嵌条目，个别环境/工具读不出来 | 安装包图标缺失 | 设计文档已给退路（给 16/32/48 补 BMP/DIB）。**CI 首次出包后按 P1 确认** |
| R-6 | 换安装器后旧版（jpackage/WiX MSI）无法原地升级 | 老用户需先卸载 | release.yml 的 Release notes 与 CHANGELOG 均已写明 ✅ |
| R-7 | 版本号被回退（D-1） | **发版阻断** | 交付前与用户确认 |
| R-8 | `windowLostFocus` 现在会触发，理论上存在"弹出后 >250ms 因系统弹窗/通知导致误关" | 菜单偶发自动关闭 | 与原生菜单行为一致；真机 M6 顺带观察 |
| R-9 | ISCC 未在本机安装（`"C:\Program Files (x86)\Inno Setup 6\ISCC.exe"` 不存在） | `.iss` 只做了静态校验，未做过真实编译 | 本机受"禁止运行 iscc"约束；**待 CI 首次发版验证** |
| R-10 | `ci.yml` 新增 `manifest` job 依赖 runner 的 PyYAML | ubuntu runner 默认有；脚本已加 `pip install` 兜底 ✅ | 低 |

---

## 11. 附：本次验证执行的命令清单（可复现）

```bash
# A 回归
./gradlew :desktopApp:compileKotlin --rerun-tasks --console=plain          # BUILD SUCCESSFUL, 4 executed
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest --rerun-tasks   # 52 executed, 167 tests / 0 fail

# B 图标
java scripts/gen-tray-icons/GenTrayIcons.java . --check                    # 全部 PASS, exit 0
python3  -> 解析 desktopApp/icon.ico 的 9 个 ICONDIRENTRY                  # 16..256 全 PNG
python3  -> 10 档归一化到 16x16 做像素对称差比对                            # symdiff <= 3/256
python3  -> 48x48 ASCII 掩码目视核对 tray-16 / tray-24 / icon-512
grep -n "simplified" scripts/gen-tray-icons/GenTrayIcons.java              # 无（HEAD 里有）

# C 启动路径
grep -n "delay(500" desktopApp/.../Main.kt                                 # 仅注释
grep -rn "appIconPainter" desktopApp/                                      # none
grep -rn "ProcessBuilder|Runtime.getRuntime().exec" desktopApp/src/main/kotlin/

# D 托盘（独立探针，未进仓库，位于 /tmp/qaprobe/）
javac TrayProbe2.java && java TrayProbe2
  # old JWindow.isFocusableWindow=false / new JDialog=true
  # popupWindow=###focusableSwingPopup###, isFocusableWindow=true, gainedFocus=true, isFocusOwner=true
  # Robot -> 主窗口/弹层/Esc 三处事件投递 delta 均为 0（环境限制）
  # Toolkit.getAWTEventListeners() 20 次 attach/detach 后 leak=0
javac ProcScanProbe.java && java ProcScanProbe        # commandLine 0/441 可读
javac CmdLineProbe.java  && java CmdLineProbe         # JDK17 / JDK21 同样 0/441

# E 打包
python3 scripts/ci/check-release-manifest.py                              # exit 0, 11 项 fully matched
python3 -> yaml.safe_load(release.yml / ci.yml)                            # OK；step name 无 ": "
python3 -> .iss 首 3 字节 == EF BB BF                                      # True
python3 -> 49 条 [Messages] ID 与官方 Default.isl(6.2.2 / 6.5.0+) 比对      # 47 已知 / 2 未知
python3 -> .iss 中 "&&" 计数                                               # 11 处
grep -n "packageExe|wixtoolset|candle|WiX" .github/workflows/release.yml    # 仅注释

# F 两端
git status --porcelain | grep shared/                                      # 无
grep -rn "icon.svg|icon.png" androidApp/                                    # 无
```
