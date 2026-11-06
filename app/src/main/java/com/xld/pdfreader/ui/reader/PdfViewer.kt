package com.xld.pdfreader.ui.reader

import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.Constants
import com.shockwave.pdfium.PdfDocument
import kotlin.math.abs

/** 视口与单页比例不一致时允许的页宽高比区间（异常比例回退为铺满） */
private const val MIN_PAGE_ASPECT = 0.1f
private const val MAX_PAGE_ASPECT = 10f

/** 页比例变化阈值：低于该值视为同一比例，避免取整误差引起反复重排 */
private const val PAGE_ASPECT_EPSILON = 0.002f

/**
 * AndroidPdfViewer（mhiew 维护分支，包 com.github.barteksc.pdfviewer）的 Compose 包装。
 *
 * 单页阅读模式：
 * - 视口 = 当前页：外层 [BoxWithConstraints] 按当前页宽高比把 [PDFView] 约束成
 *   阅读区内最大的同比例矩形并居中，超出的相邻页被 [clipToBounds] 裁掉。
 *   这是「只显示完整一页」的关键——仅靠 PRELOAD_OFFSET 只能限制渲染范围，
 *   而默认 FitPolicy.WIDTH 会让页面高度小于视口高度，露出上下相邻页的残影
 * - fitEachPage(true)：每页按自身比例单独适配视口宽度，混合页面尺寸的 PDF
 *   也能让当前页恰好铺满视口（配合视口比例 = 当前页比例）
 * - spacing(1)：1dp 页间距，消除 fitWidth 向下取整造成的 1px 相邻页残影
 * - PRELOAD_OFFSET = 0：只渲染与视口相交的页（即当前页），不向可见区外扩展。
 *   必须在 PDFView 构造之前设置——PagesLoader 在 PDFView 构造函数里实例化并读取该值
 * - [PageClampedPdfView] 把垂直滚动偏移钳制在当前页内：视口裁剪只能隐藏视口外内容，
 *   库本身的滚动范围仍是整个文档，缩放后拖动会滚到相邻页并触发其渲染
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
    // 当前页宽高比（width / height），0 表示尚未知（先按铺满显示，加载完成后校正）
    var pageAspect by remember(uri) { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val aspect = pageAspect.takeIf { it in MIN_PAGE_ASPECT..MAX_PAGE_ASPECT }
        val viewerModifier = (if (aspect == null) {
            Modifier.fillMaxSize()
        } else {
            // 在阅读区内取最大的、与页面同比例（宽/高）的矩形，页面两侧/上下留背景
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            when {
                availableWidth.value <= 0f || availableHeight.value <= 0f ->
                    Modifier.fillMaxSize()

                availableWidth.value / availableHeight.value > aspect ->
                    Modifier.size(width = availableHeight * aspect, height = availableHeight)

                else ->
                    Modifier.size(width = availableWidth, height = availableWidth / aspect)
            }
        }).clipToBounds()

        AndroidView(
            factory = { context ->
                // 单页渲染：渲染范围不向可见区域外扩展。
                // 必须在 PDFView 构造之前设置——PagesLoader 在 PDFView 构造函数里实例化并读取该值
                Constants.PRELOAD_OFFSET = 0
                PageClampedPdfView(context).apply {
                    val pdfView = this

                    // 视口尺寸变化（首次拿到页比例 / 翻到不同比例页 / 横竖屏切换）后，
                    // 库会按旧焦点比例重算偏移，这里重新对齐到当前页页首
                    addOnLayoutChangeListener { view, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom ->
                        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                            (view as PDFView).jumpTo(view.getCurrentPage(), false)
                        }
                    }

                    fromUri(uri)
                        // 每页单独适配视口宽度：配合「视口比例 = 当前页比例」，
                        // 当前页恰好铺满视口，不会因统一缩放而矮于视口露出相邻页
                        .fitEachPage(true)
                        // 1dp 页间距：吸收 fitWidth 向下取整的误差，避免底部露出下一页 1px
                        .spacing(1)
                        // 禁用手势翻页：翻页只通过底栏按钮 / 目录 jumpTo；
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
                            // 页变化即同步视口比例：钳制滚动后缩放平移不会跨页，
                            // 这里只会在显式翻页（含缩放状态下的翻页）时触发
                            pageAspectOf(pdfView, page)?.let { aspect ->
                                if (abs(aspect - pageAspect) > PAGE_ASPECT_EPSILON) {
                                    pageAspect = aspect
                                }
                            }
                            onPageChanged(page + 1, pageCount)
                        }
                        .onLoad { pageCount ->
                            // 先按初始页比例校正视口，避免首帧按全屏比例渲染后再跳一下
                            val initialIndex = (initialPage - 1)
                                .coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            pageAspectOf(pdfView, initialIndex)?.let { aspect ->
                                if (abs(aspect - pageAspect) > PAGE_ASPECT_EPSILON) {
                                    pageAspect = aspect
                                }
                            }
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
            modifier = viewerModifier
        )
    }
}

/**
 * 读取指定页的宽高比（width / height）。
 *
 * 使用 [PDFView.getPageSize]：与库内部排版同源（PdfFile.pageSizes），比例不受缩放和视口尺寸影响。
 * 页索引非法、文档未就绪或尺寸异常时返回 null。
 */
private fun pageAspectOf(view: PDFView, page: Int): Float? {
    if (page < 0 || page >= view.getPageCount()) return null
    val size = view.getPageSize(page)
    if (size.width <= 0f || size.height <= 0f) return null
    return size.width / size.height
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
