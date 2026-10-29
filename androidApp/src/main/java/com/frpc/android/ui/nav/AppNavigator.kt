package com.frpc.android.ui.nav

import androidx.compose.runtime.mutableStateListOf

/** 应用内屏幕（手工导航：无 Navigation-Compose 依赖，包体更小） */
sealed interface Screen {
    data object Home : Screen
    data object ConfigList : Screen
    data object Logs : Screen
    data object Monitor : Screen
    data object Settings : Screen
    /** 关于：不是底部 tab，从设置页进入 */
    data object About : Screen

    /** profileId = null 表示新建 */
    data class ServerEdit(val profileId: String?) : Screen
    data class ProxyEdit(val profileId: String, val proxyIndex: Int) : Screen
    data class VisitorEdit(val profileId: String, val visitorIndex: Int) : Screen
}

/** 简易返回栈导航器（进程内单例，随 Activity 重建自动恢复当前栈） */
class AppNavigator {
    private val backStack = mutableStateListOf<Screen>(Screen.Home)

    val current: Screen get() = backStack.last()
    val canPop: Boolean get() = backStack.size > 1

    fun push(screen: Screen) {
        backStack.add(screen)
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** 切换到顶层 Tab（重置栈，避免返回时穿堂） */
    fun selectTab(screen: Screen) {
        if (current == screen && backStack.size == 1) return
        backStack.clear()
        backStack.add(screen)
    }

    /** 回到首页（如保存成功后） */
    fun popTo(screen: Screen) {
        while (backStack.size > 1 && backStack.last() != screen) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}
