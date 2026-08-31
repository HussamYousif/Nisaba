package com.nisaba.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * ScrollView that only scrolls for fingers. Stylus strokes must never be
 * stolen by scrolling (docs/04 §5) — hover and stylus events pass through.
 */
class InkScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private fun isFinger(event: MotionEvent?): Boolean =
        event != null && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (!isFinger(ev)) return false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        if (!isFinger(ev)) return false
        return super.onTouchEvent(ev)
    }
}
