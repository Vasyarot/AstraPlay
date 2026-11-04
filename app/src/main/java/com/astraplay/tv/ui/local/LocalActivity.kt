package com.astraplay.tv.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.formatDuration
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.tvButton
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Файловый менеджер: внутренняя память и USB-накопители, навигация по папкам,
 * воспроизведение видео/аудио, удаление и переименование.
 */
class LocalActivity : AppCompatActivity() {

    private lateinit var pathView: TextView
    private lateinit var list: RecyclerView
    private var currentDir: File? = null
    private var entries: List<File> = emptyList()

    /** Медиафайлы, которые умеет открывать плеер приложения. */
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv", "3gp", "wmv", "mpg", "mpeg")
    private val audioExt = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openRoots() else status()?.text = getString(R.string.no_permission)
        }

    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(this@LocalActivity), 8.dp(this@LocalActivity), 16.dp(this@LocalActivity), 12.dp(this@LocalActivity))
        }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.local_files)))
        pathView = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@LocalActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(this@LocalActivity), 0, 16.dp(this@LocalActivity), 6.dp(this@LocalActivity))
            maxLines = 1
        }
        root.addView(pathView)

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(16.dp(this@LocalActivity), 4.dp(this@LocalActivity), 16.dp(this@LocalActivity), 4.dp(this@LocalActivity))
        }
        root.addView(statusView)

        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(tvButton(this, "Вверх", R.drawable.ic_arrow_back) { goUp() })
        toolbar.addView(tvButton(this, "Память приставки") { openDir(storageRoots().firstOrNull() ?: Environment.getExternalStorageDirectory()) })
        toolbar.addView(tvButton(this, "USB / накопители") { showRootsMenu() })
        root.addView(toolbar)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LocalActivity)
            itemAnimator = null
            setPadding(4.dp(this@LocalActivity), 4.dp(this@LocalActivity), 4.dp(this@LocalActivity), 8.dp(this@LocalActivity))
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ensurePermission()
    }

    override fun onResume() {
        super.onResume()
        LastScreen.save(this, LocalActivity::class.java)
    }

    private fun status(): TextView? = statusView

    private fun ensurePermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            openRoots()
        } else {
            permLauncher.launch(perm)
        }
    }

    /** Доступные накопители: внутренняя память + USB/SD из /storage. */
    private fun storageRoots(): List<File> {
        val out = mutableListOf<File>()
        Environment.getExternalStorageDirectory()?.let { if (it.canRead()) out += it }
        runCatching {
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead() &&
                    f.name != "emulated" && !f.name.startsWith("self") && !out.any { it.absolutePath == f.absolutePath }
                ) out += f
            }
        }
        return out.ifEmpty { listOf(File("/")) }
    }

    private fun openRoots() {
        val roots = storageRoots()
        currentDir = roots.firstOrNull()
        reload()
    }

    private fun showRootsMenu() {
        val roots = storageRoots()
        if (roots.size <= 1) {
            Toast.makeText(this, "Дополнительные накопители не найдены", Toast.LENGTH_SHORT).show()
            return
        }
        Dialogs.choice(this, "Накопители", roots.map { rootLabel(it) }, 0) { idx ->
            openDir(roots[idx])
        }
    }

    private fun rootLabel(f: File): String = when {
        f.absolutePath == Environment.getExternalStorageDirectory()?.absolutePath -> "Память приставки"
        else -> f.name
    }

    private fun openDir(dir: File?) {
        if (dir == null || !dir.isDirectory || !dir.canRead()) {
            Toast.makeText(this, "Папка недоступна", Toast.LENGTH_SHORT).show()
            return
        }
        currentDir = dir
        reload()
    }

    private fun goUp() {
        val dir = currentDir ?: return
        val roots = storageRoots()
        val parent = dir.parentFile
        if (parent != null && parent.canRead() && roots.none { it.absolutePath == dir.absolutePath }) openDir(parent)
        else Toast.makeText(this, "Это корень накопителя", Toast.LENGTH_SHORT).show()
    }

    private fun reload() {
        val dir = currentDir ?: return
        pathView.text = dir.absolutePath
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                dir.listFiles()?.let { files ->
                    files.filter { !it.isHidden }
                        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.getDefault()) })
                }.orEmpty()
            }
            entries = rows
            statusView?.text = "Папок и файлов: ${rows.size}"
            list.adapter = FilesAdapter(rows, dir)
        }
    }

    // ---------- операции с файлами ----------

    private fun isPlayable(f: File): Boolean {
        val ext = f.extension.lowercase(Locale.getDefault())
        return ext in videoExt || ext in audioExt
    }

    private fun play(f: File) {
        lifecycleScope.launch {
            val key = "file_${f.absolutePath.hashCode()}"
            val saved = withContext(Dispatchers.IO) { ServiceLocator.store.position(key) }
            val entry = MediaEntry(
                kind = Kinds.LOCAL, itemId = key, title = f.nameWithoutExtension,
                subtitle = currentDir?.name, url = Uri.fromFile(f).toString(),
                positionMs = saved?.positionMs ?: 0L, durationMs = saved?.durationMs ?: 0L)
            withContext(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
            Nav.play(this@LocalActivity, entry)
        }
    }

    private fun fileMenu(f: File) {
        val options = mutableListOf<String>()
        if (isPlayable(f)) options += "Смотреть"
        options += "Переименовать"
        options += "Удалить"
        Dialogs.choice(this, f.name, options, 0) { idx ->
            var i = idx
            if (isPlayable(f)) {
                if (i == 0) { play(f); return@choice }
                i--
            }
            when {
                options[i] == "Переименовать" -> rename(f)
                else -> Dialogs.confirm(this, "Удалить?", f.name) {
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) { f.deleteRecursively() }
                        Toast.makeText(this@LocalActivity,
                            if (ok) "Удалено" else "Не удалось удалить", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                }
            }
        }
    }

    private fun rename(f: File) {
        Dialogs.input(this, "Переименовать", "Новое имя", f.name) { value ->
            val target = File(f.parentFile, value.trim())
            if (value.isBlank() || target.exists()) {
                Toast.makeText(this, "Имя пустое или занято", Toast.LENGTH_SHORT).show()
            } else {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { f.renameTo(target) }
                    Toast.makeText(this@LocalActivity,
                        if (ok) "Переименовано" else "Не удалось переименовать", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }
    }

    // ---------- список ----------

    private class FileVH(val row: TextView) : RecyclerView.ViewHolder(row)

    private inner class FilesAdapter(private val files: List<File>, private val dir: File) :
        RecyclerView.Adapter<FileVH>() {
        override fun getItemCount() = files.size + (if (dir.parentFile?.canRead() == true) 1 else 0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val row = TextView(this@LocalActivity).apply {
                textSize = 15f
                gravity = android.view.Gravity.CENTER_VERTICAL
                minHeight = 52.dp(this@LocalActivity)
                isFocusable = true
                isClickable = true
                setPadding(14.dp(this@LocalActivity), 8.dp(this@LocalActivity), 14.dp(this@LocalActivity), 8.dp(this@LocalActivity))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6.dp(this@LocalActivity) }
                fun paint(focused: Boolean) {
                    setTextColor(ThemeManager.colorOf(this@LocalActivity, android.R.attr.textColorPrimary))
                    background = roundedBg(this@LocalActivity,
                        if (focused) withAlpha(ThemeManager.accentColor(this@LocalActivity), 65) else ThemeManager.cardColor(this@LocalActivity),
                        12f, if (focused) ThemeManager.strokeColor(this@LocalActivity) else null, 2f)
                    setTypeface(null, if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
                paint(false)
                setOnFocusChangeListener { _, focused -> paint(focused) }
            }
            return FileVH(row)
        }

        override fun onBindViewHolder(holder: FileVH, position: Int) {
            val parentFirst = dir.parentFile?.canRead() == true
            if (parentFirst && position == 0) {
                holder.row.text = "  ⬅  .."
                holder.row.setOnClickListener { goUp() }
                holder.row.setOnLongClickListener(null)
                holder.row.isLongClickable = false
                return
            }
            val index = position - (if (parentFirst) 1 else 0)
            val f = files[index]
            val label = when {
                f.isDirectory -> "📁  ${f.name}"
                isPlayable(f) -> "▶  ${f.name}"
                else -> "•  ${f.name}"
            }
            val size = if (f.isFile) {
                val bytes = f.length()
                when {
                    bytes >= 1_073_741_824 -> "  •  ${"%.1f".format(Locale.getDefault(), bytes / 1_073_741_824.0)} ГБ"
                    bytes >= 1_048_576 -> "  •  ${bytes / 1_048_576} МБ"
                    bytes > 0 -> "  •  ${bytes / 1_024} КБ"
                    else -> ""
                }
            } else ""
            holder.row.text = label + size
            holder.row.setOnClickListener {
                if (f.isDirectory) openDir(f) else if (isPlayable(f)) play(f)
                else Toast.makeText(this@LocalActivity, "Плеер открывает видео и аудио", Toast.LENGTH_SHORT).show()
            }
            holder.row.setOnLongClickListener { fileMenu(f); true }
        }
    }

    /** Системный «Назад» идёт вверх по папкам, из корня — выходит. */
    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        val dir = currentDir ?: return super.onBackPressed()
        val roots = storageRoots()
        val parent = dir.parentFile
        if (parent != null && parent.canRead() && roots.none { it.absolutePath == dir.absolutePath }) goUp()
        else super.onBackPressed()
    }
}
