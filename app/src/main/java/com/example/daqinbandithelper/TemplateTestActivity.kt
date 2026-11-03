package com.example.daqinbandithelper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 模板测试：截一张游戏当前画面，对所选模板做匹配，把"命中点/框"画在图上显示出来。
 * 用途：当你发现机器人"找不到盗匪"时，先来这里截一张游戏里盗匪在场的图，
 * 看红框是否准确框中盗匪图标——框错了就回去做采集重截，比盲改参数高效得多。
 */
class TemplateTestActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var spinner: Spinner
    private lateinit var imageView: ImageView
    private lateinit var textResult: TextView
    private lateinit var btnTest: Button

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var resultCode = -1
    private var resultData: Intent? = null

    private val mediaProj = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            resultCode = res.resultCode
            resultData = res.data
            runTest()
        } else {
            Toast.makeText(this, "未授权录屏，无法测试", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.template_test)
        prefs = Prefs(this)
        spinner = findViewById(R.id.spinner_test_tpl)
        imageView = findViewById(R.id.img_test)
        textResult = findViewById(R.id.text_test_result)
        btnTest = findViewById(R.id.btn_test_shoot)

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            C.TPL_ORDER.map { C.TPL_LABELS[it] ?: it }
        )

        btnTest.setOnClickListener {
            val name = C.TPL_ORDER.getOrNull(spinner.selectedItemPosition) ?: C.TPL_BANDIT
            if (!prefs.hasTemplate(name)) {
                Toast.makeText(this, "请先采集「${C.TPL_LABELS[name]}」模板", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (resultData == null) {
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProj.launch(mpm.createScreenCaptureIntent())
                } catch (e: Exception) {
                    Toast.makeText(this, "申请录屏失败：$e", Toast.LENGTH_LONG).show()
                }
            } else {
                runTest()
            }
        }

        findViewById<Button>(R.id.btn_test_back).setOnClickListener { finish() }
    }

    private fun runTest() {
        val name = C.TPL_ORDER.getOrNull(spinner.selectedItemPosition) ?: C.TPL_BANDIT
        btnTest.isEnabled = false
        textResult.text = "截屏中…（请让游戏画面停留在有「${C.TPL_LABELS[name]}」的状态）"
        Thread {
            try {
                val frame = captureFrame()
                if (frame == null) {
                    runOnUiThread {
                        textResult.text = "❌ 截屏失败，请重试"
                        btnTest.isEnabled = true
                    }
                    return@Thread
                }
                val tpl = prefs.loadTemplate(name)!!
                val r = TemplateMatcher.match(frame, tpl, 0f)
                val annotated = annotate(frame, r, C.TPL_LABELS[name] ?: name)
                runOnUiThread {
                    imageView.setImageBitmap(annotated)
                    val verdict = when {
                        r.score >= 0.8f -> "✅ 能稳定识别，红框已框中目标"
                        r.score >= 0.6f -> "⚠ 偏弱，可能偶尔漏判。建议重截该模板(只框图标本身)，或把『匹配阈值』调到 0.7 左右"
                        else -> "❌ 几乎识别不到（最高分 ${"%.2f".format(r.score)}）。请重截「${C.TPL_LABELS[name]}」：只框图标/按钮本身，少带背景与透明边"
                    }
                    textResult.text = "模板：${C.TPL_LABELS[name]}\n最高分：${"%.2f".format(r.score)}\n$verdict"
                    btnTest.isEnabled = true
                }
                frame.recycle()
            } catch (e: Exception) {
                runOnUiThread {
                    textResult.text = "❌ 测试异常：${e.javaClass.simpleName}: ${e.message}"
                    btnTest.isEnabled = true
                }
            } finally {
                releaseProjection()
            }
        }.start()
    }

    private fun captureFrame(): Bitmap? {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
        val dm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        dm.defaultDisplay.getRealSize(size)
        val w = size.x
        val h = size.y
        val density = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "daqin_test", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        var img: Image? = null
        for (i in 0 until 40) {
            img = imageReader!!.acquireLatestImage()
            if (img != null) break
            try { Thread.sleep(50) } catch (_: Exception) {}
        }
        if (img == null) return null
        val bmp = imageToBitmap(img)
        img.close()
        return bmp
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        }
    }

    private fun annotate(src: Bitmap, r: TemplateMatcher.Result, label: String): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = (out.width / 220f).coerceAtLeast(3f)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = stroke
            isAntiAlias = true
        }
        val left = (r.x - r.w / 2f).coerceIn(0f, out.width.toFloat())
        val top = (r.y - r.h / 2f).coerceIn(0f, out.height.toFloat())
        val right = (left + r.w).coerceAtMost(out.width.toFloat())
        val bottom = (top + r.h).coerceAtMost(out.height.toFloat())
        canvas.drawRect(left, top, right, bottom, paint)
        // 十字准星
        canvas.drawLine(r.x.toFloat(), (top - 20).coerceAtLeast(0f), r.x.toFloat(), top, paint)
        canvas.drawLine((left - 20).coerceAtLeast(0f), r.y.toFloat(), left, r.y.toFloat(), paint)
        // 文字
        val tp = Paint().apply {
            color = Color.YELLOW
            textSize = (out.width / 38f).coerceAtLeast(18f)
            isAntiAlias = true
        }
        val ty = if (top - tp.textSize - 6 > 0) top - 6 else bottom + tp.textSize + 6
        canvas.drawText("$label 分=${"%.2f".format(r.score)}", left, ty, tp)
        return out
    }

    private fun releaseProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    override fun onDestroy() {
        releaseProjection()
        super.onDestroy()
    }
}
