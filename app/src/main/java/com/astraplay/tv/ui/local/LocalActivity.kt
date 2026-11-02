package com.astraplay.tv.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.local.LocalMedia
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.formatDuration
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Видео с устройства и USB-носителей (MediaStore), сгруппировано по папкам. */
class LocalActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView
    private lateinit var status: TextView

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else status.text = getString(R.string.no_permission)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.local_media)))
        status = TextView(this).apply {
            textSize = 14f
            setPadding(16.dp(this@LocalActivity), 8.dp(this@LocalActivity), 16.dp(this@LocalActivity), 8.dp(this@LocalActivity))
        }
        root.addView(status)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LocalActivity, 5)
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ensurePermission()
    }

    private fun ensurePermission() {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            load()
        } else {
            permLauncher.launch(perm)
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { ServiceLocator.media.videos() }
            res.fold(onSuccess = { videos ->
                status.text = getString(R.string.channel_count, videos.size)
                if (videos.isEmpty()) status.text = getString(R.string.empty)
                grid.adapter = LocalAdapter(videos)
            }, onFailure = {
                status.text = getString(R.string.error)
            })
        }
    }

    private class LocalVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

    private inner class LocalAdapter(private val videos: List<LocalMedia>) :
        RecyclerView.Adapter<LocalVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LocalVH {
            val card = PosterCard(this@LocalActivity)
            card.applyMode(PosterCard.MODE_WIDE)
            card.layoutParams = GridLayoutManager.LayoutParams(220.dp(this@LocalActivity), 170.dp(this@LocalActivity)).apply {
                rightMargin = 12.dp(this@LocalActivity)
                bottomMargin = 12.dp(this@LocalActivity)
            }
            return LocalVH(card)
        }
        override fun getItemCount() = videos.size
        override fun onBindViewHolder(holder: LocalVH, position: Int) {
            val v = videos[position]
            holder.card.image.load(v.uri) {
                allowRgb565(true)
                crossfade(false)
                decoderFactory(coil.decode.VideoFrameDecoder.Factory())
            }
            holder.card.label.text = v.title
            holder.card.showBadge(
                if (v.durationMs > 0) formatDuration(v.durationMs) else null
            )
            holder.card.setOnClickListener {
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { ServiceLocator.store.position(v.key) }
                    val entry = MediaEntry(
                        kind = Kinds.LOCAL, itemId = v.key, title = v.title,
                        subtitle = v.folder, url = v.uri,
                        positionMs = saved?.positionMs ?: 0L, durationMs = v.durationMs)
                    lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
                    Nav.play(this@LocalActivity, entry)
                }
            }
            holder.card.setOnLongClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    ServiceLocator.store.addFavorite(MediaEntry(
                        kind = Kinds.LOCAL, itemId = v.key, title = v.title,
                        subtitle = v.folder, url = v.uri, poster = null))
                }
                Toast.makeText(this@LocalActivity, getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}
