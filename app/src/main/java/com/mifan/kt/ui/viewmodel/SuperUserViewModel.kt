package com.mifan.kt.ui.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mifan.kt.FanModeConstants
import com.mifan.kt.data.model.AppInfo
import com.mifan.kt.ui.FanRulesStore
import com.mifan.kt.ui.component.SearchStatus
import com.mifan.kt.ui.screen.superuser.GroupedApps
import com.mifan.kt.ui.screen.superuser.SuperUserUiState
import com.mifan.kt.ui.util.PinyinUtil
import com.mifan.kt.ui.util.ownerNameForUid
import com.mifan.kt.ui.util.pickPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

internal const val RECENTLY_INSTALLED_WINDOW_MILLIS = 60 * 60 * 1000L

enum class AppSortType {
    NAME, PACKAGE_NAME, INSTALL_TIME, UPDATE_TIME;

    companion object {
        fun fromOrdinal(ordinal: Int): AppSortType =
            entries.getOrElse(ordinal) { NAME }
    }
}

data class AppSortConfig(
    val sortType: AppSortType = AppSortType.NAME,
    val reversed: Boolean = false,
) {
    fun withType(type: AppSortType): AppSortConfig = copy(sortType = type)
    fun toggleReversed(): AppSortConfig = copy(reversed = !reversed)
}

internal fun buildRecentlyInstalledGroups(
    groups: List<GroupedApps>,
    nowMillis: Long = System.currentTimeMillis(),
): List<GroupedApps> {
    val cutoffMillis = nowMillis - RECENTLY_INSTALLED_WINDOW_MILLIS
    val collator = Collator.getInstance(Locale.getDefault())
    return groups.mapNotNull { group ->
        val latest = group.apps.maxOfOrNull { it.packageInfo.firstInstallTime } ?: return@mapNotNull null
        if (latest < cutoffMillis) null else group to latest
    }.sortedWith(
        compareByDescending<Pair<GroupedApps, Long>> { it.second }
            .thenBy(collator) { it.first.primary.label }
    ).map { it.first }
}

/**
 * KernelSU SuperUserViewModel structure; app list via PackageManager (HAS_CODE),
 * profiles replaced by fan rules.
 */
class SuperUserViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "SuperUserViewModel"
        private val appsLock = Any()
        private var cachedApps: List<AppInfo> = emptyList()
        val apps: List<AppInfo> get() = synchronized(appsLock) { cachedApps }
    }

    private val _uiState = MutableStateFlow(SuperUserUiState(isRefreshing = true))
    val uiState: StateFlow<SuperUserUiState> = _uiState.asStateFlow()
    private val refreshMutex = Mutex()
    private val searchQuery = MutableStateFlow("")
    private var sortJob: Job? = null
    var isNeedRefresh = false
        private set

    init {
        viewModelScope.launchSearchQueryCollector(searchQuery, ::applySearchText)
    }

    fun markNeedRefresh() { isNeedRefresh = true }
    fun initializePreferences() { /* defaults */ }

    fun updateSortConfig(config: AppSortConfig): Job {
        _uiState.update { it.copy(sortConfig = config) }
        sortJob?.cancel()
        return viewModelScope.launch {
            val current = _uiState.value.groupedApps
            if (current.isNotEmpty()) updateVisibleApps(current)
        }.also { sortJob = it }
    }

    fun toggleShowSystemApps(): Job {
        _uiState.update { it.copy(showSystemApps = !it.showSystemApps) }
        return refilter()
    }

    fun toggleShowOnlyPrimaryUserApps(): Job {
        _uiState.update { it.copy(showOnlyPrimaryUserApps = !it.showOnlyPrimaryUserApps) }
        return refilter()
    }

    private fun refilter(): Job = viewModelScope.launch {
        val grouped = withContext(Dispatchers.IO) { buildGroups(filterApps(apps)) }
        updateVisibleApps(grouped)
    }

    fun updateSearchStatus(status: SearchStatus) {
        val prev = _uiState.value.searchStatus
        _uiState.update { it.copy(searchStatus = status) }
        if (prev.searchText != status.searchText) searchQuery.value = status.searchText
    }

    fun updateSearchText(text: String) {
        updateSearchStatus(_uiState.value.searchStatus.copy(searchText = text))
    }

    fun setFanLevel(packageName: String, level: Int) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val previous = synchronized(appsLock) {
                apps.firstOrNull { it.packageName == packageName }?.fanLevel
                    ?: FanModeConstants.PER_APP_LEVEL_SMART
            }

            // Optimistic UI so WindowDropdown can dismiss without waiting on IO/root.
            synchronized(appsLock) {
                cachedApps = cachedApps.map {
                    if (it.packageName == packageName) it.copy(fanLevel = level) else it
                }
            }
            runCatching {
                val grouped = withContext(Dispatchers.Default) { buildGroups(filterApps(apps)) }
                updateVisibleApps(grouped)
            }.onFailure { Log.e(TAG, "setFanLevel UI update failed", it) }

            val persistOk = runCatching {
                withContext(Dispatchers.IO) {
                    FanRulesStore.setLevel(app, packageName, level)
                    // Confirm local prefs (always) or merged read still has the selection.
                    val stored = FanRulesStore.read(app)[packageName]
                        ?: FanModeConstants.PER_APP_LEVEL_SMART
                    stored == level
                }
            }.onFailure {
                Log.e(TAG, "setFanLevel persist failed pkg=$packageName level=$level", it)
            }.getOrDefault(false)

            if (!persistOk) {
                Log.e(TAG, "setFanLevel not durable pkg=$packageName level=$level; revert UI to $previous")
                synchronized(appsLock) {
                    cachedApps = cachedApps.map {
                        if (it.packageName == packageName) it.copy(fanLevel = previous) else it
                    }
                }
                runCatching {
                    val grouped = withContext(Dispatchers.Default) { buildGroups(filterApps(apps)) }
                    updateVisibleApps(grouped)
                }
            }
        }
    }

    private fun filterSearchResults(groups: List<GroupedApps>, text: String): List<GroupedApps> {
        if (text.isEmpty()) return emptyList()
        return groups.mapNotNull { group ->
            val matched = group.apps.filter {
                it.label.contains(text, true) ||
                    it.packageName.contains(text, true) ||
                    PinyinUtil.toPinyin(it.label).contains(text, true)
            }.mapTo(linkedSetOf()) { it.packageName }
            if (matched.isEmpty()) null
            else group.copy(
                apps = group.apps.sortedWith(compareByDescending { it.packageName in matched }),
                matchedPackageNames = matched,
            )
        }
    }

    private suspend fun applySearchText(text: String) {
        _uiState.update {
            it.copy(searchStatus = it.searchStatus.copy(resultStatus = searchLoadingStatusFor(text)))
        }
        if (text.isEmpty()) {
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    searchStatus = it.searchStatus.copy(resultStatus = SearchStatus.ResultStatus.DEFAULT),
                )
            }
            return
        }
        val result = withContext(Dispatchers.IO) {
            filterSearchResults(_uiState.value.groupedApps, text)
        }
        _uiState.update {
            it.copy(
                searchResults = result,
                searchStatus = it.searchStatus.copy(resultStatus = searchResultStatusFor(text, result.isEmpty())),
            )
        }
    }

    private suspend fun updateVisibleApps(grouped: List<GroupedApps>, resort: Boolean = true) {
        val sortConfig = _uiState.value.sortConfig
        val searchText = _uiState.value.searchStatus.searchText
        val triple = withContext(Dispatchers.IO) {
            val s = if (resort) sortGroups(grouped, sortConfig) else grouped
            Triple(s, filterSearchResults(s, searchText), buildRecentlyInstalledGroups(s))
        }
        _uiState.update {
            it.copy(
                groupedApps = triple.first,
                searchResults = triple.second,
                recentlyInstalledResults = triple.third,
                searchStatus = it.searchStatus.copy(
                    resultStatus = searchResultStatusFor(searchText, triple.second.isEmpty())
                ),
            )
        }
    }

    private fun filterApps(list: List<AppInfo>): List<AppInfo> {
        val st = _uiState.value
        val self = getApplication<Application>().packageName
        return list.filter {
            if (it.packageName == self) return@filter false
            if (it.hasCustomProfile) return@filter true
            val userOk = !st.showOnlyPrimaryUserApps || it.uid / 100000 == 0
            val sys = it.packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM != 0
            userOk && (st.showSystemApps || !sys)
        }
    }

    private fun buildGroups(apps: List<AppInfo>): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val cmp = compareBy<AppInfo> { if (it.hasCustomProfile) 0 else 1 }
            .thenBy(collator) { it.label }
        return apps.groupBy { it.uid }.map { (uid, list) ->
            val sorted = list.sortedWith(cmp)
            val primary = pickPrimary(sorted)
            GroupedApps(
                uid = uid,
                apps = sorted,
                primary = primary,
                anyAllowSu = primary.hasCustomProfile,
                anyCustom = primary.hasCustomProfile,
                shouldUmount = false,
                ownerName = if (sorted.size > 1) ownerNameForUid(uid, sorted, getApplication()) else null,
            )
        }
    }

    private fun groupRank(g: GroupedApps): Int = when {
        g.anyAllowSu || g.anyCustom -> 0
        g.apps.size > 1 -> 1
        else -> 2
    }

    private fun sortGroups(groups: List<GroupedApps>, config: AppSortConfig): List<GroupedApps> {
        val collator = Collator.getInstance(Locale.getDefault())
        val base: Comparator<GroupedApps> = when (config.sortType) {
            AppSortType.PACKAGE_NAME -> compareBy { it.primary.packageName }
            AppSortType.INSTALL_TIME -> compareBy { it.primary.packageInfo.firstInstallTime }
            AppSortType.UPDATE_TIME -> compareBy { it.primary.packageInfo.lastUpdateTime }
            AppSortType.NAME -> Comparator { a, b -> collator.compare(a.primary.label, b.primary.label) }
        }
        val secondary = if (config.reversed) base.reversed() else base
        return groups.sortedWith { a, b ->
            val ra = groupRank(a)
            val rb = groupRank(b)
            if (ra != rb) ra - rb else secondary.compare(a, b)
        }
    }

    private fun loadAppsFromPm(): List<AppInfo> {
        val context = getApplication<Application>()
        val pm = context.packageManager
        // Dual-source: SharedPreferences (UI durable) + Settings.System (PowerKeeper).
        val rules = FanRulesStore.read(context)
        // Lighter than MATCH_UNINSTALLED_PACKAGES — enough for user-facing app list.
        val packages = pm.getInstalledPackages(0)
        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            // KernelSU SuperUserRepository: FLAG_HAS_CODE only
            if (ai.flags and ApplicationInfo.FLAG_HAS_CODE == 0) return@mapNotNull null
            AppInfo(
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
                packageInfo = pi,
                fanLevel = rules[pi.packageName] ?: FanModeConstants.PER_APP_LEVEL_SMART,
            )
        }
    }

    suspend fun fetchAppList() {
        refreshMutex.withLock {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            runCatching {
                // Phase 1: load packages on IO (heavy).
                val newApps = withContext(Dispatchers.IO) { loadAppsFromPm() }
                synchronized(appsLock) { cachedApps = newApps }
                // Phase 2: group/filter/sort still off main thread.
                val grouped = withContext(Dispatchers.Default) { buildGroups(filterApps(newApps)) }
                updateVisibleApps(grouped)
                _uiState.update {
                    it.copy(userIds = listOf(0), isRefreshing = false, hasLoaded = true)
                }
            }.onFailure { e ->
                Log.e(TAG, "fetchAppList failed", e)
                _uiState.update { it.copy(isRefreshing = false, hasLoaded = true, error = e) }
            }
            isNeedRefresh = false
        }
    }

    fun loadAppList(force: Boolean = false, resort: Boolean = true): Job =
        viewModelScope.launch {
            // Cache hit: show immediately without rescan.
            if (!force && !isNeedRefresh && _uiState.value.groupedApps.isNotEmpty()) {
                return@launch
            }
            if (!force && !isNeedRefresh && apps.isNotEmpty() && _uiState.value.groupedApps.isEmpty()) {
                // Rebuild visible list from memory cache.
                val grouped = withContext(Dispatchers.Default) { buildGroups(filterApps(apps)) }
                updateVisibleApps(grouped)
                _uiState.update { it.copy(isRefreshing = false, hasLoaded = true) }
                return@launch
            }
            fetchAppList()
        }
}
