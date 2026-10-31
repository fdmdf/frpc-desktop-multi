package io.github.fdmdf.frpc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 覆盖 `tauri android init` 生成的 MainActivity（gen/ 不入库，由
 * scripts/tauri-android-setup.sh 复制本文件）。
 *
 * 为什么要覆盖：模板里只有 `enableEdgeToEdge()`，从 Android 15 起系统强制
 * edge-to-edge，WebView 会一直画到状态栏和手势条底下 —— 表现为顶部标题被状态栏
 * 压住、底部导航被手势条盖住。WebView 对 `env(safe-area-inset-*)` 的支持取决于
 * 系统 WebView 版本（多数设备恒为 0），不能只靠 CSS。这里按系统栏 / 刘海的高度给
 * 内容视图补内边距，等价于 iOS 的 safe-area，且与 CSS 无关、跨版本一致。
 */
class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    val content = findViewById<ViewGroup>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      )
      view.updatePadding(
        left = bars.left,
        top = bars.top,
        right = bars.right,
        bottom = bars.bottom,
      )
      insets
    }
    // 视图已 attach 时监听器不会自动收到一次 insets 分发，主动请求一次
    ViewCompat.requestApplyInsets(content)

    requestNotificationPermission()
  }

  /**
   * Android 13+ 的通知权限是运行时权限：不申请的话前台服务的常驻通知会被系统静默丢弃，
   * 服务本身还在跑但用户看不到 —— 后台保活就成了「看不见的保活」，ROM 照样清。
   * 只申请一次（用户拒绝也不再纠缠），拒绝时隧道仍可用，只是没有常驻通知。
   */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
      this,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      ActivityCompat.requestPermissions(
        this,
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        NOTIFICATION_REQUEST_CODE,
      )
    }
  }

  private companion object {
    const val NOTIFICATION_REQUEST_CODE = 0x9001
  }
}
