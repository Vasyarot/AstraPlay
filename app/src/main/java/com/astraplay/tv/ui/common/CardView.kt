package com.astraplay.tv.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.astraplay.tv.ui.theme.ThemeManager

/**
 * Универсальная TV-карточка. Режимы:
 *  [MODE_POSTER]  — постер 2:3 (фильмы, сериалы);
 *  [MODE_WIDE]    — 16:9 (продолжение просмотра, каналы);
 *  [MODE_SQUARE]  — квадрат (приложения, плейлисты).
 */
class PosterCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    companion object {
        const val MODE_POSTER = 0
        const val MODE_WIDE = 1
        const val MODE_SQUARE = 2
    }

    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    val label = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        textSize = 12f
    }
    private val badge = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.WHITE)
        setPadding(6.dp(context), 2.dp(context), 6.dp(context), 2.dp(context))
        background = roundedBg(context, Color.argb(190, 0, 0, 0), 6f)
        visibility = GONE
    }

    private var focusBorder = roundedBg(context, Color.TRANSPARENT, 10f)
    private val placeHolder = ColorDrawable(Color.argb(40, 255, 255, 255))

    /** Внешний обработчик фокуса (например, для подсказок снизу). */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        background = focusBorder

        // Скруглённые углы картинок — аккуратный современный вид
        image.clipToOutline = true
        image.background = roundedBg(context, Color.TRANSPARENT, 12f)

        val holder = FrameLayout(context).apply {
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply {
                setMargins(8.dp(context), 8.dp(context), 8.dp(context), 8.dp(context))
            })
        }
        addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 4.dp(context)
        })

        // Свой TV-стиль фокуса: подсветка + масштаб
        setOnFocusChangeListener { _, hasFocus ->
            setFocusedStyle(hasFocus)
            onFocus?.invoke(hasFocus)
        }
    }

    fun applyMode(m: Int) {
        val c = context
        when (m) {
            MODE_POSTER -> {
                layoutParams = LayoutParams(132.dp(c), 240.dp(c))
                label.gone()
            }
            MODE_WIDE -> {
                layoutParams = LayoutParams(220.dp(c), 170.dp(c))
                label.visible()
            }
            MODE_SQUARE -> {
                layoutParams = LayoutParams(120.dp(c), 160.dp(c))
                label.visible()
                image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                image.setPadding(16.dp(c), 16.dp(c), 16.dp(c), 4.dp(c))
            }
        }
    }

    fun setMargins(rightDp: Int = 12, topDp: Int = 0) {
        layoutParams = LayoutParams(layoutParams).apply {
            rightMargin = rightDp.dp(context)
            topMargin = topDp.dp(context)
        }
    }

    fun showBadge(text: String?) {
        if (text.isNullOrEmpty()) { badge.gone(); return }
        badge.text = text
        badge.visible()
    }

    fun loadPoster(url: String?) {
        if (url.isNullOrBlank()) {
            image.setImageDrawable(placeHolder)
            return
        }
        image.load(url) {
            placeholder(placeHolder)
            error(placeHolder)
            crossfade(120)
            allowRgb565(true)
        }
    }

    fun setIcon(res: Int, tint: Int) {
        image.setImageResource(res)
        image.setColorFilter(tint)
        image.setPadding(24.dp(context), 24.dp(context), 24.dp(context), 24.dp(context))
    }

    fun setFocusedStyle(focused: Boolean) {
        applyFocusScale(focused)
        val stroke = if (focused) ThemeManager.strokeColor(context) else Color.TRANSPARENT
        val fill = if (focused) withAlpha(ThemeManager.accentColor(context), 40) else Color.TRANSPARENT
        focusBorder = roundedBg(context, fill, 10f, stroke, 3f)
        background = focusBorder
        label.setTextColor(
            if (focused) ThemeManager.accentColor(context)
            else ThemeManager.colorOf(context, android.R.attr.textColorSecondary)
        )
    }
}
