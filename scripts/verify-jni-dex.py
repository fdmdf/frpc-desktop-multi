#!/usr/bin/env python3
"""核对 release 包里 Rust 依赖的 JNI 入口是否还在。

为什么需要这个守卫：
    Rust 侧是用**字符串**按名字调 Kotlin 的静态方法（`call_static_method`），
    R8 看不到这种「引用」。release 构建开着 `optimization { enable = true }`，
    这些方法会被当成死代码删掉或改名 —— 类还在、方法没了。这种问题编译器
    不管、单测碰不到，只有真机点到那一步才暴露成

        java.lang.NoSuchMethodError: no static method
        "Lio/github/fdmdf/frpc/FrpcHost;.requestIgnoreBatteryOptimizations(Landroid/content/Context;)Z"

    这类入口被删，编译期看不出来，只有真机点击时才报错。所以出包前直接从
    dex 里核对一遍，缺一个就不发。

用法：
    # 解出 APK 里的 dex，再 dump
    unzip -o -q app-release-unsigned.apk 'classes*.dex' -d /tmp/dex
    dexdump /tmp/dex/classes*.dex > /tmp/dex.txt
    python3 scripts/verify-jni-dex.py /tmp/dex.txt

    退出码 0 = 全部命中；1 = 有缺失/签名不符/静态标记丢失，并逐条打印原因。

维护：改 `tauri/app/src-tauri/src/android.rs` 里的类名、方法名或签名时，
      同步改下面的 EXPECTED；两边对不上 CI 会直接拦下。
"""

import re
import sys

# 类名（dex 里的描述符）-> {方法名: (JVM 方法签名, 是否必须是静态方法)}
EXPECTED = {
    "Lio/github/fdmdf/frpc/FrpcHost;": {
        "openUrl": ("(Landroid/content/Context;Ljava/lang/String;)V", True),
        "isIgnoringBatteryOptimizations": ("(Landroid/content/Context;)Z", True),
        "requestIgnoreBatteryOptimizations": ("(Landroid/content/Context;)Z", True),
        "openAppSettings": ("(Landroid/content/Context;)V", True),
        "moveToBack": ("(Landroid/content/Context;)V", True),
    },
    "Lio/github/fdmdf/frpc/service/FrpcKeepAliveService;": {
        "start": (
            "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V",
            True,
        ),
        "stop": ("(Landroid/content/Context;)V", True),
    },
}

# dexdump 的 method/field 都是 name/type/access 三行，字段的 type 不是 '(' 开头
RE_CLASS = re.compile(r"^\s*Class descriptor\s*:\s*'(.+?)'\s*$")
RE_NAME = re.compile(r"^\s*name\s*:\s*'(.+)'\s*$")
RE_TYPE = re.compile(r"^\s*type\s*:\s*'(.+)'\s*$")
RE_ACCESS = re.compile(r"^\s*access\s*:\s*0x([0-9a-fA-F]+)\s*\((.*?)\)")


def parse(streams):
    """从 dexdump 文本里抽出 {类描述符: {方法名: (签名, 是否静态)}}。

    多个 dex 会有重名类（同名类其实不会跨 dex 重复），后出现的覆盖前者即可。
    """
    classes = {}
    cur = None
    name = None
    mtype = None
    for stream in streams:
        for line in stream:
            m = RE_CLASS.match(line)
            if m:
                cur = m.group(1)
                classes.setdefault(cur, {})
                name = mtype = None
                continue
            m = RE_NAME.match(line)
            if m:
                name = m.group(1)
                continue
            m = RE_TYPE.match(line)
            if m:
                mtype = m.group(1)
                continue
            m = RE_ACCESS.match(line)
            if m and name and mtype:
                # 只收方法（字段的 type 是 'Ljava/...;' 这类单一描述符）
                if mtype.startswith("(") and cur is not None:
                    classes[cur][name] = (mtype, "STATIC" in m.group(2))
                name = mtype = None
    return classes


def main(argv):
    if len(argv) < 2:
        print("用法: verify-jni-dex.py <dexdump 输出文件> [更多文件...]", file=sys.stderr)
        return 2

    classes = parse(open(p, encoding="utf-8", errors="replace") for p in argv[1:])

    problems = []
    for cls, methods in EXPECTED.items():
        found = classes.get(cls)
        if found is None:
            problems.append(f"类不见了：{cls}（R8 把整个类删了？）")
            continue
        for method, (signature, must_be_static) in methods.items():
            hit = found.get(method)
            if hit is None:
                names = ", ".join(sorted(found)) or "<空>"
                problems.append(
                    f"{cls}.{method} 不存在（该类的实际方法：{names}）"
                    f" —— R8 把它当死代码删掉/改名了，检查 proguard keep 规则"
                )
                continue
            actual_signature, is_static = hit
            if must_be_static and not is_static:
                problems.append(
                    f"{cls}.{method} 不是静态方法（JVM 签名 {actual_signature}）"
                    f" —— Kotlin 侧漏了 @JvmStatic"
                )
            if actual_signature != signature:
                problems.append(
                    f"{cls}.{method} 签名不符：期望 {signature}，实际 {actual_signature}"
                    f" —— Kotlin 侧改过参数？同步 android.rs 与本脚本"
                )

    if problems:
        print("JNI 入口校验失败：", file=sys.stderr)
        for item in problems:
            print(f"  - {item}", file=sys.stderr)
        return 1

    total = sum(len(v) for v in EXPECTED.values())
    print(f"JNI 入口校验通过：{len(EXPECTED)} 个类 / {total} 个方法全部存在且签名一致")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
