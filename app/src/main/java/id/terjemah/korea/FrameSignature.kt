package id.terjemah.korea

import android.graphics.Rect
import android.media.Image
import kotlin.math.abs

/**
 * "Sidik jari" kecil sebuah frame layar: kecerahan rata-rata pada grid GW x GH.
 * Dipakai untuk tahu apakah layar sedang di-scroll atau sudah diam,
 * tanpa harus membandingkan jutaan piksel.
 */
object FrameSignature {
    const val GW = 24
    const val GH = 48
    private const val PIXEL_TOLERANCE = 24

    fun compute(img: Image): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = img.width
        val h = img.height
        val cw = w / GW
        val chh = h / GH
        val dx = cw / 4
        val dy = chh / 4
        val out = IntArray(GW * GH)
        for (gy in 0 until GH) {
            val cy = gy * chh + chh / 2
            for (gx in 0 until GW) {
                val cx = gx * cw + cw / 2
                var sum = 0
                sum += luma(buf, (cy - dy) * rs + (cx - dx) * ps)
                sum += luma(buf, (cy - dy) * rs + (cx + dx) * ps)
                sum += luma(buf, (cy + dy) * rs + (cx - dx) * ps)
                sum += luma(buf, (cy + dy) * rs + (cx + dx) * ps)
                out[gy * GW + gx] = sum / 4
            }
        }
        return out
    }

    private fun luma(buf: java.nio.ByteBuffer, i: Int): Int {
        if (i < 0 || i + 2 >= buf.limit()) return 0
        val r = buf.get(i).toInt() and 0xFF
        val g = buf.get(i + 1).toInt() and 0xFF
        val b = buf.get(i + 2).toInt() and 0xFF
        return (r * 3 + g * 6 + b) / 10
    }

    /** Fraksi sel (0..1) yang berubah, mengabaikan sel yang di-[mask]. */
    fun diff(a: IntArray, b: IntArray, mask: BooleanArray?): Float {
        var changed = 0
        var total = 0
        for (i in a.indices) {
            if (mask != null && mask[i]) continue
            total++
            if (abs(a[i] - b[i]) > PIXEL_TOLERANCE) changed++
        }
        return if (total == 0) 0f else changed.toFloat() / total
    }

    /** Tandai sel grid yang tertutup kotak terjemahan kita sendiri (agar tidak dianggap "layar berubah"). */
    fun mask(rects: List<Rect>, screenW: Int, screenH: Int): BooleanArray {
        val m = BooleanArray(GW * GH)
        if (screenW <= 0 || screenH <= 0) return m
        val cw = screenW.toFloat() / GW
        val chh = screenH.toFloat() / GH
        for (r in rects) {
            val x0 = ((r.left / cw).toInt() - 1).coerceAtLeast(0)
            val x1 = ((r.right / cw).toInt() + 1).coerceAtMost(GW - 1)
            val y0 = ((r.top / chh).toInt() - 1).coerceAtLeast(0)
            val y1 = ((r.bottom / chh).toInt() + 1).coerceAtMost(GH - 1)
            for (gy in y0..y1) for (gx in x0..x1) m[gy * GW + gx] = true
        }
        return m
    }
}
