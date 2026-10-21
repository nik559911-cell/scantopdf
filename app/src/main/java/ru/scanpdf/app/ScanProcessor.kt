package ru.scanpdf.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Превращает фото документа в «скан»:
 *  1) перспективная обрезка по четырём углам листа,
 *  2) выравнивание фона (тени и серость -> белый), цвет сохраняется,
 *  3) выравнивание текста по горизонту.
 * Всё считается на телефоне, без сети и без сторонних библиотек.
 *
 * Углы задаются нормированными координатами 0..1 в порядке:
 * левый-верхний, правый-верхний, правый-нижний, левый-нижний.
 */
object ScanProcessor {

    val FULL = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    fun process(src: Bitmap, corners: FloatArray?, bw: Boolean, deskew: Boolean, maxSide: Int): Bitmap {
        val warped = warp(src, corners ?: FULL, maxSide)
        normalize(warped, bw)
        val result = if (deskew) deskewBitmap(warped) else warped
        clearBorder(result)
        return result
    }

    // ---------------------------------------------------------------- перспектива

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(ax - bx, ay - by)

    fun warp(src: Bitmap, c: FloatArray, maxSide: Int): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val sx = FloatArray(4) { c[it * 2] * sw }
        val sy = FloatArray(4) { c[it * 2 + 1] * sh }

        val top = dist(sx[0], sy[0], sx[1], sy[1])
        val bottom = dist(sx[3], sy[3], sx[2], sy[2])
        val left = dist(sx[0], sy[0], sx[3], sy[3])
        val right = dist(sx[1], sy[1], sx[2], sy[2])
        var w = (top + bottom) / 2f
        var h = (left + right) / 2f
        if (w < 16f || h < 16f) {
            w = sw
            h = sh
        }
        // Если пропорции близки к A4 - делаем ровно A4.
        val a4 = 1.4142f
        val r = h / w
        if (abs(r - a4) < 0.12f) {
            h = w * a4
        } else if (abs(1f / r - a4) < 0.12f) {
            w = h * a4
        }
        val scale = min(1f, maxSide / max(w, h))
        val ow = max(1, (w * scale).roundToInt())
        val oh = max(1, (h * scale).roundToInt())

        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val srcPts = floatArrayOf(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], sx[3], sy[3])
        val dstPts = floatArrayOf(
            0f, 0f,
            ow.toFloat(), 0f,
            ow.toFloat(), oh.toFloat(),
            0f, oh.toFloat()
        )
        val m = Matrix()
        m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, m, paint)
        return out
    }

    // ---------------------------------------------------------------- фон -> белый

    private fun normalize(bmp: Bitmap, bw: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 1. Уменьшенная копия (усреднение блоками), ~320 px по длинной стороне.
        val sc = 320f / max(w, h)
        val sw = max(4, (w * sc).roundToInt())
        val sh = max(4, (h * sc).roundToInt())
        val planes = Array(3) { IntArray(sw * sh) }
        for (j in 0 until sh) {
            val y0 = j * h / sh
            val y1 = max(y0 + 1, (j + 1) * h / sh)
            for (i in 0 until sw) {
                val x0 = i * w / sw
                val x1 = max(x0 + 1, (i + 1) * w / sw)
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                var y = y0
                while (y < y1) {
                    val row = y * w
                    var x = x0
                    while (x < x1) {
                        val p = px[row + x]
                        r += (p shr 16) and 255
                        g += (p shr 8) and 255
                        b += p and 255
                        n++
                        x++
                    }
                    y++
                }
                val k = j * sw + i
                planes[0][k] = r / n
                planes[1][k] = g / n
                planes[2][k] = b / n
            }
        }

        // 2. Оценка цвета бумаги: максимум (убирает текст) -> медиана (убирает тени-«пятна») -> размытие.
        for (c in 0 until 3) {
            val d = maxFilter(planes[c], sw, sh, 2)
            val m = medianFilter(d, sw, sh, 15, 2)
            planes[c] = boxBlur(m, sw, sh, 2)
        }

        // 3. Подготовка билинейной интерполяции фона до полного размера.
        val x0s = IntArray(w)
        val x1s = IntArray(w)
        val xws = IntArray(w)
        for (x in 0 until w) {
            val fx = max(0f, (x + 0.5f) * sw / w - 0.5f)
            val xi = min(sw - 1, fx.toInt())
            x0s[x] = xi
            x1s[x] = min(sw - 1, xi + 1)
            xws[x] = ((fx - xi) * 256f).toInt().coerceIn(0, 256)
        }

        val lut = IntArray(256) { i ->
            (255.0 * min(1.0, i / 255.0 / 0.92).pow(1.4)).roundToInt().coerceIn(0, 255)
        }

        val pr = planes[0]
        val pg = planes[1]
        val pb = planes[2]
        for (y in 0 until h) {
            val fy = max(0f, (y + 0.5f) * sh / h - 0.5f)
            val yi = min(sh - 1, fy.toInt())
            val yi1 = min(sh - 1, yi + 1)
            val wy = ((fy - yi) * 256f).toInt().coerceIn(0, 256)
            val rowA = yi * sw
            val rowB = yi1 * sw
            val rowOut = y * w
            for (x in 0 until w) {
                val xa = x0s[x]
                val xb = x1s[x]
                val wx = xws[x]
                val bgR = bilinear(pr, rowA + xa, rowA + xb, rowB + xa, rowB + xb, wx, wy)
                val bgG = bilinear(pg, rowA + xa, rowA + xb, rowB + xa, rowB + xb, wx, wy)
                val bgB = bilinear(pb, rowA + xa, rowA + xb, rowB + xa, rowB + xb, wx, wy)

                val p = px[rowOut + x]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                var rr = lut[min(255, r * 255 / max(1, bgR))]
                var gg = lut[min(255, g * 255 / max(1, bgG))]
                var bb = lut[min(255, b * 255 / max(1, bgB))]
                if (bw) {
                    var gray = (rr * 30 + gg * 59 + bb * 11) / 100
                    gray = ((gray - 20) * 255 / 215).coerceIn(0, 255)
                    rr = gray
                    gg = gray
                    bb = gray
                }
                if (min(rr, min(gg, bb)) > 224) {
                    rr = 255
                    gg = 255
                    bb = 255
                }
                px[rowOut + x] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun bilinear(pl: IntArray, a: Int, b: Int, c: Int, d: Int, wx: Int, wy: Int): Int {
        val top = pl[a] * (256 - wx) + pl[b] * wx
        val bot = pl[c] * (256 - wx) + pl[d] * wx
        return (top * (256 - wy) + bot * wy) shr 16
    }

    private fun maxFilter(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0
                for (k in max(0, x - r)..min(w - 1, x + r)) {
                    val v = src[y * w + k]
                    if (v > m) m = v
                }
                tmp[y * w + x] = m
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = 0
                for (k in max(0, y - r)..min(h - 1, y + r)) {
                    val v = tmp[k * w + x]
                    if (v > m) m = v
                }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Медиана по окну (2r+1)x(2r+1), берём каждую step-ую точку для скорости. */
    private fun medianFilter(src: IntArray, w: Int, h: Int, r: Int, step: Int): IntArray {
        val out = IntArray(w * h)
        val hist = IntArray(256)
        for (y in 0 until h) {
            for (x in 0 until w) {
                java.util.Arrays.fill(hist, 0)
                var n = 0
                var yy = y - r
                while (yy <= y + r) {
                    val cy = yy.coerceIn(0, h - 1)
                    var xx = x - r
                    while (xx <= x + r) {
                        val cx = xx.coerceIn(0, w - 1)
                        hist[src[cy * w + cx]]++
                        n++
                        xx += step
                    }
                    yy += step
                }
                val half = (n + 1) / 2
                var acc = 0
                var v = 0
                while (v < 255) {
                    acc += hist[v]
                    if (acc >= half) break
                    v++
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var n = 0
                for (k in max(0, x - r)..min(w - 1, x + r)) {
                    s += src[y * w + k]
                    n++
                }
                tmp[y * w + x] = s / n
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                var n = 0
                for (k in max(0, y - r)..min(h - 1, y + r)) {
                    s += tmp[k * w + x]
                    n++
                }
                out[y * w + x] = s / n
            }
        }
        return out
    }

    // ---------------------------------------------------------------- выравнивание по горизонту

    private fun deskewBitmap(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val sc = min(1f, 900f / max(w, h))
        val sw = max(4, (w * sc).roundToInt())
        val sh = max(4, (h * sc).roundToInt())
        val xs = IntArray(sw * sh)
        val ys = IntArray(sw * sh)
        var count = 0
        for (j in 0 until sh) {
            val sy = min(h - 1, (j / sc).toInt())
            for (i in 0 until sw) {
                val sx = min(w - 1, (i / sc).toInt())
                val p = px[sy * w + sx]
                val gray = (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3
                if (gray < 110) {
                    xs[count] = i
                    ys[count] = j
                    count++
                }
            }
        }
        if (count < 200) return bmp

        val maxAngle = 6.0
        val maxT = tan(Math.toRadians(maxAngle))
        val pad = ceil(sw * maxT).toInt() + 2
        val bins = sh + 2 * pad
        val hist = IntArray(bins)

        fun score(t: Double): Double {
            java.util.Arrays.fill(hist, 0)
            for (k in 0 until count) {
                val b = (ys[k] + xs[k] * t).roundToInt() + pad
                if (b in 0 until bins) hist[b]++
            }
            var s = 0.0
            for (v in hist) s += v.toDouble() * v
            return s
        }

        var bestDeg = 0.0
        var bestScore = -1.0
        var deg = -maxAngle
        while (deg <= maxAngle + 1e-9) {
            val s = score(tan(Math.toRadians(deg)))
            if (s > bestScore) {
                bestScore = s
                bestDeg = deg
            }
            deg += 0.1
        }
        val centre = bestDeg
        deg = centre - 0.1
        while (deg <= centre + 0.1 + 1e-9) {
            val s = score(tan(Math.toRadians(deg)))
            if (s > bestScore) {
                bestScore = s
                bestDeg = deg
            }
            deg += 0.02
        }
        if (abs(bestDeg) < 0.12) return bmp

        // t = tan(bestDeg) - наклон, который «выпрямляет» строки; поворачиваем по часовой на atan(t).
        val rotate = Math.toDegrees(atan(tan(Math.toRadians(bestDeg)))).toFloat()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        m.postRotate(rotate, w / 2f, h / 2f)
        canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        return out
    }

    private fun clearBorder(bmp: Bitmap) {
        val t = max(3, bmp.width / 250).toFloat()
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val canvas = Canvas(bmp)
        val p = Paint()
        p.color = Color.WHITE
        canvas.drawRect(0f, 0f, w, t, p)
        canvas.drawRect(0f, h - t, w, h, p)
        canvas.drawRect(0f, 0f, t, h, p)
        canvas.drawRect(w - t, 0f, w, h, p)
    }

    // ---------------------------------------------------------------- поиск листа

    /**
     * Пробует найти лист по светлому малонасыщенному цвету. Если уверенности нет - возвращает весь кадр.
     * Это самая слабая часть: при сложном фоне рамку лучше поправить вручную.
     */
    fun detectPage(src: Bitmap): FloatArray {
        val sc = 400f / max(src.width, src.height)
        val sw = max(8, (src.width * sc).roundToInt())
        val sh = max(8, (src.height * sc).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val mask = BooleanArray(sw * sh)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            mask[i] = mx >= 110 && (mx - mn) * 100 < mx * 22
        }

        val visited = BooleanArray(sw * sh)
        val stack = IntArray(sw * sh)
        var bestCount = 0
        var best: IntArray? = null // x,y для TL,TR,BR,BL

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var n = 0
            var minSum = Int.MAX_VALUE
            var maxSum = Int.MIN_VALUE
            var minDiff = Int.MAX_VALUE
            var maxDiff = Int.MIN_VALUE
            var tl = 0
            var tr = 0
            var br = 0
            var bl = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val x = cur % sw
                val y = cur / sw
                n++
                val sum = x + y
                val diff = x - y
                if (sum < minSum) { minSum = sum; tl = cur }
                if (sum > maxSum) { maxSum = sum; br = cur }
                if (diff > maxDiff) { maxDiff = diff; tr = cur }
                if (diff < minDiff) { minDiff = diff; bl = cur }
                if (x > 0 && mask[cur - 1] && !visited[cur - 1]) { visited[cur - 1] = true; stack[sp++] = cur - 1 }
                if (x < sw - 1 && mask[cur + 1] && !visited[cur + 1]) { visited[cur + 1] = true; stack[sp++] = cur + 1 }
                if (y > 0 && mask[cur - sw] && !visited[cur - sw]) { visited[cur - sw] = true; stack[sp++] = cur - sw }
                if (y < sh - 1 && mask[cur + sw] && !visited[cur + sw]) { visited[cur + sw] = true; stack[sp++] = cur + sw }
            }
            if (n > bestCount) {
                bestCount = n
                best = intArrayOf(tl % sw, tl / sw, tr % sw, tr / sw, br % sw, br / sw, bl % sw, bl / sw)
            }
        }

        val b = best ?: return FULL.copyOf()
        val total = (sw * sh).toFloat()
        if (bestCount / total < 0.25f) return FULL.copyOf()

        // Площадь четырёхугольника (формула Гаусса).
        var area = 0f
        for (k in 0 until 4) {
            val k2 = (k + 1) % 4
            area += b[k * 2] * b[k2 * 2 + 1] - b[k2 * 2] * b[k * 2 + 1]
        }
        area = abs(area) / 2f
        val frac = area / total
        if (frac < 0.30f || frac > 0.97f) return FULL.copyOf()

        return floatArrayOf(
            (b[0] + 0.5f) / sw, (b[1] + 0.5f) / sh,
            (b[2] + 0.5f) / sw, (b[3] + 0.5f) / sh,
            (b[4] + 0.5f) / sw, (b[5] + 0.5f) / sh,
            (b[6] + 0.5f) / sw, (b[7] + 0.5f) / sh
        )
    }
}
