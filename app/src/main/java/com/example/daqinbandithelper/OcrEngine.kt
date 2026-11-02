package com.example.daqinbandithelper

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ML Kit 中文 OCR 封装（阻塞式，带超时；失败返回 null，绝不抛异常）。 */
object OcrEngine {
    private val client = try {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    } catch (e: Exception) {
        null
    }

    fun recognize(bitmap: Bitmap, timeoutMs: Long = 2000): String? {
        val c = client ?: return null
        val latch = CountDownLatch(1)
        var text: String? = null
        try {
            c.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { v: Text -> text = v.text; latch.countDown() }
                .addOnFailureListener { latch.countDown() }
        } catch (e: Exception) {
            return null
        }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return null
        }
        return text
    }
}
