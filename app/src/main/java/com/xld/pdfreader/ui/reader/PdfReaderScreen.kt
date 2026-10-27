package com.xld.pdfreader.ui.reader

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xld.pdfreader.R
import com.xld.pdfreader.appContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    docId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val viewModel: PdfReaderViewModel = viewModel(
        key = docId,
        factory = viewModelFactory {
            initializer { PdfReaderViewModel(context.appContainer.repository, docId) }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val relocateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.relocate(uri)
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 禁用 Drawer 边缘滑动手势：避免与 PDF 阅读区的缩放/平移手势冲突，
        // 目录只通过顶栏目录按钮打开。
        // 注意 material3 源码中 scrim（空白处）的点击收起回调被
        // `if (gesturesEnabled && ...)` 包裹，故 Drawer 打开期间临时启用
        // 手势，保证点击空白处可收起；关闭时仍禁用，避免与 PDF 手势冲突
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            TocDrawer(
                toc = state.toc,
                onTocClick = { item ->
                    viewModel.requestJump(item.page)
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.saveProgressNow()
                            onBack()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.reader_back)
                            )
                        }
                    },
                    title = {
                        Text(
                            text = state.doc?.displayName.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            enabled = state.loadState == ReaderUiState.LoadState.LOADED
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_toc),
                                contentDescription = stringResource(R.string.reader_toc)
                            )
                        }
                    }
                )
            },
            bottomBar = {
                ReaderBottomBar(
                    page = state.page,
                    pageCount = state.pageCount,
                    enabled = state.loadState == ReaderUiState.LoadState.LOADED,
                    onPrev = { viewModel.requestJump(state.page - 1) },
                    onNext = { viewModel.requestJump(state.page + 1) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
            ) {
                when (state.loadState) {
                    ReaderUiState.LoadState.FAILED -> {
                        LoadFailedContent(
                            onRelocate = {
                                relocateLauncher.launch(arrayOf("application/pdf"))
                            },
                            onRemoveRecord = { viewModel.removeRecord(onBack) }
                        )
                    }

                    else -> {
                        // doc 未就绪（VM 尚在读库）时只显示 loading；
                        // 就绪后 PdfViewer 立即组合开始加载——onLoad 回调才会把状态推到 LOADED，
                        // 若等 LOADED 才组合 PdfViewer 会死锁（永远停在 loading）
                        val uri = state.doc?.uri?.let(Uri::parse)
                        if (uri == null) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            // key(uri)：relocate 换绑后 uri 变化，AndroidView 需重建（factory 只在首次组合执行）
                            key(uri) {
                                PdfViewer(
                                    uri = uri,
                                    initialPage = state.page,
                                    jumpTarget = state.jumpTarget,
                                    onJumpConsumed = { viewModel.clearJump() },
                                    onPageChanged = { page, count ->
                                        viewModel.onPageChanged(page, count)
                                    },
                                    onLoadComplete = { count -> viewModel.onLoadComplete(count) },
                                    onTocAvailable = { toc -> viewModel.onTocAvailable(toc) },
                                    onFailed = { viewModel.onFailed() }
                                )
                            }
                            if (state.loadState == ReaderUiState.LoadState.LOADING) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    pageCount: Int,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // edge-to-edge 下 Scaffold 不会给 bottomBar 加导航栏内边距，
                // 内容行需抬到系统导航栏之上（Surface 背景仍铺满屏幕底部）
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev, enabled = enabled && page > 1) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(R.string.reader_prev)
                )
            }
            Text(
                text = if (pageCount > 0) {
                    stringResource(R.string.list_progress, page.coerceAtMost(pageCount), pageCount)
                } else {
                    stringResource(R.string.list_progress_unknown)
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onNext,
                enabled = enabled && pageCount > 0 && page < pageCount
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.reader_next)
                )
            }
        }
    }
}

@Composable
private fun LoadFailedContent(
    onRelocate: () -> Unit,
    onRemoveRecord: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            modifier = Modifier.padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = stringResource(R.string.reader_load_failed),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(
            text = stringResource(R.string.reader_load_failed_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRelocate) {
                Text(stringResource(R.string.reader_relocate))
            }
            TextButton(onClick = onRemoveRecord) {
                Text(stringResource(R.string.reader_remove_record))
            }
        }
    }
}
