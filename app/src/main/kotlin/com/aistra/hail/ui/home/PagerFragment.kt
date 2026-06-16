package com.aistra.hail.ui.home

import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.AppStateCache
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailApi.addTag
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.DialogInputBinding
import com.aistra.hail.databinding.FragmentPagerBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.*
import com.aistra.hail.views.AlphabetFastScroller
import com.aistra.hail.work.HWork
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Collator
import kotlin.math.roundToInt

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {
    private var query: String = String()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var fastScrollPositions: Map<Char, Int> = emptyMap()
    private var fastScrollerPlacement = AlphabetFastScroller.Placement.END
    private var pendingCurrentListUpdate = false
    private var searchTextWatcher: TextWatcher? = null
    private var searchBackCallback: OnBackPressedCallback? = null
    private var updateCurrentListJob: Job? = null
    private var updateCurrentListGeneration = 0
    private var multiselect: Boolean
        set(value) {
            (parentFragment as HomeFragment).multiselect = value
        }
        get() = (parentFragment as HomeFragment).multiselect
    private val selectedList get() = (parentFragment as HomeFragment).selectedList
    private val tabs: TabLayout get() = (parentFragment as HomeFragment).binding.tabs
    private val adapter get() = (parentFragment as HomeFragment).binding.pager.adapter as HomeAdapter
    private val tag: Pair<String, Int> get() = HailData.tags[tabs.selectedTabPosition]
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val menuHost = requireActivity() as MenuHost
        menuHost.addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        _binding = FragmentPagerBinding.inflate(inflater, container, false)
        pagerAdapter = PagerAdapter(selectedList).apply {
            onItemClickListener = this@PagerFragment
            onItemLongClickListener = this@PagerFragment
            onSectionHeaderClickListener = ::showLetterPicker
        }
        binding.recyclerView.run {
            val gridLayoutManager = GridLayoutManager(
                activity, resources.getInteger(
                    if (HailData.compactIcon) R.integer.home_span_compact else R.integer.home_span
                )
            ).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (pagerAdapter.isFullSpan(position)) spanCount else 1
                }
            }
            layoutManager = gridLayoutManager
            adapter = pagerAdapter
            setupPullSearch(this)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            consumePendingCurrentListUpdate()
                            reboundFromTailSpacer()
                            hideFastScrollers()
                            activity.fab.run {
                                postDelayed({ if (tag == true) show() }, 1000)
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            showFastScrollers()
                            activity.fab.hide()
                        }
                    }
                }
            })
            applyDefaultInsetter { paddingRelative(isRtl, bottom = true) }

        }

        setupFastScroller(binding.fastScrollerEnd, AlphabetFastScroller.Placement.END)
        setupFastScroller(binding.fastScrollerStart, AlphabetFastScroller.Placement.START)
        setupFastScroller(binding.fastScrollerBottom, AlphabetFastScroller.Placement.BOTTOM)
        AppStateCache.updates.observe(viewLifecycleOwner) {
            updateStateSnapshot(it.packageNames)
            app.setAutoFreezeService()
        }
        return binding.root
    }

    private fun setupPullSearch(recyclerView: RecyclerView) {
        val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        val triggerDistance = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 72f, resources.displayMetrics)
        val maxTranslation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics)
        var startX = 0f
        var startY = 0f
        var pulling = false

        fun pullDistance(y: Float) = (y - startY - touchSlop).coerceAtLeast(0f)

        fun applyPull(distance: Float) {
            recyclerView.translationY = (distance * 0.35f).coerceAtMost(maxTranslation)
        }

        fun resetPull() {
            pulling = false
            recyclerView.animate().translationY(0f).setDuration(160L).start()
        }

        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                updateFastScrollerPlacement(rv, e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        pulling = false
                        rv.animate().cancel()
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.y - startY
                        val dx = e.x - startX
                        if (!rv.canScrollVertically(-1) && dy > touchSlop && dy > kotlin.math.abs(dx)) {
                            pulling = true
                            rv.parent.requestDisallowInterceptTouchEvent(true)
                            applyPull(pullDistance(e.y))
                            return true
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetPull()
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (!pulling) return
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> applyPull(pullDistance(e.y))
                    MotionEvent.ACTION_UP -> {
                        if (pullDistance(e.y) >= triggerDistance) focusHomeSearch(activity.homeSearchInput)
                        resetPull()
                    }

                    MotionEvent.ACTION_CANCEL -> resetPull()
                }
            }
        })
    }

    private fun updateFastScrollerPlacement(recyclerView: RecyclerView, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN || recyclerView.width <= 0) return
        val placement =
            if (event.x < recyclerView.width / 2f) AlphabetFastScroller.Placement.START
            else AlphabetFastScroller.Placement.END
        if (fastScrollerPlacement == placement) return
        fastScrollerPlacement = placement
        pagerAdapter.setSectionHeaderAlignEnd(placement == AlphabetFastScroller.Placement.START)
    }

    private fun requestCurrentListUpdate() {
        val recyclerView = _binding?.recyclerView ?: return
        if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.isComputingLayout) {
            recyclerView.post {
                if (_binding != null) updateCurrentList()
            }
        } else {
            pendingCurrentListUpdate = true
        }
    }

    private fun consumePendingCurrentListUpdate() {
        if (!pendingCurrentListUpdate) return
        val recyclerView = _binding?.recyclerView ?: return
        if (recyclerView.isComputingLayout) {
            recyclerView.post { consumePendingCurrentListUpdate() }
            return
        }
        pendingCurrentListUpdate = false
        updateCurrentList()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentList()
        updateBarTitle()
        activity.appbar.setLiftOnScrollTargetView(binding.recyclerView)
        tabs.getTabAt(tabs.selectedTabPosition)?.view?.setOnLongClickListener {
            if (isResumed) showTagDialog()
            true
        }
        activity.fab.setOnClickListener {
            setListFrozen(true, pagerAdapter.appList.filterNot { it.whitelisted })
        }
        activity.fab.setOnLongClickListener {
            setListFrozen(true)
            true
        }
    }

    private fun updateCurrentList() {
        val generation = ++updateCurrentListGeneration
        val tagId = tag.second
        val queryText = query
        val nineKeySearch = HailData.nineKeySearch
        val spanCount = (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val tailSpacerHeight = binding.recyclerView.height.takeIf { height -> height > 0 }
            ?: resources.displayMetrics.heightPixels
        updateCurrentListJob?.cancel()
        updateCurrentListJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                val apps = HailData.checkedList.toList()
                buildCurrentList(apps, tagId, queryText, nineKeySearch)
            }
            if (generation != updateCurrentListGeneration || _binding == null) return@launch
            binding.empty.isVisible = result.entries.isEmpty()
            pagerAdapter.setStateSnapshot(AppStateCache.statesFor(result.packages))
            pagerAdapter.submitAppEntries(
                result.entries,
                spanCount,
                tailSpacerRows = 1,
                tailSpacerHeight = tailSpacerHeight
            )
            pagerAdapter.setSectionHeaderAlignEnd(fastScrollerPlacement == AlphabetFastScroller.Placement.START)
            AppStateCache.primeAsync(result.packages)
            updateFastScroller()
            app.setAutoFreezeService()
        }
    }

    private suspend fun buildCurrentList(
        apps: List<AppInfo>,
        tagId: Int,
        queryText: String,
        nineKeySearch: Boolean
    ): CurrentListResult {
        val collator = Collator.getInstance()
        val entries = mutableListOf<PagerAdapter.AppEntry>()
        for (appInfo in apps) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val name = appInfo.name
            if (queryText.isEmpty()) {
                if (tagId !in appInfo.tagIdList) continue
            } else {
                val nameText = name.toString()
                val matches = (nineKeySearch && NineKeySearch.search(queryText, appInfo.packageName, nameText))
                        || FuzzySearch.search(appInfo.packageName, queryText)
                        || FuzzySearch.search(nameText, queryText)
                        || PinyinSearch.searchPinyinAll(nameText, queryText)
                if (!matches) continue
            }
            entries.add(
                PagerAdapter.AppEntry(
                    info = appInfo,
                    name = name,
                    primaryLetter = AlphabetIndex.primaryLetter(name),
                    sortKey = AlphabetIndex.sortKey(name)
                )
            )
        }
        entries.sortWith { a, b ->
            when {
                a.info.pinned && !b.info.pinned -> -1
                b.info.pinned && !a.info.pinned -> 1
                else -> {
                    val keyResult = a.sortKey.compareTo(b.sortKey, true)
                    if (keyResult != 0) keyResult else collator.compare(a.name, b.name)
                }
            }
        }
        return CurrentListResult(entries, entries.map { it.info.packageName })
    }

    private data class CurrentListResult(
        val entries: List<PagerAdapter.AppEntry>,
        val packages: List<String>
    )

    private fun updateStateSnapshot(changedPackages: Collection<String>? = null) {
        val packages = pagerAdapter.appList.map { it.packageName }
        pagerAdapter.updateStateSnapshot(AppStateCache.statesFor(packages), changedPackages)
    }

    private fun updateFastScroller() {
        fastScrollPositions = pagerAdapter.sectionPositions
        fastScrollers().forEach {
            it.setAvailableLetters(fastScrollPositions.keys)
            it.hideScroller(0L)
        }
    }

    private fun scrollToLetter(letter: Char) {
        val position = if (letter == '#') pagerAdapter.firstUnletteredAppPosition() else fastScrollPositions[letter]
        if (position == null || position == RecyclerView.NO_POSITION) return
        pagerAdapter.setActiveLetter(letter)
        (binding.recyclerView.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(position, 0)
        activity.fab.hide()
    }

    private fun scrollToLetterNearTouch(letter: Char, anchorFraction: Float) {
        val headerPosition = fastScrollPositions[letter] ?: return
        val recyclerView = binding.recyclerView
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
        val rowHeight = estimatedAppRowHeight()
        val appCount = pagerAdapter.sectionAppCounts[letter] ?: 1
        val sectionRows = ((appCount + layoutManager.spanCount - 1) / layoutManager.spanCount).coerceAtLeast(1)
        val sectionHeight = sectionRows * rowHeight
        val anchorY = ((recyclerView.height * anchorFraction) - resources.getDimensionPixelSize(R.dimen.padding_small))
            .roundToInt()
        val minOffset = recyclerView.paddingTop
        val maxOffset = (recyclerView.height - recyclerView.paddingBottom - sectionHeight).coerceAtLeast(minOffset)
        val offset = (anchorY - sectionHeight).coerceIn(minOffset, maxOffset)
        pagerAdapter.setActiveLetter(letter)
        layoutManager.scrollToPositionWithOffset(headerPosition + 1, offset)
        activity.fab.hide()
    }

    private fun estimatedAppRowHeight(): Int {
        val recyclerView = binding.recyclerView
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val position = recyclerView.getChildAdapterPosition(child)
            if (pagerAdapter.isAppPosition(position) && child.height > 0) {
                return child.height
            }
        }
        return resources.getDimensionPixelSize(R.dimen.app_icon_size) +
                resources.getDimensionPixelSize(R.dimen.padding_large) +
                resources.getDimensionPixelSize(R.dimen.padding_small)
    }

    private fun reboundFromTailSpacer() {
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        val tailPosition = pagerAdapter.firstTailSpacerPosition()
        if (tailPosition == RecyclerView.NO_POSITION) return
        val lastSectionPosition = pagerAdapter.lastSectionStartPosition()
        if (lastSectionPosition == RecyclerView.NO_POSITION) return
        if (layoutManager.findFirstVisibleItemPosition() >= tailPosition) {
            binding.recyclerView.smoothScrollToPosition(lastSectionPosition)
        }
    }

    private fun showLetterPicker(currentLetter: Char) {
        val available = fastScrollPositions.keys
        val hasUnlettered = pagerAdapter.firstUnletteredAppPosition() != RecyclerView.NO_POSITION
        val overlay = FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface),
                    230
                )
            )
            alpha = 0f
        }
        val scroll = ScrollView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            setOnClickListener { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        }
        val container = LinearLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.padding_large)
                marginEnd = resources.getDimensionPixelSize(R.dimen.padding_large)
                topMargin = resources.getDimensionPixelSize(R.dimen.padding_large)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_large)
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipToPadding = false
            setPadding(
                resources.getDimensionPixelSize(R.dimen.padding_medium),
                resources.getDimensionPixelSize(R.dimen.padding_large),
                resources.getDimensionPixelSize(R.dimen.padding_medium),
                resources.getDimensionPixelSize(R.dimen.padding_large)
            )
        }
        val cellHeight = resources.getDimensionPixelSize(R.dimen.letter_picker_cell_size)
        val cellMargin = resources.getDimensionPixelSize(R.dimen.padding_extra_small)
        fun addRow(): LinearLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, cellHeight)
            orientation = LinearLayout.HORIZONTAL
            container.addView(this)
        }
        fun LinearLayout.addCell(label: String, enabled: Boolean, selected: Boolean = false, onClick: (() -> Unit)? = null) {
            addView(TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply {
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }
                text = label
                gravity = Gravity.CENTER
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                includeFontPadding = false
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        if (enabled) com.google.android.material.R.attr.colorOnSurface
                        else com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                alpha = when {
                    selected -> 1f
                    enabled -> 0.95f
                    else -> 0.3f
                }
                if (selected) {
                    val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
                    setBackgroundColor(surface)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                } else if (enabled) {
                    val value = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
                    setBackgroundResource(value.resourceId)
                }
                onClick?.let {
                    setOnClickListener {
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                        it()
                    }
                }
            })
        }
        val labels = listOf("#") + ('A'..'Z').map { it.toString() }
        labels.chunked(4).forEach { rowLabels ->
            val row = addRow()
            rowLabels.forEach { label ->
                val letter = label.single()
                val enabled = if (letter == '#') hasUnlettered else letter in available
                row.addCell(label, enabled, currentLetter == letter) {
                    scrollToLetter(letter)
                }
            }
            repeat(4 - rowLabels.size) {
                row.addCell("", false)
            }
        }
        scroll.addView(container)
        overlay.addView(scroll)
        binding.root.addView(overlay)
        overlay.animate().alpha(1f).setDuration(140L).start()
    }

    private fun setupFastScroller(view: AlphabetFastScroller, placement: AlphabetFastScroller.Placement) {
        view.setPlacement(placement)
        view.onLetterSelected = ::scrollToLetterNearTouch
        view.onLetterCleared = { pagerAdapter.setActiveLetter(null) }
        when (placement) {
            AlphabetFastScroller.Placement.START ->
                view.applyDefaultInsetter { marginRelative(isRtl, start = true, bottom = true) }

            AlphabetFastScroller.Placement.END ->
                view.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = true) }

            AlphabetFastScroller.Placement.BOTTOM ->
                view.applyDefaultInsetter { marginRelative(isRtl, start = true, end = true, bottom = true) }
        }
    }

    private fun fastScrollers() = listOf(
        binding.fastScrollerStart,
        binding.fastScrollerEnd,
        binding.fastScrollerBottom
    )

    private fun activeFastScrollers() = if (isLandscape) {
        listOf(binding.fastScrollerBottom)
    } else if (fastScrollerPlacement == AlphabetFastScroller.Placement.START) {
        listOf(binding.fastScrollerStart)
    } else {
        listOf(binding.fastScrollerEnd)
    }

    private fun showFastScrollers() {
        val active = activeFastScrollers()
        fastScrollers().filterNot { it in active }.forEach { it.hideScroller(0L) }
        active.forEach { it.showScroller() }
    }

    private fun hideFastScrollers() {
        fastScrollers().forEach { it.hideScroller() }
    }

    private fun updateBarTitle() {
        activity.supportActionBar?.title = ""
        activity.homeSearchInput.hint =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.action_search)
    }

    override fun onItemClick(info: AppInfo) {
        if (multiselect) {
            if (info in selectedList) selectedList.remove(info)
            else selectedList.add(info)
            updateCurrentList()
            updateBarTitle()
            return
        }
        if (info.applicationInfo == null) {
            Snackbar.make(activity.fab, R.string.app_not_installed, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_remove_home) { removeCheckedApp(info.packageName) }.show()
            return
        }
        launchApp(info.packageName)
    }

    override fun onItemLongClick(info: AppInfo): Boolean {
        if (info.applicationInfo == null && (!multiselect || info !in selectedList)) {
            exportToClipboard(listOf(info))
            return true
        }
        if (info in selectedList) {
            onMultiSelect()
            return true
        }
        val pkg = info.packageName
        val frozen = AppStateCache.stateOrRefresh(pkg) == AppInfo.State.FROZEN
        val action = getString(if (frozen) R.string.action_unfreeze else R.string.action_freeze)
        MaterialAlertDialogBuilder(activity).setTitle(info.name).setItems(
            resources.getStringArray(R.array.home_action_entries).filter {
                (it != getString(R.string.action_freeze) || !frozen) && (it != getString(R.string.action_unfreeze) || frozen) && (it != getString(
                    R.string.action_pin
                ) || !info.pinned) && (it != getString(R.string.action_unpin) || info.pinned) && (it != getString(
                    R.string.action_whitelist
                ) || !info.whitelisted) && (it != getString(R.string.action_remove_whitelist) || info.whitelisted) && (it != getString(
                    R.string.action_unfreeze_remove_home
                ) || frozen)
            }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> launchApp(pkg)
                1 -> setListFrozen(!frozen, listOf(info), skipWhitelisted = false)
                2 -> {
                    val values = resources.getIntArray(R.array.deferred_task_values)
                    val entries = arrayOfNulls<String>(values.size)
                    values.forEachIndexed { i, it ->
                        entries[i] = resources.getQuantityString(R.plurals.deferred_task_entry, it, it)
                    }
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.action_deferred_task)
                        .setItems(entries) { _, i ->
                            HWork.setDeferredFrozen(pkg, !frozen, values[i].toLong())
                            Snackbar.make(
                                activity.fab, resources.getQuantityString(
                                    R.plurals.msg_deferred_task, values[i], values[i], action, info.name
                                ), Snackbar.LENGTH_INDEFINITE
                            ).setAction(R.string.action_undo) { HWork.cancelWork(pkg) }.show()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }

                3 -> {
                    info.pinned = !info.pinned
                    HailData.saveApps()
                    updateCurrentList()
                }

                4 -> {
                    info.whitelisted = !info.whitelisted
                    HailData.saveApps()
                    updateCurrentList()
                }

                5 -> tagDialog(info)

                6 -> if (tabs.tabCount > 1) MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.action_unfreeze_tag)
                    .setItems(HailData.tags.map { it.first }.toTypedArray()) { _, index ->
                        HShortcuts.addPinShortcut(
                            info,
                            pkg,
                            info.name,
                            HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg).addTag(HailData.tags[index].first)
                        )
                    }.setPositiveButton(R.string.action_skip) { _, _ ->
                        HShortcuts.addPinShortcut(
                            info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                        )
                    }.setNegativeButton(android.R.string.cancel, null).show()
                else HShortcuts.addPinShortcut(
                    info, pkg, info.name, HailApi.getIntentForPackage(HailApi.ACTION_LAUNCH, pkg)
                )

                7 -> exportToClipboard(listOf(info))
                8 -> removeCheckedApp(pkg)
                9 -> {
                    setListFrozen(false, listOf(info), updateList = false, skipWhitelisted = false) {
                        if (AppStateCache.stateOf(pkg) != AppInfo.State.FROZEN) removeCheckedApp(pkg)
                    }
                }
            }
        }.setNeutralButton(R.string.action_details) { _, _ ->
            HUI.startActivity(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HPackages.packageUri(pkg)
            )
        }.setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun tagDialog(info: AppInfo) {
        val checkedItems = BooleanArray(HailData.tags.size) { index ->
            HailData.tags[index].second in info.tagIdList
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setMultiChoiceItems(
            HailData.tags.map { it.first }.toTypedArray(), checkedItems
        ) { _, index, isChecked ->
            checkedItems[index] = isChecked
        }.setPositiveButton(android.R.string.ok) { _, _ ->
            info.tagIdList.clear()
            checkedItems.forEachIndexed { index, checked ->
                if (checked) info.tagIdList.add(HailData.tags[index].second)
            }
            if (info.tagIdList.isEmpty()) {
                removeCheckedApp(info.packageName, false)
            }
            HailData.saveApps()
            updateCurrentList()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(listOf(info))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun deselect(update: Boolean = true) {
        selectedList.clear()
        if (!update) return
        updateCurrentList()
        updateBarTitle()
    }

    private fun onMultiSelect() {
        MaterialAlertDialogBuilder(activity).setTitle(
            getString(
                R.string.msg_selected, selectedList.size.toString()
            )
        ).setItems(
            intArrayOf(
                R.string.action_freeze,
                R.string.action_unfreeze,
                R.string.action_tag_set,
                R.string.action_export_clipboard,
                R.string.action_remove_home,
                R.string.action_unfreeze_remove_home
            ).map { getString(it) }.toTypedArray()
        ) { _, which ->
            when (which) {
                0 -> {
                    setListFrozen(true, selectedList, updateList = false) { deselect() }
                }

                1 -> {
                    setListFrozen(false, selectedList, updateList = false) { deselect() }
                }

                2 -> triStateTagDialog()

                3 -> {
                    exportToClipboard(selectedList)
                    deselect()
                }

                4 -> {
                    selectedList.forEach { removeCheckedApp(it.packageName, false) }
                    HailData.saveApps()
                    deselect()
                }

                5 -> {
                    val selected = selectedList.toList()
                    setListFrozen(false, selected, updateList = false) {
                        selected.forEach {
                            if (AppStateCache.stateOf(it.packageName) != AppInfo.State.FROZEN) removeCheckedApp(
                                it.packageName,
                                false
                            )
                        }
                        HailData.saveApps()
                        deselect()
                    }
                }
            }
        }.setNegativeButton(R.string.action_deselect) { _, _ ->
            deselect()
        }.setNeutralButton(R.string.action_select_all) { _, _ ->
            selectedList.addAll(pagerAdapter.appList.filterNot { it in selectedList })
            updateCurrentList()
            updateBarTitle()
            onMultiSelect()
        }.show()
    }

    private fun triStateTagDialog() {
        val initialStates = Array(HailData.tags.size) { index ->
            val tagId = HailData.tags[index].second
            when (selectedList.count { tagId in it.tagIdList }) {
                selectedList.size -> ToggleableState.On
                0 -> ToggleableState.Off
                else -> ToggleableState.Indeterminate
            }
        }
        val states = mutableStateListOf(*initialStates)
        MaterialAlertDialogBuilder(activity).setTitle(R.string.action_tag_set).setView(ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { TriStateTagList(initialStates, states) } }
        }).setPositiveButton(android.R.string.ok) { _, _ ->
            selectedList.forEach {
                states.forEachIndexed { index, state ->
                    val tagId = HailData.tags[index].second
                    when (state) {
                        ToggleableState.On -> {
                            if (tagId !in it.tagIdList) it.tagIdList.add(tagId)
                        }

                        ToggleableState.Off -> it.tagIdList.remove(tagId)
                        ToggleableState.Indeterminate -> {}
                    }
                }
                if (it.tagIdList.isEmpty()) removeCheckedApp(it.packageName, false)
            }
            HailData.saveApps()
            deselect()
        }.setNeutralButton(R.string.action_tag_add) { _, _ ->
            showTagDialog(selectedList)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    @Composable
    private fun TriStateTagList(initialStates: Array<ToggleableState>, states: MutableList<ToggleableState>) = Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        HailData.tags.forEachIndexed { index, tag ->
            Row(modifier = Modifier.fillMaxWidth().clickable {
                states[index] = if (initialStates[index] == ToggleableState.Indeterminate) when (states[index]) {
                    ToggleableState.On -> ToggleableState.Off
                    ToggleableState.Off -> ToggleableState.Indeterminate
                    ToggleableState.Indeterminate -> ToggleableState.On
                }
                else if (states[index] == ToggleableState.On) ToggleableState.Off
                else ToggleableState.On
            }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = states[index],
                    onClick = null,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary)
                )
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = tag.first,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }

    private fun launchApp(packageName: String) {
        if (AppStateCache.stateOrRefresh(packageName) == AppInfo.State.FROZEN && AppManager.setAppFrozen(
                packageName,
                false
            )
        ) {
            updateStateSnapshot(listOf(packageName))
        }
        app.packageManager.getLaunchIntentForPackage(packageName)?.let {
            HShortcuts.addDynamicShortcut(packageName)
            startActivity(it)
            clearHomeSearch()
            clearHomeSearchFocus()
        } ?: HUI.showToast(R.string.activity_not_found)
    }

    private fun setListFrozen(
        frozen: Boolean,
        list: List<AppInfo> = HailData.checkedList,
        updateList: Boolean = true,
        skipWhitelisted: Boolean = true,
        onDone: (() -> Unit)? = null
    ) {
        if (HailData.workingMode == HailData.MODE_DEFAULT) {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.msg_guide)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        } else if (HailData.workingMode == HailData.MODE_SHIZUKU_HIDE) {
            runCatching { HShizuku.isRoot }.onSuccess {
                if (!it) {
                    MaterialAlertDialogBuilder(activity).setMessage(R.string.shizuku_hide_adb)
                        .setPositiveButton(android.R.string.ok, null).show()
                    return
                }
            }
        }
        val targetList = list.filterNot { skipWhitelisted && it.whitelisted }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val targetState = if (frozen) AppInfo.State.FROZEN else AppInfo.State.UNFROZEN
                    val filtered = targetList.filter {
                        when (AppStateCache.stateOf(it.packageName)) {
                            null -> true
                            AppInfo.State.NOT_FOUND -> false
                            targetState -> false
                            else -> true
                        }
                    }
                    AppManager.setListFrozenResult(frozen, filtered, skipWhitelisted)
                }
            }.getOrElse {
                HLog.e(it)
                null
            }
            when (val toastArg = result?.toastArg) {
                null -> HUI.showToast(R.string.permission_denied)
                else -> {
                    if (result.successPackages.isNotEmpty()) {
                        updateStateSnapshot(result.successPackages)
                        app.setAutoFreezeService()
                    } else if (updateList) {
                        updateStateSnapshot()
                    }
                    HUI.showToast(
                        if (frozen) R.string.msg_freeze else R.string.msg_unfreeze, toastArg
                    )
                }
            }
            onDone?.invoke()
        }
    }

    private fun showTagDialog(list: List<AppInfo>? = null) {
        val binding = DialogInputBinding.inflate(layoutInflater)
        binding.inputLayout.setHint(R.string.tag)
        list ?: binding.editText.setText(tag.first)
        MaterialAlertDialogBuilder(activity).setTitle(if (list != null) R.string.action_tag_add else R.string.action_tag_set)
            .setView(binding.root).setPositiveButton(android.R.string.ok) { _, _ ->
                val tagName = binding.editText.text.toString()
                val tagId = tagName.hashCode()
                if (HailData.tags.any { it.first == tagName || it.second == tagId }) return@setPositiveButton
                if (list != null) { // Add tag
                    HailData.tags.add(tagName to tagId)
                    adapter.notifyItemInserted(adapter.itemCount - 1)
                    if (query.isEmpty() && tabs.tabCount == 2) tabs.isVisible = true
                    if (list == selectedList) triStateTagDialog() else tagDialog(list.first())
                } else { // Rename tag
                    val position = tabs.selectedTabPosition
                    val defaultTab = position == 0
                    val oldTagId = HailData.tags[position].second
                    HailData.tags[position] = tagName to if (defaultTab) 0 else tagId
                    if (!defaultTab) {
                        pagerAdapter.appList.forEach {
                            val index = it.tagIdList.indexOf(oldTagId)
                            if (index != -1) it.tagIdList[index] = tagId
                        }
                        HailData.saveApps()
                    }
                    adapter.notifyItemChanged(position)
                }
                HailData.saveTags()
            }.apply {
                val position = tabs.selectedTabPosition
                if (list != null || position == 0) return@apply
                setNeutralButton(R.string.action_tag_remove) { _, _ ->
                    val tagIdToRemove = HailData.tags[position].second
                    pagerAdapter.appList.forEach {
                        if (it.tagIdList.remove(tagIdToRemove) && it.tagIdList.isEmpty()) {
                            removeCheckedApp(it.packageName, false)
                        }
                    }
                    HailData.tags.removeAt(position)
                    adapter.notifyItemRemoved(position)
                    if (tabs.tabCount == 1) tabs.isVisible = false
                    HailData.saveApps()
                    HailData.saveTags()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun exportToClipboard(list: List<AppInfo>) {
        if (list.isEmpty()) return
        HUI.copyText(if (list.size > 1) JSONArray().run {
            list.forEach { put(it.packageName) }
            toString()
        } else list[0].packageName)
        HUI.showToast(
            R.string.msg_exported, if (list.size > 1) list.size.toString() else list[0].name
        )
    }

    private fun importFromClipboard() = runCatching {
        val str = HUI.pasteText() ?: throw IllegalArgumentException()
        val json = if (str.contains('[')) JSONArray(
            str.substring(
                str.indexOf('[')..str.indexOf(']', str.indexOf('['))
            )
        )
        else JSONArray().put(str)
        var i = 0
        for (index in 0 until json.length()) {
            val pkg = json.getString(index)
            if (HPackages.getApplicationInfoOrNull(pkg) != null && !HailData.isChecked(pkg)) {
                HailData.addCheckedApp(pkg, tag.second, false)
                i++
            }
        }
        if (i > 0) {
            HailData.saveApps()
            updateCurrentList()
        }
        HUI.showToast(getString(R.string.msg_imported, i.toString()))
    }

    private suspend fun importFrozenApp() = withContext(Dispatchers.IO) {
        HPackages.getInstalledApplications().map { it.packageName }
            .filter { AppManager.isAppFrozen(it) && !HailData.isChecked(it) }
            .onEach { HailData.addCheckedApp(it, tag.second, false) }.size
    }

    private fun removeCheckedApp(packageName: String, saveApps: Boolean = true) {
        HailData.removeCheckedApp(packageName, saveApps)
        if (saveApps) updateCurrentList()
    }

    private fun MenuItem.updateIcon() = icon?.setTint(
        MaterialColors.getColor(
            activity.findViewById(R.id.toolbar),
            if (multiselect) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface
        )
    )

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> updateCurrentList()

            R.id.action_multiselect -> {
                multiselect = !multiselect
                item.updateIcon()
                if (multiselect) {
                    updateBarTitle()
                    HUI.showToast(R.string.tap_to_select)
                } else deselect()
            }

            R.id.action_freeze_current -> setListFrozen(true, pagerAdapter.appList.filterNot { it.whitelisted })

            R.id.action_unfreeze_current -> setListFrozen(false, pagerAdapter.appList)
            R.id.action_freeze_all -> setListFrozen(true)
            R.id.action_unfreeze_all -> setListFrozen(false)
            R.id.action_freeze_non_whitelisted -> setListFrozen(true, HailData.checkedList.filterNot { it.whitelisted })

            R.id.action_import_clipboard -> importFromClipboard()
            R.id.action_import_frozen -> lifecycleScope.launch {
                val size = importFrozenApp()
                if (size > 0) {
                    HailData.saveApps()
                    updateCurrentList()
                }
                HUI.showToast(getString(R.string.msg_imported, size.toString()))
            }

            R.id.action_export_current -> exportToClipboard(pagerAdapter.appList)
            R.id.action_export_all -> exportToClipboard(HailData.checkedList)
        }
        return false
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_home, menu)
        val searchInput = activity.homeSearchInput
        searchTextWatcher?.let(searchInput::removeTextChangedListener)
        if (searchInput.text.toString() != query) {
            searchInput.setText(query)
            searchInput.setSelection(searchInput.text?.length ?: 0)
        }
        tabs.isVisible = query.isEmpty() && tabs.tabCount > 1
        activity.homeSearchBar.isVisible = true
        updateHomeSearchClear(searchInput)
        activity.homeSearchIcon.setOnClickListener { focusHomeSearch(searchInput) }
        activity.homeSearchBar.setOnClickListener { focusHomeSearch(searchInput) }
        setupT9EditText(searchInput, binding.recyclerView)
        setupSearchBackCallback()
        searchInput.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP || query.isEmpty()) return@setOnTouchListener false
            val drawable = searchInput.compoundDrawablesRelative[2] ?: return@setOnTouchListener false
            val drawableWidth = drawable.intrinsicWidth.coerceAtLeast(0)
            val hitClear = if (searchInput.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                event.x <= searchInput.paddingLeft + drawableWidth + searchInput.compoundDrawablePadding
            } else {
                event.x >= searchInput.width - searchInput.paddingRight - drawableWidth - searchInput.compoundDrawablePadding
            }
            if (!hitClear) return@setOnTouchListener false
            clearHomeSearch()
            focusHomeSearch(searchInput)
            true
        }
        searchTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val newText = s?.toString().orEmpty()
                if (query == newText) return
                query = newText
                tabs.isVisible = query.isEmpty() && tabs.tabCount > 1
                updateHomeSearchClear(searchInput)
                updateCurrentList()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        }.also(searchInput::addTextChangedListener)
        updateBarTitle()
        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onPause() {
        searchTextWatcher?.let(activity.homeSearchInput::removeTextChangedListener)
        searchTextWatcher = null
        clearHomeSearchFocus()
        super.onPause()
    }

    override fun onDestroyView() {
        searchTextWatcher?.let(activity.homeSearchInput::removeTextChangedListener)
        searchTextWatcher = null
        searchBackCallback?.remove()
        searchBackCallback = null
        updateCurrentListJob?.cancel()
        updateCurrentListJob = null
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }

    private fun focusHomeSearch(searchInput: EditText) {
        searchInput.requestFocus()
        if (HailData.nineKeySearch) return
        searchInput.post {
            searchInput.context.getSystemService<InputMethodManager>()
                ?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSearchBackCallback() {
        searchBackCallback?.remove()
        searchBackCallback = object : OnBackPressedCallback(query.isNotEmpty()) {
            override fun handleOnBackPressed() {
                clearHomeSearch()
                clearHomeSearchFocus()
            }
        }.also {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
        }
    }

    private fun clearHomeSearch() {
        val searchInput = activity.homeSearchInput
        if (searchInput.text.isNotEmpty()) searchInput.text.clear()
        if (query.isEmpty()) return
        query = ""
        tabs.isVisible = tabs.tabCount > 1
        updateHomeSearchClear(searchInput)
        updateCurrentList()
    }

    private fun updateHomeSearchClear(searchInput: EditText) {
        val icon = if (query.isEmpty()) 0 else R.drawable.ic_outline_close
        searchInput.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icon, 0)
        searchInput.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.padding_small)
        searchBackCallback?.isEnabled = query.isNotEmpty()
    }

    private fun clearHomeSearchFocus() {
        val searchInput = activity.homeSearchInput
        searchInput.clearFocus()
        hideT9KeyboardFor(searchInput)
        searchInput.context.getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }
}
