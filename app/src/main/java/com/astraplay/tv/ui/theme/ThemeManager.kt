package com.astraplay.tv.ui.theme

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator

/**
 * 12 тем оформления. Активити обязана вызвать [apply] ДО super.onCreate,
 * т.к. смена темы меняет ресурсы окна.
 */
object ThemeManager {

    data class ThemeInfo(
        val id: Int,
        @StyleRes val styleRes: Int,
        val title: String,
        @ColorRes val swatchRes: Int, // цвет превью в настройках
    )

    private val themes = listOf(
        ThemeInfo(0, R.style.Theme_AstraPlay_Astral, "Astral", R.color.astral_accent),
        ThemeInfo(1, R.style.Theme_AstraPlay_Oled, "Midnight OLED", R.color.oled_accent),
        ThemeInfo(2, R.style.Theme_AstraPlay_Emerald, "Emerald", R.color.emerald_accent),
        ThemeInfo(3, R.style.Theme_AstraPlay_Crimson, "Crimson", R.color.crimson_accent),
        ThemeInfo(4, R.style.Theme_AstraPlay_Amber, "Amber", R.color.amber_accent),
        ThemeInfo(5, R.style.Theme_AstraPlay_Ice, "Ice", R.color.ice_accent),
        ThemeInfo(6, R.style.Theme_AstraPlay_Violet, "Violet", R.color.violet_accent),
        ThemeInfo(7, R.style.Theme_AstraPlay_Teal, "Teal", R.color.teal_accent),
        ThemeInfo(8, R.style.Theme_AstraPlay_Rose, "Rose", R.color.rose_accent),
        ThemeInfo(9, R.style.Theme_AstraPlay_Graphite, "Graphite", R.color.graphite_accent),
        ThemeInfo(10, R.style.Theme_AstraPlay_Forest, "Forest", R.color.forest_accent),
        ThemeInfo(11, R.style.Theme_AstraPlay_Coffee, "Coffee", R.color.coffee_accent),
    )

    fun all(): List<ThemeInfo> = themes

    fun current(): ThemeInfo = themes.getOrElse(ServiceLocator.prefs.themeId) { themes[0] }

    /** Вызвать в Activity.onCreate до super.onCreate(). */
    fun apply(activity: Activity) = activity.setTheme(current().styleRes)

    fun select(id: Int) {
        ServiceLocator.prefs.themeId = id
    }

    // ---- программный доступ к цветам темы ----

    fun colorOf(context: Context, @AttrRes attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    fun panelColor(context: Context) = colorOf(context, R.attr.panelBg)
    fun cardColor(context: Context) = colorOf(context, R.attr.cardBg)
    fun strokeColor(context: Context) = colorOf(context, R.attr.focusStroke)
    fun accentColor(context: Context) = colorOf(context, androidx.appcompat.R.attr.colorPrimary)
}
