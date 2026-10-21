package ru.scanpdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Картинка с четырьмя перетаскиваемыми углами для ручной обрезки листа. */
class CropView(context: Context) : View(context) {

    private var bmp: Bitmap? = null
    private val pts = ScanProcessor.FULL.copyOf()
    private var dragged = -1
    private val density = resources.displayMetrics.density
    private val dst = RectF()

    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 111, 217)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 111, 217)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    init {
        setBackgroundColor(Color.rgb(32, 32, 32))
    }

    fun setBitmap(b: Bitmap, corners: FloatArray) {
        bmp = b
        setCorners(corners)
    }

    fun setCorners(c: FloatArray) {
        for (i in 0 until 8) pts[i] = c[i]
        invalidate()
    }

    fun getCorners(): FloatArray = pts.copyOf()

    private fun layoutImage() {
        val b = bmp ?: return
        val margin = 28f * density
        val aw = width - 2 * margin
        val ah = height - 2 * margin
        val s = min(aw / b.width, ah / b.height)
        val dw = b.width * s
        val dh = b.height * s
        dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun vx(i: Int): Float = dst.left + pts[i * 2] * dst.width()
    private fun vy(i: Int): Float = dst.top + pts[i * 2 + 1] * dst.height()

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        layoutImage()
        canvas.drawBitmap(b, null, dst, null)

        val path = Path()
        path.moveTo(vx(0), vy(0))
        for (i in 1 until 4) path.lineTo(vx(i), vy(i))
        path.close()

        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRect(dst, dimPaint)
        canvas.restore()

        canvas.drawPath(path, linePaint)
        val r = 13f * density
        for (i in 0 until 4) {
            canvas.drawCircle(vx(i), vy(i), r, handleFill)
            canvas.drawCircle(vx(i), vy(i), r, handleStroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best = -1
                var bestD = 64f * density
                for (i in 0 until 4) {
                    val d = hypot(e.x - vx(i), e.y - vy(i))
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                dragged = best
                return best >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragged >= 0 && dst.width() > 0f && dst.height() > 0f) {
                    pts[dragged * 2] = ((e.x - dst.left) / dst.width()).coerceIn(0f, 1f)
                    pts[dragged * 2 + 1] = ((e.y - dst.top) / dst.height()).coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragged = -1
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
