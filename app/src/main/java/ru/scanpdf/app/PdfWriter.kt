package ru.scanpdf.app

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.min

/**
 * Минимальный писатель PDF: каждая страница - одна JPEG-картинка, вписанная в A4.
 * Файлы получаются небольшими и открываются везде. Сеть не нужна.
 */
object PdfWriter {

    class Img(val jpeg: ByteArray, val w: Int, val h: Int)

    private class Out(val s: OutputStream) {
        var n = 0L
        fun write(b: ByteArray) {
            s.write(b)
            n += b.size
        }

        fun write(str: String) = write(str.toByteArray(Charsets.ISO_8859_1))
    }

    private const val A4_W = 595.0
    private const val A4_H = 842.0

    fun write(stream: OutputStream, imgs: List<Img>) {
        val out = Out(BufferedOutputStream(stream, 64 * 1024))
        val count = imgs.size
        val objCount = 2 + 3 * count
        val offsets = LongArray(objCount + 1)

        out.write("%PDF-1.4\n")

        offsets[1] = out.n
        out.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        offsets[2] = out.n
        val kids = StringBuilder()
        for (i in 0 until count) kids.append(3 + 3 * i).append(" 0 R ")
        out.write("2 0 obj\n<< /Type /Pages /Kids [ $kids] /Count $count >>\nendobj\n")

        for (i in 0 until count) {
            val img = imgs[i]
            val pageId = 3 + 3 * i
            val contentId = 4 + 3 * i
            val imageId = 5 + 3 * i

            val landscape = img.w > img.h
            val pw = if (landscape) A4_H else A4_W
            val ph = if (landscape) A4_W else A4_H
            val s = min(pw / img.w, ph / img.h)
            val dw = img.w * s
            val dh = img.h * s
            val x = (pw - dw) / 2.0
            val y = (ph - dh) / 2.0

            offsets[pageId] = out.n
            out.write(
                "$pageId 0 obj\n<< /Type /Page /Parent 2 0 R " +
                    String.format(Locale.US, "/MediaBox [0 0 %.2f %.2f] ", pw, ph) +
                    "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n"
            )

            val content = String.format(Locale.US, "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q", dw, dh, x, y)
            val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
            offsets[contentId] = out.n
            out.write("$contentId 0 obj\n<< /Length ${contentBytes.size} >>\nstream\n")
            out.write(contentBytes)
            out.write("\nendstream\nendobj\n")

            offsets[imageId] = out.n
            out.write(
                "$imageId 0 obj\n<< /Type /XObject /Subtype /Image /Width ${img.w} /Height ${img.h} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${img.jpeg.size} >>\nstream\n"
            )
            out.write(img.jpeg)
            out.write("\nendstream\nendobj\n")
        }

        val xrefPos = out.n
        out.write("xref\n0 ${objCount + 1}\n")
        out.write("0000000000 65535 f \n")
        for (id in 1..objCount) {
            out.write(String.format(Locale.US, "%010d 00000 n \n", offsets[id]))
        }
        out.write("trailer\n<< /Size ${objCount + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        out.s.flush()
    }
}
