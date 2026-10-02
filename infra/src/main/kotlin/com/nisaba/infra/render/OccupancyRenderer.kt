package com.nisaba.infra.render

import android.graphics.Bitmap
import android.graphics.Color
import com.nisaba.domain.shared.PageSize
import com.nisaba.scribe.layout.Occupancy

/** Marks the grid cells of a page picture that contain something dark (printed text, figures) so answers avoid them. */
object OccupancyRenderer {
    private const val SAMPLES = 4
    private const val DARK = 170

    fun of(bitmap: Bitmap, size: PageSize, cell: Float = Occupancy.CELL): Occupancy {
        val occ = Occupancy.empty(size, cell)
        val sx = bitmap.width / size.w; val sy = bitmap.height / size.h
        var y = 0f
        while (y < size.h) {
            var x = 0f
            while (x < size.w) {
                if (cellIsDark(bitmap, x, y, cell, sx, sy)) occ.mark(com.nisaba.domain.shared.PageRect(x, y, x + cell - 1f, y + cell - 1f))
                x += cell
            }
            y += cell
        }
        return occ
    }

    private fun cellIsDark(b: Bitmap, x: Float, y: Float, cell: Float, sx: Float, sy: Float): Boolean {
        for (i in 0 until SAMPLES) for (j in 0 until SAMPLES) {
            val px = ((x + cell * (i + 0.5f) / SAMPLES) * sx).toInt().coerceIn(0, b.width - 1)
            val py = ((y + cell * (j + 0.5f) / SAMPLES) * sy).toInt().coerceIn(0, b.height - 1)
            val c = b.getPixel(px, py)
            if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < DARK) return true
        }
        return false
    }
}
