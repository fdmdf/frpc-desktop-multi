package io.github.fdmdf.frpc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * Rust 侧通过 JNI 调用的宿主能力：打开外部链接、申请电池优化白名单。
 *
 * 为什么要 Kotlin 中转：`startActivity` 需要 Intent/Uri/PendingIntent 这些
 * Java 对象，从 Rust 里拼 JNI 调用又长又容易写错（每个构造器签名手写）。
 * 这里用 `@JvmStatic` 暴露成 `(Landroid/content/Context;...)` 的静态方法，
 * Rust 侧只需要 `call_static_method` 一行。
 */
object FrpcHost {

    private const val TAG = "FrpcHost"

    /** 用系统浏览器打开链接（桌面端对应 `open_url`）。 */
    @JvmStatic
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for $url")
            toast(context, "没有可打开该链接的应用")
        } catch (e: Exception) {
            Log.w(TAG, "openUrl failed: ${e.message}")
        }
    }

    /** 是否已在电池优化白名单里（true 表示系统不会因为这个应用的省电策略杀掉它）。 */
    @JvmStatic
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 拉起系统的「不限制电量」确认框。
     *
     * 返回值：true = 本来就在白名单（无需操作）；false = 已跳转到系统页面，等用户确认。
     * 直接跳总设置页在国产 ROM 上经常找不到应用，所以先试带包名的直连弹窗。
     */
    @JvmStatic
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (isIgnoringBatteryOptimizations(context)) return true
        val pkgUri = Uri.parse("package:${context.packageName}")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startQuietly(context, direct)) return false
        // 部分 ROM 没有这个入口，退回列表页
        if (startQuietly(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            return false
        }
        toast(context, "无法打开电池优化设置，请在系统设置里手动允许后台运行")
        return false
    }

    private fun startQuietly(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startActivity ${intent.action} failed: ${e.message}")
        false
    }

    private fun toast(context: Context, text: String) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast failed: ${e.message}")
        }
    }

    /** Build 版本，供 Rust 侧日志用（JNI 返回值比读系统属性可靠）。 */
    @JvmStatic
    fun sdkInt(): Int = Build.VERSION.SDK_INT
}
