package com.hyperss.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperss.app.data.RustRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.hyperss_core.ProjectInfo

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()

    private val _projects = MutableStateFlow<List<ProjectInfo>>(emptyList())
    val projects: StateFlow<List<ProjectInfo>> = _projects.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _projects.value = repo.listProjects()
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun createProject(prefix: String, displayName: String, onDone: (ProjectInfo?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val p = repo.createProject(prefix, displayName)
                refresh()
                onDone(p)
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
                onDone(null)
            }
        }
    }

    fun deleteProject(id: Long, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.deleteProject(id)
                refresh()
                onDone(true)
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
                onDone(false)
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}