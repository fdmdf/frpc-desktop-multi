"""交叉核对 release.yml：
1) desktop/android job 实际产出的文件名模板
2) release job 发布前校验清单要求的必需文件
两者必须完全对上，否则会出现「产物明明有，却报缺失」或「拼错名字的文件混进 Release」。

产品名与版本号都从仓库里现读，不在本脚本里硬编码 —— 否则每次发版脚本自身就会漂移，
校验出来的结论反而是错的。
"""
import re
import sys

import yaml

RELEASE_YML = ".github/workflows/release.yml"
BUILD_GRADLE = "desktopApp/build.gradle.kts"

d = yaml.safe_load(open(RELEASE_YML, encoding="utf-8"))
jobs = d["jobs"]

# 产品名来自 release.yml 的 env.PRODUCT（与 desktopApp/build.gradle.kts 的 packageName 同源）
P = d.get("env", {}).get("PRODUCT") or "Frpc-Android-Multi"


def read_fallback_version() -> str:
    """读 desktopApp/build.gradle.kts 里 appVersionRaw 的兜底版本（最后一处 "x.y.z"）。

    与 release.yml 的 Resolve and validate version 步骤用同样的取法（tail -1），
    两处对同一份文件取同一个值，脚本就不会随发版漂移。
    """
    with open(BUILD_GRADLE, encoding="utf-8") as f:
        found = re.findall(r'"(\d+\.\d+\.\d+)"', f.read())
    if not found:
        raise SystemExit("无法从 %s 解析兜底版本号，请检查文件格式" % BUILD_GRADLE)
    return found[-1]


V = read_fallback_version()

# ---- 1) 收集各 job 产出的目标文件名 ----
produced = set()
for jn in ("desktop", "android"):
    for st in jobs[jn]["steps"]:
        if st.get("name") != "Collect artifacts":
            continue
        for line in st["run"].split("\n"):
            m = re.match(r"\s*(take|take_opt)\s+\"[^\"]+\"\s+'[^']+'\s+\"([^\"]+)\"", line)
            if m:
                produced.add(m.group(2))
            # cp "$DIR/androidApp-release.apk" "$OUT/$PRODUCT-$V-android-arm64.apk"
            m = re.search(r'cp\s+"[^"]+"\s+"[^"]*/\$OUT/([^"]+)"', line)
            if not m:
                m = re.search(r'cp\s+"[^"]+"\s+"\$OUT/([^"]+)"', line)
            if m:
                produced.add(m.group(1))
            m = re.search(r'DestinationPath\s+.([^"\']*\$OUT/[^"\']+)', line)
            if m:
                produced.add(m.group(1).split("/")[-1])
            m = re.search(r'tar\s+-czf\s+"\$OUT/([^"]+)"', line)
            if m:
                produced.add(m.group(1))

# macOS 两个架构共用一段脚本，按矩阵展开
expanded = set()
for name in produced:
    for arch in ("arm64", "x64"):
        if "$A" in name:
            expanded.add(name.replace("$A", arch))
        else:
            expanded.add(name)
            break

# 运行时常量（引擎版本留档、zip 内的 exe 名等不走 dist 根目录的忽略）
expanded.add("frpc-engine-VERSION.txt")


def sub(s):
    return s.replace("$V", V).replace("$PRODUCT", P)


expanded = {sub(x) for x in expanded}

# ---- 2) release job 的校验清单 ----
required, optional = set(), set()
for st in jobs["release"]["steps"]:
    if st.get("name") != "Verify artifact manifest":
        continue
    for m in re.finditer(r'check "([^"]+)"\s+(required|optional)', st["run"]):
        f = sub(m.group(1))
        (required if m.group(2) == "required" else optional).add(f)

# Android 签名与否二选一，两个名字都要认（产出侧两条 cp 都在，清单侧只保留一个）
UNSIGNED_APK = P + "-" + V + "-android-arm64-unsigned.apk"
required.discard(UNSIGNED_APK)
expanded.discard(UNSIGNED_APK)

print("=== 发布前必需清单（%d 项，V=%s P=%s）===" % (len(required), V, P))
for f in sorted(required):
    print("  ", f)

missing = required - expanded     # 清单要、但没有任何 job 产出 -> 发版必挂
orphan = optional - expanded      # 声明可选、但同样没人产出 -> 多半是写错了名字
extra = expanded - required - optional   # 有产出却不在清单里 -> 拼错的名字会混进 Release

print()
print("必需但没有任何 job 产出 :", sorted(missing) or "无")
print("可选但没有任何 job 产出 :", sorted(orphan) or "无")
print("产出但不在校验清单里    :", sorted(extra) or "无")

if missing:
    print("\n::error::release.yml 的「Verify artifact manifest」要求了 %d 个产物，"
          "但没有 job 会产出它们。" % len(missing))
if extra:
    print("\n::error::有 %d 个产物会被产出却不在发布清单里，通常是文件名拼错，"
          "或新增/改名后忘了同步「Verify artifact manifest」。" % len(extra))

sys.exit(1 if (missing or extra) else 0)
