package ru.scanpdf.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class PageItem(val id: String, val file: File, var corners: FloatArray, var thumb: Bitmap?)

class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK = 1
        const val REQ_CAMERA = 2
        const val REQ_CROP = 3
        const val MAX_SRC = 3600
        const val PDF_SIDE = 2339 // A4 при 200 dpi
        const val THUMB_SIDE = 700
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val pages = ArrayList<PageItem>()
    private var counter = 0

    @Volatile private var deskewOn = true
    @Volatile private var bwOn = false

    private var cameraUri: Uri? = null
    private var cropId: String? = null

    private lateinit var listBox: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var nameEdit: EditText
    private lateinit var pdfButton: Button
    private val d: Float get() = resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * d).roundToInt()

    // ------------------------------------------------------------------ UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)
        root.setPadding(dp(16), dp(20), dp(16), dp(12))

        val title = TextView(this)
        title.text = "Скан в PDF"
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.rgb(20, 30, 50))
        root.addView(title)

        val sub = TextView(this)
        sub.text = "Работает без интернета. Фон станет белым, цвет сохранится, текст выровняется."
        sub.textSize = 13f
        sub.setTextColor(Color.rgb(100, 110, 125))
        sub.setPadding(0, dp(2), 0, dp(10))
        root.addView(sub)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val pick = makeButton("Из галереи") { pickImages() }
        val cam = makeButton("Снять фото") { takePhoto() }
        btnRow.addView(pick, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(cam, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        val cbDeskew = CheckBox(this)
        cbDeskew.text = "Выровнять по горизонту"
        cbDeskew.isChecked = true
        cbDeskew.setOnCheckedChangeListener { _, checked ->
            deskewOn = checked
            rebuildAllThumbs()
        }
        root.addView(cbDeskew)

        val cbBw = CheckBox(this)
        cbBw.text = "Чёрно-белый (по умолчанию цветной)"
        cbBw.isChecked = false
        cbBw.setOnCheckedChangeListener { _, checked ->
            bwOn = checked
            rebuildAllThumbs()
        }
        root.addView(cbBw)

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        emptyText = TextView(this)
        emptyText.text = "Добавьте фото документов - их можно выбрать из галереи, снять камерой или " +
            "отправить в это приложение через «Поделиться»."
        emptyText.textSize = 15f
        emptyText.setTextColor(Color.rgb(120, 128, 140))
        emptyText.setPadding(dp(4), dp(24), dp(4), dp(24))
        emptyText.gravity = Gravity.CENTER

        val scroll = ScrollView(this)
        scroll.addView(
            listBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        nameEdit = EditText(this)
        nameEdit.hint = "Имя файла (необязательно)"
        nameEdit.isSingleLine = true
        root.addView(nameEdit)

        pdfButton = makeButton("Создать PDF") { createPdf() }
        root.addView(pdfButton)

        setContentView(root)
        refreshList()
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun smallButton(text: String, onClick: () -> Unit): Button {
        val b = makeButton(text, onClick)
        b.minWidth = 0
        b.minimumWidth = 0
        b.textSize = 13f
        b.setPadding(dp(10), 0, dp(10), 0)
        return b
    }

    private fun refreshList() {
        listBox.removeAllViews()
        if (pages.isEmpty()) {
            listBox.addView(emptyText)
        }
        for ((index, page) in pages.withIndex()) {
            listBox.addView(buildCard(index, page))
        }
        pdfButton.text = if (pages.isEmpty()) "Создать PDF" else "Создать PDF (страниц: ${pages.size})"
        pdfButton.isEnabled = pages.isNotEmpty()
    }

    private fun buildCard(index: Int, page: PageItem): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.setPadding(dp(8), dp(8), dp(8), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.rgb(242, 245, 250))
        bg.cornerRadius = dp(10).toFloat()
        card.background = bg
        val cardLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cardLp.bottomMargin = dp(8)
        card.layoutParams = cardLp

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setBackgroundColor(Color.WHITE)
        val thumb = page.thumb
        if (thumb != null) img.setImageBitmap(thumb)
        img.setOnClickListener { openCrop(page) }
        card.addView(img, LinearLayout.LayoutParams(dp(96), dp(136)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), 0, 0, 0)

        val label = TextView(this)
        label.text = "Страница ${index + 1}"
        label.textSize = 16f
        label.setTypeface(null, Typeface.BOLD)
        label.setTextColor(Color.rgb(20, 30, 50))
        col.addView(label)

        val hint = TextView(this)
        hint.text = "нажмите на миниатюру, чтобы поправить рамку"
        hint.textSize = 12f
        hint.setTextColor(Color.rgb(120, 128, 140))
        col.addView(hint)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.addView(smallButton("↑") { movePage(index, -1) })
        row1.addView(smallButton("↓") { movePage(index, 1) })
        row1.addView(smallButton("⟳") { rotatePage(page) })
        col.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.addView(smallButton("Рамка") { openCrop(page) })
        row2.addView(smallButton("Удалить") { deletePage(page) })
        col.addView(row2)

        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    // ------------------------------------------------------------------ добавление изображений

    private fun pickImages() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            toast("Не найдено приложение для выбора файлов")
        }
    }

    private fun takePhoto() {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "scan_" + System.currentTimeMillis() + ".jpg")
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScanPdf")
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Не удалось подготовить файл для камеры")
            return
        }
        cameraUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        try {
            startActivityForResult(i, REQ_CAMERA)
        } catch (e: ActivityNotFoundException) {
            contentResolver.delete(uri, null, null)
            cameraUri = null
            toast("Не найдено приложение камеры")
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (list != null) uris.addAll(list)
            }
        }
        if (uris.isNotEmpty()) {
            intent.action = Intent.ACTION_MAIN // чтобы не добавлять повторно при пересоздании
            importUris(uris)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) {
                    for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
                } else {
                    val u = data.data
                    if (u != null) uris.add(u)
                }
                importUris(uris)
            }
            REQ_CAMERA -> {
                val u = cameraUri
                cameraUri = null
                if (u == null) return
                if (resultCode == RESULT_OK) {
                    importUris(listOf(u))
                } else {
                    contentResolver.delete(u, null, null)
                }
            }
            REQ_CROP -> {
                val id = cropId
                cropId = null
                if (resultCode != RESULT_OK || data == null || id == null) return
                val c = data.getFloatArrayExtra("corners") ?: return
                val page = pages.firstOrNull { it.id == id } ?: return
                page.corners = c
                executor.execute { makeThumb(page) }
            }
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val dlg = showBusy("Загрузка фото…")
        executor.execute {
            var failed = 0
            for (u in uris) {
                try {
                    val bmp = decodeUri(u)
                    counter++
                    val id = System.currentTimeMillis().toString() + "_" + counter
                    val f = File(cacheDir, "src_$id.jpg")
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    val corners = ScanProcessor.detectPage(bmp)
                    bmp.recycle()
                    val item = PageItem(id, f, corners, null)
                    handler.post {
                        pages.add(item)
                        refreshList()
                    }
                    makeThumb(item)
                } catch (e: Throwable) {
                    failed++
                }
            }
            handler.post {
                dlg.dismiss()
                if (failed > 0) toast("Не удалось открыть файлов: $failed")
            }
        }
    }

    /** Декодирует изображение (в том числе HEIC средствами системы) с учётом поворота EXIF. */
    private fun decodeUri(uri: Uri): Bitmap {
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = max(info.size.width, info.size.height)
                if (m > MAX_SRC) {
                    val s = MAX_SRC.toFloat() / m
                    decoder.setTargetSize(
                        max(1, (info.size.width * s).roundToInt()),
                        max(1, (info.size.height * s).roundToInt())
                    )
                }
            }
        } catch (e: Throwable) {
            // запасной путь ниже
        }
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("нет данных")
        val decoded = stream.use { BitmapFactory.decodeStream(it) }
        return decoded ?: throw IOException("не изображение")
    }

    // ------------------------------------------------------------------ действия со страницами

    private fun movePage(index: Int, delta: Int) {
        val j = index + delta
        if (index < 0 || index >= pages.size || j < 0 || j >= pages.size) return
        val tmp = pages[index]
        pages[index] = pages[j]
        pages[j] = tmp
        refreshList()
    }

    private fun deletePage(page: PageItem) {
        pages.remove(page)
        page.file.delete()
        refreshList()
    }

    private fun openCrop(page: PageItem) {
        cropId = page.id
        val i = Intent(this, CropActivity::class.java)
        i.putExtra("path", page.file.path)
        i.putExtra("corners", page.corners)
        startActivityForResult(i, REQ_CROP)
    }

    private fun rotatePage(page: PageItem) {
        val dlg = showBusy("Поворот…")
        executor.execute {
            try {
                val bmp = loadFile(page.file, MAX_SRC)
                if (bmp != null) {
                    val m = Matrix()
                    m.postRotate(90f)
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    if (rotated !== bmp) bmp.recycle()
                    FileOutputStream(page.file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    page.corners = ScanProcessor.detectPage(rotated)
                    rotated.recycle()
                    makeThumb(page)
                }
            } catch (e: Throwable) {
                handler.post { toast("Не удалось повернуть") }
            }
            handler.post { dlg.dismiss() }
        }
    }

    private fun rebuildAllThumbs() {
        val snapshot = pages.toList()
        if (snapshot.isEmpty()) return
        executor.execute {
            for (p in snapshot) makeThumb(p)
        }
    }

    /** Считает миниатюру (уже обработанную) и обновляет список. Вызывать из фонового потока. */
    private fun makeThumb(page: PageItem) {
        try {
            val src = loadFile(page.file, THUMB_SIDE) ?: return
            val out = ScanProcessor.process(src, page.corners, bwOn, deskewOn, THUMB_SIDE)
            src.recycle()
            handler.post {
                page.thumb = out
                refreshList()
            }
        } catch (e: Throwable) {
            // миниатюра не критична
        }
    }

    private fun loadFile(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inMutable = false
        return BitmapFactory.decodeFile(file.path, opts)
    }

    // ------------------------------------------------------------------ PDF

    private fun createPdf() {
        if (pages.isEmpty()) {
            toast("Сначала добавьте фото")
            return
        }
        val snapshot = pages.toList()
        val baseName = cleanName(nameEdit.text.toString())
        val dlg = showBusy("Создание PDF…")
        executor.execute {
            try {
                val imgs = ArrayList<PdfWriter.Img>()
                for (p in snapshot) {
                    val src = loadFile(p.file, MAX_SRC) ?: continue
                    val out = ScanProcessor.process(src, p.corners, bwOn, deskewOn, PDF_SIDE)
                    src.recycle()
                    val bos = ByteArrayOutputStream()
                    out.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                    imgs.add(PdfWriter.Img(bos.toByteArray(), out.width, out.height))
                    out.recycle()
                }
                if (imgs.isEmpty()) throw IOException("нет страниц")

                val fileName = "$baseName.pdf"
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanPdf")
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("не удалось создать файл")
                val os = contentResolver.openOutputStream(uri) ?: throw IOException("не удалось записать файл")
                os.use { PdfWriter.write(it, imgs) }
                handler.post {
                    dlg.dismiss()
                    showDone(uri, fileName)
                }
            } catch (e: Throwable) {
                handler.post {
                    dlg.dismiss()
                    toast("Ошибка: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun cleanName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim('.', ' ')
        if (cleaned.isNotEmpty()) return cleaned
        return "Скан_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private fun showDone(uri: Uri, fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("PDF готов")
            .setMessage("Сохранён в папке «Загрузки/ScanPdf»:\n$fileName")
            .setPositiveButton("Открыть") { _, _ ->
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, "application/pdf")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    toast("Нет приложения для просмотра PDF")
                }
            }
            .setNeutralButton("Поделиться") { _, _ ->
                val i = Intent(Intent.ACTION_SEND)
                i.type = "application/pdf"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Отправить PDF"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ------------------------------------------------------------------ утилиты

    private fun showBusy(message: String): AlertDialog {
        val dlg = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .create()
        dlg.show()
        return dlg
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
