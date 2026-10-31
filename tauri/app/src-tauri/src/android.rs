//! Android 原生能力桥（JNI）：前台保活通知、外部浏览器、电池优化白名单。
//!
//! 只在 Android 构建里编译。桌面端对应能力分别是托盘、系统默认浏览器、无需申请。
//!
//! 设计取舍：Intent / Uri / 通知这些 Java 对象全部在 Kotlin 里拼（`FrpcHost`、
//! `FrpcKeepAliveService` 的静态方法），Rust 只用 `call_static_method` 传
//! Context + String。反过来从 Rust 里逐个 `new_object` 拼 Intent 的构造器签名，
//! 既难写又容易因为 ROM 差异炸在运行时。
//!
//! 所有调用都包 `catch_unwind`：JNI 里 panic 会直接 abort 整个应用，
//! 保活失败只是少一条通知，不该让隧道跟着崩。

#![cfg(target_os = "android")]

use std::sync::atomic::{AtomicBool, Ordering};

use jni::objects::{JObject, JValue};
use jni::JavaVM;

const SERVICE_CLASS: &str = "io/github/fdmdf/frpc/service/FrpcKeepAliveService";
const HOST_CLASS: &str = "io/github/fdmdf/frpc/FrpcHost";

/// 服务是否已在前台（避免每次状态变化都重复 startService）。
static SERVICE_ON: AtomicBool = AtomicBool::new(false);

/// 取 JNIEnv + Activity，执行一段 JNI 调用；panic / 附加线程失败都收敛成 Err。
fn with_env<R, F>(what: &str, f: F) -> Result<R, String>
where
    F: FnOnce(&mut jni::JNIEnv<'_>, &JObject<'_>) -> Result<R, String>,
{
    let attempt = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ctx = ndk_context::android_context();
        let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }
            .map_err(|e| format!("{what}: JavaVM 不可用（{e}）"))?;
        let mut env = vm
            .attach_current_thread()
            .map_err(|e| format!("{what}: 附加 JNI 线程失败（{e}）"))?;
        // ndk-context 里存的是 Activity 的全局引用；JObject 只是借用，不会释放它
        let activity = unsafe { JObject::from_raw(ctx.context().cast()) };
        f(&mut env, &activity)
    }))
    .map_err(|_| format!("{what}: JNI 调用 panic"))?;
    attempt
}

/// 拉起（或更新）隧道运行期的常驻通知。
pub fn keepalive_start(title: &str, text: &str) -> Result<(), String> {
    with_env("keepalive_start", |env, activity| {
        let class = env
            .find_class(SERVICE_CLASS)
            .map_err(|e| format!("找不到 {SERVICE_CLASS}（{e}）"))?;
        let jtitle = env.new_string(title).map_err(|e| e.to_string())?;
        let jtext = env.new_string(text).map_err(|e| e.to_string())?;
        env.call_static_method(
            class,
            "start",
            "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)V",
            &[
                JValue::Object(activity),
                JValue::Object(&jtitle),
                JValue::Object(&jtext),
            ],
        )
        .map_err(|e| format!("FrpcKeepAliveService.start 调用失败（{e}）"))?;
        SERVICE_ON.store(true, Ordering::Relaxed);
        Ok(())
    })
}

/// 收掉常驻通知（隧道停止、启动失败、应用退出时调用）。
pub fn keepalive_stop() -> Result<(), String> {
    if !SERVICE_ON.swap(false, Ordering::Relaxed) {
        return Ok(());
    }
    with_env("keepalive_stop", |env, activity| {
        let class = env.find_class(SERVICE_CLASS).map_err(|e| e.to_string())?;
        env.call_static_method(
            class,
            "stop",
            "(Landroid/content/Context;)V",
            &[JValue::Object(activity)],
        )
        .map_err(|e| format!("FrpcKeepAliveService.stop 调用失败（{e}）"))?;
        Ok(())
    })
}

/// 用系统浏览器打开链接（桌面端走 `explorer` / `open` / `xdg-open`）。
pub fn open_url(url: &str) -> Result<(), String> {
    with_env("open_url", |env, activity| {
        let class = env.find_class(HOST_CLASS).map_err(|e| e.to_string())?;
        let jurl = env.new_string(url).map_err(|e| e.to_string())?;
        env.call_static_method(
            class,
            "openUrl",
            "(Landroid/content/Context;Ljava/lang/String;)V",
            &[JValue::Object(activity), JValue::Object(&jurl)],
        )
        .map_err(|e| format!("FrpcHost.openUrl 调用失败（{e}）"))?;
        Ok(())
    })
}

/// 引导用户把应用加入电池优化白名单。
///
/// 返回 `true` 表示本来就在白名单里（不需要再操作）；`false` 表示已跳到系统页面，
/// 是否真的加入要用户确认后重新查询才知道。
pub fn request_battery_exemption() -> Result<bool, String> {
    with_env("request_battery_exemption", |env, activity| {
        let class = env.find_class(HOST_CLASS).map_err(|e| e.to_string())?;
        let result = env
            .call_static_method(
                class,
                "requestIgnoreBatteryOptimizations",
                "(Landroid/content/Context;)Z",
                &[JValue::Object(activity)],
            )
            .map_err(|e| format!("FrpcHost.requestIgnoreBatteryOptimizations 失败（{e}）"))?;
        Ok(result.z().unwrap_or(false))
    })
}
