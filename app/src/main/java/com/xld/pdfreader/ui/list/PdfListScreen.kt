package com.xld.pdfreader.ui.list

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.pdfreader.R
import com.xld.pdfreader.appContainer
import com.xld.pdfreader.ui.list.components.PdfCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfListScreen(
    onOpenDocument: (String) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val viewModel: PdfListViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PdfListViewModel(context.appContainer.repository) }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val addLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importDocuments(uris) }

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var deleteMenuExpanded by remember { mutableStateOf(false) }

    // 一次性事件 → Snackbar
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is ListEvent.ImportFinished -> context.getString(
                    R.string.snackbar_import_result, event.added, event.skipped
                )
                is ListEvent.RecordsDeleted -> context.getString(
                    R.string.snackbar_records_deleted, event.count
                )
                is ListEvent.FilesDeleted -> {
                    val failed = context.getString(
                        R.string.snackbar_files_delete_failed, event.failedCount
                    )
                    if (event.failedCount > 0) {
                        context.getString(R.string.snackbar_files_deleted, event.deleted) + "，" + failed
                    } else {
                        context.getString(R.string.snackbar_files_deleted, event.deleted)
                    }
                }
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.exitSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelection() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.selection_exit)
                            )
                        }
                    },
                    title = {
                        Text(stringResource(R.string.selection_count, state.selectedIds.size))
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAllVisible() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_select_all),
                                contentDescription = stringResource(R.string.selection_select_all)
                            )
                        }
                        Box {
                            IconButton(onClick = { deleteMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.selection_delete)
                                )
                            }
                            DropdownMenu(
                                expanded = deleteMenuExpanded,
                                onDismissRequest = { deleteMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.selection_delete_records)) },
                                    onClick = {
                                        deleteMenuExpanded = false
                                        viewModel.requestDelete(files = false)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.selection_delete_files)) },
                                    onClick = {
                                        deleteMenuExpanded = false
                                        viewModel.requestDelete(files = true)
                                    }
                                )
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.list_title)) },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sort),
                                    contentDescription = stringResource(R.string.list_sort)
                                )
                            }
                            SortMenu(
                                expanded = sortMenuExpanded,
                                sort = state.sort,
                                onDismiss = { sortMenuExpanded = false },
                                onSortKeyChange = { viewModel.onSortKeyChange(it) },
                                onSortDirectionToggle = {
                                    sortMenuExpanded = false
                                    viewModel.onSortDirectionToggle()
                                }
                            )
                        }
                        IconButton(onClick = { addLauncher.launch(arrayOf("application/pdf")) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.list_add)
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!state.selectionMode) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onQueryChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.list_search_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }

            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                state.isEmpty -> EmptyState(
                    title = stringResource(R.string.list_empty_title),
                    text = stringResource(R.string.list_empty_text)
                )

                state.isSearchEmpty -> EmptyState(
                    title = stringResource(R.string.list_search_empty_text, state.searchQuery),
                    text = ""
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.documents, key = { it.id }) { doc ->
                        PdfCard(
                            document = doc,
                            selectionMode = state.selectionMode,
                            selected = doc.id in state.selectedIds,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelect(doc.id)
                                else onOpenDocument(doc.id)
                            },
                            onLongClick = { viewModel.enterSelection(doc.id) }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = {
                Text(
                    stringResource(
                        if (pending.files) R.string.selection_delete_files
                        else R.string.selection_delete_records
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (pending.files) R.string.selection_confirm_delete_files
                        else R.string.selection_confirm_delete_records,
                        pending.count
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(stringResource(R.string.selection_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(stringResource(R.string.selection_cancel))
                }
            }
        )
    }
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    sort: SortSettings,
    onDismiss: () -> Unit,
    onSortKeyChange: (SortKey) -> Unit,
    onSortDirectionToggle: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortKey.entries.forEach { key ->
            val label = when (key) {
                SortKey.TIME -> stringResource(R.string.list_sort_time)
                SortKey.NAME -> stringResource(R.string.list_sort_name)
                SortKey.SIZE -> stringResource(R.string.list_sort_size)
            }
            DropdownMenuItem(
                text = {
                    Text(
                        if (sort.key == key) {
                            label + " · " + stringResource(
                                if (sort.ascending) R.string.list_sort_asc else R.string.list_sort_desc
                            )
                        } else label
                    )
                },
                onClick = {
                    if (sort.key == key) onSortDirectionToggle()
                    else onSortKeyChange(key)
                }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(
                        if (sort.ascending) R.string.list_sort_asc else R.string.list_sort_desc
                    )
                )
            },
            onClick = onSortDirectionToggle
        )
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_description),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.size(16.dp)
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (text.isNotEmpty()) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
