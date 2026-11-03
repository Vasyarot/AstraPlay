package com.astraplay.tv.ui.smb

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.db.SmbServer
import com.astraplay.tv.data.smb.SmbClient
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.formatDuration
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сеть (SMB): список серверов, навигация по папкам NAS/роутера,
 * воспроизведение видео прямо из шары.
 */
class SmbBrowserActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var titleView: TextView

    private var browsing: SmbServer? = null
    private var currentDir = ""
    private var browseJob: kotlinx.coroutines.Job? = null
    private data class FileRow(val name: String, val directory: Boolean, val size: Long, val modified: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        titleView = makeTitle(this, getString(R.string.network_smb))
        root.addView(titleView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp(this@SmbBrowserActivity), 0, 16.dp(this@SmbBrowserActivity), 4.dp(this@SmbBrowserActivity))
        }
        root.addView(actions)
        actions.addView(actionButton(getString(R.string.smb_add_server)) { showAddServer() })

        status = TextView(this).apply {
            textSize = 13f
            setPadding(16.dp(this@SmbBrowserActivity), 6.dp(this@SmbBrowserActivity), 16.dp(this@SmbBrowserActivity), 6.dp(this@SmbBrowserActivity))
            setTextColor(ThemeManager.colorOf(this@SmbBrowserActivity, android.R.attr.textColorSecondary))
        }
        root.addView(status)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SmbBrowserActivity)
            setHasFixedSize(true)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        showServers()
    }

    private fun actionButton(label: String, click: () -> Unit): TextView {
        val c = this
        return TextView(c).apply {
            text = label
            textSize = 14f
            isFocusable = true
            isClickable = true
            setPadding(14.dp(c), 8.dp(c), 14.dp(c), 8.dp(c))
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
            background = roundedBg(c, ThemeManager.cardColor(c), 8f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8.dp(c) }
            setOnClickListener { click() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(c,
                    if (f) withAlpha(ThemeManager.accentColor(c), 50) else ThemeManager.cardColor(c),
                    8f, if (f) ThemeManager.strokeColor(c) else null, 2f)
            }
        }
    }

    // ---------- список серверов ----------

    private fun showServers() {
        browseJob?.cancel()
        browsing = null
        titleView.text = getString(R.string.network_smb)
        lifecycleScope.launch {
            val servers = withContext(Dispatchers.IO) { ServiceLocator.store.smbServers() }
            if (servers.isEmpty()) {
                status.text = getString(R.string.smb_empty)
                list.adapter = null
            } else {
                status.text = ""
                list.adapter = ServersAdapter(servers)
            }
        }
    }

    private fun showAddServer() {
        Dialogs.input(this, getString(R.string.smb_add_server), getString(R.string.playlist_name)) { name ->
            Dialogs.input(this, getString(R.string.smb_address), "smb://192.168.1.10/Video") { base ->
                Dialogs.input(this, getString(R.string.smb_user), "", allowEmpty = true) { user ->
                    Dialogs.input(this, getString(R.string.smb_password), "", allowEmpty = true, password = true) { pass ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { ServiceLocator.store.addSmbServer(
                                name, normalizeBase(base),
                                user.ifBlank { null }, pass.ifBlank { null }) }
                            showServers()
                        }
                    }
                }
            }
        }
    }

    private fun normalizeBase(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("smb://")) s = "smb://" + s.removePrefix("//").removePrefix("smb:")
        return s.trimEnd('/')
    }

    // ---------- просмотр папок ----------

    private fun browse(server: SmbServer, dir: String) {
        browseJob?.cancel()
        browsing = server
        currentDir = dir
        status.text = getString(R.string.smb_connecting)
        list.adapter = null
        browseJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { SmbClient.list(server.url(dir)).map { (file, directory) ->
                    file.use { FileRow(it.name, directory, if (directory) 0 else it.length(), if (directory) 0 else it.lastModified()) }
                }.filter { it.directory || SmbClient.isVideo(it.name) } }
            }
            items.fold(onSuccess = { entries ->
                status.text = dir.ifEmpty { "/" }
                titleView.text = "${server.name}  •  ${getString(R.string.network_smb)}"
                list.adapter = FilesAdapter(server, dir, entries)
            }, onFailure = {
                status.text = getString(R.string.smb_error)
                Toast.makeText(this@SmbBrowserActivity,
                    getString(R.string.smb_error), Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun play(server: SmbServer, file: FileRow) {
        val url = server.url(currentDir + "/" + file.name)
        val name = file.name.substringBeforeLast('.')
        val entry = MediaEntry(
            kind = Kinds.LOCAL, itemId = "smb_" + url.hashCode(), title = name,
            subtitle = "${server.name} (SMB)", url = url)
        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
        Nav.play(this, entry)
    }

    // ---------- адаптеры ----------

    private inner class ServersAdapter(private val servers: List<SmbServer>) :
        RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH = RowVH(makeRow())
        override fun getItemCount() = servers.size
        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val s = servers[position]
            holder.title.text = s.name
            holder.subtitle.text = s.base + if (s.user.isNullOrBlank()) "  •  ${getString(R.string.off)}" else "  •  " + s.user
            holder.row.setOnClickListener { browse(s, "") }
            holder.row.setOnLongClickListener {
                Dialogs.confirm(this@SmbBrowserActivity, getString(R.string.delete),
                    getString(R.string.smb_delete_q, s.name)) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ServiceLocator.store.deleteSmbServer(s.id) }
                        showServers()
                    }
                }
                true
            }
        }
    }

    private inner class FilesAdapter(
        private val server: SmbServer,
        private val dir: String,
        private val entries: List<FileRow>,
    ) : RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH = RowVH(makeRow())
        override fun getItemCount() = entries.size + if (dir.isNotEmpty()) 1 else 0
        override fun onBindViewHolder(holder: RowVH, position: Int) {
            if (dir.isNotEmpty() && position == 0) {
                holder.title.text = getString(R.string.smb_up)
                holder.subtitle.text = ""
                holder.row.setOnClickListener { browse(server, dir.substringBeforeLast('/', "")) }
                return
            }
            val idx = position - if (dir.isNotEmpty()) 1 else 0
            val file = entries[idx]
            val isDir = file.directory
            val name = file.name.trimEnd('/')
            if (isDir) {
                holder.title.text = "📁 $name"
                holder.subtitle.text = ""
                holder.row.setOnClickListener { browse(server, "$dir/$name") }
            } else {
                holder.title.text = name
                val date = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(file.modified))
                holder.subtitle.text = String.format(Locale.US, "%.1f МБ", file.size / 1048576f) + "  •  $date"
                holder.row.setOnClickListener {
                    if (SmbClient.isVideo(name)) play(server, file)
                    else Toast.makeText(this@SmbBrowserActivity,
                        getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onBackPressed() {
        val server = browsing
        if (server == null) super.onBackPressed()
        else if (currentDir.isEmpty()) showServers()
        else browse(server, currentDir.substringBeforeLast('/', ""))
    }

    private fun makeRow(): LinearLayout {
        val c = this
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(16.dp(c), 10.dp(c), 16.dp(c), 10.dp(c))
            background = roundedBg(c, android.graphics.Color.TRANSPARENT, 10f)
            setOnFocusChangeListener { v, f ->
                (v as LinearLayout).background = roundedBg(c,
                    if (f) withAlpha(ThemeManager.accentColor(c), 40) else android.graphics.Color.TRANSPARENT,
                    10f, if (f) ThemeManager.strokeColor(c) else null, 2f)
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val title = TextView(c).apply {
            textSize = 15f
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
        }
        val subtitle = TextView(c).apply {
            textSize = 12f
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorSecondary))
        }
        row.addView(title)
        row.addView(subtitle)
        return row
    }

    private inner class RowVH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val title: TextView = row.getChildAt(0) as TextView
        val subtitle: TextView = row.getChildAt(1) as TextView
    }
}
