package com.example.daqinbandithelper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

class CropActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var bmp: Bitmap? = null
    private var tpl: String? = null
    private var dispLeft = 0f
    private var dispTop = 0f
    private var dispScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.crop_activity)
        prefs = Prefs(this)

        val path = intent.getStringExtra("path")
        tpl = intent.getStringExtra(C.EXTRA_TPL)
        if (path == null) { finish(); return }
        bmp = BitmapFactory.decodeFile(path)
        if (bmp == null) { finish(); return }

        val imgView = findViewById<ImageView>(R.id.crop_image)
        imgView.setImageBitmap(bmp)
        val cropView = findViewById<CropView>(R.id.crop_view)

        imgView.post {
            val d = imgView.drawable ?: return@post
            val iw = d.intrinsicWidth.toFloat()
            val ih = d.intrinsicHeight.toFloat()
            val vw = imgView.width.toFloat()
            val vh = imgView.height.toFloat()
            if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) return@post
            dispScale = min(vw / iw, vh / ih)
            dispLeft = (vw - iw * dispScale) / 2f
            dispTop = (vh - ih * dispScale) / 2f
        }

        cropView.onRect = { r -> saveCrop(r) }
        findViewById<Button>(R.id.btn_crop_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_crop_save).setOnClickListener { cropView.triggerCrop() }
    }

    private fun saveCrop(r: Rect) {
        val src = bmp ?: return
        val bx = ((r.left - dispLeft) / dispScale).toInt().coerceIn(0, src.width)
        val by = ((r.top - dispTop) / dispScale).toInt().coerceIn(0, src.height)
        val bw = ((r.width()) / dispScale).toInt().coerceIn(1, src.width - bx)
        val bh = ((r.height()) / dispScale).toInt().coerceIn(1, src.height - by)
        if (bw < 4 || bh < 4) return
        val cropped = Bitmap.createBitmap(src, bx, by, bw, bh)
        val name = tpl ?: C.TPL_BANDIT
        prefs.saveTemplate(name, cropped)
        cropped.recycle()
        Toast.makeText(this, "已保存模板：${C.TPL_LABELS[name] ?: name}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
