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
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.astraplay.tv.ui.theme.ThemeManager

/**
 * Единый ViewHolder для [PosterCard]. Все адаптеры, чьи ViewHolder попадают
 * в общий пул [CardPools], обязаны использовать именно его: пул не различает
 * классы ViewHolder с одинаковым viewType, и чужой класс привёл бы к крашу
 * при приведении типов.
 */
open class CardVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

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

    private lateinit var artwork: FrameLayout
    private val placeHolder = ColorDrawable(Color.argb(40, 255, 255, 255))

    /** Внешний обработчик фокуса (например, для подсказок снизу). */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        isFocusable = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        background = null

        // Скруглённые углы картинок — аккуратный современный вид
        image.clipToOutline = true
        image.background = roundedBg(context, Color.TRANSPARENT, 12f)

        val holder = FrameLayout(context).apply {
            setPadding(4.dp(context), 4.dp(context), 4.dp(context), 4.dp(context))
            background = roundedBg(context, ThemeManager.cardColor(context), 14f)
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply {
                setMargins(8.dp(context), 8.dp(context), 8.dp(context), 8.dp(context))
            })
        }
        artwork = holder
        label.setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setPadding(4.dp(context), 0, 4.dp(context), 2.dp(context))
        addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, 40.dp(context)).apply {
            topMargin = 8.dp(context)
        })

        // Фокус выделяет изображение, не перекрывая подпись.
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
                label.visible()
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
        image.colorFilter = null
        image.setPadding(0, 0, 0, 0)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        if (url.isNullOrBlank()) {
            image.setImageDrawable(placeHolder)
            return
        }
        // без crossfade: глобальный лоадер настроен на мгновенный показ —
        // анимации на слабых приставках дают рывки при прокрутке
        image.load(url) {
            placeholder(placeHolder)
            error(placeHolder)
            allowRgb565(true)
        }
    }

    fun setIcon(res: Int, tint: Int) {
        image.setImageResource(res)
        image.setColorFilter(tint)
        image.setPadding(24.dp(context), 24.dp(context), 24.dp(context), 24.dp(context))
    }

    fun setFocusedStyle(focused: Boolean) {
        // Keep the title stationary: only the artwork receives a focus ring.
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        // Drawable фокуса создаётся один раз: новый GradientDrawable на каждое
        // перемещение фокуса давало рывки при быстрой навигации по сетке
        artwork.background = if (focused) cachedFocusedBg else cachedIdleBg
        label.setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
        label.setTextColor(
            if (focused) ThemeManager.accentColor(context)
            else ThemeManager.colorOf(context, android.R.attr.textColorSecondary)
        )
    }

    private val cachedIdleBg by lazy { roundedBg(context, ThemeManager.cardColor(context), 14f) }
    private val cachedFocusedBg by lazy {
        roundedBg(context, withAlpha(ThemeManager.accentColor(context), 40), 14f, ThemeManager.strokeColor(context), 2f)
    }
}
