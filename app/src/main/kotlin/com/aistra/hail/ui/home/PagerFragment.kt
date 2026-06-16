package com.aistra.hail.ui.home

import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.EditText
import androidx.appcompat.widget.SearchView
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.Collator

class PagerFragment : MainFragment(), PagerAdapter.OnItemClickListener, PagerAdapter.OnItemLongClickListener,
    MenuProvider {
    private var query: String = String()
    private var _binding: FragmentPagerBinding? = null
    private val binding get() = _binding!!
    private lateinit var pagerAdapter: PagerAdapter
    private var fastScrollPositions: Map<Char, Int> = emptyMap()
    private var pendingCurrentListUpdate = false
    private val nameCollator = Collator.getInstance()
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
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            consumePendingCurrentListUpdate()
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
            applyDefaultInsetter { paddingRelative(isRtl, bottom = isLandscape) }

        }

        binding.refresh.apply {
            setOnRefreshListener {
                updateCurrentList()
                binding.refresh.isRefreshing = false
            }
            applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true) }
        }
        setupFastScroller(binding.fastScrollerStart, AlphabetFastScroller.Placement.START)
        setupFastScroller(binding.fastScrollerEnd, AlphabetFastScroller.Placement.END)
        setupFastScroller(binding.fastScrollerBottom, AlphabetFastScroller.Placement.BOTTOM)
        AppStateCache.updates.observe(viewLifecycleOwner) {
            updateStateSnapshot(it.packageNames)
            app.setAutoFreezeService()
        }
        return binding.root
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

    private fun updateCurrentList() = HailData.checkedList.filter {
        if (query.isEmpty()) tag.second in it.tagIdList
        else ((HailData.nineKeySearch && NineKeySearch.search(
            query, it.packageName, it.name.toString()
        )) || FuzzySearch.search(it.packageName, query) || FuzzySearch.search(
            it.name.toString(), query
        ) || PinyinSearch.searchPinyinAll(it.name.toString(), query))
    }.map {
        PagerAdapter.AppEntry(
            info = it,
            primaryLetter = AlphabetIndex.primaryLetter(it.name),
            sortKey = AlphabetIndex.sortKey(it.name)
        )
    }.sortedWith(::compareAppEntries).let {
        binding.empty.isVisible = it.isEmpty()
        val packages = it.map { entry -> entry.info.packageName }
        pagerAdapter.setStateSnapshot(AppStateCache.statesFor(packages))
        val spanCount = (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        pagerAdapter.submitAppEntries(
            it,
            spanCount,
            tailSpacerRows = 1,
            tailSpacerHeight = binding.recyclerView.height.takeIf { height -> height > 0 }
                ?: resources.displayMetrics.heightPixels
        )
        AppStateCache.primeAsync(packages)
        updateFastScroller()
        app.setAutoFreezeService()
    }

    private fun compareAppEntries(a: PagerAdapter.AppEntry, b: PagerAdapter.AppEntry): Int = when {
        a.info.pinned && !b.info.pinned -> -1
        b.info.pinned && !a.info.pinned -> 1
        else -> {
            val keyResult = a.sortKey.compareTo(b.sortKey, true)
            if (keyResult != 0) keyResult else nameCollator.compare(a.info.name, b.info.name)
        }
    }

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
        val position = fastScrollPositions[letter] ?: return
        pagerAdapter.setActiveLetter(letter)
        (binding.recyclerView.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(position, 0)
        activity.fab.hide()
    }

    private fun setupFastScroller(view: AlphabetFastScroller, placement: AlphabetFastScroller.Placement) {
        view.setPlacement(placement)
        view.onLetterSelected = ::scrollToLetter
        view.onLetterCleared = { pagerAdapter.setActiveLetter(null) }
        when (placement) {
            AlphabetFastScroller.Placement.START ->
                view.applyDefaultInsetter { marginRelative(isRtl, start = true, bottom = isLandscape) }

            AlphabetFastScroller.Placement.END ->
                view.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }

            AlphabetFastScroller.Placement.BOTTOM ->
                view.applyDefaultInsetter { marginRelative(isRtl, start = !isLandscape, end = true, bottom = isLandscape) }
        }
    }

    private fun fastScrollers() = listOf(
        binding.fastScrollerStart,
        binding.fastScrollerEnd,
        binding.fastScrollerBottom
    )

    private fun activeFastScrollers() = if (isLandscape) {
        listOf(binding.fastScrollerBottom)
    } else {
        listOf(binding.fastScrollerStart, binding.fastScrollerEnd)
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
        activity.supportActionBar?.title =
            if (multiselect) getString(R.string.msg_selected, selectedList.size.toString())
            else getString(R.string.app_name)
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
                1 -> setListFrozen(!frozen, listOf(info))
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
                    setListFrozen(false, listOf(info), false) {
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
                    setListFrozen(true, selectedList, false) { deselect() }
                }

                1 -> {
                    setListFrozen(false, selectedList, false) { deselect() }
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
                    setListFrozen(false, selected, false) {
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
        } ?: HUI.showToast(R.string.activity_not_found)
    }

    private fun setListFrozen(
        frozen: Boolean,
        list: List<AppInfo> = HailData.checkedList,
        updateList: Boolean = true,
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
        val targetList = list.toList()
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
                    AppManager.setListFrozenResult(frozen, *filtered.toTypedArray())
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
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.maxWidth = resources.getDimensionPixelSize(R.dimen.home_search_width)
        searchView.queryHint = getString(R.string.action_search)
        searchView.setIconifiedByDefault(false)
        searchView.isIconified = false
        searchView.clearFocus()
        if (HailData.nineKeySearch) {
            val editText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            editText.inputType = InputType.TYPE_CLASS_PHONE
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                if (query == newText) return true
                query = newText
                tabs.isVisible = query.isEmpty() && tabs.tabCount > 1
                updateCurrentList()
                return true
            }

            override fun onQueryTextSubmit(query: String): Boolean = true
        })
        menu.findItem(R.id.action_multiselect).updateIcon()
    }

    override fun onDestroyView() {
        pagerAdapter.onDestroy()
        super.onDestroyView()
        _binding = null
    }
}
