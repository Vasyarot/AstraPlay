package com.astraplay.tv.ui.torrent

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.*
import com.astraplay.tv.data.sources.OnlineSources
import com.astraplay.tv.data.torrent.TorrServerClient
import com.astraplay.tv.ui.common.*
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.*

/** TorrServer handles the torrent; AstraPlay selects a file and plays its HTTP stream. */
class TorrentPlayerActivity : AppCompatActivity() {
    private val settings by lazy { getSharedPreferences("torrent_streaming", MODE_PRIVATE) }
    private lateinit var address: EditText
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var files: LinearLayout
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp(this@TorrentPlayerActivity), 12.dp(this@TorrentPlayerActivity), 20.dp(this@TorrentPlayerActivity), 20.dp(this@TorrentPlayerActivity)) }
        column.addView(makeTitle(this, "Смотреть торрент"))
        column.addView(TextView(this).apply {
            text = "Для просмотра нужен запущенный TorrServer на приставке или в вашей сети. Укажите его адрес и magnet-ссылку либо ссылку на .torrent."
            setTextColor(ThemeManager.colorOf(this@TorrentPlayerActivity, android.R.attr.textColorSecondary))
        })
        address = EditText(this).apply { setSingleLine(); hint = "Адрес TorrServer"; setText(settings.getString("server", "http://127.0.0.1:8090")) }
        link = EditText(this).apply { setSingleLine(); hint = "magnet:?xt=… или https://…/movie.torrent"; setText(intent.getStringExtra("link") ?: intent.dataString.orEmpty()) }
        column.addView(address)
        column.addView(link)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(tvButton(this, "Получить файлы") { load() })
        actions.addView(tvButton(this, "Отменить") { loadJob?.cancel(); status.text = "Отменено" })
        actions.addView(tvButton(this, "Открыть внешним приложением") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link.text.toString()))) }
                .onFailure { status.text = "Приложение для торрентов не установлено" }
        })
        column.addView(actions)
        status = TextView(this).apply { setPadding(0, 12.dp(this@TorrentPlayerActivity), 0, 12.dp(this@TorrentPlayerActivity)) }
        column.addView(status)
        files = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(files)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun load() {
        val server = address.text.toString().trim()
        val torrent = link.text.toString().trim()
        if (!OnlineSources.validWebUrl(server) || !TorrServerClient.validLink(torrent)) { status.text = "Проверьте адрес TorrServer и ссылку на торрент"; return }
        settings.edit().putString("server", server).apply()
        loadJob?.cancel()
        files.removeAllViews()
        status.text = "Получаем список файлов…"
        loadJob = lifecycleScope.launch {
            try {
                val client = TorrServerClient(server, ServiceLocator.http.client)
                val (hash, rows) = withTimeout(45000) { client.videos(torrent) }
                status.text = "Выберите файл: ${rows.size}"
                for (file in rows) files.addView(tvButton(this@TorrentPlayerActivity, file.name.substringAfterLast('/') + " • ${file.size / 1048576} МБ") {
                    Nav.play(this@TorrentPlayerActivity, MediaEntry(Kinds.TORRENT, "torrent_${hash}_${file.index}", file.name.substringAfterLast('/'), url = client.stream(hash, file)))
                })
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) status.text = "TorrServer не ответил вовремя или нет раздающих" else throw error
            } catch (error: Exception) {
                status.text = "Не удалось подключиться: ${error.message}. Убедитесь, что TorrServer запущен."
            }
        }
    }
}
