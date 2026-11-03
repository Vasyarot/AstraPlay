package com.astraplay.tv.ui.iptv

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.iptv.Programme
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Полноэкранная сетка телепрограммы: каналы слева, таймлайн 6 часов справа.
 * Синхронная горизонтальная прокрутка всех строк, клик по передаче — смотреть канал.
 */
class EpgGridActivity : AppCompatActivity() {

    companion object {
        const val WINDOW_MIN = 360          // 6 часов
        const val PX_PER_MIN = 5f           // ширина минуты, dp
        const val LABEL_W = 160             // ширина колонки каналов, dp
        const val MAX_ROWS = 80
    }

    private val hsvList = mutableListOf<HorizontalScrollView>()
    private var syncing = false
    private var windowStart = 0L
    private var channels: List<com.astraplay.tv.data.iptv.Channel> = emptyList()
    private var playlistId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        playlistId = intent.getLongExtra("playlistId", -1L)

        lifecycleScope.launch {
            val epgOk = ServiceLocator.epg.isLoaded
            if (!epgOk) {
                Toast.makeText(this@EpgGridActivity,
                    getString(R.string.epg_grid_empty), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            channels = withContext(Dispatchers.IO) { ServiceLocator.store.channels(playlistId) }
            if (channels.isEmpty()) { finish(); return@launch }
            buildGrid()
        }
    }

    private fun buildGrid() {
        windowStart = System.currentTimeMillis() / (30 * 60_000) * (30 * 60_000) - 30 * 60_000
        val windowEnd = windowStart + WINDOW_MIN * 60_000L
        val pxPerMin = PX_PER_MIN * resources.displayMetrics.density

        val vScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- линейка времени ----
        val rulerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rulerRow.addView(spacer())
        val rulerInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var t = windowStart
        while (t < windowEnd) {
            val halfHour = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
            rulerInner.addView(TextView(this).apply {
                text = halfHour
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ThemeManager.accentColor(this@EpgGridActivity))
                gravity = Gravity.START
                setPadding(6.dp(this@EpgGridActivity), 4.dp(this@EpgGridActivity), 0, 4.dp(this@EpgGridActivity))
            }, LinearLayout.LayoutParams((30 * pxPerMin).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            t += 30 * 60_000L
        }
        val rulerHsv = makeSyncedHsv()
        rulerHsv.addView(rulerInner)
        rulerRow.addView(rulerHsv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(rulerRow)

        // ---- строки каналов ----
        var shown = 0
        for (ch in channels) {
            if (shown >= MAX_ROWS) break
            val programs = ServiceLocator.epg.forChannel(ch.epgId, ch.name, windowStart, windowEnd)
            if (programs.isEmpty()) continue

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(channelLabel(ch))

            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fillProgramBlocks(inner, programs, ch, pxPerMin)

            val hsv = makeSyncedHsv()
            hsv.addView(inner, FrameLayout.LayoutParams(
                (WINDOW_MIN * pxPerMin).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(hsv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val rowWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rowWrap.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(rowWrap, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            shown++
        }

        vScroll.addView(column)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(makeTitle(this,
            getString(R.string.epg_grid) + "  •  " + getString(R.string.epg_now)))
        root.addView(vScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun fillProgramBlocks(
        container: LinearLayout,
        programs: List<Programme>,
        ch: com.astraplay.tv.data.iptv.Channel,
        pxPerMin: Float,
    ) {
        var cursor = windowStart
        val now = System.currentTimeMillis()
        for (p in programs) {
            val start = maxOf(p.startMs, cursor)
            val stop = minOf(p.stopMs, windowStart + WINDOW_MIN * 60_000L)
            if (stop <= start) continue
            if (start > cursor) container.addView(View(this), LinearLayout.LayoutParams(
                (((start - cursor) / 60_000f) * pxPerMin).toInt(), 1))
            val width = (((stop - start) / 60_000f) * pxPerMin).toInt().coerceAtLeast(1)
            cursor = stop
            val isNow = now in p.startMs until p.stopMs
            container.addView(TextView(this).apply {
                isFocusable = true
                isClickable = true
                text = p.title
                textSize = 12f
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ThemeManager.colorOf(this@EpgGridActivity, android.R.attr.textColorPrimary))
                background = roundedBg(this@EpgGridActivity,
                    if (isNow) withAlpha(ThemeManager.accentColor(this@EpgGridActivity), 60)
                    else ThemeManager.cardColor(this@EpgGridActivity), 8f,
                    if (isNow) ThemeManager.accentColor(this@EpgGridActivity) else null, 2f)
                setPadding(8.dp(this@EpgGridActivity), 0, 8.dp(this@EpgGridActivity), 0)
                layoutParams = LinearLayout.LayoutParams(width, 64.dp(this@EpgGridActivity)).apply {
                    topMargin = 2.dp(this@EpgGridActivity)
                }
                setOnClickListener {
                    val entry = MediaEntry(
                        kind = Kinds.CHANNEL, itemId = ch.key, title = ch.name,
                        poster = ch.logo, url = ch.url, subtitle = ch.group)
                    lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
                    Nav.play(this@EpgGridActivity, entry, playlistId = playlistId)
                }
            })
        }
    }

    private fun spacer(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LABEL_W.dp(this@EpgGridActivity), 1)
    }

    private fun channelLabel(ch: com.astraplay.tv.data.iptv.Channel): TextView =
        TextView(this).apply {
            text = ch.name
            textSize = 12f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ThemeManager.colorOf(this@EpgGridActivity, android.R.attr.textColorPrimary))
            setPadding(10.dp(this@EpgGridActivity), 0, 6.dp(this@EpgGridActivity), 0)
            layoutParams = LinearLayout.LayoutParams(LABEL_W.dp(this@EpgGridActivity), 68.dp(this@EpgGridActivity))
        }

    /** HSV с подпиской на общую синхронизацию scrollX. */
    private fun makeSyncedHsv(): HorizontalScrollView {
        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        hsvList.remove(hsv) // на случай пересоздания
        hsvList.add(hsv)
        hsv.setOnScrollChangeListener { v, x, _, _, _ ->
            if (syncing) return@setOnScrollChangeListener
            syncing = true
            for (other in hsvList) if (other !== v) other.scrollTo(x, 0)
            syncing = false
        }
        return hsv
    }
}
