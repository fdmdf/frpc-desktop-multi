# 基线数据（Tauri 2 重写 · M0 实测落档）

> 用途：迁移验收对照（`docs/tauri-migration-plan.md` 第 8 节的量化指标来源）。
> 采集时间：2026-10-31 · 基线版本 **v1.3.3**（Kotlin / Compose Multiplatform）。

## 1. 安装包体积（GitHub Release v1.3.3 实测）

| 平台 | 产物 | 体积 |
|---|---|---|
| Windows x64 | setup.exe | **101.2 MB** |
| Windows x64 | 便携 zip | 99.5 MB |
| Linux x64 | .deb | 93.7 MB |
| Linux x64 | .tar.gz | 105.1 MB |
| macOS arm64 | .dmg | 110.6 MB |
| macOS x64 | .dmg | 111.9 MB |
| Android arm64 | .apk | **7.6 MB** |

> 注：Android APK 不捆 JRE 所以只有 7.6 MB —— 反证桌面端 94~112 MB 的膨胀
> 几乎全部来自捆绑 JVM，与迁移方案第 2 节的判断一致。

**Tauri 目标**：桌面各产物 ≤ 15 MB（NSIS / DMG / deb）；Android APK 维持个位数 MB 级。

## 2. 冷启动

- 优化前 2767 ms → 优化后 **1684 ms**（`dev` 分支提交 `2b2bc51`，JVM 路线已接近瓶颈）
- **Tauri 目标 ≤ 1000 ms**

测量口径：冷启动到主窗口可交互（非 splash）。

## 3. 稳态内存（待实测，M0 遗留）

测量步骤：

1. 启动应用 → 进入主页 → 切到监控页，保持常开 5 分钟；
2. Windows 任务管理器记录主进程"内存（活动的专用工作集）"（不含 frpc 子进程）；
   macOS 用活动监视器 Memory 列；Linux 用 `ps -o rss= -p <pid>`（KiB）；
3. 记录三个时点：启动后 30 s / 主页 5 min / 监控页 5 min，取最大值。

| 时点 | Windows | macOS | Linux |
|---|---|---|---|
| 启动后 30 s | 待实测 | 待实测 | 待实测 |
| 主页 5 min | 待实测 | 待实测 | 待实测 |
| 监控页 5 min | 待实测 | 待实测 | 待实测 |

**Tauri 目标 ≤ 150 MB**（对照迁移方案第 8 节）。

## 4. 迁移后回填表（M3 验收时填写）

| 指标 | 基线（v1.3.3） | Tauri 实测 | 目标 | 结论 |
|---|---|---|---|---|
| Windows setup (NSIS) | 101.2 MB | 1.11 MiB（M0 骨架）/ 2.21 MiB（M2 全量 UI，未含引擎） | ≤15 MB | 达标 |
| Windows setup (MSI) | 101.2 MB | 1.59 MiB（M0）/ 3.00 MiB（M2） | ≤15 MB | 达标 |
| Windows setup（含 frpc 引擎） | 101.2 MB | **NSIS 7.07 MiB / MSI 9.39 MiB** | ≤15 MB | **✓ 达标（7% 基线体积）** |
| macOS dmg (arm64) | 110.6 MB | 待 CI 实测 | ≤15 MB | CI 矩阵已配置 |
| Linux deb | 93.7 MB | 待 CI 实测 | ≤15 MB | CI 矩阵已配置 |
| Android APK | 7.6 MB | 待 M4 | ≤10 MB | 插件源码已交付 |
| 冷启动 (Windows) | 1684 ms | 待实测 | ≤1000 ms | — |
| 稳态内存 (Windows) | 待实测 | **≈30 MB**（绿色版启动即主页空闲 5s 抽样） | ≤150 MB | 初测远优于目标；待监控页常开复测 |
| 八协议 e2e | 全绿 | **全绿**（`scripts/tauri-e2e.sh`：verify ×8 + 真实隧道回环） | 全绿 | ✓ |

> M0 实测（2026-10-31，本机 Windows x64，`tauri build` release + LTO，7m27s）：
> 骨架版安装包 NSIS 1.11 MiB / MSI 1.59 MiB。
> M2 实测（完整前后端，未含引擎）：NSIS 2.21 MiB / MSI 3.00 MiB。
> M3：frpc 引擎（16.9 MB）经 NSIS 压缩后随包，最终体积见回填表 M3 行。
