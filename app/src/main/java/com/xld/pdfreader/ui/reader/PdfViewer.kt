package com.xld.pdfreader.ui.reader

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.util.Constants
import com.shockwave.pdfium.PdfDocument

/**
 * AndroidPdfViewer（mhiew 维护分支，包 com.github.barteksc.pdfviewer）的 Compose 包装。
 *
 * 阅读区视口模式：
 * - 视口 = 顶栏与底栏之间的整个阅读区：AndroidView 直接 `fillMaxSize` + `clipToBounds`，
 *   初始与缩放后的可视范围都是整个阅读区，缩放内容被裁剪在阅读区内
 * - 只显示当前页：由 [PageClampedPdfView] 在 `onDraw` 里把绘制裁剪在当前页的屏幕矩形内，
 *   相邻页即使被库渲染也不会画出来，页外区域透明、露出主题背景
 * - fitEachPage(true)：每页按自身比例单独适配阅读区宽度，混合尺寸的 PDF
 *   也能让当前页铺满阅读区宽度
 * - spacing(1)：1dp 页间距，消除 fitWidth 向下取整的 1px 相邻页残影
 * - PRELOAD_OFFSET = 0：只渲染与阅读区相交的页，不向可见区外扩展。
 *   必须在 PDFView 构造之前设置——PagesLoader 在 PDFView 构造函数里实例化并读取该值
 * - [PageClampedPdfView] 把垂直滚动偏移钳制在当前页内（页矮于视口时要求页完整落在
 *   视口内且视口中心恒在当前页内——库用视口中心判定 currentPage），缩放后拖动不会滚到相邻页
 * - enableSwipe(false)：禁用手势翻页，翻页只走底栏按钮 / 目录的 jumpTo；
 *   双指缩放/双击缩放/缩放后拖动平移不受影响（onScroll 在缩放态总会移动内容）
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
            PageClampedPdfView(context).apply {
                val pdfView = this

                fromUri(uri)
                    // 每页单独适配阅读区宽度：当前页恰好铺满阅读区宽度，
                    // 不会因统一缩放而矮于/窄于阅读区
                    .fitEachPage(true)
                    // 1dp 页间距：吸收 fitWidth 向下取整的误差，避免底部露出下一页 1px
                    .spacing(1)
                    // 禁用手势翻页：翻页只通过底栏按钮 / 目录 jumpTo；
                    // 双指缩放/双击缩放/缩放后拖动平移不受影响（onScroll 在缩放态总会移动内容）
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
                        onTocAvailable(flattenBookmarks(pdfView.tableOfContents, level = 0))
                    }
                    .onError { onFailed() }
                    .load()
            }
        },
        update = { view ->
            if (jumpTarget != null) {
                // 立即跳转（不做滚动动画）：动画会滚过中间页并触发其渲染，
                // 与「只渲染当前页」冲突
                view.jumpTo(jumpTarget - 1, false)
                onJumpConsumed()
            }
        },
        onRelease = { pdfView -> pdfView.recycle() },
        // 视口 = 整个阅读区；clipToBounds 把缩放后的内容裁剪在阅读区内
        modifier = modifier.fillMaxSize().clipToBounds()
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
