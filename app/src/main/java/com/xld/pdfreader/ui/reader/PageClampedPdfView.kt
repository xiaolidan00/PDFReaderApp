package com.xld.pdfreader.ui.reader

import android.content.Context
import android.graphics.Canvas
import com.github.barteksc.pdfviewer.PDFView
import kotlin.math.abs

/**
 * 阅读区视口模式下的 [PDFView]：只绘制当前页，并把垂直滚动偏移钳制在当前页内。
 *
 * 为什么需要：
 * - 视口已扩大为整个阅读区（顶栏与底栏之间），库本身会渲染/绘制与视口相交的
 *   上一页/下一页，靠视口裁剪藏不住相邻页 → 必须在 [onDraw] 里把绘制裁剪到当前页
 * - 库的滚动范围是整个文档（`PDFView.moveTo` 只按 `docLen` 限制），缩放后拖动会滚到
 *   相邻页并触发其渲染，`loadPageByOffset` 还会改掉 `currentPage`
 *
 * 实现要点：
 * - 只重写 `moveTo(float, float, boolean)`：库内 `moveRelativeTo`（拖动）、
 *   `zoomCenteredTo`（双指/双击缩放）、动画帧都最终汇聚到这三个参数的重载
 * - `jumpTo` 是显式换页，期间放行钳制（否则会被旧页范围卡住），跳完后补一次居中定位
 * - 页顶偏移与库内 `PdfFile.pageOffsets` 同源：`Σ(前 i 页高度 + spacingPx)`，
 *   `getPageSize(i)` 为不含 zoom 的适配尺寸，使用时再乘 `zoom`
 * - 页顶偏移缓存为 `FloatArray`，仅在视口尺寸变化（页面尺寸重算）时失效，
 *   避免每次拖动都遍历所有页
 * - 只处理纵向模式（本应用未启用 `swipeHorizontal`），横向模式直接交给父类
 *
 * 位置不变式：**视口中心必须始终落在当前页内**。
 * 库的 `DragPinchManager.onScroll` 每次拖动都会调用 `loadPageByOffset()`，
 * 它按「视口中心所在页」判定 `currentPage`；一旦中心落到页外/相邻页，
 * 当前页判定与进度回调就会错乱，[onDraw] 的裁剪也会跟着裁错页。
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
        // 库的 jumpTo 是「页顶对齐视口顶」；当页矮于视口时改为垂直居中：
        // 1) 与旧版「页比例视口居中」的观感一致；2) 保证视口中心落在本页内
        //    （顶对齐时中心可能落到页下方的空白甚至下一页，导致 currentPage 误判）
        // 仅处理非动画跳转：本应用所有跳转均为非动画（动画会滚过中间页并触发渲染）
        if (!withAnimation) {
            val current = getCurrentPage()
            if (current in 0 until getPageCount() && height > 0) {
                val contentTop = pageContentTopOf(current) ?: return
                val pageHeight = getPageSize(current).height * getZoom()
                if (pageHeight in 1f..height.toFloat()) {
                    // 目标：页屏幕中心 = 视口中心 → y = viewH/2 - pageHeight/2 - 页顶内容坐标
                    val centeredY = height / 2f - pageHeight / 2f - contentTop
                    if (abs(getCurrentYOffset() - centeredY) > 0.5f) {
                        programmaticScroll = true
                        try {
                            // loadPageByOffset=false：居中后视口中心已在本页内，无需重算页号
                            moveTo(currentXOffset, centeredY, false)
                        } finally {
                            programmaticScroll = false
                        }
                    }
                }
            }
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
        val viewHeight = height.toFloat()

        // 视口必须完全落在 [pageTop, pageTop + pageHeight] 内（页比视口高）：
        // maxY = -pageTop（页顶贴视口顶），minY = -(pageTop + pageHeight - viewHeight)（页底贴底）
        val maxY = -pageTop
        val minY = -(pageTop + pageHeight - viewHeight)
        val clampedY = if (pageHeight >= viewHeight) {
            if (minY >= maxY) maxY else yOffset.coerceIn(minY, maxY)
        } else {
            // 页比视口矮（视口 = 整个阅读区后的常态）：改为
            // 「页完整落在视口内 且 视口中心落在本页内」两个约束的交集，恒非空（居中值必在其中）
            val lo = maxOf(-pageTop, viewHeight / 2f - pageTop - pageHeight)
            val hi = minOf(viewHeight - pageTop - pageHeight, viewHeight / 2f - pageTop)
            if (lo > hi) yOffset else yOffset.coerceIn(lo, hi)
        }

        // 横向不做钳制：页面纵向排列，横向移动不会跨页；库本身已按页宽限制
        super.moveTo(xOffset, clampedY, loadPageByOffset)
    }

    override fun onDraw(canvas: Canvas) {
        val page = getCurrentPage()
        val pageCount = getPageCount()
        if (isRecycled || pageCount <= 0 || page < 0 || page >= pageCount || height <= 0) {
            super.onDraw(canvas)
            return
        }
        val contentTop = pageContentTopOf(page) ?: run {
            super.onDraw(canvas)
            return
        }
        // 屏幕坐标 = 内容坐标 × zoom 后 + 当前纵向偏移（与库 onDraw 的 translate 同源）
        val pageTop = contentTop + getCurrentYOffset()
        val pageHeight = getPageSize(page).height * getZoom()
        // 上下各让出半个页间距（spacingPx/2）：吸收页顶偏移的取整误差，避免裁掉页面自身
        // 边缘；相邻页与本页相距整整一个 spacing，半个 spacing 永远露不出相邻页
        val margin = getSpacingPx() / 2f
        canvas.save()
        canvas.clipRect(
            0f,
            pageTop - margin,
            width.toFloat(),
            pageTop + pageHeight + margin
        )
        super.onDraw(canvas)
        canvas.restore()
    }

    /** 当前页页顶的内容坐标（Σ 页高 + spacing，× zoom），页号非法或缓存不匹配时返回 null */
    private fun pageContentTopOf(page: Int): Float? {
        val pageCount = getPageCount()
        if (page < 0 || page >= pageCount) return null
        val tops = pageTops?.takeIf { it.size == pageCount }
            ?: buildPageTops(pageCount).also { pageTops = it }
        return tops[page] * getZoom()
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
