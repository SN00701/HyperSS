package com.hyperss.app.ui.project

import android.app.Application
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hyperss.app.data.RustBridge
import com.hyperss.app.data.RustRepository
import com.hyperss.app.util.PdfUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.hyperss_core.ImageInfo
import java.io.File
import java.io.FileOutputStream

class ProjectDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RustRepository()

    private val _projectId = MutableStateFlow(-1L)
    val projectId: StateFlow<Long> = _projectId.asStateFlow()

    private val _images = MutableStateFlow<List<ImageInfo>>(emptyList())
    val images: StateFlow<List<ImageInfo>> = _images.asStateFlow()

    /** 图片列表排序：false = 按命名序号从小到大，true = 从大到小。 */
    private val _sortDesc = MutableStateFlow(false)
    val sortDesc: StateFlow<Boolean> = _sortDesc.asStateFlow()

    fun toggleSort() {
        _sortDesc.value = !_sortDesc.value
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(projectId: Long) {
        if (projectId <= 0) return
        _projectId.value = projectId
        refresh()
    }

    fun refresh() {
        val id = _projectId.value
        if (id <= 0) return
        viewModelScope.launch {
            _busy.value = true
            try {
                _images.value = repo.listImages(id)
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
            } finally {
                _busy.value = false
            }
        }
    }

    fun toggleSelect(id: Long) {
        val s = _selected.value.toMutableSet()
        if (!s.add(id)) s.remove(id)
        _selected.value = s
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun selectAll() {
        _selected.value = _images.value.map { it.id }.toSet()
    }

    /** 全部已选则取消全选，否则全选。 */
    fun toggleSelectAll() {
        val all = _images.value.map { it.id }.toSet()
        _selected.value = if (_selected.value == all) emptySet() else all
    }

    fun isBatchMode(): Boolean = _selected.value.isNotEmpty()

    fun deleteSelected(onDone: (Boolean) -> Unit = {}) {
        val ids = _selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.deleteImages(ids)
                _selected.value = emptySet()
                refresh()
                onDone(true)
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
                onDone(false)
            }
        }
    }

    fun renameAlias(id: Long, alias: String?, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                repo.renameAlias(id, alias?.trim()?.ifEmpty { null })
                refresh()
                onDone(true)
            } catch (e: Throwable) {
                _error.value = repo.describe(e)
                onDone(false)
            }
        }
    }

    fun imageFile(image: ImageInfo): File =
        File(RustBridge.storageRoot(getApplication()), image.filePath)

    /** 批量导出选中图片到系统相册，返回成功数量。 */
    fun exportImagesToGallery(images: List<ImageInfo>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            var ok = 0
            for (image in images) {
                try {
                    val file = imageFile(image)
                    if (!file.exists()) continue
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, image.fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HyperSS")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = getApplication<Application>().contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: run {
                        resolver.delete(uri, null, null)
                        continue
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ok++
                } catch (e: Throwable) {
                    _error.value = e.message ?: "导出失败"
                }
            }
            onDone(ok)
        }
    }

    /** 生成 PDF 布局预览（每页 perPage 张），在 IO 线程解码与排版。 */
    fun buildPdfPreview(images: List<ImageInfo>, perPage: Int, onDone: (List<android.graphics.Bitmap>?) -> Unit) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmps = images.mapNotNull { PdfUtils.decodeBounded(imageFile(it).absolutePath) }
                    if (bmps.isEmpty()) null else PdfUtils.buildPreview(bmps, perPage)
                }.getOrNull()
            }
            onDone(result)
        }
    }

    /** 生成并导出 PDF 到 Download/HyperSSDL。 */
    fun exportImagesToPdf(images: List<ImageInfo>, perPage: Int, name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bmps = images.mapNotNull { PdfUtils.decodeBounded(imageFile(it).absolutePath) }
                    val bytes = PdfUtils.buildPdf(bmps, perPage) ?: return@runCatching false
                    PdfUtils.exportToDownloads(getApplication(), bytes, name) != null
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    /** 导出到系统相册（MediaStore 插入，复制而非移动，无需存储权限）。 */
    fun exportToGallery(image: ImageInfo, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val file = imageFile(image)
                if (!file.exists()) {
                    onDone(false)
                    return@launch
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, image.fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HyperSS")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    onDone(false)
                    return@launch
                }
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    onDone(false)
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                // MediaStore 插入即完成索引，无需再发媒体扫描广播（Q+ 已废弃且无效）
                onDone(true)
            } catch (e: Throwable) {
                _error.value = e.message ?: "导出失败"
                onDone(false)
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }
}