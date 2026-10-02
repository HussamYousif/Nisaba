package com.nisaba.domain.answer

/** One stroked outline in the drawing's own coordinates (usually a 1000×1000 viewBox). */
class DiagramShape(val xs: FloatArray, val ys: FloatArray, val closed: Boolean)

/** A parsed diagram: shapes as polylines (curves already flattened), in a [viewW]×[viewH] coordinate space. */
class DiagramDrawing(val viewW: Float, val viewH: Float, val shapes: List<DiagramShape>)
