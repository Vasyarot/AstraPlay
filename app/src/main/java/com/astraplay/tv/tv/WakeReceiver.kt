package com.astraplay.tv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.core.WakeTimer
import com.astraplay.tv.player.LivePlayerActivity

/**
 * Срабатывание таймера включения: открывает телеканал (выбранный или самый
 * первый из первого плейлиста). На Android 9 запуск активити из будильника
 * разрешён; режим «ежедневно» сразу перезапланируется на завтра.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WakeTimer.ACTION) return
        ServiceLocator.init(context)
        val prefs = ServiceLocator.prefs
        val mode = prefs.wakeMode
        if (mode == 0) return
        if (mode == 1) {
            WakeTimer.schedule(context) // завтра в то же время
        } else {
            // одноразовый сработал — выключаем
            prefs.wakeMode = 0
            prefs.wakeOnceAt = 0L
        }
        val channel = resolveChannel(prefs) ?: return
        // будим экран: из спящего режима активити может стартовать при погашенном
        // экране — пользователь решит, что таймер не сработал
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        @Suppress("DEPRECATION") // на Android 7–9 это единственный способ зажечь экран из фона
        val wake = pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
            "AstraPlay:WakeTimer")
        wake.acquire(90_000L)
        context.startActivity(Intent(context, LivePlayerActivity::class.java)
            .putExtra("url", channel.first)
            .putExtra("title", channel.second)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Выбранный канал или самый первый из первого плейлиста. */
    private fun resolveChannel(prefs: com.astraplay.tv.core.Prefs): Pair<String, String>? {
        if (prefs.wakeChannelUrl.isNotBlank()) {
            return prefs.wakeChannelUrl to prefs.wakeChannelTitle.ifBlank { "IPTV" }
        }
        return runCatching {
            val store = ServiceLocator.store
            val playlist = store.playlists().firstOrNull() ?: return null
            val ch = store.channels(playlist.id).firstOrNull() ?: return null
            ch.url to ch.name.ifBlank { "IPTV" }
        }.getOrNull()
    }
}
