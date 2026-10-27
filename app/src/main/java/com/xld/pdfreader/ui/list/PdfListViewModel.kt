package com.xld.pdfreader.ui.list

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xld.pdfreader.data.PdfDocumentEntity
import com.xld.pdfreader.data.PdfRepository
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortKey { TIME, NAME, SIZE }

data class SortSettings(val key: SortKey = SortKey.TIME, val ascending: Boolean = false)

data class ListUiState(
    val isLoading: Boolean = true,
    /** 过滤 + 排序后的列表 */
    val documents: List<PdfDocumentEntity> = emptyList(),
    val sort: SortSettings = SortSettings(),
    val searchQuery: String = "",
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet()
) {
    /** 搜索有内容但无匹配结果（区别于空库） */
    val isSearchEmpty: Boolean
        get() = !isLoading && documents.isEmpty() && searchQuery.isNotBlank()

    val isEmpty: Boolean
        get() = !isLoading && documents.isEmpty() && searchQuery.isBlank()
}

sealed interface ListEvent {
    data class ImportFinished(val added: Int, val skipped: Int) : ListEvent
    data class RecordsDeleted(val count: Int) : ListEvent
    data class FilesDeleted(val deleted: Int, val failedCount: Int) : ListEvent
}

/** 待确认的删除操作 */
data class PendingDelete(val files: Boolean, val count: Int)

@OptIn(FlowPreview::class)
class PdfListViewModel(
    private val repository: PdfRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(SortSettings())
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow(true)

    private val _pendingDelete = MutableStateFlow<PendingDelete?>(null)
    val pendingDelete: StateFlow<PendingDelete?> = _pendingDelete

    private val _events = MutableSharedFlow<ListEvent>(extraBufferCapacity = 8)
    val events = _events

    val uiState: StateFlow<ListUiState> = combine(
        combine(repository.observeAll(), query.debounce(300), sort) { docs, q, s ->
            Triple(docs, q, s)
        },
        combine(selectionMode, selectedIds, loading) { mode, ids, isLoading ->
            Triple(mode, ids, isLoading)
        }
    ) { (docs, q, s), (mode, ids, isLoading) ->
        ListUiState(
            isLoading = isLoading,
            documents = filterAndSort(docs, q, s),
            sort = s,
            searchQuery = q,
            selectionMode = mode,
            selectedIds = ids
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListUiState())

    init {
        // Room Flow 首次发射后即视为加载完成
        viewModelScope.launch {
            repository.observeAll().collect { loading.value = false }
        }
    }

    private fun filterAndSort(
        docs: List<PdfDocumentEntity>,
        q: String,
        sort: SortSettings
    ): List<PdfDocumentEntity> {
        val filtered = if (q.isBlank()) docs
        else docs.filter { it.displayName.contains(q.trim(), ignoreCase = true) }

        val collator = Collator.getInstance(Locale.CHINA)
        val base: Comparator<PdfDocumentEntity> = when (sort.key) {
            SortKey.TIME -> compareBy { if (it.lastOpenedAt > 0) it.lastOpenedAt else it.addedAt }
            SortKey.NAME -> compareBy(collator) { it.displayName }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        val ordered = if (sort.ascending) base else base.reversed()
        return filtered.sortedWith(ordered)
    }

    // ---- 输入动作 ----

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    fun onSortKeyChange(key: SortKey) {
        sort.update { it.copy(key = key) }
    }

    fun onSortDirectionToggle() {
        sort.update { it.copy(ascending = !it.ascending) }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val result = repository.importDocuments(uris)
            _events.emit(ListEvent.ImportFinished(result.added, result.skipped))
        }
    }

    // ---- 多选模式 ----

    fun enterSelection(docId: String) {
        selectionMode.value = true
        selectedIds.value = setOf(docId)
    }

    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun toggleSelect(docId: String) {
        selectedIds.update { current ->
            if (docId in current) current - docId else current + docId
        }
    }

    fun selectAllVisible() {
        selectedIds.value = uiState.value.documents.mapTo(mutableSetOf()) { it.id }
    }

    // ---- 删除 ----

    fun requestDelete(files: Boolean) {
        val count = selectedIds.value.size
        if (count > 0) _pendingDelete.value = PendingDelete(files, count)
    }

    fun dismissDeleteConfirm() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val pending = _pendingDelete.value ?: return
        _pendingDelete.value = null
        val ids = selectedIds.value.toList()
        exitSelection()
        viewModelScope.launch {
            if (pending.files) {
                val result = repository.deleteFiles(ids)
                _events.emit(ListEvent.FilesDeleted(result.deleted, result.failed.size))
            } else {
                repository.deleteRecords(ids)
                _events.emit(ListEvent.RecordsDeleted(ids.size))
            }
        }
    }
}
