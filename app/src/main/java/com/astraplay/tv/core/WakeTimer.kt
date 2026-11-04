package com.astraplay.tv.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.astraplay.tv.tv.WakeReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Таймер включения: в назначенное время [WakeReceiver] запускает телеканал.
 * Работает через AlarmManager — приложение может быть закрыто, сработает всё
 * равно, если приставка включена. Режимы: ежедневно в HH:MM и одноразово
 * «через N минут» ([[Prefs.wakeMode]]: 0 — выкл, 1 — ежедневно, 2 — одноразово).
 */
object WakeTimer {

    const val ACTION = "com.astraplay.tv.WAKE_ALARM"
    private const val REQUEST_CODE = 1001

    /** Ставит (переставляет) будильник согласно текущим настройкам. */
    fun schedule(context: Context) {
        val prefs = ServiceLocator.prefs
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context)
        am.cancel(pi)
        val at = when (prefs.wakeMode) {
            1 -> nextDailyAt(prefs.wakeHour, prefs.wakeMinute, System.currentTimeMillis())
            2 -> prefs.wakeOnceAt
            else -> return
        }
        if (at <= System.currentTimeMillis()) return
        // setAlarmClock — самый сильный класс будильника: система не сдвигает его
        // в doze и энергосбережении, а прошивка приставок считает его «будильником
        // пользователя» и чаще выводит устройство из спящего режима, чем обычные
        // точные будильники. Разрешений на Android 12+ не требует.
        val show = PendingIntent.getActivity(
            context, REQUEST_CODE + 1,
            // что показать по тапу на значок будильника в системе — сам приложение
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context))
    }

    /** Ближайший момент «ежедневно в [hour]:[minute]»: сегодня или завтра. */
    fun nextDailyAt(hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Подпись для строки настроек. */
    fun describe(prefs: Prefs): String = when (prefs.wakeMode) {
        1 -> "ежедневно в %02d:%02d".format(prefs.wakeHour, prefs.wakeMinute)
        2 -> if (prefs.wakeOnceAt > System.currentTimeMillis())
            "в %s".format(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prefs.wakeOnceAt)))
        else "выкл"
        else -> "выкл"
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, WakeReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
