package ru.scanpdf.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max

/** Экран ручной подгонки рамки листа. Возвращает 8 нормированных координат. */
class CropActivity : Activity() {

    private lateinit var cropView: CropView
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        val corners = intent.getFloatArrayExtra("corners") ?: ScanProcessor.FULL.copyOf()
        if (path == null) {
            finish()
            return
        }
        bitmap = loadScaled(path, 2000)
        val bmp = bitmap
        if (bmp == null) {
            Toast.makeText(this, "Не удалось открыть изображение", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.rgb(32, 32, 32))

        val hint = TextView(this)
        hint.text = "Перетащите углы так, чтобы рамка совпала с краями листа"
        hint.setTextColor(Color.WHITE)
        hint.textSize = 14f
        hint.gravity = Gravity.CENTER
        hint.setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        root.addView(hint)

        cropView = CropView(this)
        cropView.setBitmap(bmp, corners)
        root.addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())

        val auto = Button(this)
        auto.text = "Авто"
        auto.isAllCaps = false
        auto.setOnClickListener {
            cropView.setCorners(ScanProcessor.detectPage(bmp))
        }
        val whole = Button(this)
        whole.text = "Весь кадр"
        whole.isAllCaps = false
        whole.setOnClickListener {
            cropView.setCorners(ScanProcessor.FULL.copyOf())
        }
        val ok = Button(this)
        ok.text = "Готово"
        ok.isAllCaps = false
        ok.setOnClickListener {
            val data = Intent()
            data.putExtra("corners", cropView.getCorners())
            setResult(RESULT_OK, data)
            finish()
        }
        bar.addView(auto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(whole, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        setContentView(root)
    }

    private fun loadScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeFile(path, opts)
    }
}
