package com.astraplay.tv.ui.common

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.astraplay.tv.R
import com.astraplay.tv.ui.theme.ThemeManager

/** Простые TV-диалоги в стиле темы. */
object Dialogs {

    private fun newDialog(context: Context, box: LinearLayout): AlertDialog =
        AlertDialog.Builder(context).create().apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setView(box)
        }

    /** Контейнер диалога в стиле темы. */
    private fun content(context: Context, title: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(context, ThemeManager.panelColor(context), 14f,
                ThemeManager.strokeColor(context), 1f)
            setPadding(24.dp(context), 20.dp(context), 24.dp(context), 16.dp(context))
            addView(TextView(context).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp(context) })
        }

    private fun button(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            isFocusable = true
            isClickable = true
            setPadding(16.dp(context), 10.dp(context), 16.dp(context), 10.dp(context))
            setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
            background = roundedBg(context, Color.TRANSPARENT, 8f)
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(
                    context,
                    if (f) withAlpha(ThemeManager.accentColor(context), 60) else Color.TRANSPARENT,
                    8f,
                    if (f) ThemeManager.strokeColor(context) else null,
                    2f,
                )
            }
            setOnClickListener { onClick() }
        }

    private fun buttonsRow(context: Context, onCancel: () -> Unit, okLabel: String, onOk: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button(context, context.getString(android.R.string.cancel)) { onCancel() })
            addView(button(context, okLabel) { onOk() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12.dp(context) })
        }

    fun confirm(context: Context, title: String, message: String, onOk: () -> Unit) {
        val box = content(context, title)
        box.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorSecondary))
        })
        val dlg = newDialog(context, box)
        box.addView(buttonsRow(context, { dlg.dismiss() }, context.getString(android.R.string.ok)) {
            dlg.dismiss(); onOk()
        })
        dlg.show()
    }

    fun input(context: Context, title: String, hint: String, preset: String = "", allowEmpty: Boolean = false, password: Boolean = false, onOk: (String) -> Unit) {
        val box = content(context, title)
        val edit = EditText(context).apply {
            setText(preset)
            setHint(hint)
            textSize = 15f
            setSingleLine(true)
            if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
            setHintTextColor(ThemeManager.colorOf(context, android.R.attr.textColorSecondary))
            background = roundedBg(context, withAlpha(Color.WHITE, 18), 8f)
            setPadding(12.dp(context), 10.dp(context), 12.dp(context), 10.dp(context))
        }
        box.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dlg = newDialog(context, box)
        box.addView(buttonsRow(context, { dlg.dismiss() }, context.getString(R.string.apply)) {
            dlg.dismiss()
            val v = edit.text.toString().trim()
            if (allowEmpty || v.isNotEmpty()) onOk(v)
        })
        dlg.show()
        edit.requestFocus()
    }

    fun choice(
        context: Context,
        title: String,
        items: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val box = content(context, title)
        val dlg = newDialog(context, box)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, s ->
            val rb = RadioButton(context).apply {
                text = s
                textSize = 15f
                isChecked = i == selectedIndex
                isFocusable = true
                isClickable = true
                setPadding(8.dp(context), 10.dp(context), 8.dp(context), 10.dp(context))
                setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
                setOnClickListener {
                    dlg.dismiss(); onSelect(i)
                }
            }
            list.addView(rb)
        }
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(scroll)
        box.addView(button(context, context.getString(android.R.string.cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END; topMargin = 8.dp(context) })
        dlg.show()
    }
}
