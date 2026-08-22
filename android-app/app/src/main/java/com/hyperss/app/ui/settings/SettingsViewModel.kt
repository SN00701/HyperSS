package com.hyperss.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperss.app.data.RustRepository
import com.hyperss.app.util.Permissions
import com.hyperss.app.util.Settings
import com.hyperss.app.util.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val hasOverlay: Boolean = false,
    val hasNotifications: Boolean = true,
    val hasAccessibility: Boolean = false,
    val language: String = "system",
    val theme: String = "system",
    val duplicateRate: Int = 50,
    val version: String = "",
)

/** 设置页手动检查更新的状态。 */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val version: String, val notes: String) : UpdateUi
    data class Failed(val reason: String) : UpdateUi
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()

    private val _uiState = MutableStateFlow(SettingsUiState(version = repo.version()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _updateState.asStateFlow()

    private var checkJob: Job? = null

    /** 手动检查 GitHub 最新 Release。 */
    fun checkUpdate() {
        if (_updateState.value == UpdateUi.Checking) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _updateState.value = UpdateUi.Checking
            _updateState.value = when (val r = UpdateChecker.check(repo.version())) {
                is UpdateChecker.Result.UpdateAvailable -> UpdateUi.Available(r.version, r.notes)
                UpdateChecker.Result.UpToDate -> UpdateUi.UpToDate
                is UpdateChecker.Result.Failed -> UpdateUi.Failed(r.reason)
            }
        }
    }

    /** 关闭检查结果对话框（「稍后」时由界面记录跳过版本）。 */
    fun dismissUpdateDialog() {
        _updateState.value = UpdateUi.Idle
    }

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
