package com.astraplay.tv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.ui.home.HomeActivity

/** Автозапуск при включении ТВ (опция в настройках). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        ServiceLocator.init(context)
        if (!ServiceLocator.prefs.autostart) return
        context.startActivity(Intent(context, HomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
