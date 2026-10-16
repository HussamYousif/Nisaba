package com.nisaba.infra.render

import android.graphics.Bitmap
import android.graphics.Color
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.layout.Occupancy

/** Marks the grid cells of a page picture that contain something dark (printed text, figures) so answers avoid them. */
object OccupancyRenderer {
    private const val SAMPLES = 4
    private const val DARK = 170

    /**
     * A cell is occupied when any of its [SAMPLES]×[SAMPLES] sample points is dark. The picture is read a row of pixels at
     * a time (a few hundred bulk reads) instead of a call per sample point (hundreds of thousands on a page).
     */
    fun of(bitmap: Bitmap, size: PageSize, cell: Float = Occupancy.CELL): Occupancy {
        val occ = Occupancy.empty(size, cell)
        val sx = bitmap.width / size.w; val sy = bitmap.height / size.h
        val cols = kotlin.math.ceil(size.w / cell).toInt()
        val row = IntArray(bitmap.width)
        val dark = BooleanArray(cols)
        var y = 0f
        while (y < size.h) {
            dark.fill(false)
            for (j in 0 until SAMPLES) {
                val py = ((y + cell * (j + 0.5f) / SAMPLES) * sy).toInt().coerceIn(0, bitmap.height - 1)
                bitmap.getPixels(row, 0, bitmap.width, 0, py, bitmap.width, 1)
                for (cx in 0 until cols) if (!dark[cx]) dark[cx] = rowIsDark(row, cx * cell, cell, sx, bitmap.width)
            }
            for (cx in 0 until cols) {
                if (dark[cx]) occ.mark(com.nisaba.domain.shared.PageRect(cx * cell, y, cx * cell + cell - 1f, y + cell - 1f))
            }
            y += cell
        }
        return occ
    }

    private fun rowIsDark(row: IntArray, x: Float, cell: Float, sx: Float, width: Int): Boolean {
        for (i in 0 until SAMPLES) {
            val c = row[((x + cell * (i + 0.5f) / SAMPLES) * sx).toInt().coerceIn(0, width - 1)]
            if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < DARK) return true
        }
        return false
    }
}
