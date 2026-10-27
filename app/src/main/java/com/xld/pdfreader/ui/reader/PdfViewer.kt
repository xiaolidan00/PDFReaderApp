package com.xld.pdfreader.ui.reader

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.Constants
import com.shockwave.pdfium.PdfDocument

/**
 * AndroidPdfViewer（mhiew 维护分支，包 com.github.barteksc.pdfviewer）的 Compose 包装。
 *
 * 单页阅读模式：
 * - 只渲染当前页：[Constants.PRELOAD_OFFSET] = 0（渲染范围不再向可见区域外扩展）。
 *   必须在 PDFView 构造之前设置——PagesLoader 在 PDFView 构造函数里实例化并读取该值
 * - enableSwipe(false)：禁用手势翻页，翻页只走底栏按钮（jumpTo 程序化跳页不受影响）。
 *   库内 onFling 直接受 swipe 开关拦截；onScroll 为 isZooming || isSwipeEnabled 才移动内容，
 *   即未缩放时拖动无效、缩放态下拖动即平移——手势缩放/移动仍然可用
 * - enableAnnotationRendering 开启 PDF 内已有批注（荧光笔/高亮等）的渲染
 * - [onRelease] 必须 recycle，否则原生内存泄漏
 */
@Composable
fun PdfViewer(
    uri: Uri,
    initialPage: Int,
    jumpTarget: Int?,
    onJumpConsumed: () -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onLoadComplete: (Int) -> Unit,
    onTocAvailable: (List<TocItem>) -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            // 单页渲染：渲染范围不向可见区域外扩展。
            // 必须在 PDFView 构造之前设置——PagesLoader 在 PDFView 构造函数里实例化并读取该值
            Constants.PRELOAD_OFFSET = 0
            PDFView(context, null).apply {
                fromUri(uri)
                    // 禁用手势翻页：翻页只通过底栏按钮 jumpTo；
                    // 双指缩放/缩放后拖动平移不受影响（onScroll 在缩放态总会移动内容）
                    .enableSwipe(false)
                    .enableDoubletap(true)
                    // 批注渲染必须配在 Configurator 上：
                    // load() 会用 Configurator 的 annotationRendering（默认 false）
                    // 覆盖直接在 PDFView 上设置的值
                    .enableAnnotationRendering(true)
                    .enableAntialiasing(true)
                    .defaultPage((initialPage - 1).coerceAtLeast(0))
                    .onPageChange { page, pageCount ->
                        onPageChanged(page + 1, pageCount)
                    }
                    .onLoad { pageCount ->
                        onLoadComplete(pageCount)
                        onTocAvailable(flattenBookmarks(tableOfContents, level = 0))
                    }
                    .onError { onFailed() }
                    .load()
            }
        },
        update = { view ->
            if (jumpTarget != null) {
                view.jumpTo(jumpTarget - 1, true)
                onJumpConsumed()
            }
        },
        onRelease = { pdfView -> pdfView.recycle() },
        modifier = modifier.fillMaxSize()
    )
}

private fun flattenBookmarks(
    bookmarks: List<PdfDocument.Bookmark>,
    level: Int
): List<TocItem> {
    val result = mutableListOf<TocItem>()
    for (bookmark in bookmarks) {
        val pageIdx = bookmark.pageIdx
        if (pageIdx >= 0) {
            result.add(TocItem(bookmark.title ?: "", (pageIdx + 1).toInt(), level))
        }
        result.addAll(flattenBookmarks(bookmark.children ?: emptyList(), level + 1))
    }
    return result
}
