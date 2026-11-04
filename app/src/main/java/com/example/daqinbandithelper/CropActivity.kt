package com.example.daqinbandithelper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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
        cropView.onDrag = { r -> updateInfo(r) }
        findViewById<Button>(R.id.btn_crop_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_crop_save).setOnClickListener { cropView.triggerCrop() }
    }

    /** 屏幕坐标矩形 → 原图像素矩形 */
    private fun toSource(r: Rect): Rect {
        val src = bmp ?: return Rect()
        val bx = ((r.left - dispLeft) / dispScale).toInt().coerceIn(0, src.width)
        val by = ((r.top - dispTop) / dispScale).toInt().coerceIn(0, src.height)
        val bw = (r.width() / dispScale).toInt().coerceIn(1, src.width - bx)
        val bh = (r.height() / dispScale).toInt().coerceIn(1, src.height - by)
        return Rect(bx, by, bx + bw, by + bh)
    }

    /** 选区灰度标准差：越大说明细节越丰富，越适合当模板 */
    private fun detailScore(r: Rect): Float {
        val src = bmp ?: return 0f
        val w = r.width()
        val h = r.height()
        if (w < 2 || h < 2) return 0f
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, r.left, r.top, w, h)
        var sum = 0f
        for (c in px) sum += gray(c)
        val mean = sum / px.size
        var v = 0f
        for (c in px) {
            val d = gray(c) - mean
            v += d * d
        }
        return kotlin.math.sqrt(v / px.size)
    }

    private fun gray(c: Int) =
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    /** 实时提示：选区在原图中的实际像素尺寸 + 细节是否够 + 大小是否合适 */
    private fun updateInfo(viewRect: Rect) {
        val tv = findViewById<TextView>(R.id.tv_crop_info) ?: return
        if (dispScale <= 0f) {
            tv.text = "拖动框选：只框按钮/图标本身，60~200 像素见方。同一按钮建议截 2~3 张不同状态（常态/高亮/半透明）"
            return
        }
        val sr = toSource(viewRect)
        val w = sr.width()
        val h = sr.height()
        if (w < 4 || h < 4) {
            tv.text = "选区太小（${w}×${h}px），请拖大一点"
            return
        }
        val d = detailScore(sr)
        val sizeTip = when {
            maxOf(w, h) < 40 -> " ⚠ 偏小，建议 ≥40px"
            maxOf(w, h) > 260 -> " ⚠ 偏大，建议 ≤200px（会拖慢且易误匹配）"
            else -> " ✅ 大小合适"
        }
        val detailTip = when {
            d < 6f -> " ⚠ 纯色区域，匹配不到，请框有花纹/文字的部分"
            d < 14f -> " ⚠ 细节偏平，建议框得更紧凑"
            else -> " ✅ 细节充足"
        }
        tv.text = "选区 ${w}×${h}px（原图）｜细节 ${"%.0f".format(d)}$sizeTip$detailTip"
    }

    private fun saveCrop(r: Rect) {
        val src = bmp ?: return
        if (dispScale <= 0f) return
        val sr = toSource(r)
        val bx = sr.left
        val by = sr.top
        val bw = sr.width()
        val bh = sr.height()
        if (bw < 16 || bh < 16) {
            Toast.makeText(this, "选区太小（${bw}×${bh}px），请框大一点", Toast.LENGTH_SHORT).show()
            return
        }
        val cropped = Bitmap.createBitmap(src, bx, by, bw, bh)
        val name = tpl ?: C.TPL_BANDIT
        prefs.saveTemplate(name, cropped)
        val n = prefs.templateCount(name)
        val d = detailScore(sr)
        // 自测：用刚截的整图去匹配这张模板，分数应接近 1.0；偏低说明截歪/截错
        val self = try {
            TemplateMatcher.match(bmp!!, cropped, 0f)
        } catch (_: Exception) { null }
        cropped.recycle()
        val selfScore = self?.score ?: -1f
        Diag.log("模板", "保存 ${C.TPL_LABELS[name] ?: name} #${n - 1} ${bw}×${bh}px 细节=${"%.0f".format(d)} 自测分=${"%.2f".format(selfScore)}")
        val warn = when {
            selfScore in 0.0f..0.6f -> "（自测分仅 ${"%.2f".format(selfScore)}，可能没截中目标，建议重截）"
            d < 8f -> "（细节偏低，可能匹配不到）"
            else -> ""
        }
        val tip = if (n < 3) "\n可换一种状态再点📷截图追加（建议 2~3 张）" else "\n已采集 $n 张，足够"
        Toast.makeText(this, "已保存「${C.TPL_LABELS[name] ?: name}」第$n张 ${bw}×${bh}px$warn$tip", Toast.LENGTH_SHORT).show()
        finish()
    }
}
