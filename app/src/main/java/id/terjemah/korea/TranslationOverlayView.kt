package id.terjemah.korea

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.max

/**
 * Lapisan layar penuh (tembus sentuhan) yang menempelkan kotak putih berisi terjemahan
 * tepat di atas posisi teks Korea.
 */
class TranslationOverlayView(context: Context) : View(context) {

    private class Placed(val rect: RectF, val layout: StaticLayout)

    private var placed: List<Placed> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#E8453C")
    }
    private val textPaintTemplate = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val loc = IntArray(2)
    private val pad = dp(5f)
    private val minWidth = dp(110f)
    private val maxText = sp(17f)
    private val minText = sp(9f)
    private val radius = dp(8f)

    /**
     * Susun ulang kotak-kotak terjemahan.
     * @return kotak yang benar-benar digambar (koordinat layar) — dipakai untuk masking deteksi gerak.
     */
    fun setBoxes(boxes: List<TextBox>, screenW: Int): List<Rect> {
        placed = boxes.map { layoutBox(it, screenW.toFloat()) }
        invalidate()
        return placed.map { p ->
            Rect(p.rect.left.toInt(), p.rect.top.toInt(), p.rect.right.toInt(), p.rect.bottom.toInt())
        }
    }

    private fun layoutBox(box: TextBox, screenW: Float): Placed {
        val r = RectF(box.rect)
        r.inset(-pad, -pad)

        // Kotak teks Korea sering sempit; lebarkan agar teks Indonesia muat.
        if (r.width() < minWidth) {
            val cx = r.centerX()
            r.left = cx - minWidth / 2f
            r.right = cx + minWidth / 2f
        }
        if (r.left < 0f) r.offset(-r.left, 0f)
        if (r.right > screenW) r.offset(screenW - r.right, 0f)

        val innerW = max(1, (r.width() - 2 * pad).toInt())
        val paint = TextPaint(textPaintTemplate)
        var size = maxText
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder
                .obtain(box.text, 0, box.text.length, paint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()
            if (layout.height <= r.height() - 2 * pad || size <= minText) break
            size -= sp(1f)
        }
        // Kalau tetap tidak muat di ukuran terkecil, tinggikan kotaknya.
        val needed = layout.height + 2 * pad
        if (needed > r.height()) {
            val extra = needed - r.height()
            r.top -= extra / 2f
            r.bottom += extra / 2f
        }
        return Placed(r, layout)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Kotak memakai koordinat layar; geser sesuai posisi jendela overlay ini.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (p in placed) {
            canvas.drawRoundRect(p.rect, radius, radius, bgPaint)
            canvas.drawRoundRect(p.rect, radius, radius, borderPaint)
            canvas.save()
            canvas.translate(p.rect.left + pad, p.rect.top + (p.rect.height() - p.layout.height) / 2f)
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
