package com.astraplay.tv.ui.common

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.astraplay.tv.ui.theme.ThemeManager

fun tvButton(context: Context, title: String, icon: Int = 0, action: () -> Unit): TextView =
    TextView(context).apply {
        text = title
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = 48.dp(context)
        isFocusable = true
        isClickable = true
        setPadding(14.dp(context), 10.dp(context), 14.dp(context), 10.dp(context))
        fun paint(focused: Boolean) {
            setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
            background = roundedBg(context,
                if (focused) withAlpha(ThemeManager.accentColor(context), 65) else ThemeManager.cardColor(context),
                12f, if (focused) ThemeManager.strokeColor(context) else null, 2f)
            setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
        }
        if (icon != 0) {
            val drawable = ContextCompat.getDrawable(context, icon)?.mutate()
            drawable?.setTint(ThemeManager.accentColor(context))
            drawable?.setBounds(0, 0, 20.dp(context), 20.dp(context))
            setCompoundDrawables(drawable, null, null, null)
            compoundDrawablePadding = 10.dp(context)
        }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { action() }
    }
