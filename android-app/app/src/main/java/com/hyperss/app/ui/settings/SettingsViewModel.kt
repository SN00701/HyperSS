package com.hyperss.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hyperss.app.data.RustRepository
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val hasOverlay: Boolean = false,
    val hasNotifications: Boolean = true,
    val hasAccessibility: Boolean = false,
    val language: String = "system",
    val theme: String = "system",
    val duplicateRate: Int = 50,
    val version: String = "",
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()

    private val _uiState = MutableStateFlow(SettingsUiState(version = repo.version()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun refresh() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            hasOverlay = Permissions.hasOverlay(app),
            hasNotifications = Permissions.hasNotifications(app),
            hasAccessibility = Permissions.hasAccessibility(app),
            language = Settings.language,
            theme = Settings.theme,
            duplicateRate = Settings.duplicateRate,
        )
    }

    fun setLanguage(value: String) {
        Settings.language = value
        refresh()
    }

    fun setTheme(value: String) {
        Settings.theme = value
        refresh()
    }

    fun setDuplicateRate(value: Int) {
        Settings.duplicateRate = value
        refresh()
    }
}