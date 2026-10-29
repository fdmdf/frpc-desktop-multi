package com.frpc.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import com.frpc.android.data.AppLanguage
import com.frpc.android.di.AppContainer
import com.frpc.android.util.LocaleHelper
import com.frpc.android.data.ThemeMode
import com.frpc.android.ui.nav.AppNavigator
import com.frpc.android.ui.nav.Screen
import com.frpc.android.ui.screens.AboutScreen
import com.frpc.android.ui.screens.ConfigEditScreen
import com.frpc.android.ui.screens.ConfigListScreen
import com.frpc.android.ui.screens.HomeScreen
import com.frpc.android.ui.screens.LogScreen
import com.frpc.android.ui.screens.MonitorScreen
import com.frpc.android.ui.screens.ProxyEditScreen
import com.frpc.android.ui.screens.SettingsScreen
import com.frpc.android.ui.screens.VisitorEditScreen
import com.frpc.android.ui.theme.FrpcTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            val container = (application as FrpcApplication).container
            val themeMode by container.settingsRepository.themeMode.collectAsStateWithLifecycle()
            FrpcTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    else -> isSystemInDarkTheme()
                },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FrpcApp(container)
                }
            }
        }
    }

    /**
     * 语言必须在 super.attachBaseContext 之前换掉：
     * 那之后 Activity 的 Resources 已经按旧语言加载完了，再改就只有部分字符串生效。
     */
    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) return super.attachBaseContext(newBase)
        val language = runCatching {
            (newBase.applicationContext as FrpcApplication).container.settingsRepository.language.value
        }.getOrDefault(AppLanguage.SYSTEM)
        super.attachBaseContext(LocaleHelper.apply(newBase, language))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private data class TabItem(
    val screen: Screen,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun FrpcApp(container: AppContainer) {
    val navigator = androidx.compose.runtime.remember { AppNavigator() }
    val current = navigator.current

    // 应用内语言：首次进入已由 attachBaseContext 生效，之后切换需要重建 Activity 才能换掉资源
    val activity = LocalContext.current as? Activity
    val language by container.settingsRepository.language.collectAsStateWithLifecycle()
    val initialLanguage = remember { language }
    LaunchedEffect(language) {
        if (language != initialLanguage) activity?.recreate()
    }

    val tabs = listOf(
        TabItem(Screen.Home, R.string.nav_home, Icons.Filled.Dashboard),
        TabItem(Screen.ConfigList, R.string.nav_configs, Icons.Filled.Dns),
        TabItem(Screen.Logs, R.string.nav_logs, Icons.Filled.Subject),
        TabItem(Screen.Monitor, R.string.nav_monitor, Icons.Filled.Insights),
        TabItem(Screen.Settings, R.string.nav_settings, Icons.Filled.Settings),
    )

    androidx.activity.compose.BackHandler(enabled = navigator.canPop) {
        navigator.pop()
    }

    // 兜底：只要当前不在任何编辑页，编辑草稿就必须清干净。
    // 覆盖「切底部 Tab 直接离开配置页」这类没走返回箭头的路径，
    // 否则草稿会一直存活，下次点开同一份配置看到的还是上次没保存的内容。
    LaunchedEffect(current) {
        val inEditor = current is Screen.ServerEdit ||
            current is Screen.ProxyEdit ||
            current is Screen.VisitorEdit
        if (!inEditor) container.configEditorStore.clearAll()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = when (current) {
                        is Screen.ServerEdit, is Screen.ProxyEdit, is Screen.VisitorEdit -> false
                        else -> current == tab.screen
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigator.selectTab(tab.screen) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val screen = current) {
                Screen.Home -> HomeScreen(container, navigator)
                Screen.ConfigList -> ConfigListScreen(container, navigator)
                Screen.Logs -> LogScreen(container)
                Screen.Monitor -> MonitorScreen(container)
                Screen.Settings -> SettingsScreen(container, navigator)
                is Screen.ServerEdit -> ConfigEditScreen(container, navigator, screen.profileId)
                is Screen.ProxyEdit -> ProxyEditScreen(container, navigator, screen.profileId, screen.proxyIndex)
                is Screen.VisitorEdit -> VisitorEditScreen(container, navigator, screen.profileId, screen.visitorIndex)
                Screen.About -> AboutScreen(container, navigator)
            }
        }
    }
}
