# 重构第一轮 · 修改清单（2026-10-29）

> 原则：去冗余 / 去重 / 去死依赖 + 命名统一。**不加任何新功能**，版本号未动（1.3.4 / versionCode 8）。

## 一、去重：消除跨端重复定义

| 改动 | 原因 |
|---|---|
| `ServerProfile` 从两端各一份合并为 `shared/.../model/ServerProfile.kt` | 两端持久化的是同一份 `profiles.json`，却各存一份**逐字符重复**的模型，改字段要改两处 |
| 两端格式化合并为 `shared/format/Formatters.kt` | `android/util/Formatters.kt` 与 `desktop/ui/Format.kt` 功能重复**且行为不一致** |
| `ProfileBundle` / `ImportSummary` 拆到 `desktopApp/data/ProfileBundle.kt` | 全量导入导出目前**只有桌面端有**，不该塞进共享模型 |

放在 `jvmCommonMain` 而不是 `commonMain`：`ServerProfile` 靠 `java.util.UUID` 生成 id，commonMain 拿不到。

### 格式化统一后的口径（两处行为变更）

1. 时区固定 **Asia/Shanghai**（桌面端原为 `ZoneId.systemDefault()`）；
2. 小数一律 **Locale.US**（桌面端原跟系统 locale，`1.5 MB` 在某些语言下会显示成 `1,5 MB`）；
3. `formatMemoryKb` 签名放宽为 `Long?`（桌面端依赖 `--` 兜底），并保留 Android 的 GB 档；
4. 非今天的日志行补上毫秒 `.SSS`，与当天口径一致。

## 二、修正一个潜在 bug

**Android 端口校验改用 `shared` 的 `PortCheck::isInUse`**，删除 `util/Formatters.isPortInUse`。

旧实现是裸 `ServerSocket(port)` bind、**没有 SO_REUSEADDR**，且塞在格式化文件里违反单一职责。
frpc 退出后 admin 端口会留 TIME_WAIT，frpc 自己（Go `net.Listen` 自带 SO_REUSEADDR）其实绑得上，
但旧检测会误报「端口被占用」——桌面端早就修过，Android 这条路径漏了。现在两端同一口径。

## 三、清理冗余

- 未使用导入清零（20 处）。扫描时特意排除 Kotlin 委托算子 `getValue` / `setValue` / `provideDelegate`
  —— 它们不以字面量出现，不能算未使用。
- 内联全限定名改为 import（16 个文件），含 `MonitorScreen.kt` 里 9 处 `com.frpc.desktop.engine.LogProxyPhase.*`。
- 移除 3 个未使用依赖：`androidx-lifecycle-viewmodel-compose`、`compose-ui-tooling`、`-tooling-preview`
  （全仓 **0 个 `@Preview`**）。删除理由写进 `androidApp/build.gradle.kts` 注释，防止回潮。

## 四、明确不做 / 已否决

- **`scripts/frpc-web-dist`（489 KB 前端产物）不能删**：`client/api_router.go` 把 `/static/` 挂到
  `helper.AssetsFS` 并把 `/` 302 过去，`cmd/frpc/main.go` 里 `_ ".../web/frpc"` 正是 embed 它；
  wiki《快速开始》把它作为已交付功能写给用户。删它＝砍已上线功能。构建脚本的占位页只是兜底。
- **两个 FrpcSupervisor 不合到 shared**：平台差异真实（前台服务通知 / 孤儿清扫 / PowerShell 取
  metrics / 日志导出，且 object vs 可注入 class），合并只会多一层抽象。
- **`ProcessStats`(shared) 与 `ProcessMetrics`(desktop) 的 /proc 解析重复**，但 CPU% 语义不同
  （单核算满 100% vs 整机占比），合并会改监控页读数且桌面侧无单测 —— 留待单独一轮。

## 五、踩过的坑

用正则脚本做「全限定名 → import」时**必须先把字符串字面量 mask 掉**：第一版脚本把
`FrpcService.kt` 的 `ACTION_START = "com.frpc.android.action.START"` 改成了 `"START"`，
Intent action 是跨进程契约，改了旧版 PendingIntent / 通知按钮就会失效。已还原，并补了
「拿 HEAD 版本所有含 `com.frpc.` 的字符串字面量逐个比对」的校验。判重插入 import 要看**值**不是 key。

## 六、验证

- `:desktopApp:compileKotlin` + `:androidApp:compileDebugKotlin` → BUILD SUCCESSFUL
- `:shared:jvmTest` `:shared:testDebugUnitTest` `:androidApp:testDebugUnitTest` →
  `--rerun-tasks` 强制重跑，52 个 task 全部执行，**167 个用例 / 0 失败 / 0 跳过**
- 一致性核查：`ServerProfile` 定义数 = 1；旧包名引用残留 = 0；字符串字面量丢失 = 0

## 七、已知遗留

`ProfileBundle` 全量导出 / 导入目前只有桌面端有入口，Android 侧暂无（只有 `shareToml`）。
这是既有的两端能力差，补它属于新功能，不在本轮范围。
