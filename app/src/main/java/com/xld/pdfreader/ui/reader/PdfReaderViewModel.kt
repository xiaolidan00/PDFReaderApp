package com.xld.pdfreader.ui.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xld.pdfreader.data.PdfDocumentEntity
import com.xld.pdfreader.data.PdfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录项，page 为 1-based 页码 */
data class TocItem(val title: String, val page: Int, val level: Int)

data class ReaderUiState(
    val doc: PdfDocumentEntity? = null,
    /** 1-based 当前页 */
    val page: Int = 1,
    val pageCount: Int = 0,
    val toc: List<TocItem> = emptyList(),
    val loadState: LoadState = LoadState.LOADING,
    /** 1-based 跳页请求，消费后置 null */
    val jumpTarget: Int? = null
) {
    enum class LoadState { LOADING, LOADED, FAILED }
}

@OptIn(FlowPreview::class)
class PdfReaderViewModel(
    private val repository: PdfRepository,
    private val docId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    /** 翻页进度防抖写库 */
    private val pendingProgress = MutableStateFlow<Int?>(null)

    /** 不随 VM 销毁取消的兜底作用域（返回前最后保存、移除记录） */
    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        viewModelScope.launch {
            val doc = repository.getDocument(docId)
            if (doc == null) {
                _uiState.update { it.copy(loadState = ReaderUiState.LoadState.FAILED) }
            } else {
                _uiState.update {
                    it.copy(
                        doc = doc,
                        page = doc.currentPage,
                        pageCount = doc.pageCount,
                        loadState = ReaderUiState.LoadState.LOADING
                    )
                }
            }
        }
        viewModelScope.launch {
            pendingProgress.filterNotNull().debounce(500).collect { page ->
                repository.updateProgress(docId, page)
            }
        }
    }

    // ---- PDFView 回调 ----

    fun onPageChanged(page1Based: Int, pageCount: Int) {
        _uiState.update { it.copy(page = page1Based, pageCount = pageCount) }
        pendingProgress.value = page1Based
    }

    fun onLoadComplete(pageCount: Int) {
        _uiState.update {
            it.copy(
                pageCount = pageCount,
                loadState = ReaderUiState.LoadState.LOADED
            )
        }
        viewModelScope.launch { repository.updatePageCount(docId, pageCount) }
    }

    fun onTocAvailable(toc: List<TocItem>) {
        _uiState.update { it.copy(toc = toc) }
    }

    fun onFailed() {
        _uiState.update { it.copy(loadState = ReaderUiState.LoadState.FAILED) }
        viewModelScope.launch { repository.markBroken(docId, true) }
    }

    // ---- 跳页 ----

    fun requestJump(page1Based: Int) {
        val count = _uiState.value.pageCount
        val clamped = if (count > 0) page1Based.coerceIn(1, count) else page1Based
        _uiState.update { it.copy(jumpTarget = clamped) }
    }

    fun clearJump() {
        _uiState.update { it.copy(jumpTarget = null) }
    }

    // ---- 生命周期兜底 ----

    /** 返回键/离开时立即落库，不等待防抖 */
    fun saveProgressNow() {
        val page = _uiState.value.page
        ioScope.launch { repository.updateProgress(docId, page) }
    }

    // ---- 失效文件处理 ----

    fun relocate(uri: Uri) {
        viewModelScope.launch {
            if (repository.relocateDocument(docId, uri)) {
                val doc = repository.getDocument(docId)
                _uiState.update {
                    it.copy(
                        doc = doc,
                        page = doc?.currentPage ?: 1,
                        pageCount = doc?.pageCount ?: 0,
                        toc = emptyList(),
                        loadState = ReaderUiState.LoadState.LOADING
                    )
                }
            }
        }
    }

    fun removeRecord(onDone: () -> Unit) {
        ioScope.launch {
            repository.deleteRecords(listOf(docId))
            withContext(Dispatchers.Main) { onDone() }
        }
    }
}
