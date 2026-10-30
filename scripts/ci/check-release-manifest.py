"""交叉核对 release.yml：
1) desktop/android job 实际产出的文件名模板
2) release job 发布前校验清单要求的必需文件
两者必须完全对上，否则会出现「产物明明有，却报缺失」或「拼错名字的文件混进 Release」。
"""
import re
import sys

import yaml

V, P = "1.3.1", "Frpc-Android-Multi"
path = ".github/workflows/release.yml"
d = yaml.safe_load(open(path, encoding="utf-8"))
jobs = d["jobs"]

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

# Android 签名与否二选一，两个名字都要认
required.discard(P + "-" + V + "-android-arm64-unsigned.apk")

print("=== 发布前必需清单（%d 项）===" % len(required))
for f in sorted(required):
    print("  ", f)

print()
print("必需但没有任何 job 产出 :", sorted(required - expanded) or "无")
print("可选但没有任何 job 产出 :", sorted(optional - expanded) or "无")
print("产出但不在校验清单里    :", sorted(expanded - required - optional) or "无")

missing = required - expanded
sys.exit(1 if missing else 0)
