package com.nisaba.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.nisaba.magic.MagicTheme
import com.nisaba.notebook.model.NotebookPage
import com.nisaba.notebook.model.Stroke

/**
 * The writing surface (docs/03 §2, docs/04 §3–5).
 *
 * - Stylus (EMR) draws with pressure; fingers never draw.
 * - Hover events gate palm rejection while the pen is in range.
 * - Pen side button (BTN_STYLUS/BTN_STYLUS2) acts as a held eraser.
 * - Ink renders in page space through [PageTransform]; dirty-rect invalidation
 *   keeps per-move redraws minimal while writing.
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var page: NotebookPage? = null
        set(value) {
            field = value
            transform.pageWidth = value?.width ?: 2480
            transform.pageHeight = value?.height ?: 1860
            transform.setViewport(width, height)
            invalidate()
        }

    var inkColor: Int = MagicTheme.INK
    var eraserActive: Boolean = false

    /** Fired for every new stroke point (summon idle reset / autosave, M3). */
    var onStrokePoint: (() -> Unit)? = null

    /** Fired on pen-up with the finished stroke (tool == "eraser" for erases). */
    var onStrokeComplete: ((Stroke) -> Unit)? = null

    /** Fired when the pen side-button state changes. */
    var onEraserButton: ((Boolean) -> Unit)? = null

    private val transform = PageTransform()
    private val current = StrokeBuilder()
    private var penHovering = false
    private var drawing = false
    private var eraserButtonHeld = false

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        transform.setViewport(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(MagicTheme.PARCHMENT)
        val p = page ?: return
        canvas.save()
        canvas.translate(transform.offsetX, transform.offsetY)
        canvas.scale(transform.scale, transform.scale)
        drawRules(canvas, p)
        inkPaint.color = inkColor
        for (s in p.strokes) StrokeRenderer.draw(canvas, s, inkPaint)
        current.buildPreview()?.let { StrokeRenderer.draw(canvas, it, inkPaint) }
        canvas.restore()
    }

    private fun drawRules(canvas: Canvas, p: NotebookPage) {
        val rule = Paint().apply {
            color = MagicTheme.INK
            alpha = MagicTheme.RULE_ALPHA
            strokeWidth = 1.2f
        }
        var y = MagicTheme.RULE_SPACING
        while (y < p.height) {
            canvas.drawLine(0f, y, p.width.toFloat(), y, rule)
            y += MagicTheme.RULE_SPACING
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> penHovering = true

            MotionEvent.ACTION_HOVER_EXIT -> penHovering = false

            MotionEvent.ACTION_DOWN -> {
                // Palm rejection: fingers never draw; while the pen hovers,
                // even finger touches are ignored outright.
                if (!isStylus) return false
                if (page == null) return false
                val erasing = eraserActive || eraserButtonHeld
                current.start(
                    transform.toPageX(event.x),
                    transform.toPageY(event.y),
                    event.pressure,
                    event.eventTime,
                    erasing,
                )
                drawing = true
                onStrokePoint?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return false
                if (isStylus) {
                    val pageX = transform.toPageX(event.x)
                    val pageY = transform.toPageY(event.y)
                    current.append(pageX, pageY, event.pressure, event.eventTime)
                    onStrokePoint?.invoke()
                    invalidateDirty(pageX, pageY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (drawing) {
                    finishStroke()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    drawing = false
                    current.cancel()
                    invalidate()
                }
            }
        }

        val btn = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)
        if (btn != eraserButtonHeld) {
            eraserButtonHeld = btn
            onEraserButton?.invoke(btn)
        }
        return true
    }

    private fun finishStroke() {
        drawing = false
        val stroke = current.build() ?: return
        onStrokeComplete?.invoke(stroke)
        invalidate()
    }

    private fun invalidateDirty(pageX: Float, pageY: Float) {
        val vx = transform.toViewX(pageX)
        val vy = transform.toViewY(pageY)
        val pad = (24 * transform.scale).toInt() + 8
        invalidate(
            (vx - pad).toInt(), (vy - pad).toInt(),
            (vx + pad).toInt(), (vy + pad).toInt(),
        )
    }

    /** True when the stylus is currently drawing a stroke. */
    val isDrawing: Boolean get() = drawing
}
