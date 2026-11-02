# 开机自启动（Windows / macOS / Linux）

桌面端支持「登录系统后自动启动本应用」。开关在 **设置 → 启动 → 开机时自动启动**，
Android 不提供该开关（移动端由启动器管理，没有「开机自启」这个概念）。

## 1. 为什么不用一个跨平台库

三家操作系统**没有**统一的「开机自启动」API，各自只有一个约定俗成的落点：

| 平台 | 落点 | 触发时机 | 需要的权限 | 失败时的典型表现 |
|------|------|----------|------------|------------------|
| Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\frpc-desktop-multi` | 用户登录 | 当前用户对注册表的写权限（**不需要管理员**） | `reg add` 返回「拒绝访问」，被安全软件拦 |
| macOS | `~/Library/LaunchAgents/io.github.fdmdf.frpc.plist` | 用户登录、进入 Aqua 图形会话 | 家目录可写 | 受 TCC / 企业策略保护时写入被拒 |
| Linux | `$XDG_CONFIG_HOME/autostart/frpc-desktop-multi.desktop`（缺省 `~/.config/autostart/`） | 桌面环境登录 | 配置文件可写 | 家目录只读 / 磁盘满 / 无 XDG 自启动支持的桌面环境 |

自己实现而不是引入插件，是为了把「写什么内容」和「怎么落到系统」拆开，
从而在**任意宿主机**上单测三种平台的产物（见第 6 节）。

## 2. 分层

```
frpc-core/src/autostart.rs     纯逻辑：生成 plist / .desktop / 注册表命令行，解析回读
  ├─ AutostartSpec             「要写什么」——纯函数，含转义与引号规则
  ├─ SystemOps (trait)         「怎么落盘」——读写文件 + 执行 reg.exe
  ├─ enable / disable / status 三个动作
  └─ reconcile                 启动时自愈（路径变更 / 注册项丢失）
frpc-core/src/store.rs         SettingsData.autostart / autostartPath（持久化）
app/src-tauri/src/autostart.rs Tauri 胶水：拼装平台参数、落盘意图、记日志
app/src-tauri/src/commands.rs  IPC：get_autostart_status / set_autostart
app/ui/src/views/SettingsView.vue  开关（绑系统真实状态，失败回滚）
```

`SystemOps` 只在 `RealSystem` 里碰真实文件系统和 `reg.exe`；测试用内存实现
（`FakeSystem`）模拟注册表，所以 Windows 的逻辑也能在 macOS/Linux CI 上跑。

## 3. 配置项与状态持久化

`~/.frpcdash/settings.json`（与其它设置同文件，旧版本文件无需迁移）：

| 字段 | 类型 | 含义 |
|------|------|------|
| `autostart` | bool | **用户意图**：用户希望开机自启吗 |
| `autostartPath` | string | 上次成功注册时使用的可执行文件路径，用于识别「程序被移动」 |

关键约定：**意图与系统真实状态分开存**。

- 界面开关读的是 `get_autostart_status` 探测到的**系统真实状态**，
  不是 `settings.autostart` —— 否则用户在「任务管理器 → 启动」里禁用了，
  我们的界面还显示「已开启」，那是最招人烦的一类假状态。
- `set_autostart` 的顺序是**先写系统、成功后才落盘意图**。写失败则两处都不变，
  不会出现「设置说开着、注册表里没有」的分裂。
- 通用 `set_settings` 会**保留**已有的 `autostart` / `autostartPath`：
  前端每次都是整份 settings 回写，若照抄它带来的字段就等于绕过了系统写入。

## 4. 异常处理

自启动是锦上添花的能力，任何失败都不允许影响应用主流程：

| 情况 | 处理 |
|------|------|
| 写入无权限（Windows 组策略 / Linux 家目录只读 / macOS TCC） | 返回 `code = permission`，界面提示 + 回滚开关；`settings.json` 不动 |
| `reg.exe` 不存在或被安全软件拦截 | 返回 `code = command`，同上 |
| 平台不支持（Android） | `supported = false`，开关置灰并说明原因 |
| 程序被移动 / 升级换了安装目录 | 启动时 `reconcile` 发现注册项指向旧路径 → 重写为新路径，并写一条应用日志 |
| 注册项被系统清理 / 被用户从注册表删掉 | 启动时 `reconcile` 发现缺失 → 补回（仅当用户意图为「开」） |
| 用户从「任务管理器 → 启动」禁用 | 注册表值仍在但带 `StartupApproved` 标记，本应用**不再改回**；界面按探测结果如实显示 |
| Linux 桌面环境停用 | `.desktop` 里 `Hidden=true` / `X-GNOME-Autostart-enabled=false` 视为未启用 |
| 读取状态本身失败 | 状态结构里带 `detail`，界面显示原因而不崩 |

`reconcile` 只做「补」不做「删」：`autostart == false` 时绝不去动系统里的项，
避免用户手工加过的启动项被应用悄悄抹掉。

## 5. 启动参数与静默启动

注册时统一附加 `--autostart`：

- Windows：`"C:\...\frpc-desktop-multi.exe" --autostart`
  （路径**总是**加引号，规避 Unquoted Service Path 那类解析歧义）
- macOS：`ProgramArguments` = `[<exe>, --autostart]`
- Linux：`Exec="<exe>" --autostart`

应用启动时若检测到该参数**且**开启了「关闭窗口时最小化到托盘」，
则直接隐藏主窗口、只留托盘图标 —— 开机时不该有窗口弹出来抢焦点。
没开「关闭到托盘」的用户仍然看到窗口，符合「我开了自启就该看到界面」的预期。

## 6. 测试

```bash
# 核心层（含 20 个自启动相关用例，三平台产物都在内）
cargo test --manifest-path tauri/Cargo.toml -p frpc-core --lib autostart
```

覆盖点：plist / .desktop / 注册表命令行的生成与回读（含空格、引号、`$`、`&` 等转义）、
`reg query` 输出解析（`REG_SZ` / `REG_EXPAND_SZ`）、无权限与命令缺失的错误分类、
启用/关闭幂等、路径变更自愈、注册项恢复、`Hidden=true` 判定。

## 7. 手工验收

| 平台 | 开启后检查 | 关闭后检查 |
|------|-----------|-----------|
| Windows | `reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v frpc-desktop-multi`；任务管理器 → 启动 里出现且状态为「已启用」 | 上面的键值消失 |
| macOS | `plutil -lint ~/Library/LaunchAgents/io.github.fdmdf.frpc.plist`；系统设置 → 通用 → 登录项 里出现 | 文件被删除 |
| Linux | `desktop-file-validate ~/.config/autostart/frpc-desktop-multi.desktop`；GNOME「启动应用程序」里出现 | 文件被删除 |

路径变更自愈的验证：开启自启 → 把可执行文件复制到另一个目录并从那里启动 →
应用日志里出现「开机自启动项已更新到新路径」，注册项随之变化。

## 8. 已知差异与限制

- **Windows**：写 `HKCU` 而非 `HKLM`，所以只对当前用户生效，不会弹 UAC；
  这也意味着「为所有用户安装」的场景需要每个用户各自开启一次。
- **macOS**：LaunchAgent 在**用户登录后**运行，而不是开机时（macOS 没有「未登录就跑」
  的用户级方案）。macOS 13+ 的「登录项」界面会把它显示为后台项目，用户可在此禁用；
  应用使用 `LimitLoadToSessionType=Aqua` 限定只在图形会话加载。
  产物未签名 / 未公证时，首次自启动可能被 Gatekeeper 拦下，需要在系统设置里放行。
- **Linux**：依赖桌面环境对 XDG autostart 的支持（GNOME / KDE / XFCE 均支持；
  纯窗口管理器和部分精简发行版不会处理 `.desktop`）。
  AppImage 打包时不能直接用 `current_exe()`（那是 `/tmp/.mount_xxxx` 这个每次启动都变的
  挂载点），应用优先取 `$APPIMAGE` 指向的真实文件；用 `--appimage-extract-and-run` 或
  自行解包运行的场景仍需手动确认路径。
- **Android**：不支持，开关不展示。
