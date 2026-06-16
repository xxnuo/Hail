package com.aistra.hail.ui.home

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AlphabetIndex
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class PagerAdapter(
    private val selectedList: List<AppInfo>,
    private val flags: MutableMap<String, Int> = mutableMapOf(),
    private val states: MutableMap<String, AppInfo.State> = mutableMapOf()
) : ListAdapter<PagerAdapter.Item, PagerAdapter.ViewHolder>(HomeDiff(selectedList, flags, states)) {
    private var loadIconJob: Job? = null
    private var activeLetter: Char? = null
    private var sectionHeaderGravity = Gravity.START or Gravity.CENTER_VERTICAL
    private var tailSpacerHeight: Int = 0
    var appList: List<AppInfo> = emptyList()
        private set
    var appEntries: List<AppEntry> = emptyList()
        private set
    var sectionPositions: Map<Char, Int> = emptyMap()
        private set
    var sectionAppCounts: Map<Char, Int> = emptyMap()
        private set
    var onSectionHeaderClickListener: ((Char) -> Unit)? = null
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener

    override fun getItemViewType(position: Int): Int = when (currentList[position]) {
        is Item.App -> VIEW_TYPE_APP
        is Item.Header -> VIEW_TYPE_HEADER
        is Item.Spacer -> VIEW_TYPE_SPACER
        is Item.TailSpacer -> VIEW_TYPE_TAIL_SPACER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        when (viewType) {
            VIEW_TYPE_APP -> ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_home, parent, false))
            VIEW_TYPE_HEADER -> ViewHolder(TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                minHeight = resources.getDimensionPixelSize(R.dimen.home_section_header_min_height)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.padding_medium),
                    resources.getDimensionPixelSize(R.dimen.padding_large),
                    resources.getDimensionPixelSize(R.dimen.padding_medium),
                    resources.getDimensionPixelSize(R.dimen.padding_medium)
                )
                gravity = sectionHeaderGravity
                isClickable = true
                isFocusable = true
                val selectable = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                setBackgroundResource(selectable.resourceId)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            VIEW_TYPE_TAIL_SPACER -> ViewHolder(Space(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tailSpacerHeight)
            })
            else -> ViewHolder(Space(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            })
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.any { it == PAYLOAD_STATE }) {
            val item = currentList[position]
            if (item is Item.App) {
                val info = item.entry.info
                val state = stateOf(info.packageName)
                flags[info.packageName] = info.getFlag(selectedList, state)
                holder.bindState(info, state, info in selectedList)
            }
            return
        }
        if (payloads.any { it == PAYLOAD_SECTION_HEADER_GRAVITY }) {
            (holder.itemView as? TextView)?.gravity = sectionHeaderGravity
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = currentList[position]
        if (item is Item.Header) {
            (holder.itemView as? TextView)?.run {
                gravity = sectionHeaderGravity
                text = item.letter.toString()
            }
            holder.itemView.setOnClickListener { onSectionHeaderClickListener?.invoke(item.letter) }
            return
        }
        if (item !is Item.App) return
        val entry = item.entry
        val info = entry.info
        val state = stateOf(info.packageName)
        val name = entry.name
        holder.name = name
        flags[info.packageName] = info.getFlag(selectedList, state)
            holder.itemView.run {
                alpha = activeLetter?.let {
                if (entry.matchesActiveLetter(it)) 1f else 0.28f
            } ?: 1f
            setOnClickListener { onItemClickListener.onItemClick(info) }
            setOnLongClickListener { onItemLongClickListener.onItemLongClick(info) }
            holder.appIcon?.run {
                info.applicationInfo?.let {
                    loadIconJob = AppIconCache.loadIconBitmapAsync(
                        context, it, myUserId, this, HailData.grayscaleIcon && state == AppInfo.State.FROZEN
                    )
                } ?: run {
                    setImageDrawable(context.packageManager.defaultActivityIcon)
                    colorFilter = null
                }
            }
            holder.bindState(info, state, info in selectedList)
        }
    }

    fun setStateSnapshot(snapshot: Map<String, AppInfo.State>) {
        states.clear()
        states.putAll(snapshot)
    }

    fun updateStateSnapshot(snapshot: Map<String, AppInfo.State>, changedPackages: Collection<String>? = null) {
        setStateSnapshot(snapshot)
        if (itemCount == 0) return
        val changed = changedPackages?.toSet()
        if (changed == null) notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE)
        else currentList.forEachIndexed { index, item ->
            if (item is Item.App && item.entry.info.packageName in changed) notifyItemChanged(index, PAYLOAD_STATE)
        }
    }

    fun submitAppEntries(entries: List<AppEntry>, spanCount: Int, tailSpacerRows: Int, tailSpacerHeight: Int) {
        appEntries = entries
        appList = entries.map { it.info }
        this.tailSpacerHeight = tailSpacerHeight
        val positions = mutableMapOf<Char, Int>()
        val counts = mutableMapOf<Char, Int>()
        submitList(buildItems(entries, spanCount, tailSpacerRows, positions, counts))
        sectionPositions = positions
        sectionAppCounts = counts
    }

    fun isFullSpan(position: Int): Boolean = currentList.getOrNull(position).let {
        it is Item.Header || it is Item.TailSpacer
    }

    fun isAppPosition(position: Int): Boolean = currentList.getOrNull(position) is Item.App

    fun firstTailSpacerPosition(): Int = currentList.indexOfFirst { it is Item.TailSpacer }

    fun firstUnletteredAppPosition(): Int =
        currentList.indexOfFirst { it is Item.App && it.entry.primaryLetter == null }

    fun lastSectionStartPosition(): Int =
        listOf(sectionPositions.values.maxOrNull(), firstUnletteredAppPosition().takeIf { it != -1 })
            .filterNotNull()
            .maxOrNull() ?: currentList.indexOfLast { it is Item.App || it is Item.Header }

    fun setActiveLetter(letter: Char?) {
        if (activeLetter == letter) return
        activeLetter = letter
        notifyDataSetChanged()
    }

    fun setSectionHeaderAlignEnd(alignEnd: Boolean) {
        val gravity = (if (alignEnd) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        if (sectionHeaderGravity == gravity) return
        sectionHeaderGravity = gravity
        currentList.forEachIndexed { index, item ->
            if (item is Item.Header) notifyItemChanged(index, PAYLOAD_SECTION_HEADER_GRAVITY)
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    private fun stateOf(packageName: String) = states[packageName] ?: AppInfo.State.UNFROZEN

    private fun buildItems(
        entries: List<AppEntry>,
        spanCount: Int,
        tailSpacerRows: Int,
        positions: MutableMap<Char, Int>,
        counts: MutableMap<Char, Int>
    ): List<Item> {
        val safeSpanCount = spanCount.coerceAtLeast(1)
        val items = mutableListOf<Item>()
        var currentLetter: Char? = null
        var spanCursor = 0
        entries.forEach { entry ->
            val letter = entry.primaryLetter
            if (items.isNotEmpty() && letter != currentLetter) {
                if (spanCursor != 0) {
                    repeat(safeSpanCount - spanCursor) {
                        items.add(Item.Spacer("${currentLetter ?: "#"}-${letter ?: "#"}-${items.size}"))
                    }
                    spanCursor = 0
                }
            }
            if (letter != currentLetter) {
                currentLetter = letter
                if (letter != null) {
                    positions.putIfAbsent(letter, items.size)
                    items.add(Item.Header(letter))
                    spanCursor = 0
                }
            }
            entry.sectionStartPosition = items.size
            if (letter != null) counts[letter] = (counts[letter] ?: 0) + 1
            items.add(Item.App(entry))
            spanCursor = (spanCursor + 1) % safeSpanCount
        }
        if (items.isNotEmpty()) {
            if (spanCursor != 0) {
                repeat(safeSpanCount - spanCursor) {
                    items.add(Item.Spacer("tail-align-$it"))
                }
            }
            repeat(tailSpacerRows.coerceAtLeast(1)) {
                items.add(Item.TailSpacer("tail-$it"))
            }
        }
        return items
    }

    private class HomeDiff(
        private val selectedList: List<AppInfo>,
        private val flags: Map<String, Int>,
        private val states: Map<String, AppInfo.State>
    ) : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
            oldItem is Item.App && newItem is Item.App -> oldItem.entry.info == newItem.entry.info
            oldItem is Item.Header && newItem is Item.Header -> oldItem.letter == newItem.letter
            oldItem is Item.Spacer && newItem is Item.Spacer -> oldItem.key == newItem.key
            oldItem is Item.TailSpacer && newItem is Item.TailSpacer -> oldItem.key == newItem.key
            else -> false
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = when {
            oldItem is Item.App && newItem is Item.App ->
                flags[oldItem.entry.info.packageName] == newItem.entry.info.getFlag(
                    selectedList,
                    states[newItem.entry.info.packageName] ?: AppInfo.State.UNFROZEN
                )
                        && oldItem.entry.primaryLetter == newItem.entry.primaryLetter
                        && oldItem.entry.sortKey == newItem.entry.sortKey
                        && oldItem.entry.sectionStartPosition == newItem.entry.sectionStartPosition

            oldItem is Item.Header && newItem is Item.Header -> true
            oldItem is Item.Spacer && newItem is Item.Spacer -> true
            oldItem is Item.TailSpacer && newItem is Item.TailSpacer -> true
            else -> false
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appIcon: ImageView? = view.findViewById(R.id.app_icon)
        val appName: TextView? = view.findViewById(R.id.app_name)
        var name: CharSequence? = null

        fun bindState(info: AppInfo, state: AppInfo.State, selected: Boolean) {
            val icon = appIcon ?: return
            val textView = appName ?: return
            val appNameText = name ?: info.name.also { name = it }
            AppIconCache.setGrayscale(icon, HailData.grayscaleIcon && state == AppInfo.State.FROZEN)
            textView.run {
                text = buildString {
                    if (!HailData.grayscaleIcon && state == AppInfo.State.FROZEN) append("\u2744\uFE0F")
                    if (info.whitelisted) append("\uD83D\uDD12")
                    append(appNameText)
                }
                isEnabled = !HailData.grayscaleIcon || state != AppInfo.State.FROZEN
                when {
                    selected -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                    )

                    state == AppInfo.State.NOT_FOUND -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                    )

                    else -> setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
            }
        }
    }

    data class AppEntry(
        val info: AppInfo,
        val name: CharSequence,
        val primaryLetter: Char?,
        val sortKey: String,
        var sectionStartPosition: Int = RecyclerView.NO_POSITION
    ) {
        fun matchesActiveLetter(letter: Char): Boolean =
            if (letter == '#') primaryLetter == null else primaryLetter == letter
    }

    sealed interface Item {
        data class App(val entry: AppEntry) : Item
        data class Header(val letter: Char) : Item
        data class Spacer(val key: String) : Item
        data class TailSpacer(val key: String) : Item
    }

    interface OnItemClickListener {
        fun onItemClick(info: AppInfo)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(info: AppInfo): Boolean
    }

    companion object {
        private const val VIEW_TYPE_APP = 0
        private const val VIEW_TYPE_SPACER = 1
        private const val VIEW_TYPE_TAIL_SPACER = 2
        private const val VIEW_TYPE_HEADER = 3
        private const val PAYLOAD_STATE = "state"
        private const val PAYLOAD_SECTION_HEADER_GRAVITY = "section_header_gravity"
    }
}

private fun AppInfo.getFlag(selectedList: List<AppInfo>, state: AppInfo.State) =
    (1 shl state.ordinal) or (this in selectedList).shl(3) or whitelisted.shl(4)

private fun Boolean.shl(bitCount: Int) = if (this) 1 shl bitCount else 0
