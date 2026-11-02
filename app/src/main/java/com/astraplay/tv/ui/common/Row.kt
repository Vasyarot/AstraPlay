package com.astraplay.tv.ui.common

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.ui.theme.ThemeManager

/**
 * Горизонтальный ряд карточек с заголовком — базовый строительный блок
 * всех экранов. Общий пул переиспользования между рядами экономит память.
 */
object CardPools {
    val shared = RecyclerView.RecycledViewPool()
}

class MediaRow<T : Any> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val titleView = makeTitle(context, "")
    private val rv = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        setHasFixedSize(true)
        setItemViewCacheSize(6)
        setRecycledViewPool(CardPools.shared)
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        setPadding(16.dp(context), 0, 16.dp(context), 0)
    }

    init {
        orientation = VERTICAL
        addView(titleView)
        addView(rv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun titleColorDefault() {
        titleView.setTextColor(ThemeManager.colorOf(context, android.R.attr.textColorPrimary))
    }

    fun setData(
        items: List<T>,
        mode: Int,
        binder: (PosterCard, T) -> Unit,
        click: (T) -> Unit,
        longClick: ((T) -> Boolean)? = null,
    ) {
        rv.adapter = RowAdapter(items, mode, binder, click, longClick)
        visibility = if (items.isEmpty()) GONE else VISIBLE
    }

    fun clear() {
        rv.adapter = null
        visibility = GONE
    }
}

private class RowVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

private class RowAdapter<T>(
    private var items: List<T>,
    private val mode: Int,
    private val binder: (PosterCard, T) -> Unit,
    private val click: (T) -> Unit,
    private val longClick: ((T) -> Boolean)?,
) : RecyclerView.Adapter<RowVH>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RowVH {
        val card = PosterCard(parent.context)
        card.applyMode(mode)
        card.setMargins()
        return RowVH(card)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RowVH, position: Int) {
        val item = items[position]
        holder.card.layoutParams = (holder.card.layoutParams as LinearLayout.LayoutParams).apply {
            width = when (mode) {
                PosterCard.MODE_POSTER -> 132.dp(holder.card.context)
                PosterCard.MODE_WIDE -> 220.dp(holder.card.context)
                else -> 120.dp(holder.card.context)
            }
            rightMargin = 12.dp(holder.card.context)
        }
        binder(holder.card, item)
        holder.card.setOnClickListener { click(item) }
        holder.card.setOnLongClickListener { longClick?.invoke(item) ?: false }
    }
}
