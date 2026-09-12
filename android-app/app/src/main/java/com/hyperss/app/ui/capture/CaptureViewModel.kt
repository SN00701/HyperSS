package com.hyperss.app.ui.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperss.app.data.CaptureController
import com.hyperss.app.data.RustRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.hyperss_core.CaptureMode
import uniffi.hyperss_core.ProjectInfo

data class CaptureSetupState(
    val projects: List<ProjectInfo> = emptyList(),
    val selectedProjectId: Long = -1L,
    val mode: CaptureMode = CaptureMode.AUTO_SCROLL,
    val stepDp: Int = 200,
    val newProjectPrefix: String = "",
    val newProjectName: String = "",
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()
    private var creatingProject = false

    val setup = MutableStateFlow(CaptureSetupState())
    val controllerState: StateFlow<CaptureController.State> =
        CaptureController.state.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureController.State.Idle)

    fun loadProjects() {
        viewModelScope.launch {
            try {
                val projects = repo.listProjects()
                val current = setup.value
                val valid = projects.any { p -> p.id == current.selectedProjectId }
                setup.value = current.copy(
                    projects = projects,
                    selectedProjectId = if (current.selectedProjectId > 0 && valid) current.selectedProjectId
                    else projects.firstOrNull()?.id ?: -1L,
                )
            } catch (_: Throwable) {
            }
        }
    }

    fun selectProject(id: Long) {
        setup.value = setup.value.copy(selectedProjectId = id)
    }

    fun selectMode(mode: CaptureMode) {
        setup.value = setup.value.copy(mode = mode)
    }

    fun setStepDp(value: Int) {
        setup.value = setup.value.copy(stepDp = value.coerceIn(20, 2000))
    }

    fun setNewProjectPrefix(value: String) {
        setup.value = setup.value.copy(newProjectPrefix = value.trim())
    }

    fun setNewProjectName(value: String) {
        setup.value = setup.value.copy(newProjectName = value.trim())
    }

    fun ensureProject(onDone: (Long) -> Unit) {
        val s = setup.value
        if (s.selectedProjectId > 0) {
            onDone(s.selectedProjectId)
            return
        }
        if (creatingProject) return
        creatingProject = true
        viewModelScope.launch {
            try {
                val p = repo.createProject(
                    if (s.newProjectPrefix.isBlank()) "hs" else s.newProjectPrefix,
                    if (s.newProjectName.isBlank()) getApplication<android.app.Application>().getString(com.hyperss.app.R.string.capture_default_project_name) else s.newProjectName,
                )
                setup.value = setup.value.copy(selectedProjectId = p.id, projects = setup.value.projects + p)
                onDone(p.id)
            } catch (e: Throwable) {
                onDone(-1L)
            } finally {
                creatingProject = false
            }
        }
    }
}