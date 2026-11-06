package com.xld.pdfreader.ui.reader

import android.content.Context
import com.github.barteksc.pdfviewer.PDFView

/**
 * 单页模式的 [PDFView]：把垂直滚动偏移钳制在当前页内。
 *
 * 为什么需要：视口裁成「一页大小」只能隐藏视口外的内容，库本身的滚动范围仍是整个文档
 * （`PDFView.moveTo` 只按 `docLen` 限制），缩放后拖动会滚到相邻页，`PagesLoader`
 * 就会渲染与视口相交的上一页/下一页，同时 `loadPageByOffset` 还会改掉 `currentPage`。
 *
 * 实现要点：
 * - 只重写 `moveTo(float, float, boolean)`：库内 `moveRelativeTo`（拖动）、
 *   `zoomCenteredTo`（双指/双击缩放）、动画帧都最终汇聚到这三个参数的重载
 * - `jumpTo` 是显式换页，期间放行钳制，否则会被旧页范围卡住
 * - 页顶偏移与库内 `PdfFile.pageOffsets` 同源：`Σ(前 i 页高度 + spacingPx)`，
 *   `getPageSize(i)` 为不含 zoom 的适配尺寸，使用时再乘 `zoom`
 * - 页顶偏移缓存为 `FloatArray`，仅在视口尺寸变化（页面尺寸重算）时失效，
 *   避免每次拖动都遍历所有页
 * - 只处理纵向模式（本应用未启用 `swipeHorizontal`），横向模式直接交给父类
 */
internal class PageClampedPdfView(context: Context) : PDFView(context, null) {

    /** 显式换页期间的滚动不做钳制（jumpTo 会先移动偏移、后改 currentPage） */
    private var programmaticScroll = false

    /** 未缩放的各页页顶偏移（与 PdfFile.pageOffsets 一致） */
    private var pageTops: FloatArray? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 必须先失效缓存再调 super：super 会重算页面尺寸并在内部调用 moveTo(x, y)，
        // 那时需要按新尺寸构建页顶偏移
        pageTops = null
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun jumpTo(page: Int, withAnimation: Boolean) {
        programmaticScroll = true
        try {
            super.jumpTo(page, withAnimation)
        } finally {
            programmaticScroll = false
        }
    }

    override fun moveTo(xOffset: Float, yOffset: Float, loadPageByOffset: Boolean) {
        val page = getCurrentPage()
        val pageCount = getPageCount()
        if (programmaticScroll || !isSwipeVertical || page < 0 || page >= pageCount) {
            super.moveTo(xOffset, yOffset, loadPageByOffset)
            return
        }

        val tops = pageTops?.takeIf { it.size == pageCount }
            ?: buildPageTops(pageCount).also { pageTops = it }

        val zoom = getZoom()
        val pageTop = tops[page] * zoom
        val pageHeight = getPageSize(page).height * zoom

        // 视口必须完全落在 [pageTop, pageTop + pageHeight] 内：
        // maxY = -pageTop（页顶贴视口顶部），minY = -(pageTop + pageHeight - viewHeight)（页底贴底）
        val maxY = -pageTop
        val minY = -(pageTop + pageHeight - height)
        val clampedY = if (minY >= maxY) maxY else yOffset.coerceIn(minY, maxY)

        // 横向不做钳制：页面纵向排列，横向移动不会跨页；库本身已按页宽限制
        super.moveTo(xOffset, clampedY, loadPageByOffset)
    }

    private fun buildPageTops(pageCount: Int): FloatArray {
        val tops = FloatArray(pageCount)
        val spacing = getSpacingPx().toFloat()
        var offset = 0f
        for (i in 0 until pageCount) {
            tops[i] = offset
            offset += getPageSize(i).height + spacing
        }
        return tops
    }
}
