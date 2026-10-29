# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [1.3.4] - 2026-10-29

### 变更

- **托盘生命周期统一为 install / update / remove 三段，且全部幂等**：所有可变状态收进一个
  会话对象，`AppTray` 本身只持有这一个引用。`install` 已装好就直接返回 true（不会重复注册
  鼠标监听、不会挂第二个 TrayIcon），`remove` 可以安全地被调用两次（退出时 `quitApp()` 与
  `DisposableEffect.onDispose` 都会走到它）；`SystemTray.isSupported()` 为 false（部分 Linux
  桌面环境）时 install 返回 false，应用照常运行。公开方法内部统一走同一个 EDT 助手，
  不在 EDT 上 `invokeAndWait` 自锁。
- **托盘菜单改为「创建一次、反复复用」**：`JPopupMenu` / 自绘面板 / 1×1 锚点窗口在会话里长期持有，
  弹层监听、Esc 键绑定、鼠标行命中监听都只在 install 那一刻注册一次；每次打开只重新构建菜单项
  列表并重新量算尺寸。每次关闭时会把挂到弹层窗口上的失焦监听**成对摘掉**（弹层窗口会被
  PopupFactory 复用，不摘会越积越多）；锚点窗口只在跨屏时才重建。
- **悬停提示并入唯一的推送入口**：去掉 `AppTray.updateTooltip()`，深浅色与 tooltip 一起由
  `AppTray.update(TrayState)` 推送 —— 两个入口推同一份状态容易打架。菜单开着时主题切换
  直接换配色重绘，不再把菜单关掉重建。
- **菜单模型用 sealed interface 表达**：`MenuItem.Action`（文字 + 事件）/ `MenuItem.Separator`，
  不再用「event == null 表示分隔线」这种写法；菜单只有「显示主界面 / 设置 / 关于 / ── / 退出」，
  不做显示-隐藏文案切换、不加勾选框。
- **跨平台交互矩阵**：Windows / Linux 左键显示主界面、右键弹出菜单；macOS 单击即弹菜单
  （状态栏规范，Ctrl+点击同样走 pressed 分支）；`isPopupTrigger` 的 pressed / released 时机
  按平台分开处理，保留 50ms 去重窗口兜底，避免同一次物理点击把菜单「关掉又打开」。
- **Windows 安装程序改用 Inno Setup**：发布流水线不再跑 `packageExe`（jpackage + WiX），
  只跑 `createDistributable` 出 app-image，再由 `ISCC` 打安装包（脚本在
  `packaging/windows/Frpc-Android-Multi.iss`）。理由是 jpackage 的 `--icon` 根本不写进
  installer，向导语言又跟着 JDK 的 `Locale.getDefault()` 走（runner 是 en-US 就是英文），
  而 CMP 1.12.2 的 DSL 不暴露 `--resource-dir`，连改语言文件的口子都没有。
  顺带删掉 CI 里的 `choco install wixtoolset`，省 2~4 分钟和一个联网依赖。
- **桌面快捷方式改为默认不创建**：开始菜单快捷方式与「安装完成后立即运行」仍是默认勾选，
  桌面快捷方式挪到附加任务里，默认不勾 —— 装完不再凭空多一个桌面图标。

### 新增

- **Windows 提供用户级与机器级两个安装包**，由同一份 `.iss`、同一份图标、同一份中文文案产出：
  `Frpc-Android-Multi-<版本>-windows-x64-setup.exe`（用户级，免 UAC，装到
  `%LOCALAPPDATA%\Programs\Frpc-Android-Multi`）与
  `Frpc-Android-Multi-<版本>-windows-x64-setup-machine.exe`（机器级，需 UAC 提权，
  装到 `C:\Program Files\Frpc-Android-Multi`）。两者的卸载表分开（HKCU / HKLM），
  可以并存，互不覆盖。
- **CI 增加发布流水线自检 job**（`ci.yml` 的 `Release manifest consistency`）：跑
  `scripts/ci/check-release-manifest.py` 交叉核对 release.yml 的产出与发布前校验清单、
  解析两份 workflow 确认 YAML 可解析、校验 `.iss` 的 UTF-8 BOM —— 把这几类只在发版时才炸的
  问题提前到 PR 阶段。

### 移除

- **`--tray-test` 离屏自检入口**：连同 `runTrayTest()`、`AppTray.renderPreview()`、
  `MenuPanel.hoverForTest` 一并删除 —— 菜单只画一次的公共渲染代码肉眼就能核对，
  为此保留的离屏渲染通道与 `javax.imageio` 依赖不再需要。
- **随之失效的禁用态与死字段**：`MenuEntry.enabled` 与其绘制分支、`Palette.disabled` 配色
  一并移除（新菜单没有任何禁用项）。原来私有的 `screenScale` 改为 `internal` —— `AppIcons`
  要在别的包里按屏幕缩放倍率挑托盘图标尺寸，所以不是收回可见性，而是放开到模块内。
- **主窗口句柄 `awtWindow`**：改为「自增令牌 + Window 内 LaunchedEffect」把已经存在的窗口
  提到前台，不再缓存 `java.awt.Window` —— 从根上没有窗口销毁后的悬垂引用。
- **CI 的 WiX 安装步骤**：Inno Setup 不需要它，删掉后少一个 `choco` 联网依赖。

### 修复

- **退出流程重复执行**：`quitApp()` 加幂等保护（已经在退就直接返回），并统一为
  `shutdown → AppTray.remove() → SingleInstance.release() → exitApplication()` 的顺序。
- **Windows 安装包 exe 没有图标**：jpackage 的 `--icon` 只作用于 app-image 的 launcher
  （本机实测 launcher 里 9 档 PNG 9/9 命中，105MB 的 installer 里 0/9），installer 的图标由
  WiX 模板决定且无法覆盖。现在由 `SetupIconFile` 把 `desktopApp/icon.ico` 直接写进安装程序的
  PE 资源，`UninstallDisplayIcon` 同理。
- **Windows 安装向导是英文**：jpackage 生成 WiX 工程时按 JDK 的 `Locale.getDefault()` 选
  `MsiInstallerStrings_<lang>.wxl`，GitHub runner 是 en-US，于是整套文案是英文 —— 这是机制
  决定的，不是配置漏了。现在只声明一个语言 `chinesesimplified`（ISCC 自带的
  `ChineseSimplified.isl`），并逐条覆盖 `[Messages]`（按钮、向导页标题、说明、完成页、
  卸载、占用提示），与系统区域和 JDK locale 完全无关。
- **`scripts/ci/check-release-manifest.py` 的版本号与产品名硬编码**：改为从
  `desktopApp/build.gradle.kts` 的兜底版本与 release.yml 的 `PRODUCT` 现读；同时新增反向
  校验 —— 有产出却不在发布清单里的文件（多半是文件名拼错）也会直接失败。

> **升级提示**：Windows 安装包换了安装器，旧版是 jpackage/WiX 产出的 MSI，与本安装程序的
> 卸载表不兼容，**装过旧版的用户请先卸载再安装新版**，无法原地升级。Release notes 里同步写明。

### 重构（内部清理，含两处行为变更）

- **`ServerProfile` 去重**：Android 与桌面端各存了一份逐字符重复的定义，现合并为
  `shared` 中的唯一一份 —— 两端持久化的本就是同一份 `profiles.json`。
- **格式化统一到 `shared/format/Formatters.kt`**：两端各有一套且口径不一致，现统一为一套。
  由此带来两处**行为变更**：
  1. **日志时间与时间戳固定按北京时间（UTC+8）显示**，桌面端原先跟随系统时区；
  2. **小数统一用 `Locale.US`**，避免部分语言环境下 `1.5 MB` 被显示成 `1,5 MB`。
- **Android 端口校验改用 `shared` 的 `PortCheck::isInUse`**：旧实现是裸
  `ServerSocket(port)` 绑定且未设 `SO_REUSEADDR`，frpc 退出后 admin 端口残留
  TIME_WAIT 时会**误报「端口被占用」**（frpc 用的 Go `net.Listen` 本就带该选项，其实绑得上）。
  桌面端早已如此，本次补齐 Android，两端口径一致。
- 清理未使用导入与内联全限定名；移除 3 个未使用的依赖
  （`lifecycle-viewmodel-compose`、`compose-ui-tooling`、`-tooling-preview` —— 全仓无 `@Preview`）。

> **已知遗留**：`ProfileBundle` 全量导出 / 导入目前仍只有桌面端有入口，Android 侧暂无，
> 属于既有的两端能力差，不在本次范围内。

## [1.3.3] - 2026-10-29

### 变更

- **托盘右键菜单精简为四项**：显示主界面 / 设置 / 关于 / ── / 退出。
  去掉全部图标与子菜单，只留一条分隔线，行高与内边距按字体的真实度量算。
  原来堆在托盘上的入口（启动 / 停止 frpc、重载配置、打开状态页、切换配置档案、
  打开数据 / 日志 / 运行目录、应用启动时自动运行、关闭窗口时最小化到托盘、
  检查更新、项目主页）全部撤下 —— 主窗口里这些功能一个没少，托盘只留最常用的四个动作。
- **菜单改为全自绘**（外壳仍是 `JPopupMenu`，内容是一个自己画的 `JPanel`）：
  自己画才能同时控制住三件事 —— DPI 下每个尺寸都对齐整设备像素、深浅色两套配色
  不依赖 Windows L&F 的暗色适配（它经常画成黑底黑字）、中文一定用得上组件字体。
  字体固定 **13pt 不乘缩放倍率**：`FontMetrics` 与窗口坐标一样都是逻辑像素，
  再乘一次倍率等于在 150% 屏上放大两次（会被画成 18pt 的物理大小）。

### 新增

- **菜单三态**：正常 / 悬停（浅蓝色块，左右各内缩）/ 禁用（灰字、不进悬停、不响应点击）。
- **四种关闭路径**：点击菜单外任意位置（含桌面与其他应用的窗口）、弹层窗口失焦、
  按下 Esc、再次点击托盘图标 —— 都会立刻收起并释放弹层窗口。
  前两条靠 `JPopupMenu` 自带的**原生鼠标捕获**（`BasicPopupMenuUI.MouseGrabber` →
  `sun.awt.SunToolkit.grab()`）：自己实现不了，`sun.awt` 是 qualified export，
  而纯 `AWTEventListener` 只能收到本 JVM 的事件，点击桌面 / 别的进程收不到。
  Esc 要自己挂：`PopupMenu` 的 Esc 键绑定只在 Synth / Metal L&F 里有，**Windows L&F 没有**。
- **点击菜单项先关菜单再执行**：否则「设置」「关于」要弹的窗会被置顶的菜单压住。
- **弹出位置贴任务栏**：按 `getScreenInsets` 判断任务栏在哪条边（下 / 上 / 左 / 右），
  横向以托盘图标为中心，最后按屏幕「可用区」收边 —— 放不下就翻转或内收，不会被截断。

### 修复

- **DPI 适配**：字号、行高、内边距、分隔线、边框全部按设备像素对齐。
  1 像素线用 `1/scale` 的逻辑线宽绘制，横线再落到「半个设备像素」上，
  否则会被抗锯齿摊成两行灰线。每次弹出都重读缩放倍率，多屏混插（100% + 150%）时跟着屏走。
- **弹层被 1×1 锚点窗口裁掉**：弹层强制走重量级（`isLightWeightPopupEnabled = false`）。

### 移除

- **托盘菜单字形资源**：`desktopApp/src/main/resources/tray/menu/` 下 26 个 PNG
  （13 个字形 × 16/32 两套）与 `GenTrayIcons.java` 里的 `Glyph` 枚举、`drawGlyph`、
  `menuSheet` 一并删除。新菜单不用图标；将来要恢复，把生成器改回去重跑即可。
  托盘图标（`tray-<n>.png` / `tray-mac*.png`）与窗口图标不受影响，字节未变。

### 说明

- Android 端本次无改动，仅随发版同步递增版本号（`versionCode` 6 → 7）。

## [1.3.2] - 2026-10-28

### 新增

- **桌面端托盘右键菜单补全**（原来只有「显示窗口 / 停止 frpc / 退出」三项）：
  显示或隐藏主窗口、启动 / 停止 frpc（同一项随状态切换文案与图标）、重载配置、
  打开 frpc 状态页（未启用 webServer 时置灰）、两个开关项（应用启动时自动运行、
  关闭窗口时最小化到托盘，带勾选框）、设置、检查更新（结果就地替换该项文案，
  有新版本直接打开下载页）、关于（新增加「关于」对话框）、退出。
  菜单按功能分成 4 段，段间有分隔线；启动项在「启动中 / 停止中」时置灰，
  停止 / 重载 / 状态页在未运行时置灰。菜单项一律不标快捷键：托盘菜单无法注册全局热键
  （需要 JNA/JIntellitype），只在主窗口聚焦时生效的「半快捷键」反而会误导用户。
- **桌面端托盘菜单美化**：字号与内外边距随 DPI 缩放（96 DPI 用 12px，200% 屏放大到 18px），
  每项都带 16/32 两套单色图标（图标列对齐），分隔线用独立配色，
  深浅色两套配色显式设置（不依赖 Windows L&F 的暗色适配 —— 它经常画成黑底黑字），
  主题切换时重建菜单（Swing 的 UI delegate 在建组件时就把 UIManager 的值快照走了）。
- **菜单项图标资源**：`scripts/gen-tray-icons/GenTrayIcons.java` 新增 11 个矢量字形
  （window/play/stop/reload/web/gear/update/info/quit/box/box-check），
  输出 `tray/menu/<n>.png`（16px）与 `<n>@2x.png`（32px）纯黑 + alpha 蒙版，
  运行时按 DPI 选择、按菜单前景色着色，深浅色主题共用一份资源。
  Java 标准库没有 SVG 渲染器（`ImageIO` 不认 `.svg`），所以走构建期预渲染而不是运行时读 SVG。

### 修复

- **托盘菜单被 1×1 锚点窗口裁掉**：Swing 默认用轻量级弹层，菜单会被画进锚点窗口内部，
  而锚点只有 1×1 —— 屏幕上什么都看不到。改为重量级弹层（`isLightWeightPopupEnabled = false`）。

### 变更

- **Android 主页视觉分层**：代理规则 / 访问者从「每条一张卡片」改成「一组一张卡片 + 行分隔线」，
  卡片内标题带计数圆片；概览四个指标之间加竖向分隔线，数值加粗放大、标签降为灰色小字；
  提示类文字缩进到内容区并降级为 bodySmall，不再和主信息抢视觉权重。
- **Android 设置页视觉分层**：按「外观 / 启动 / 电池优化 / 日志 / 引擎 / 关于 / 危险操作」分组，
  每组一张卡片、行间用分隔线；每行带图标 + 标题 + 副标题 + 尾部控件；
  「去设置」从纯文字链接改成 tonal 按钮，达标后显示状态片而不是留一个点了没反应的按钮；
  「关于」改成带箭头的整行入口；「清空全部配置」单独放进 errorContainer 底色的危险操作卡片。

### 修复

- **桌面端托盘图标重做（消除白边 / 按分辨率提供多尺寸）**：原来托盘用的是
  `resources/icon.png` —— 512×512 的 **24 位 RGB（无 alpha）**，圆角底板外是纯白不透明像素，
  托盘上就是一圈白底白边；而且只有 512 一张，运行时由 AWT 缩到 16/20/24，插值又把白边混进图形。
  现在改为 `scripts/gen-tray-icons/GenTrayIcons.java` 按尺寸生成 ARGB 透明图标：
  Windows/Linux 托盘按 DPI 选 `tray/tray-<n>.png`（16/20/22/24/32/40/48/64/128/256），
  macOS 用模板图 `tray/tray-mac.png`（22px）与 `tray/tray-mac@2x.png`（44px），
  小尺寸（≤24）自动简化字形（去掉源点圆点、加粗笔画）保证清晰；
  `icon.png` 重新导出为带 alpha 的 512，窗口/任务栏图标也不再有白底；
  `icon.ico` 内嵌 16–256 九种尺寸，避免 Explorer 缩放发虚。

## [1.3.1] - 2026-10-28

### 修复

- **桌面端托盘右键菜单的中文渲染成方框（□□□）**：菜单原本用 AWT 的 `PopupMenu`，
  Windows 上菜单由系统按菜单字体绘制，`MenuItem.setFont` 不生效，而该字体不含中文字形。
  改成 **Swing 的 `JPopupMenu`**（菜单项由 Java 自己绘制，组件字体一定生效），
  L&F 默认值与组件字体都显式设为中文字体，并用 1x1 透明窗口作弹出锚点、右键在鼠标位置弹出。

## [1.3.0] - 2026-10-28

### 变更

- **首页卡片控件顺序**：状态标签在前、启停开关在后（两端一致）。
- **运行中开关规则后「在线数 / 总数」对不上**：安卓端点开关只标「待重载」（不重启进程），
  此时列表按最新配置算、在线数却来自还在跑的旧配置，会出现「在线 3 / 总 2」。
  现在分母取 **frpc 真正加载的那份配置**（supervisor 新增 `ActiveRuleCounts` 快照，
  只在启动 / 重载成功 / 自动重启时更新），分子分母回到同一口径；列表标题仍按实际条目数显示。
  常驻通知、桌面端概览与系统托盘同样改为这一口径。
- **运行中切换「当前配置」不再只改默认配置**：以前运行中点另一份配置，只是把默认配置改掉，
  首页选中的那份纹丝不动（frpc 跑的还是原来那份），显示的与实际运行的分叉。
  现在运行中切换会先确认，然后停止当前进程、用新配置重新启动（未运行时仍是直接设为默认，
  界面立即跟随）。两端一致。
- **访问者摘要不再用箭头**：访问者没有远程端口，`→` 容易被读成指向一个端口。
  改成「STCP · 本地 127.0.0.1:8080 · 访问 ThinkBook16+.testx」这种写法，
  首页 / 监控页 / 配置页列表（两端）统一。代理规则仍保留箭头，它指向的确实是远程端口或域名。

### 修复

- **桌面端托盘提示在运行中删除当前档案时会崩溃**：拼提示文字时强解引用了当前档案。
  改成取不到就按 0 处理，不再抛异常。

## [1.2.0] - 2026-10-28

### 新增

- **访问者规则也加了启停开关**：与代理规则一致——列表行内直接开关，编辑页里也有同一项。
  关闭时写入 `enabled = false`（frp 的 `VisitorBaseConfig` 本来就有这个字段，
  `FilterClientConfigurers` 对代理与访问者按同一规则过滤），frpc 不会再连对方的私密代理。
  禁用的访问者不再计入主页「访问者（N）」的计数，卡片状态标「已禁用」，规则本身仍保留在列表里。
  **桌面端一并补齐**：配置页的代理与访问者行、首页与监控页的访问者卡片、访问者编辑对话框
  都有同一套开关与「已禁用」状态（桌面端此前只有代理有开关）。
- **首页也能直接启停规则**：Android 与桌面端的首页卡片都加了行内开关，与配置页一致，
  不必进编辑页翻开关。Android 端运行中点开关会自动标「待重载」（frpc 拿的还是旧配置），
  桌面端改完立刻重载。

### 修复

- **禁用后的规则仍被算进在线分母**：常驻通知（Android）与系统托盘提示（桌面端）里的
  「在线 / 总数」用的是配置里的全部规则数，禁用一条就变成永远凑不齐的「0/1」。
  现在两端都只统计启用中的代理与访问者。

## [1.1.0] - 2026-10-27

### 变更

- **产品名称改为 `Frpc-Android-Multi`**：界面标题、桌面端窗口/托盘/安装包名、Android 桌面图标名
  以及所有发布产物的文件名统一改用新名称。
  Android 包名（`com.frpc.android`）、macOS `bundleID`（`com.frpc.dash`）与 Windows `upgradeUuid`
  **均未改动**，已安装的用户可以直接覆盖升级。

### 修复

- **「选择本机端口」扫不出任何端口**：两条路都断了。
  - Android：实现是读 `/proc/net/tcp*`，但 Android 10 起这个文件不对应用开放（系统里也没有
    netstat / ss），所以永远是空列表。现在读不到就退化为「主动 connect 探测」——连得上就是
    在监听；先扫常见端口（含局域网地址，只绑网卡的服务也能扫到）再全量扫 loopback，
    并行 + 8 秒预算，扫不完也先把已扫到的列出来。弹窗另外给了「重新扫描」。
  - 桌面端：跑 `netstat -ano` 时只等 3 秒且**不排空 stdout**，连接数多的机器上输出有几 MB，
    管道一满 netstat 就卡在写，超时被我们杀掉 → 同样返回空。改成一边等一边用线程排空，
    超时也返回已采集的部分；超时放宽到 10 秒。
  - macOS：lsof 的 NAME 列自带空格（`*:8080 (LISTEN)`），按空白切分后取最后一列拿到的是
    `(LISTEN)`，一条端口都解析不出来。改为从第 8 列起整段拼回再截断；lsof 万一仍为空，
    macOS 也允许退到探测。
- **设置 → 关于 一进去就闪退**：关于页用了 `painterResource(R.mipmap.ic_launcher)`，
  而 `ic_launcher` 是 `<adaptive-icon>` XML（mipmap-anydpi-v26），
  `painterResource` 只认 `<vector>`，解析到它直接抛异常。
  改为用图标前景矢量（`R.drawable.ic_launcher_foreground`）+ 自适应图标同款底色自己拼。
- **未保存的编辑会残留**：编辑代理/访问者（以及服务器配置）后不保存直接返回，
  草稿没有被丢弃，下次进入同一条规则看到的还是上次那堆没保存的修改。
  现在返回即放弃（改过才弹「放弃未保存的修改？」确认），
  并加了兜底：一旦离开编辑栈（返回列表 / 切底部 Tab）就清空草稿。
- **停止后无法立刻再启动**：frpc 退出时，admin 端口上已建立的连接会留下 TIME_WAIT，
  而旧的端口检测是「不带 SO_REUSEADDR 试绑一次」，内核在这几十秒内一律拒绝，
  于是每次停止后再点启动都被拦在「状态接口端口 X 已被占用」。
  检测改为「先连后绑 + SO_REUSEADDR」，与 Go `net.Listen` 的默认行为对齐；
  停止流程额外等到端口真正释放才回到「未启动」，并给启停加了串行锁（停止与紧接着的启动不再互相覆盖）。

### 优化

- **运行中的配置不再禁止编辑**：以前点进去只给「停止 / 返回」，改一个端口都要先停服务。
  现在可以直接编辑保存，并且把话说清楚：
  - 编辑页顶部挂一块「该配置正在运行」提示，写明保存 ≠ 生效；
  - 点保存时弹「保存并重载 / 仅保存 / 取消」——选仅保存就打上「待重载」标记；
  - 没重载时主页出现「配置尚未生效」卡片（带重载按钮），配置列表对应项标「待重载」。
  （桌面端同样处理）
- **禁用的代理规则不再计入统计**：frpc 根本不会注册禁用规则，之前它们仍算在
  「代理规则（N）」与「X/Y 在线代理」的分母里，于是永远显示 0/1 像是故障。
  现在两处都只统计已启用的规则（禁用项仍列出，带「已禁用」标签）。

## [1.0.0] - 2026-10-27

首个正式版本。功能设计与交互体验以 [frpc-android](https://github.com/fdmdf/frpc-android) 为基准对齐：
核心业务流程（配置 → 代理 → 启动 → 日志）与模块划分（服务端配置 / 代理管理 / 运行时 / 日志 / 关于）保持一致，
并在此基础上保留桌面端（Windows / Linux / macOS）与 Android 双端共用的 `:shared` 核心模块。

### 新增

- **应用内双语切换**：简体中文 / English / 跟随系统，保存在设置里，切换即时生效（此前只能跟随系统语言）
- **启动前自检与引导**：点「启动」时先检查服务端地址、内置引擎、状态接口端口，被拦下时弹窗说明原因并可直接跳到配置页修正——
  避免 frpc 秒退后被自动重启掩盖，界面上只剩「反复重启」的假象
- **代理列表快捷启停**：列表行内直接开关，禁用的代理不注册到 frps；不必每次进编辑页翻开关
- **独立「关于」页**：应用版本、内置 frpc 版本与 ABI、项目主页、问题反馈、开源许可
- **清空全部配置**：危险操作，需二次确认；frpc 运行中会被拦下
- **本机端口选择器**（Android）：扫描正在监听的端口，点一行填入本地端口（桌面端此前已有）
- **端口能力下沉到 `:shared`**：`PortCheck` / `LocalPorts` 供两端共用，桌面端与 Android 不再各维护一份

### 变更

- 桌面端 `preflight` 更名为 `checkBeforeStart` 并对 UI 层开放，两端启动前校验口径统一
- 版本号统一为 `1.0.0`（Android `versionCode = 1`），桌面端 `packageVersion` 与运行时 `VERSION` 同源

### 已有能力（与参考项目对齐项）

- 自动重连：退避间隔 2s → 5s → 10s → 30s → 60s，连续异常 5 次后停止并说明原因
- 前台服务 + 唤醒锁保活，进程异常退出后由看护任务重启
- 8 种代理类型 + 3 种访问者，按类型裁剪字段，保存前校验（必填、端口占用、重名、类型不支持的字段）
- 传输层 / TLS 双向认证 / 认证方式 / metadatas / 日志保留天数等全量配置
- 真实状态优先读 frpc 的 admin API（含真实远程端口），接口关闭时用日志推断
- 日志分级过滤、搜索、导出；CPU / 内存 / 运行时长实时采样
