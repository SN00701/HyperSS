package com.hyperss.app.ui.project

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperss.app.R
import com.hyperss.app.data.RustRepository
import com.hyperss.app.util.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.hyperss_core.ProjectInfo

data class ProjectSettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val project: ProjectInfo? = null,
    val name: String = "",
    val mode: String = "AUTO_SCROLL",
    val stepDp: Int = 200,
    val loopCount: Int = 0,
    val maxFrames: Int = 10,
    val saving: Boolean = false,
    val saved: Boolean = false,
)

class ProjectSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()

    private val _uiState = MutableStateFlow(ProjectSettingsUiState())
    val uiState: StateFlow<ProjectSettingsUiState> = _uiState.asStateFlow()

    private var projectId = -1L

    fun load(id: Long) {
        if (id == projectId && _uiState.value.project != null) return
        projectId = id
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val p = repo.getProject(id)
                _uiState.value = ProjectSettingsUiState(
                    loading = false,
                    project = p,
                    name = p.displayName,
                    mode = Settings.getProjectMode(id),
                    stepDp = Settings.getProjectStepDp(id),
                    loopCount = Settings.getProjectLoopCount(id),
                    maxFrames = Settings.getProjectMaxFrames(id),
                )
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = repo.describe(e),
                )
            }
        }
    }

    fun updateName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun updateMode(value: String) {
        _uiState.value = _uiState.value.copy(mode = value)
    }

    fun updateStep(value: Int) {
        _uiState.value = _uiState.value.copy(stepDp = value.coerceIn(40, 1000))
    }

    fun updateLoopCount(value: Int) {
        _uiState.value = _uiState.value.copy(loopCount = value.coerceIn(0, 999))
    }

    fun updateMaxFrames(value: Int) {
        _uiState.value = _uiState.value.copy(maxFrames = value.coerceIn(3, 20))
    }

    fun save(onDone: (Boolean) -> Unit = {}) {
        val state = _uiState.value
        val trimmed = state.name.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = state.copy(
                error = getApplication<Application>().getString(R.string.project_name_empty),
            )
            return
        }
        _uiState.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                if (trimmed != state.project?.displayName) {
                    val updated = repo.renameProject(projectId, trimmed)
                    _uiState.value = _uiState.value.copy(project = updated, name = updated.displayName)
                }
                Settings.setProjectMode(projectId, state.mode)
                Settings.setProjectLoopCount(projectId, state.loopCount)
                Settings.setProjectMaxFrames(projectId, state.maxFrames)
                if (state.mode == "FIXED_STEP") {
                    Settings.setProjectStepDp(projectId, state.stepDp)
                }
                _uiState.value = _uiState.value.copy(saving = false, saved = true)
                onDone(true)
            } catch (e: Throwable) {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = repo.describe(e),
                )
                onDone(false)
            }
        }
    }

    fun consumeError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}