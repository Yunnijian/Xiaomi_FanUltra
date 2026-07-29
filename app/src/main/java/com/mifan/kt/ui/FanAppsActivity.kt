package com.mifan.kt.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mifan.kt.ui.screen.superuser.SuperUserActions
import com.mifan.kt.ui.screen.superuser.SuperUserPagerMiuix
import com.mifan.kt.ui.theme.KernelSuMiuixTheme
import com.mifan.kt.ui.viewmodel.SuperUserViewModel

class FanAppsActivity : ComponentActivity() {
    private val viewModel: SuperUserViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SuperUserViewModel(application) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KernelSuMiuixTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // Lazy: paint scaffold first, load packages in background (do not join).
                LaunchedEffect(Unit) {
                    viewModel.initializePreferences()
                    viewModel.loadAppList()
                }
                Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                    SuperUserPagerMiuix(
                        uiState = uiState,
                        actions = SuperUserActions(
                            onRefresh = { viewModel.loadAppList(force = true) },
                            onSearchTextChange = viewModel::updateSearchText,
                            onSearchStatusChange = viewModel::updateSearchStatus,
                            onClearSearch = { viewModel.updateSearchText("") },
                            onToggleShowSystemApps = { viewModel.toggleShowSystemApps() },
                            onToggleShowOnlyPrimaryUserApps = { viewModel.toggleShowOnlyPrimaryUserApps() },
                            onUpdateSortConfig = { viewModel.updateSortConfig(it) },
                            onFanLevelChange = viewModel::setFanLevel,
                        ),
                        bottomInnerPadding = 0.dp,
                    )
                }
            }
        }
    }
}
