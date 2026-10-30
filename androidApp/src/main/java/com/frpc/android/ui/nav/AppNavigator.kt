package com.frpc.android.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Screen {
    data object Home : Screen
    data object ConfigList : Screen
    data object Logs : Screen
    data object Monitor : Screen
    data object Settings : Screen
    data object About : Screen

    data class ServerEdit(val profileId: String?) : Screen
    data class ProxyEdit(val profileId: String, val proxyIndex: Int) : Screen
    data class VisitorEdit(val profileId: String, val visitorIndex: Int) : Screen
}

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

    fun selectTab(screen: Screen) {
        if (current == screen && backStack.size == 1) return
        backStack.clear()
        backStack.add(screen)
    }

    fun popTo(screen: Screen) {
        while (backStack.size > 1 && backStack.last() != screen) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}
