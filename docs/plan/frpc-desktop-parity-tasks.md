# frpc-desktop-multi × frpc-desktop 功能对齐任务清单

> 对照对象：`luckjiawei/frpc-desktop`（v2.0.0，Tauri 2 + Vue3；桌面端单 frps 模型）
> 对照结果：**能力缺口 26 项**，按「配置表达能力 → 配置操作效率 → 引擎与运行 → 系统集成 → Android 对齐」分 6 批推进。
> 生成日期：2026-10-26。本清单只描述改造点与验收，不含代码实现。
>
> **实施状态（2026-10-27，v1.0.0）**
> 已完成：T01 T02 T03 T04 T05 T06 T07 T08（配置列表与代理规则筛选）T09 T10 T11 T12 T13 T17 T20 T23 T24
> 部分完成：T18（「关于」页已落地，自动更新检查未做）、T22（应用内中英切换已落地，桌面端文案 i18n 抽取未做）
> 未完成：T14（分享链接）T15（引擎多版本）T16（PATH 自动检测）T19（开机自启）T21（首次引导）T25（Android 全量导入导出 SAF）T26（快捷磁贴）
> 与计划的差异：T03 的「无认证」不能写 `method = "none"`（frpc 只认 token/oidc），实际实现为**整段省略 `[auth]`**，已用 `frpc verify` 验证。

---

## 0. 先厘清两边的产品模型差异（决定哪些能抄、哪些不能抄）

| 维度 | frpc-desktop | frpc-desktop-multi | 影响 |
|---|---|---|---|
| 服务端 | 一个 frps，全局唯一配置 | 多份 `ServerProfile`，每份一个 frps | 它的「设置页」= 我们的「档案编辑页」，页面不能一一对应 |
| 代理与访问者 | 都用 `[[proxies]]` 表达，访问者靠 `visitorsModel=visitors` 区分 | `proxies` / `visitors` 两段独立，贴合 frp schema | **不要改成它的单表模型**，我们已支持 `fallbackTo`/`keepTunnelOpen` 且语义正确 |
| 代理类型 | 7 种（无 tcpmux） | 8 种（含 tcpmux） | 不要回退 |
| 存储 | SQLite（3 张表 + JSON 列） | 单 JSON 文件 + 原子写 | 现有规模不需要数据库，见「不建议照搬」 |
| 引擎 | 多版本管理（下载/导入/切换） | 内置单一版本 + 自定义路径 | **最大缺口** |
| 平台 | 桌面三端 | 桌面三端 + Android | Android 需独立排期 |

一句话结论：**frpc-desktop 强在「配置字段覆盖度 + 配置操作效率 + frp 版本管理 + 系统集成」，frpc-desktop-multi 强在「运行态可观测性（admin API / 资源曲线 / 登录态）+ 多档案 + 双端一致」。补齐方向应是前者，不要动后者。**

---

## 1. 现状证据（按代码位置）

| 能力 | frpc-desktop | frpc-desktop-multi 现状 | 结论 |
|---|---|---|---|
| 传输心跳 | `heartbeatInterval` / `heartbeatTimeout` / `tcpMuxKeepaliveInterval` | `TransportConfig` 无这三个字段（`shared/.../model/FrpcConfig.kt:72`） | 缺 |
| 连接侧绑定/代理 | `connectServerLocalIP` / `proxyURL` | 模型有字段，**UI 未暴露**（同上 :80-83） | 半缺 |
| TLS 证书 | `certFile` / `keyFile` / `trustedCaFile` / `disableCustomTLSFirstByte` | `TlsConfig` 只有 `enable` / `serverName`（:86-91） | 缺 |
| 认证方式 | `none` / `token` | 校验器只接受 `token`，其它直接报错（`ConfigValidator.kt:84-94`）；UI 强制 token | 缺 |
| 多用户 | `user` + `metadatas`（含 token） | 有 `user`，无 `metadatas` | 缺 |
| 日志字段 | `maxDays` / `disablePrintColor` | 只有 `to`/`level`/`maxDays`，后两者 UI 未暴露（`ProfileEditScreen.kt:407-423`） | 缺 |
| 代理插件 | `https2http` / `tls2raw`（含证书/私钥选择） | 无 `plugin` 建模（`ProxyConfig.kt:58-109`） | 缺 |
| 启动前预检 | 绑定 webServer 端口测占用 → 明确错误码 B1006 | `ConfigValidator` 的 `portInUse` 参数在桌面端**永远传默认 `{ false }`**（`ProfileEditScreen.kt:80,156`、`Tools.kt:36`） | 缺（Android 已接线：`ConfigEditScreen.kt:134`） |
| 引擎版本 | 列表 / 下载（含镜像）/ 导入本地 / 删除 / 切换 | 仅内置 + 自定义路径（`EngineManager.kt:12-65`） | 缺 |
| 引擎自动检测 | PATH / `which` / 常见目录（`system/detector.rs`） | 无 | 缺 |
| 代理列表 | 搜索 + 卡片/列表 + 分页 + 行内启停 + 复制映射地址 | 纯 `forEach` 平铺（`ProfilesScreen.kt:207-284`），无搜索/分页，行内无开关 | 缺 |
| 批量端口 | `8080-8085` / `8080,8081`，本端与远端数量必须一致 | `localPort: Int?` 只能单端口 | 缺 |
| 本机端口选择 | 弹窗列出端口 → 点选 | 无 | 缺 |
| 代理名生成 | 「生成」按钮随机名 | 无 | 缺 |
| 全量导入导出 | `exportConfig` / `importTomlConfig`（整份 frpc.toml） | 只有单档案导入/导出（`ProfilesScreen.kt:126-147,255-263`） | 缺 |
| 一键清空 | `resetAllConfig` | 无 | 缺 |
| 分享链接 | base64 编码配置 + 脱敏证书路径，应用内导入 | 无 | 缺 |
| 日志页 | 「应用日志 / 连接日志」双 tab + 打开日志文件 + 自动刷新 | 单一实时流（`LogScreen.kt:43`），应用日志混在 `appendSystemLog` 里 | 缺 |
| 关于页 | GitHub 最新 release 检查 | 仅显示版本号（`SettingsScreen.kt:141-151`） | 缺 |
| 开机自启 | 三平台 autostart | 桌面端无（Android 有 `BootReceiver`） | 缺 |
| 静默启动 | `--silent` 直接进托盘 | 无 | 缺 |
| 首次引导 | intro.js 分步引导 + 完成状态入库 | 无 | 缺 |
| 中英双语 | zh-CN / en-US 全量 | 桌面端硬编码中文；Android 已用 `strings.xml` | 缺（桌面） |
| 单实例 | Tauri 默认单实例 | 未处理，重复启动会开两个窗口抢同一份配置 | 缺 |
| 托盘 | 显示/退出 | 显示/停止/退出 + tooltip 状态（`AppTray.kt`） | **领先** |
| 资源监控 | 侧栏 CPU/内存两个数字 | 独立监控页 + 曲线 + 线程数（`MonitorScreen.kt`） | **领先** |
| 登录态 | `connectionError` 字符串 | `serverConnected` + `loginError` + 中文排错提示 | **领先** |
| 真实远程端口 | 无（只显示配置值） | admin API 读真实 `remoteAddr` | **领先** |

---

## 2. 任务清单

> 规模：S ≈ 半天内、M ≈ 1–2 天、L ≈ 3 天以上（单人，含测试）。

### 批次 1 · 配置表达力（P0，模型层，一个 PR 打通两端）

#### T01 传输层字段补全 — M
- **目标**：`[transport]` 支持 `heartbeatInterval` / `heartbeatTimeout` / `tcpMuxKeepaliveInterval`，并把已有但未暴露的 `connectServerLocalIP` / `proxyURL` 放进 UI。
- **改动**：
  - `shared/src/commonMain/kotlin/com/frpc/shared/model/FrpcConfig.kt`：`TransportConfig` 增 3 个 `Int?` 字段（**必须可空**，frp 默认 10/90/30，写死默认值会改掉用户没动过的行为）。
  - `desktopApp/.../ui/ProfileEditScreen.kt:358-405`「传输」卡片：数值输入 + 折叠的「高级」区放 `connectServerLocalIP`、`proxyURL`。
  - `androidApp/.../ui/screens/ConfigEditScreen.kt` 同步。
- **注意**：`dialServerKeepalive` 允许负数（禁用保活），现有校验 `if (t.dialServerTimeout < 0 || t.dialServerKeepalive < 0) => ERROR` 是**错的**，需改成只校验 `dialServerTimeout >= 0`。
- **验收**：`FrpcTomlTest` 增往返用例（字段全填 / 全空两种）；导出 TOML 后 `frpc verify -c` 通过。

#### T02 TLS 证书与首字节开关 — M
- **目标**：`[transport.tls]` 支持 `certFile` / `keyFile` / `trustedCaFile` / `disableCustomTLSFirstByte`（双向 TLS、私有 CA 场景）。
- **改动**：`FrpcConfig.kt:86-91` 扩 `TlsConfig`；`ProfileEditScreen`「传输」区加「启用双向 TLS」展开块 + 文件选择（复用 `chooseFile()`，`ProfilesScreen.kt:318`）。
- **注意**：`disableCustomTLSFirstByte` 在 frp 里**默认 true**，模型必须是 `Boolean? = null`，只在用户显式关闭时写 `false`；Android 端文件选择用 SAF，取不到真实路径时提示「Android 无法读取该路径，请改用导出 TOML 手工配置」。
- **验收**：导出的 TOML 中 `[transport.tls]` 出现 `certFile`/`keyFile`/`trustedCaFile`；未开启时不出现任何证书键。

#### T03 认证方式支持 `none` — S
- **目标**：frps 未开 token 时也能连（现在会被校验器拦死）。
- **改动**：`ConfigValidator.kt:84-94` 放行 `none`（且不校验 token）；`ProfileEditScreen.kt:282-306` 改为「无 / Token」下拉，选 Token 才显示输入框；Android `ConfigEditScreen` 同步。
- **验收**：`auth.method = "none"` 的档案可保存、可启动，导出的 TOML 里 `[auth]` 段写 `method = "none"` 且无 token；`ConfigValidatorTest` 加用例。

#### T04 多用户 `metadatas` — S
- **目标**：frps 开了 multiuser 插件时，客户端能带 `metadatas.token`（配合已有 `user`）。
- **改动**：`FrpcConfig` 增 `metadatas: Map<String, String>? = null`；档案编辑页「服务器」区加「多用户令牌」输入，落入 `metadatas["token"]`；为空时整段不写。
- **验收**：TOML 出现 `[metadatas]\ntoken = "..."`；为空时无该段；`FrpcTomlTest` 覆盖。

#### T05 日志字段补全 — S
- **目标**：暴露 `log.maxDays`、新增 `log.disablePrintColor`。
- **改动**：`LogConfig` 增 `disablePrintColor: Boolean? = null`；`ProfileEditScreen.kt:407-423` 加「保留天数」数字框 + 「关闭控制台颜色」开关（**仅桌面端显示**，Android 采集 stdout 需要纯文本，开关置灰并说明原因）。
- **验收**：导出 TOML 含 `maxDays`/`disablePrintColor`；Android 侧不产生该字段的编辑入口。

---

### 批次 2 · 代理能力（P1）

#### T06 代理插件 `[proxies.plugin]` — M
- **目标**：HTTPS 代理支持 `https2http`（TLS 卸载到本地 HTTP）与 `tls2raw`（TLS 卸载到原始 TCP，WebSocket 场景）。
- **改动**：
  - `ProxyConfig.kt` 增 `plugin: PluginConfig?`，字段：`type`（`""`/`https2http`/`tls2raw`）、`localAddr`、`crtPath`、`keyPath`、`hostHeaderRewrite`（https2http 用）。
  - **必须进 `sanitizedForType()`**：仅 `https` 允许 `plugin`，其它类型一律清空，否则 frp 严格解码会直接退出。
  - `Dialogs.kt:33-363` 的 `ProxyEditDialog` 增加「插件」区块（仅 https 显示），两个单选项 + 证书/私钥选择。
  - `FrpcToml` 的编码结果需验证：`[proxies.plugin]` 必须紧跟对应 `[[proxies]]`，不能被 ktoml 归并到别的表（现有 `mergeDuplicateTables` 逻辑要覆盖该场景）。
- **注意**：启用 plugin 后 `localIP`/`localPort` 会被 frp 忽略（改由 `localAddr` 生效），UI 上要提示并默认把 `localAddr` 预填为「本地地址:端口」。
- **验收**：导出 https2http 配置 → `frpc verify` 通过 → e2e 脚本里加一条 `https`（插件）用例，浏览器访问返回本地 HTTP 内容。

#### T07 启动前三态预检（含端口占用真实检测） — S
- **目标**：对齐 frpc-desktop 的 B1001 / B1005 / B1006 三类拦截，把「点了启动才在日志里看到失败」提前到点击时。
- **改动**：
  - `ConfigValidator.kt`：`portInUse` 已在签名里，桌面端 `ProfileEditScreen.kt:80,156` 与 `Tools.kt:36` 传入真实实现（`ServerSocket(port)` 试绑 + 立即 close；**不要**用 `SO_REUSEADDR`，否则测不出占用）。
  - `FrpcSupervisor.start()`（`FrpcSupervisor.kt:96`）在写 TOML 前做三道检查：`serverAddr` 空 → 提示去配置页；`webServer.port` 被占 → 明确说「状态接口端口 7400 被占用，同机跑多份配置时要各用不同端口」；引擎文件不存在/不可执行 → 指向设置页。
  - 错误要能一键跳转（主页 → 对应档案的编辑页），参考首页现有 `onEditProfile` 通道（`Main.kt:266-279`）。
- **验收**：手工把 `webServer.port` 设为已被占用的 7400 并启动 → 主页直接显示可读错误、日志里不出现 frpc 启动失败；单测覆盖 `portInUse = { it == 7400 }`。

#### T08 代理列表：搜索 / 卡片·列表 / 分页 — M
- **目标**：代理规则超过 20 条后仍能快速定位。
- **改动**：`ProfilesScreen.kt`（档案列表）与 `HomeScreen.kt:284-338`（运行态代理卡片）各加一条工具栏：搜索框（匹配 名称/类型/本地 IP/端口/域名）+ 视图切换（卡片/列表）+ 每页 10/20/50。
- **注意**：搜索状态要 `rememberSaveable`，别每次 tab 切换就丢；列表模式下用 `LazyColumn`（现状是 `Column + verticalScroll`，条目多会全量组合）。
- **验收**：30 条代理时输入「8080」只剩相关项；切换 tab 回来搜索词还在；列表模式滚动 60fps（`--screenshot` 无法验证，需人工点）。

#### T09 行内启停（`enabled` 开关） — S
- **目标**：不打开编辑弹窗就能停掉某条代理。
- **改动**：列表行右侧加 `Switch`，写 `enabled = false` 并保存档案；若该档案**正在运行**，保存后调用 `supervisor.reload()`（`FrpcSupervisor.kt:184`，走 admin API `/api/reload` 或 SIGHUP），reload 失败则提示「需重启生效」。
- **注意**：运行中改档案目前是**禁止**的（`ProfileEditScreen.kt:86-122`）。本任务等于开一个受控例外：只允许改 `enabled`，其余字段仍锁。要在 UI 上写清楚。
- **验收**：运行中关掉一条 tcp 代理 → frps 侧该端口不再监听（`curl` 连不上）→ 再打开恢复；`reloadCount` 不增加。

#### T10 批量端口 — M
- **目标**：`8080-8085`、`8080,8081,8083` 一次建多条代理（对端 frps `allowPorts` 允许时非常常用）。
- **改动**：
  - 模型层保持 `ProxyConfig.localPort: Int?` 不变，**在保存时展开**：解析表达式 → 生成 N 条 `ProxyConfig`，名称为 `原名-端口号`，本端与远端数量必须一致（不一致直接拦下，对齐其提示「请确保内网端口和外网端口数量一致」）。
  - 解析器做成 `shared` 纯函数（`PortRange.parse(expr): Result<List<Int>, PortRangeError>`），桌面 + Android 共用；上限保护：单次展开 ≤ 100 条。
  - `Dialogs.kt` 的本地/远程端口框从 `NumberField` 换成文本输入 + 实时条数提示（「将创建 6 条：8080→18080 …」）。
- **注意**：名称冲突要在展开**前**校验（`原名-8080` 已存在就拦），别等 `ConfigValidator` 报重复名。
- **验收**：输入 `8080-8085` 保存 → 代理列表出现 6 条、名字带端口后缀；数量不一致时报错且一条都不写；单测覆盖区间/逗号/混合/越界/超限。

#### T11 本机端口选择器 — M
- **目标**：点「本机端口」弹出系统正在监听的端口，点一下填入。
- **改动**：`desktopApp/.../ui/Dialogs.kt` 加 `LocalPortPickerDialog`；采集实现新建 `desktopApp/.../runtime/ListeningPorts.kt`：Windows 用 `netstat -ano -p tcp`（含 udp），Linux 读 `/proc/net/tcp{,6}`，macOS 用 `lsof -nP -iTCP -sTCP:LISTEN`；解析结果含 `协议 / 本地地址 / 端口 / PID`，按端口排序、去重。
- **注意**：frpc-desktop 2.0 的 `proxy/getLocalPorts` 已退化为「读数据库里用过的端口」（`db/proxy_repo.rs:141`），**不要照抄**；我们要的是真实监听端口。命令执行要设超时（2s）并回收子进程（项目已有过 CPU 采集子进程泄漏的教训）。
- **验收**：本机起一个 `python -m http.server 8000`，弹窗里能看到 8000/TCP 并可点选；无权限/命令缺失时降级为「手动输入」不报错。

#### T12 代理名生成 + 映射地址复制 — S
- **目标**：新建时一键生成不冲突的英文名；列表里一键复制对外地址。
- **改动**：`ProxyEditDialog` 名称框右侧加「生成」（`类型-6位随机`，与现有档案内代理名查重）；列表行加复制按钮，地址规则：tcp/udp → `serverAddr:remotePort`，http/https → `协议://首个域名`，stcp/sudp/xtcp → `bindAddr:bindPort`（访问者行）。
- **注意**：`remotePort` 为空时不显示复制按钮（避免复制出 `host:null`）。
- **验收**：生成 5 次无重复；复制内容与主页显示的实际地址一致。

---

### 批次 3 · 档案与数据（P1）

#### T13 全量导入 / 导出 / 一键清空 — M
- **目标**：换机备份、批量迁移。
- **改动**：
  - `ProfileStore`（`data/ProfileStore.kt`）增 `exportAll(): String`（现有 JSON 结构 + `schemaVersion` 包装）与 `importAll(json, mode = MERGE|REPLACE)`；导入保留原 id（同 id 覆盖）、id 冲突且名称不同则新建。
  - `ProfilesScreen` 顶栏加「导出全部 / 导入全部 / 清空全部」；清空前必须先 `supervisor.stop()` 并二次确认（列出将被删除的档案名与代理数）。
  - 同时补「导出当前档案为 frpc.toml」（已有）+「导出全部为目录」（每条一个 toml + 一份 `profiles.json`），供命令行 frpc 用户直接使用。
- **注意**：`schemaVersion` 现在没有，先加 1，后续迁移有落点；导入要沿用现有「逐条解析、跳过坏条目」的容错（`ProfileStore.kt:39-57`）。
- **验收**：导出 3 份档案 → 清空 → 导入 → 3 份都回来且代理数量一致；导入一个含坏条目的 JSON，好条目仍能进来并在顶部红条列出坏条目名。

#### T14 分享链接（base64，脱敏） — M
- **目标**：把一份档案发给同事，对方粘进来即可用（frpc-desktop 的「复制链接/导入链接」）。
- **改动**：新增 `shared/.../share/ShareCodec.kt`：`encode(profile, includeSecrets: Boolean)` → `frpcdash://<base64url(json)>`；默认**剔除** `auth.token`、`secretKey`、`metadatas`、证书路径，只保留结构；`decode` 做 schema 校验 + 大小上限（64KB）。
- **注意**：frpc-desktop 的链接带密钥且只在自家 App 可用，我们默认脱敏更安全；复制前弹窗告知「已剔除密钥字段，对方需自行补 token」。
- **验收**：编码→解码往返结构一致；含 token 的档案编码后字符串中搜不到 token 明文；非法/超长链接给出可读错误。

---

### 批次 4 · 引擎与运行（P1，工作量最大）

#### T15 引擎版本管理（分两步） — L
- **目标**：用户能按 frps 版本选 frpc（这是 frpc-desktop 的核心卖点，也是当前最容易被吐槽的点）。
- **第 1 步（MVP，M）**：「导入本地 frpc 二进制」——选择文件 → 校验可执行（`-v` 能输出 `frp`）→ 复制到 `~/.frpcdash/engines/<version>/frpc[.exe]` → 写入 `engines.json`（版本、路径、来源、导入时间、sha256）；设置页列表展示 + 切换 + 删除（内置版本不可删）。
- **第 2 步（M）**：在线下载——`GET https://api.github.com/repos/fatedier/frp/releases`（带 `User-Agent`，10s 超时，失败降级到内置静态清单），按 `平台/架构` 匹配 asset（`frp_<ver>_windows_amd64.zip` 等），下载 → 解包（zip/tar.gz）→ 取 `frpc` → 校验 sha256（`frp_sha256_checksums.txt`）→ 落盘同上。
- **改动**：`data/EngineStore.kt`（新）、`engine/EngineManager.kt:12-65`（改为从 store 取当前版本）、`SettingsScreen.kt:92-139`、`data/SettingsStore.kt:10-24`（`enginePath` 迁移为 `selectedEngineId`，保留旧字段做兼容读取）。
- **注意**：
  - 下载与解包必须在协程 IO 线程，带进度与取消；**禁止**在 Compose 主线程做 IO。
  - Windows 上需保留 `frpc.exe` 的 +x 语义（实际是 ACL，不用管），Linux/macOS 必须 `chmod 755`。
  - 多版本目录要防路径穿越（版本号只允许 `[0-9A-Za-z.\-_]`）。
  - Android 端**不接**本功能（jniLibs 是 SO，不是可执行文件）。
- **验收**：导入 0.61.0 与内置 0.71.1 → 切换后主页显示对应版本、`frpc -v` 输出一致；删除当前使用中的版本要先停服并回退到内置；下载中断后重试不产生半截文件（`*.part` 清理）。

#### T16 引擎自动检测（PATH） — S
- **目标**：系统里已装 frpc 的用户不用手填路径。
- **改动**：`EngineManager` 加 `detectSystemFrpc()`：`which frpc` / `where frpc`（Windows）+ 常见目录（`/usr/local/bin`、`/opt/homebrew/bin`、`C:\Program Files\frp*`），校验可执行后作为「检测到系统 frpc」候选，默认不自动启用，需用户点「使用它」。
- **验收**：PATH 里放一个假 frpc（打印 `0.60.0`）→ 设置页能识别并显示版本。

#### T17 日志页拆分 + 打开日志文件 — M
- **目标**：对齐 frpc-desktop 的「连接日志 / 应用日志」双 tab；出错时能直接打开原始文件。
- **改动**：
  - 缓冲区里其实**已经打了来源标记**：`appendSystemLog` 写入 `LogLine(..., tag = "app", ...)`（`engine/FrpcSupervisor.kt:435`、`engine/LogParser.kt:30-36`），所以拆 tab 只需在 `LogScreen` 按 `line.tag == "app"` 过滤，不需要重构采集链路。
  - 补的是「落盘 + 双 tab UI + 打开目录」：`LogScreen.kt` 顶部分 tab；应用日志补全事件（启动/停止/重载、自动重启、引擎切换、配置保存、异常退出码）。
  - 新增落盘：`~/.frpcdash/logs/frpc-YYYYMMDD.log` 与 `app-YYYYMMDD.log`（滚动保留 7 天），日志页加「打开日志目录」/「导出当前视图」。
  - 「自动刷新」开关（默认开）——落盘文件被外部 tail 时仍以内存流为准，避免双源不一致。
- **注意**：`log.to` 仍固定 `console`（frpc 自己写文件会与 App 采集重复），这点不要改。
- **验收**：启动→停止→再启动，应用日志 3 条以上且连接日志不混入；关掉自动滚动后新日志不抢焦点；`logs/` 下按天分文件。

#### T18 关于页：版本检查 — S
- **目标**：知道有没有新版本。
- **改动**：`SettingsScreen.kt:141-151` 扩为「关于」卡片：当前版本、引擎版本、检查更新（`GET /repos/<owner>/frpc-desktop-multi/releases/latest`，比对 `tag_name`）、GitHub / 文档 / 反馈三个链接。
- **注意**：请求失败静默降级为「检查失败，稍后再试」，不弹错误框；不自动下载，只跳转 release 页。
- **验收**：断网时点检查 → 提示失败但不崩；有新版时显示版本号并可点开浏览器。

---

### 批次 5 · 系统集成与体验（P2）

#### T19 桌面开机自启 — M
- **目标**：开机自动拉起（frpc-desktop 的三平台 autostart）。
- **改动**：新增 `desktopApp/.../core/AutoStart.kt`：Windows 写 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`；macOS 写 `~/Library/LaunchAgents/com.frpcdash.app.plist`；Linux 写 `~/.config/autostart/frpc-dash.desktop`。设置页开关 + 当前状态回读（以系统实际状态为准，不信本地布尔）。
- **注意**：写入的路径必须是**安装后的可执行文件路径**（`ProcessHandle.current().info().command()`），开发态跑 `gradlew run` 时要提示「当前为开发模式，不写入自启项」。
- **验收**：Windows 上开关一次 → 注册表项出现/消失；重启后应用自动出现在托盘。

#### T20 静默启动 — S
- **目标**：开机自启时不弹窗，直接进托盘并自动连接。
- **改动**：`Main.kt:92-110` 的 `main()` 增加 `--silent` 解析（与已有 `--screenshot`/`--smoke` 同一套分支），`Main.kt:152` 的 `windowVisible` 初值改为 `!silent`；与 `closeToTray`、`autoStartOnLaunch` 的组合逻辑写清楚（静默 + 自动连接 = 后台起隧道）。
- **验收**：`frpc-desktop-multi.exe --silent` 启动后无窗口、托盘有图标、tooltip 显示运行中；点托盘「显示」能打开窗口。

#### T21 首次使用引导 — M
- **目标**：新用户 3 步上手（frpc-desktop 用 intro.js）。
- **改动**：`SettingsStore` 加 `guideCompleted: Boolean`；`ProfilesScreen` 首次进入时覆盖 3 步卡片：① 填 frps 地址/端口/Token ② 加一条 tcp 代理 ③ 回主页启动。第 3 步带「去启动」按钮。可跳过，跳过也记完成。
- **注意**：已有档案（`profiles.isNotEmpty()`）视为已完成，避免老用户被打扰。
- **验收**：全新数据目录启动出现引导；点「跳过」后重启不再出现。

#### T22 桌面端中英文 i18n — L
- **目标**：对齐 frpc-desktop 的双语；同时让桌面端和 Android 文案口径统一。
- **改动**：新建 `desktopApp/src/main/resources/i18n/{zh-CN,en-US}.properties` + `ui/Strings.kt`（`string(key, vararg args)` 从 `SettingsData.language` 读，默认跟随系统 `Locale`）；先抽 5 个页面（Main/Settings/Profiles/Home 标题与按钮），代理编辑弹窗的大段说明文案分两批抽。
- **注意**：**不要**把 frp 字段名（`serverAddr`、`tcpMux`）翻译掉——它们是配置键，翻了反而对不上文档。
- **验收**：切到 English 后 5 个页面无中文残留（`grep -P '[\x{4e00}-\x{9fa5}]'` 扫 `Main.kt/…/SettingsScreen.kt` 的字符串字面量为 0）；切回中文正常；Android 端 `strings.xml` 已有，补 `values-en/`。

#### T23 单实例 + 二次启动唤起 — S
- **目标**：重复双击图标不出现两个实例抢同一份 `profiles.json`。
- **改动**：`Main.kt` 启动时用 `FileLock`（`~/.frpcdash/app.lock`）判断；已存在则通过本地回环端口（如 127.0.0.1:随机端口 + token 文件）通知已有实例显示窗口并退出自身。
- **验收**：连开两次只留一个窗口；杀掉进程后锁能自动释放（不要用残留 pid 文件判断）。

---

### 批次 6 · Android 对齐（P2，按需）

#### T24 模型新字段两端同步 — M
- **目标**：批次 1 新增的 transport/TLS/log/metadatas 字段必须在 Android 编辑器出现，否则 Android 保存一次就会把桌面端配好的字段**抹掉**（`ServerProfile` 共用同一份 `FrpcConfig`，这是真实风险）。
- **改动**：`androidApp/.../ui/screens/ConfigEditScreen.kt` 补对应输入；`ConfigEditorStore` 的草稿字段同步；`androidApp/.../test/ConfigEditorStoreTest.kt` 加「载入含新字段的档案 → 保存 → 字段不丢」用例。
- **验收**：桌面端导出含 `heartbeatInterval` 的档案 → Android 打开保存 → 导出 TOML 该字段仍在。

#### T25 Android 全量导入 / 导出（SAF） — M
- **目标**：手机端也能备份与迁移。
- **改动**：`ConfigListScreen` 用 `ActivityResultContracts.CreateDocument/OpenDocument` 接 `application/json`；复用 T13 的 `exportAll/importAll`。
- **验收**：导出到 Downloads → 清空 → 从 Downloads 导入恢复。

#### T26 Android 快捷启停（可选） — S
- **目标**：桌面小组件 / 快捷设置磁贴一键启停。
- **改动**：`TileService` + `AppWidgetProvider`，复用 `FrpcService` 的启停 Intent。
- **验收**：下拉快捷面板出现磁贴，点击后服务状态与 App 内一致。

---

## 3. 明确「不照搬」的 6 点

1. **访问者并入 `[[proxies]]`（`visitorsModel`）**：frp 的 visitor 是独立 schema（`serverName`/`bindAddr`/`bindPort`/`fallbackTo`），我们的 `VisitorConfig` 更准，且已处理「`bindPort=0` 会被 frpc 拒绝」「`serverUser` 留空继承本机前缀」两个坑。
2. **`webServer.enable` 布尔字段**：我们已有 `webServer: WebServerConfig?`，`null` 即关闭，语义等价，别再造一个字段。
3. **代理类型只到 7 种**：不要为了对齐删掉 `tcpmux`。
4. **单服务器模型**：不要合并多档案。
5. **SQLite + 三张表**：配置量级（几十份档案）用单 JSON + 原子写更轻。只有 T15 的「引擎版本清单」值得单独一个 `engines.json`，也不值得上数据库。
6. **`getLocalPorts` 读数据库**：这是它 2.0 的退化实现，我们要真枚举系统监听端口。

---

## 4. 执行顺序与依赖

```
批次1 (T01–T05)  ──┬─→ 批次2 (T06–T12) ─→ 批次3 (T13–T14)
                   └─→ T24（Android 同步，必须紧跟批次1，否则字段会被 Android 抹掉）
批次4 (T15–T18) 独立可并行
批次5 (T19–T23) 依赖 T20 与 T23 的启动参数改造，建议同一 PR 做
```

**建议先做这 5 件（性价比最高、风险最低）**：
`T07`（端口预检接线，半小时级收益）→ `T05`（日志字段）→ `T03`（auth none）→ `T09`（行内启停）→ `T01`（传输字段）。
理由：这 5 项都在现有模型/页面上增量改，不改数据格式，不动两端契约，且能立刻消除「配不出来 / 点了才知道失败」的体感问题。

---

## 5. 每批的验收命令（沿用项目现有入口）

```bash
# 编译 + 单测（每批必过）
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest
./gradlew :desktopApp:compileKotlin

# TOML 往返与 frpc 认可性（批次1/2 必做）
./gradlew :desktopApp:run --args="--export-toml <profiles.json> <outDir>"
desktopApp/engine/frpc.exe verify -c <outDir>/<profile>.toml   # Linux/macOS 去掉 .exe

# 真实隧道回归（批次2/4 必做）
bash scripts/e2e/e2e-protocols.sh

# 桌面无界面自检（批次4/5 必做）
<安装目录>/frpc-desktop-multi.exe --smoke <frpc.toml>
<安装目录>/frpc-desktop-multi.exe --tray-test tray.png
```

手工回归清单（每批结束跑一遍）：
1. 新建档案 → 加 tcp / http / https / stcp 各一条 → 保存 → 启动 → 主页 4 条状态正确。
2. 停服 → 编辑 → 改端口 → 启动 → 真实远程端口刷新。
3. 导入一份外部 `frpc.toml`（含 plugin / healthCheck 等未建模字段）→ 不崩、不误报、多余字段被丢弃且提示。
4. Android 打开同一份档案 → 保存 → 回桌面端确认字段没丢。
5. 断网启动 → 显示「未连上服务器」+ 中文原因，不崩。

---

## 6. 风险清单

| 风险 | 说明 | 处理 |
|---|---|---|
| frp 严格解码 | 任何新字段只要落在不支持的代理类型上，`frpc` 直接退出（未知字段错误） | 所有新字段必须进 `sanitizedForType()` + `ConfigValidator` 的类型门禁，并加单测 |
| Android 反向覆盖 | 两端共用 `FrpcConfig`，Android 编辑器不认识新字段 → 保存即丢 | 批次 1 与 T24 同批合入，或先在 Android 侧加「不认识的字段原样保留」的兜底（`JsonElement` 透传） |
| `disableCustomTLSFirstByte` 默认值 | frp 默认 `true`，误写 `false` 会改变握手行为 | 模型用 `Boolean?`，只在用户显式操作时写 |
| 端口检测误报 | `SO_REUSEADDR` 或 TIME_WAIT 状态会让已释放端口显示被占 | 只用 `ServerSocket` 试绑 + `setReuseAddress(false)`，并在文案里说明「可能被 TIME_WAIT 占用」 |
| 引擎下载安全 | 从网络取二进制并执行 | 强制 sha256 校验 + 只接受 `fatedier/frp` 域名 + 落盘后 `-v` 自检 |
| 批量端口爆炸 | 用户输入 `1-65535` 会生成 6 万条代理 | 解析器硬上限 100 条，超限直接拒绝并提示 |
| 运行中改配置 | T09 打破了「运行中不可编辑」的约束 | 只放行 `enabled`，其余字段仍锁；reload 失败回滚到「需重启」提示 |
| i18n 翻错配置键 | 把 `serverAddr` 之类翻成中文会导致文档对不上 | 配置键一律不翻译，只翻说明与标题 |

---

## 7. 与 README「已知限制」的联动

完成后应同步修改 `README.md` 的「已知限制」段落：
- 删除「代理插件（`plugin`）、健康检查、`loadBalancer`、`metadatas`、`includes` 等高级字段未建模」中的 `plugin` 与 `metadatas`（T06/T04 已覆盖）。
- 保留 `healthCheck` / `loadBalancer` / `includes` 未建模（本次不对齐，frpc-desktop 也没做）。
- 新增说明：引擎支持多版本切换（T15）、证书路径在 Android 端需手工配置。
