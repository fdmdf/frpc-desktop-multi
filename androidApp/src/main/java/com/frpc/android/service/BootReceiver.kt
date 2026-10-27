package com.frpc.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.frpc.android.FrpcApplication
import com.frpc.android.R

/**
 * 开机自启：BOOT_COMPLETED / 更新后重启。
 * Android 12+ 对后台启动 FGS 有限制，失败时降级为事件通知提示用户手动打开。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? FrpcApplication ?: return
        val container = app.container
        if (!container.settingsRepository.autoStartOnBoot.value) return
        val profileId = container.settingsRepository.defaultProfileId.value ?: return
        val profile = container.configRepository.find(profileId) ?: return
        try {
            context.startForegroundService(FrpcService.startIntent(context, profileId))
        } catch (e: Exception) {
            Notifications.postEvent(context, context.getString(R.string.notification_boot_failed))
        }
    }
}
