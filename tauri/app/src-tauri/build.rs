use std::path::PathBuf;

/// 把内置引擎版本编进二进制。
///
/// 桌面端可以从打包资源 `resources/engine/VERSION` 读到版本，但 **Android 不行**：
/// 引擎是 jniLibs 里的 `libfrpc.so`（落在 nativeLibraryDir），APK 的 assets 里
/// 没有 `resources/engine/VERSION`，界面于是永远显示 unknown。
///
/// CI 在 `cargo` 之前就跑了 `scripts/build-frpc.sh`，构建期这两个文件里至少
/// 有一个存在（桌面模式写 resources/engine，Android 模式写 jniLibs），
/// 所以在此把它固化成一个环境变量，运行时用 `option_env!` 兜底。
fn emit_engine_version() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let candidates = [
        manifest.join("resources").join("engine").join("VERSION"),
        manifest
            .join("..")
            .join("..")
            .join("engines")
            .join("android")
            .join("jniLibs")
            .join("VERSION"),
    ];

    for path in &candidates {
        println!("cargo:rerun-if-changed={}", path.display());
    }

    let version = candidates
        .iter()
        .find_map(|p| std::fs::read_to_string(p).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());

    match version {
        Some(v) => println!("cargo:rustc-env=FRPC_ENGINE_VERSION={v}"),
        None => {
            println!("cargo:rustc-env=FRPC_ENGINE_VERSION=");
            println!("cargo:warning=未找到引擎 VERSION 文件（先跑 scripts/build-frpc.sh），内置版本将回退到 frpc -v 探测");
        }
    }
}

fn main() {
    emit_engine_version();
    tauri_build::build()
}
