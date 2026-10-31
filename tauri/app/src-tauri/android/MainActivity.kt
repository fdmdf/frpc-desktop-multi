package io.github.fdmdf.frpc

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
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
  }
}
