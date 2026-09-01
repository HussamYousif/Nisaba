package com.nisaba.ink

/**
 * Maps page space (2480×1860 for blank pages; PDF page box at 300 dpi later)
 * into view space: contain-fit with centering. Strokes are stored in page
 * space so they are zoom-independent (docs/04 §5, docs/05 §2).
 */
class PageTransform {
    var pageWidth: Int = 2480
    var pageHeight: Int = 1860
    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    fun setViewport(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        scale = minOf(
            viewWidth.toFloat() / pageWidth,
            viewHeight.toFloat() / pageHeight,
            MAX_SCALE
        )
        offsetX = (viewWidth - pageWidth * scale) / 2f
        offsetY = (viewHeight - pageHeight * scale) / 2f
    }

    /**
     * Fit-width mode: the page (and any answer ink below it) spans the view
     * width; the view height grows to the content. Used once answers render
     * on the canvas (docs/08 §1).
     */
    fun setViewportFitWidth(viewWidth: Int) {
        if (viewWidth <= 0) return
        scale = minOf(viewWidth.toFloat() / pageWidth, MAX_SCALE)
        offsetX = (viewWidth - pageWidth * scale) / 2f
        offsetY = 0f
    }

    fun toPageX(viewX: Float): Float = (viewX - offsetX) / scale
    fun toPageY(viewY: Float): Float = (viewY - offsetY) / scale
    fun toViewX(pageX: Float): Float = pageX * scale + offsetX
    fun toViewY(pageY: Float): Float = pageY * scale + offsetY

    companion object {
        /** Never upscale past native 300 dpi. */
        const val MAX_SCALE = 1.0f
    }
}
