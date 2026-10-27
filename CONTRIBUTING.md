# 贡献指南

感谢你愿意参与本项目。以下流程能让协作更顺畅。

## 开发环境

- JDK 17+（推荐 21）
- Android SDK：platform 36、build-tools 36.0.0
- Go 1.26+（仅在重建 frpc 引擎时需要）
- 建议使用 Android Studio 最新稳定版

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :shared:jvmTest :shared:testDebugUnitTest :androidApp:testDebugUnitTest
```

## 目录约定

| 目录 | 说明 |
| --- | --- |
| `shared/` | 跨平台逻辑层。**不依赖任何 Android API**，可被 JVM/Android 双端复用与测试 |
| `shared/src/jvmCommonMain/` | 只使用 JVM 标准库（`java.net` / `java.io`）的实现，Android 与 JVM 共用 |
| `androidApp/` | Android 专属：Service、进程守护、Compose UI |
| `scripts/` | 可复现的构建脚本（引擎编译、发布） |

新增业务逻辑时优先放进 `shared/`，并在 `commonTest`（双端运行）或 `jvmCommonTest` 中补测试。

## 代码规范

- Kotlin 官方代码风格（`kotlin.code.style=official`），4 空格缩进
- Compose 组件按「屏幕 → 组件 → 状态」分层，避免在 Composable 中做 IO
- 与 frpc 配置相关的字段变更必须同步：
  1. `shared/model/*.kt` 的模型
  2. `ConfigValidator` 的校验规则
  3. 对应的单元测试
  4. `README.md` 的兼容性说明（如涉及协议行为）
- 提交前请确保测试全绿，且 Debug 包可正常安装启动

## 提交信息

采用约定式提交（Conventional Commits）：

```
feat: 新增 xtcp visitor 高级参数
fix: 修复 kcp 端口不匹配时的状态误报
docs: 补充 quic 端口对齐说明
chore(frpc): bump engine to v0.71.2
```

## 提交 PR

1. Fork 并从 `main` 切出分支：`feat/xxx`、`fix/xxx`
2. 保持改动聚焦，一个 PR 只做一件事
3. PR 描述中说明：动机、改动点、验证方式（测试命令 / 截图 / 日志）
4. 涉及 UI 的改动请附截图（深色/浅色各一张更佳）

## 报告问题

使用 Issue 模板提交，务必包含：

- 机型与 Android 版本
- App 版本（设置 → 关于）
- 引擎版本（设置 → frpc 引擎）
- 相关日志（日志页「分享」导出）
- 复现步骤

## 引擎升级

`scripts/build-frpc.sh` 支持 `FRP_VERSION` 环境变量：

```bash
FRP_VERSION=v0.72.0 bash scripts/build-frpc.sh
```

升级后请更新 `README.md` 兼容性矩阵，并跑一遍协议矩阵测试（见 Wiki：协议兼容性）。
