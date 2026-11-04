package com.astraplay.tv.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Память «где был пользователь» для слабых приставок: система убивает
 * фоновый процесс (1 ГБ RAM), и запуск с баннера лаунчера открывает
 * приложение заново с главного экрана.
 *
 * Каждый контентный экран в onResume записывает свой маршрут (класс + аргументы);
 * HomeActivity при холодном старте, если приложение свернули недавно,
 * восстанавливает записанный экран поверх себя. Плееры тоже восстанавливаются:
 * позицию видео HomeActivity подставляет из сохранённого места просмотра,
 * телеканалы открываются на живом эфире.
 */
object LastScreen {

    /** Сколько времени после сворачивания ещё имеет смысл восстанавливаться. */
    private const val TTL_MS = 15 * 60_000L

    private const val KEY_ROUTE = "last_screen_route"
    private const val KEY_PAUSED_AT = "last_screen_paused_at"

    /** Маршрут: какой экран открыть и с какими аргументами. */
    data class Route(val cls: Class<*>, val args: JSONObject) {
        fun open(context: Context) {
            val i = Intent(context, cls)
            for (key in args.keys()) {
                val v = args.opt(key) ?: continue
                if (v is java.io.Serializable) i.putExtra(key, v)
            }
            context.startActivity(i)
        }
    }

    /** Любой экран ушёл с переднего плана — фиксируем момент сворачивания. */
    fun markPaused(context: Context) {
        prefs(context).edit().putLong(KEY_PAUSED_AT, System.currentTimeMillis()).apply()
    }

    /** Экран записывает себя при входе на передний план. */
    fun save(context: Context, cls: Class<*>, args: JSONObject = JSONObject()) {
        prefs(context).edit().putString(KEY_ROUTE, JSONObject()
            .put("c", cls.name)
            .put("a", args)
            .toString()).apply()
    }

    /** Маршрут на восстановление — если записан и приложение свернули недавно. */
    fun pending(context: Context): Route? {
        val sp = prefs(context)
        val at = sp.getLong(KEY_PAUSED_AT, 0L)
        if (System.currentTimeMillis() - at > TTL_MS) return null
        val raw = sp.getString(KEY_ROUTE, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val cls = runCatching { Class.forName(o.getString("c")) }.getOrNull() ?: return null
        if (!Activity::class.java.isAssignableFrom(cls)) return null
        return Route(cls, o.optJSONObject("a") ?: JSONObject())
    }

    /** Маршрут использован или пользователь вернулся на главный экран. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ROUTE).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("last_screen", Context.MODE_PRIVATE)
}
