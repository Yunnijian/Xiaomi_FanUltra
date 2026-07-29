package com.mifan.kt.ui.screen.superuser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mifan.kt.R
import com.mifan.kt.FanModeConstants
import com.mifan.kt.data.model.AppInfo
import com.mifan.kt.ui.component.AppIconImage
import com.mifan.kt.ui.component.ScrollToTopOnChange
import com.mifan.kt.ui.component.SearchStatus
import com.mifan.kt.ui.component.miuix.SearchBarFake
import com.mifan.kt.ui.component.miuix.SearchBox
import com.mifan.kt.ui.component.miuix.SearchPager
import com.mifan.kt.ui.theme.LocalEnableBlur
import com.mifan.kt.ui.theme.isInDarkTheme
import com.mifan.kt.ui.util.BlurredBar
import com.mifan.kt.ui.util.ownerNameForUid
import com.mifan.kt.ui.util.rememberBlurBackdrop
import com.mifan.kt.ui.viewmodel.AppSortType
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.mifan.kt.ui.viewmodel.AppSortConfig

@Composable
fun SuperUserPagerMiuix(
    uiState: SuperUserUiState,
    actions: SuperUserActions,
    bottomInnerPadding: Dp,
) {
    val searchStatus = uiState.searchStatus
    val enableBlur = LocalEnableBlur.current
    val density = LocalDensity.current

    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.per_app_fan_config_title),
                        navigationIcon = {},
                        actions = {
                            // MIUIX WindowIconDropdownMenu (window-level; works in standalone Activity).
                            WindowIconDropdownMenu(
                                entry = DropdownEntry(
                                    items = buildSortDropdownItems(
                                        sortConfig = uiState.sortConfig,
                                        onPickType = { type ->
                                            actions.onUpdateSortConfig(uiState.sortConfig.withType(type))
                                        },
                                        onToggleReverse = {
                                            actions.onUpdateSortConfig(uiState.sortConfig.toggleReversed())
                                        },
                                    ),
                                ),
                                collapseOnSelection = true,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Sort,
                                    tint = colorScheme.onSurface,
                                    contentDescription = stringResource(R.string.menu_sort),
                                )
                            }
                            WindowIconDropdownMenu(
                                entry = DropdownEntry(
                                    items = buildMoreDropdownItems(
                                        showSystemApps = uiState.showSystemApps,
                                        showOnlyPrimaryUserApps = uiState.showOnlyPrimaryUserApps,
                                        isMultiUser = uiState.userIds.size > 1,
                                        onToggleSystem = actions.onToggleShowSystemApps,
                                        onTogglePrimary = actions.onToggleShowOnlyPrimaryUserApps,
                                    ),
                                ),
                                collapseOnSelection = true,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    tint = colorScheme.onSurface,
                                    contentDescription = null,
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        bottomContent = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        with(density) {
                                            val newOffsetY = coordinates.positionInWindow().y.toDp()
                                            if (searchStatus.offsetY != newOffsetY) {
                                                actions.onSearchStatusChange(searchStatus.copy(offsetY = newOffsetY))
                                            }
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    actions.onSearchStatusChange(searchStatus.copy(current = SearchStatus.Status.EXPANDING))
                                                }
                                            }
                                        } else Modifier,
                                    ),
                            ) {
                                SearchBarFake(searchStatus.label, dynamicTopPadding)
                            }
                        }
                    )
                }
            }
        },
        popupHost = {
            val expandedSearchUids = remember { mutableStateOf(setOf<Int>()) }
            LaunchedEffect(uiState.searchResults) {
                expandedSearchUids.value = uiState.searchResults
                    .filter { it.apps.size > 1 }
                    .map { it.uid }
                    .toSet()
            }
            searchStatus.SearchPager(
                onSearchStatusChange = actions.onSearchStatusChange,
                defaultResult = {
                    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                    if (uiState.recentlyInstalledResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .overScrollVertical(),
                        ) {
                            item {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.recently_installed),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                                )
                            }
                            items(uiState.recentlyInstalledResults, key = { it.uid }, contentType = { "recent-group" }) { group ->
                                Column {
                                    GroupItem(
                                        group = group,
                                        onToggleExpand = {},
                                        onFanLevelChange = actions.onFanLevelChange,
                                    )
                                    AnimatedVisibility(
                                        visible = group.apps.size > 1,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Column {
                                            group.apps.forEach { app ->
                                                SimpleAppItem(app = app)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                        }
                                    }
                                }
                            }
                            item {
                                Spacer(Modifier.height(maxOf(bottomInnerPadding, imeBottomPadding)))
                            }
                        }
                    }
                },
                searchBarTopPadding = dynamicTopPadding,
            ) {
                val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                ) {
                    item {
                        Spacer(Modifier.height(6.dp))
                    }
                    items(uiState.searchResults, key = { it.uid }, contentType = { "group" }) { group ->
                        val expanded = expandedSearchUids.value.contains(group.uid)
                        AnimatedVisibility(
                            visible = uiState.searchResults.isNotEmpty(),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column {
                                GroupItem(
                                    group = group,
                                    onToggleExpand = {
                                        if (group.apps.size > 1) {
                                            expandedSearchUids.value =
                                                if (expanded) expandedSearchUids.value - group.uid else expandedSearchUids.value + group.uid
                                        }
                                    },
                                        onFanLevelChange = actions.onFanLevelChange,
                                )
                                AnimatedVisibility(
                                    visible = expanded && group.apps.size > 1,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column {
                                        group.apps.forEach { app ->
                                            SimpleAppItem(
                                                app = app,
                                                matched = group.matchedPackageNames.contains(app.packageName),
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(maxOf(bottomInnerPadding, imeBottomPadding)))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        searchStatus.SearchBox {
            val lazyListState = rememberLazyListState()
            val refreshTick = remember { mutableIntStateOf(0) }
            val latestGroupedApps = rememberUpdatedState(uiState.groupedApps)
            val latestRefreshing = rememberUpdatedState(uiState.isRefreshing)
            ScrollToTopOnChange(
                lazyListState,
                uiState.sortConfig,
                uiState.showSystemApps,
                uiState.showOnlyPrimaryUserApps,
                refreshTick.intValue,
                isBusy = { latestRefreshing.value },
            ) { latestGroupedApps.value }
            val pullToRefreshState = rememberPullToRefreshState()
            val refreshTexts = listOf(
                stringResource(R.string.refresh_pulling),
                stringResource(R.string.refresh_release),
                stringResource(R.string.refresh_refresh),
                stringResource(R.string.refresh_complete),
            )

            val expandedUids = remember { mutableStateOf(setOf<Int>()) }
            PullToRefresh(
                isRefreshing = uiState.isRefreshing,
                pullToRefreshState = pullToRefreshState,
                onRefresh = {
                    actions.onRefresh()
                    refreshTick.intValue++
                },
                refreshTexts = refreshTexts,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 6.dp,
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection)
                ),
            ) {
                Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 6.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection),
                            end = innerPadding.calculateEndPadding(layoutDirection)
                        ),
                        overscrollEffect = null,
                    ) {
                        items(uiState.groupedApps, key = { it.uid }, contentType = { "group" }) { group ->
                            val expanded = expandedUids.value.contains(group.uid)
                            Column {
                                GroupItem(
                                    group = group,
                                    onToggleExpand = {
                                        if (group.apps.size > 1) {
                                            expandedUids.value =
                                                if (expanded) expandedUids.value - group.uid else expandedUids.value + group.uid
                                        }
                                    }
                                ,
                                    onFanLevelChange = actions.onFanLevelChange,
                                )
                                AnimatedVisibility(
                                    visible = expanded && group.apps.size > 1,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column {
                                        group.apps.forEach { app ->
                                            SimpleAppItem(app = app)
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(bottomInnerPadding))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimpleAppItem(
    app: AppInfo,
    matched: Boolean = false,
) {
    Row {
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .width(6.dp)
                .height(24.dp)
                .align(Alignment.CenterVertically)
                .clip(RoundedCornerShape(16.dp))
                .background(if (matched) colorScheme.primary else colorScheme.primaryContainer)
        )
        Card(
            modifier = Modifier
                .padding(start = 6.dp, end = 12.dp, bottom = 6.dp)
        ) {
            BasicComponent(
                title = app.label,
                summary = app.packageName,
                startAction = {
                    AppIconImage(
                        packageInfo = app.packageInfo,
                        label = app.label,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .size(40.dp)
                    )
                },
                insideMargin = PaddingValues(horizontal = 9.dp)
            )
        }
    }
}

@Composable
private fun buildSortDropdownItems(
    sortConfig: AppSortConfig,
    onPickType: (AppSortType) -> Unit,
    onToggleReverse: () -> Unit,
): List<DropdownItem> {
    val entries = listOf(
        AppSortType.NAME to stringResource(R.string.sort_by_name),
        AppSortType.PACKAGE_NAME to stringResource(R.string.sort_by_package_name),
        AppSortType.INSTALL_TIME to stringResource(R.string.sort_by_install_time),
        AppSortType.UPDATE_TIME to stringResource(R.string.sort_by_update_time),
    )
    val items = entries.map { (type, title) ->
        DropdownItem(
            text = title,
            selected = sortConfig.sortType == type,
            onClick = { onPickType(type) },
        )
    }.toMutableList()
    items += DropdownItem(
        text = stringResource(R.string.sort_reverse),
        selected = sortConfig.reversed,
        onClick = onToggleReverse,
    )
    return items
}

@Composable
private fun buildMoreDropdownItems(
    showSystemApps: Boolean,
    showOnlyPrimaryUserApps: Boolean,
    isMultiUser: Boolean,
    onToggleSystem: () -> Unit,
    onTogglePrimary: () -> Unit,
): List<DropdownItem> {
    val items = mutableListOf(
        DropdownItem(
            text = stringResource(R.string.show_system_apps),
            selected = showSystemApps,
            onClick = onToggleSystem,
        )
    )
    if (isMultiUser) {
        items += DropdownItem(
            text = stringResource(R.string.show_only_primary_user_apps),
            selected = showOnlyPrimaryUserApps,
            onClick = onTogglePrimary,
        )
    }
    return items
}

@Composable
private fun GroupItem(
    group: GroupedApps,
    onToggleExpand: () -> Unit,
    onFanLevelChange: (String, Int) -> Unit,
) {
    // KernelSU App Profile: WindowDropdownPreference (value + ↕). No StatusTag chip.
    val options = remember { fanModeOptions() }
    val titles = remember { options.map { it.second } }
    val selectedIndex = options.indexOfFirst { it.first == group.primary.fanLevel }.let { if (it >= 0) it else 0 }
    val pkg = group.primary.packageName

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onLongPress = if (group.apps.size > 1) onToggleExpand else null,
    ) {
        WindowDropdownPreference(
            items = titles,
            selectedIndex = selectedIndex,
            title = if (group.apps.size > 1) ownerNameForUid(group.uid, group.apps) else group.primary.label,
            summary = if (group.apps.size > 1) {
                stringResource(R.string.group_contains_apps, group.apps.size)
            } else {
                pkg
            },
            startAction = {
                AppIconImage(
                    packageInfo = group.primary.packageInfo,
                    label = group.primary.label,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(48.dp)
                )
            },
            showValue = true,
        ) { index ->
            val level = options.getOrNull(index)?.first ?: return@WindowDropdownPreference
            onFanLevelChange(pkg, level)
        }
    }
}

private fun fanModeOptions(): List<Pair<Int, String>> = listOf(
    FanModeConstants.PER_APP_LEVEL_SMART to "智能调频",
    1 to "静谧模式",
    2 to "高速强冷",
    4 to "狂暴模式",
    FanModeConstants.PER_APP_LEVEL_OFF to "关闭风扇",
)
