//! Android 原生能力桥（JNI）：前台保活通知、外部浏览器、电池优化白名单。
//!
//! 只在 Android 构建里编译。桌面端对应能力分别是托盘、系统默认浏览器、无需申请。
//!
//! 设计取舍：Intent / Uri / 通知这些 Java 对象全部在 Kotlin 里拼（`FrpcHost`、
//! `FrpcKeepAliveService` 的静态方法），Rust 只用 `call_static_method` 传
//! Context + String。反过来从 Rust 里逐个 `new_object` 拼 Intent 的构造器签名，
//! 既难写又容易因为 ROM 差异炸在运行时。
//!
//! 两条硬规矩（v1.4.8 上「允许后台运行」按钮报
//! 「Error invoking postMessage: Java exception was raised」就是踩了这两条）：
//!
//! 1. **不要用 `JNIEnv::find_class`**。它等价于 JNI 的 `FindClass`，从「不是 Java
//!    创建的线程」（Tauri 的命令跑在 tokio 线程池上）调用时会退化成系统
//!    ClassLoader，永远找不到 `io.github.fdmdf.frpc.*`，直接抛
//!    `ClassNotFoundException`。改用 `activity.getClassLoader().loadClass(...)`，
//!    与调用线程无关。
//! 2. **JNI 调用失败后必须清掉挂起的 Java 异常**。`call_static_method` 只返回一个
//!    `Err`，异常本身仍挂在线程上；带着异常的线程后续每次 JNI 调用都会立刻失败，
//!    包括 Tauri 自己回传结果的 IPC —— 于是真正的原因被吞掉，前端只看到
//!    「postMessage: Java exception was raised」这种毫无信息量的错。
//!
//! 所有调用都包 `catch_unwind`：JNI 里 panic 会直接 abort 整个应用，
//! 保活失败只是少一条通知，不该让隧道跟着崩。

#![cfg(target_os = "android")]

use std::sync::atomic::{AtomicBool, Ordering};

use jni::objects::{JClass, JObject, JString, JThrowable, JValue};
use jni::JavaVM;

/// Kotlin 侧的宿主能力类（全限定名，走 ClassLoader.loadClass）。
const HOST_CLASS: &str = "io.github.fdmdf.frpc.FrpcHost";
/// 前台服务类。
const SERVICE_CLASS: &str = "io.github.fdmdf.frpc.service.FrpcKeepAliveService";

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

        // 每次调用开一个局部引用帧：attach 住的线程不会回到 Java，局部引用也就不会
        // 被自动回收，反复调用（状态每切换一次就一组）迟早撑满局部引用表。
        env.push_local_frame(32)
            .map_err(|e| format!("{what}: 创建局部引用帧失败（{e}）"))?;
        let out = f(&mut env, &activity);
        // R 只会是 () / bool，不含局部引用，所以可以直接弹栈（返回值忽略）
        unsafe {
            let _ = env.pop_local_frame(&JObject::null());
        }
        out
    }))
    .map_err(|_| format!("{what}: JNI 调用 panic"))?;
    attempt
}

/// 取出并**清除**线程上挂起的 Java 异常，返回 `toString()` 的文本。
///
/// 见文件头第 2 条：不清异常，同线程后续任何 JNI 调用都会立刻失败。
fn take_exception(env: &mut jni::JNIEnv<'_>) -> Option<String> {
    if !env.exception_check().unwrap_or(false) {
        return None;
    }
    let throwable = env.exception_occurred().ok()?;
    // 先清掉才能在下面安全地调 toString（带异常时不允许调普通方法）
    let _ = env.exception_clear();
    let text = throwable_to_string(env, &throwable);
    // toString 自己理论上也可能抛，别把线程再弄脏
    let _ = env.exception_clear();
    Some(text.unwrap_or_else(|| "Java 异常（无描述）".to_string()))
}

fn throwable_to_string(env: &mut jni::JNIEnv<'_>, throwable: &JThrowable<'_>) -> Option<String> {
    let value = env
        .call_method(throwable, "toString", "()Ljava/lang/String;", &[])
        .ok()?;
    let obj = value.l().ok()?;
    // 先绑定 JString：get_string 借用它，直接写在实参位置会「临时值提前释放」
    let jtext = JString::from(obj);
    let text = env.get_string(&jtext).ok()?;
    Some(text.to_string_lossy().into_owned())
}

/// 把 jni 的 Err 翻译成「Java 异常描述优先、否则用 jni 自己的错误」。
fn java_detail(env: &mut jni::JNIEnv<'_>, err: jni::errors::Error) -> String {
    take_exception(env).unwrap_or_else(|| err.to_string())
}

/// 用 Activity 的 ClassLoader 加载应用类（理由见文件头第 1 条）。
///
/// activity 的生命周期刻意不与 `env` 的 `'local` 绑定：`call_method` 本身就允许
/// 两个生命周期不同，而调用方（`with_env` 的闭包）拿到的是两个独立的匿名生命周期。
fn load_class<'l>(
    env: &mut jni::JNIEnv<'l>,
    activity: &JObject<'_>,
    dotted: &str,
) -> Result<JClass<'l>, String> {
    let loader = env
        .call_method(activity, "getClassLoader", "()Ljava/lang/ClassLoader;", &[])
        .map_err(|e| format!("取 ClassLoader 失败：{}", java_detail(env, e)))?
        .l()
        .map_err(|e| format!("ClassLoader 不是对象：{e}"))?;
    let jname = env
        .new_string(dotted)
        .map_err(|e| format!("构造类名字符串失败：{e}"))?;
    let class = env
        .call_method(
            &loader,
            "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            &[JValue::Object(&jname)],
        )
        .map_err(|e| format!("loadClass({dotted}) 失败：{}", java_detail(env, e)))?
        .l()
        .map_err(|e| format!("loadClass({dotted}) 返回空：{e}"))?;
    Ok(JClass::from(class))
}

/// 拉起（或更新）隧道运行期的常驻通知。
pub fn keepalive_start(title: &str, text: &str) -> Result<(), String> {
    with_env("keepalive_start", |env, activity| {
        let class = load_class(env, activity, SERVICE_CLASS)?;
        let jtitle = env
            .new_string(title)
            .map_err(|e| format!("构造通知标题失败：{e}"))?;
        let jtext = env
            .new_string(text)
            .map_err(|e| format!("构造通知正文失败：{e}"))?;
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
        .map_err(|e| format!("FrpcKeepAliveService.start 失败：{}", java_detail(env, e)))?;
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
        let class = load_class(env, activity, SERVICE_CLASS)?;
        env.call_static_method(
            class,
            "stop",
            "(Landroid/content/Context;)V",
            &[JValue::Object(activity)],
        )
        .map_err(|e| format!("FrpcKeepAliveService.stop 失败：{}", java_detail(env, e)))?;
        Ok(())
    })
}

/// 用系统浏览器打开链接（桌面端走 `explorer` / `open` / `xdg-open`）。
pub fn open_url(url: &str) -> Result<(), String> {
    with_env("open_url", |env, activity| {
        let class = load_class(env, activity, HOST_CLASS)?;
        let jurl = env
            .new_string(url)
            .map_err(|e| format!("构造 URL 字符串失败：{e}"))?;
        env.call_static_method(
            class,
            "openUrl",
            "(Landroid/content/Context;Ljava/lang/String;)V",
            &[JValue::Object(activity), JValue::Object(&jurl)],
        )
        .map_err(|e| format!("FrpcHost.openUrl 失败：{}", java_detail(env, e)))?;
        Ok(())
    })
}

/// 当前是否已在电池优化白名单里（true = 系统不会因省电策略杀掉本应用）。
pub fn is_ignoring_battery_optimizations() -> Result<bool, String> {
    with_env("is_ignoring_battery_optimizations", |env, activity| {
        let class = load_class(env, activity, HOST_CLASS)?;
        let result = env
            .call_static_method(
                class,
                "isIgnoringBatteryOptimizations",
                "(Landroid/content/Context;)Z",
                &[JValue::Object(activity)],
            )
            .map_err(|e| {
                format!(
                    "FrpcHost.isIgnoringBatteryOptimizations 失败：{}",
                    java_detail(env, e)
                )
            })?;
        Ok(result.z().unwrap_or(false))
    })
}

/// 引导用户把应用加入电池优化白名单。
///
/// 返回 `true` 表示本来就在白名单里（不需要再操作）；`false` 表示已跳到系统页面，
/// 是否真的加入要用户确认后重新查询才知道。
pub fn request_battery_exemption() -> Result<bool, String> {
    with_env("request_battery_exemption", |env, activity| {
        let class = load_class(env, activity, HOST_CLASS)?;
        let result = env
            .call_static_method(
                class,
                "requestIgnoreBatteryOptimizations",
                "(Landroid/content/Context;)Z",
                &[JValue::Object(activity)],
            )
            .map_err(|e| {
                format!(
                    "FrpcHost.requestIgnoreBatteryOptimizations 失败：{}",
                    java_detail(env, e)
                )
            })?;
        Ok(result.z().unwrap_or(false))
    })
}
