package com.astraplay.tv.ui.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import com.astraplay.tv.BuildConfig
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Настройки: темы, постеры, плеер, ключ TMDB, автозапуск,
 * бэкап/восстановление (локальный JSON через SAF), кэш.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val prefs get() = ServiceLocator.prefs
    private val store get() = ServiceLocator.store

    private val createBackup =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeBackup(uri)
        }
    private val openBackup =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readBackup(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp(this@SettingsActivity), 0, 24.dp(this@SettingsActivity))
        }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.settings)))

        group(getString(R.string.appearance))
        row(getString(R.string.theme), ThemeManager.current().title) { showThemePicker() }
        row(getString(R.string.poster_size),
            if (prefs.posterSize == 1) getString(R.string.poster_large) else getString(R.string.poster_normal)
        ) {
            com.astraplay.tv.ui.common.Dialogs.choice(this,
                getString(R.string.poster_size),
                listOf(getString(R.string.poster_normal), getString(R.string.poster_large)),
                prefs.posterSize) { idx ->
                prefs.posterSize = idx
                recreate()
            }
        }

        group(getString(R.string.player_group))
        row(getString(R.string.buffer), "${prefs.hlsBufferSecs}") {
            com.astraplay.tv.ui.common.Dialogs.choice(this, getString(R.string.buffer),
                listOf("10", "15", "20", "30", "60"), selIdx(prefs.hlsBufferSecs, listOf(10, 15, 20, 30, 60))) { idx ->
                prefs.hlsBufferSecs = listOf(10, 15, 20, 30, 60)[idx]
                recreate()
            }
        }
        row(getString(R.string.seek_step), "${prefs.seekStepSecs}") {
            com.astraplay.tv.ui.common.Dialogs.choice(this, getString(R.string.seek_step),
                listOf("5", "10", "30", "60"), selIdx(prefs.seekStepSecs, listOf(5, 10, 30, 60))) { idx ->
                prefs.seekStepSecs = listOf(5, 10, 30, 60)[idx]
                recreate()
            }
        }
        toggleRow(getString(R.string.autoplay_next), prefs.autoplayNext) { prefs.autoplayNext = it }

        group(getString(R.string.system_group))
        toggleRow(getString(R.string.autostart), prefs.autostart) { prefs.autostart = it }
        row(getString(R.string.tmdb_key), if (prefs.tmdbKey.isBlank()) "—" else "••••") {
            com.astraplay.tv.ui.common.Dialogs.input(this, getString(R.string.tmdb_key),
                getString(R.string.tmdb_key_desc), prefs.tmdbKey) { v ->
                prefs.tmdbKey = v
                recreate()
            }
        }

        group(getString(R.string.data_group))
        row(getString(R.string.backup), "") { createBackup.launch("astraplay-backup.json") }
        row(getString(R.string.restore), "") {
            openBackup.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        }
        row(getString(R.string.clear_cache), "") {
            lifecycleScope.launch(Dispatchers.IO) {
                File(cacheDir, "image_cache").deleteRecursively()
            }
            Toast.makeText(this, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
        }

        group(getString(R.string.about))
        row(getString(R.string.version), BuildConfig.VERSION_NAME) { }
        note(getString(R.string.all_free_note))
    }

    private fun selIdx(value: Int, options: List<Int>): Int = options.indexOf(value).coerceAtLeast(0)

    // ---------- элементы ----------

    private fun group(title: String) {
        root.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.accentColor(this@SettingsActivity))
            setPadding(16.dp(this@SettingsActivity), 18.dp(this@SettingsActivity), 16.dp(this@SettingsActivity), 6.dp(this@SettingsActivity))
        })
    }

    private fun row(label: String, value: String, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            text = if (value.isEmpty()) label else "$label:  $value"
            textSize = 15f
            isFocusable = true
            isClickable = true
            setPadding(16.dp(this@SettingsActivity), 12.dp(this@SettingsActivity), 16.dp(this@SettingsActivity), 12.dp(this@SettingsActivity))
            setTextColor(ThemeManager.colorOf(this@SettingsActivity, android.R.attr.textColorPrimary))
            background = roundedBg(this@SettingsActivity, ThemeManager.cardColor(this@SettingsActivity), 8f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16.dp(this@SettingsActivity), 4.dp(this@SettingsActivity), 16.dp(this@SettingsActivity), 4.dp(this@SettingsActivity)) }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(this@SettingsActivity,
                    if (f) withAlpha(ThemeManager.accentColor(this@SettingsActivity), 45)
                    else ThemeManager.cardColor(this@SettingsActivity),
                    8f, if (f) ThemeManager.strokeColor(this@SettingsActivity) else null, 2f)
            }
        }
        root.addView(tv)
    }

    private fun toggleRow(label: String, current: Boolean, onChange: (Boolean) -> Unit) {
        row("$label   [${if (current) "✓" else "—"}]", "") {
            onChange(!current)
            recreate()
        }
    }

    private fun note(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SettingsActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(this@SettingsActivity), 8.dp(this@SettingsActivity), 16.dp(this@SettingsActivity), 0)
        })
    }

    // ---------- выбор темы ----------

    private fun showThemePicker() {
        val themes = ThemeManager.all()
        var dlg: AlertDialog? = null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(this@SettingsActivity, ThemeManager.panelColor(this@SettingsActivity), 14f,
                ThemeManager.strokeColor(this@SettingsActivity), 1f)
            setPadding(24.dp(this@SettingsActivity), 20.dp(this@SettingsActivity), 24.dp(this@SettingsActivity), 16.dp(this@SettingsActivity))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.select_theme)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.colorOf(this@SettingsActivity, android.R.attr.textColorPrimary))
        })

        val grid = GridLayout(this).apply {
            columnCount = 4
            rowCount = 3
        }
        themes.forEach { t ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(10.dp(this@SettingsActivity), 10.dp(this@SettingsActivity), 10.dp(this@SettingsActivity), 10.dp(this@SettingsActivity))
                setOnClickListener {
                    ThemeManager.select(t.id)
                    dlg?.dismiss()
                    recreate()
                }
                addView(android.view.View(this@SettingsActivity).apply {
                    background = roundedBg(this@SettingsActivity,
                        androidx.core.content.ContextCompat.getColor(this@SettingsActivity, t.swatchRes), 22f,
                        if (t.id == prefs.themeId) Color.WHITE else null, 3f)
                    layoutParams = LinearLayout.LayoutParams(44.dp(this@SettingsActivity), 44.dp(this@SettingsActivity))
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = t.title
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ThemeManager.colorOf(this@SettingsActivity, android.R.attr.textColorPrimary))
                    setPadding(0, 6.dp(this@SettingsActivity), 0, 0)
                })
            }
            grid.addView(item, GridLayout.LayoutParams().apply {
                width = 96.dp(this@SettingsActivity)
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                setMargins(6.dp(this@SettingsActivity), 6.dp(this@SettingsActivity), 6.dp(this@SettingsActivity), 6.dp(this@SettingsActivity))
            })
        }
        box.addView(grid)

        val dialog = AlertDialog.Builder(this).create().apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setView(box)
        }
        dlg = dialog
        dialog.show()
    }

    // ---------- бэкап ----------

    private fun writeBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(store.exportJson().toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
            }.fold(
                onSuccess = {
                    runOnUiThread { Toast.makeText(this@SettingsActivity, getString(R.string.backup_done), Toast.LENGTH_SHORT).show() }
                },
                onFailure = {
                    runOnUiThread { Toast.makeText(this@SettingsActivity, getString(R.string.backup_failed), Toast.LENGTH_SHORT).show() }
                }
            )
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val json = contentResolver.openInputStream(uri)?.use { ins ->
                    ins.readBytes().toString(Charsets.UTF_8)
                } ?: error("no stream")
                store.importJson(json)
            }.fold(
                onSuccess = {
                    runOnUiThread { Toast.makeText(this@SettingsActivity, getString(R.string.restore_done), Toast.LENGTH_SHORT).show() }
                },
                onFailure = {
                    runOnUiThread { Toast.makeText(this@SettingsActivity, getString(R.string.restore_failed), Toast.LENGTH_SHORT).show() }
                }
            )
        }
    }
}
