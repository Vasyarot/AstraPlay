package com.astraplay.tv.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import com.astraplay.tv.ui.theme.ThemeManager
import java.util.Locale

/** dp → px */
fun Int.dp(context: Context): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), context.resources.displayMetrics).toInt()

/** dp → px (обычная функция — для вызова из внутренних расширений). */
fun dpToPx(context: Context, v: Int): Int = v.dp(context)

/** Скруглённый фон (опционально с обводкой) — основной визуальный элемент темы. */
fun roundedBg(
    context: Context,
    fillColor: Int,
    radiusDp: Float = 10f,
    strokeColor: Int? = null,
    strokeDp: Float = 2f,
): GradientDrawable = GradientDrawable().apply {
    setColor(fillColor)
    val density = context.resources.displayMetrics.density
    cornerRadius = radiusDp * density
    if (strokeColor != null) {
        setStroke((strokeDp * density).toInt(), strokeColor)
    }
}

/** Цвет с альфой: withAlpha(color, alpha). */
fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** Стандартная анимация TV-фокуса: масштаб карточки. */
fun View.applyFocusScale(focused: Boolean, scale: Float = 1.08f) {
    animate().cancel()
    animate()
        .scaleX(if (focused) scale else 1f)
        .scaleY(if (focused) scale else 1f)
        .setDuration(120L)
        .start()
}

fun View.visible() { visibility = View.VISIBLE }
fun View.gone() { visibility = View.GONE }

fun TextView.applySecondary(context: Context) {
    setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorSecondary))
}

/** 4925000 → "1:22:05", 754000 → "12:34". */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatClock(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(epochMs))

/** Жирный заголовок экрана. */
fun makeTitle(context: Context, text: CharSequence): TextView =
    android.widget.TextView(context).apply {
        this.text = text
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
        setPadding(16.dp(context), 12.dp(context), 16.dp(context), 8.dp(context))
    }
