#!/usr/bin/env python3
"""生成 GitHub Release 正文。

为什么不用 `generate_release_notes: true`：
    GitHub 自动生成的是「What's Changed」commit 流水 + first-time contributor
    鸣谢，对个人项目既重复（CHANGELOG 里已经写过一遍）又难看，还夹着一堆
    `chore(release)` 这种对用户毫无意义的条目。访客真正想知道的是三件事：
    这一版改了什么、我该下哪个文件、升级会不会丢配置。

所以正文固定成三段：本次更新（从 CHANGELOG 取，单一事实来源）+ 下载 +
升级说明，末尾给完整变更日志的链接。

正文里的「本次更新」只留**条目摘要**：CHANGELOG 是给维护者看的，写的是「为什么这么改、
踩了哪个坑」；Release 页面的读者只想知道这一版动了什么。所以每条只取第一句，展开说明、
子项、根因分析都不进正文 —— 想看细节的人点末尾的完整变更日志。

签名指纹不写进正文：那是给维护者核对 CI 用的，普通用户看不懂也不需要看；
真要查，`tauri/android-signing/README.md` 里有。

用法：
    python scripts/release-notes.py v2.0.0 > release-notes.md
"""

import re
import sys

REPO = "fdmdf/frpc-desktop-multi"
CHANGELOG = "CHANGELOG.md"


def version_of(tag: str) -> str:
    return tag[1:] if tag.startswith("v") else tag


def section(changelog: str, version: str) -> str:
    """取出 `## [2.0.0] ...` 到下一个 `## [` 之间的正文。"""
    pattern = re.compile(
        r"^##\s*\[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^##\s*\[|\Z)",
        re.MULTILINE | re.DOTALL,
    )
    m = pattern.search(changelog)
    if not m:
        return ""
    body = m.group(1).strip()
    # CHANGELOG 里的标题层级比 Release 正文低一级，去掉多余空行即可直接嵌套
    return body


# 中文条目里「一句话」的边界，以及截断后挂在末尾、读起来像半句的标点
SENTENCE_END = "。！？"
DANGLING = "，,、；;：: "


def first_sentence(text: str) -> str:
    """取一条目的第一句：截到第一个句号，再把末尾的「，：。」这类挂尾标点去掉。"""
    m = re.search(f"[{SENTENCE_END}]", text)
    if m:
        text = text[: m.start()]
    return text.rstrip(DANGLING).strip()


def condense(body: str) -> str:
    """把 CHANGELOG 段落压成条目摘要，供 Release 正文使用。

    CHANGELOG 里每条通常长这样：

        - **标题句**，然后是展开说明：
          - 子项……
          第三段继续解释为什么这么改

    正文只要第一句。子项、续行、引用一律丢掉；`###` 小标题保留，用来分组
    （安卓 / 发布流程 / 界面…）。
    """
    kept: list[str] = []
    for raw in body.splitlines():
        line = raw.rstrip()
        if line.startswith("#"):
            kept.append("")
            kept.append(line)
            kept.append("")
        elif line.startswith("- "):
            text = first_sentence(line[2:].strip())
            if text:
                kept.append("- " + text)
        # 其余（续行、缩进子项、空行）都是展开说明，不进球
    lines: list[str] = []
    for line in kept:
        if line == "" and (not lines or lines[-1] == ""):
            continue
        lines.append(line)
    while lines and lines[-1] == "":
        lines.pop()
    return "\n".join(lines)


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: release-notes.py v2.0.0", file=sys.stderr)
        return 2
    tag = sys.argv[1].strip()
    ver = version_of(tag)

    try:
        changelog = open(CHANGELOG, encoding="utf-8").read()
    except OSError as e:
        print(f"读不到 {CHANGELOG}: {e}", file=sys.stderr)
        return 1

    body = section(changelog, ver)
    if not body:
        # 宁可直接失败，也不要发一个「本次更新」空白的 Release 页面
        print(f"::error::{CHANGELOG} 里找不到 [{ver}] 段落", file=sys.stderr)
        return 1
    body = condense(body)
    if not body:
        print(f"::error::{CHANGELOG} 的 [{ver}] 段落里没有可用的条目", file=sys.stderr)
        return 1

    out = []
    out.append(
        "Tauri 2 + Rust 构建的 frpc 多配置客户端：桌面端与 Android 共用同一套界面，"
        "内置 frpc 引擎，支持 8 种代理、3 种访问者、崩溃自动重启与托盘常驻。"
    )
    out.append("")
    out.append("## 本次更新")
    out.append("")
    out.append(body)
    out.append("")
    out.append("## 下载")
    out.append("")
    out.append("| 平台 | 文件 | 说明 |")
    out.append("| --- | --- | --- |")
    out.append(
        f"| Windows 10/11 | `frpc-desktop-multi_{ver}_x64-setup.exe` | 推荐，安装包（开始菜单 / 托盘图标 / 卸载项） |"
    )
    out.append(
        f"| Windows 10/11 | `frpc-desktop-multi_{ver}_x64_en-US.msi` | 批量部署或静默安装 |"
    )
    out.append(
        f"| macOS（Apple Silicon） | `frpc-desktop-multi_{ver}_aarch64.dmg` | M 系列芯片；首次打开需在「隐私与安全性」里放行 |"
    )
    out.append(
        f"| Linux | `frpc-desktop-multi_{ver}_amd64.deb` | Debian / Ubuntu，走系统包管理器 |"
    )
    out.append(
        f"| Linux | `frpc-desktop-multi_{ver}_amd64.AppImage` | 任意发行版，免安装，`chmod +x` 后直接运行 |"
    )
    out.append(
        f"| Android | `frpc-desktop-multi-{ver}-arm64.apk` | arm64 设备（绝大多数手机），需允许「未知来源」 |"
    )
    out.append("")
    out.append("## 升级说明")
    out.append("")
    out.append("- **Android**：安装包可直接覆盖安装，**无需卸载**；应用数据不受影响。")
    out.append(
        "- **桌面端**：安装包覆盖旧版本，配置与日志保留在原数据目录（「设置 → 关于」可查看路径）。"
    )
    out.append("")
    out.append(
        f"完整变更历史：[CHANGELOG.md](https://github.com/{REPO}/blob/{tag}/CHANGELOG.md)　·　"
        f"问题反馈：[Issues](https://github.com/{REPO}/issues)"
    )

    print("\n".join(out))
    return 0


if __name__ == "__main__":
    # Windows 的标准输出默认跟着控制台代码页走（cp1252 / cp936），中英混排的正文
    # 一 print 就抛 UnicodeEncodeError；用 shell 重定向成文件时更糟 —— 文件本身会是
    # 本地编码，Release 页面直接乱码。三平台统一按 UTF-8 + LF 输出。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", newline="\n")
    sys.exit(main())
