package com.example.daqinbandithelper

import android.graphics.Bitmap
import kotlin.math.maxOf
import kotlin.math.minOf
import kotlin.math.sqrt

/**
 * 纯 Kotlin 实现的模板匹配（归一化互相关 NCC，与 OpenCV matchTemplate TM_CCOEFF_NORMED 同原理）。
 * 零原生依赖，避免 OpenCV 原生库加载失败导致崩溃。
 */
object TemplateMatcher {

    data class Result(val found: Boolean, val x: Int, val y: Int, val score: Float)

    /** 转灰度亮度数组 */
    private fun gray(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val gc = (c shr 8) and 0xFF
            val b = c and 0xFF
            g[i] = 0.299f * r + 0.587f * gc + 0.114f * b
        }
        return g
    }

    private fun scale(src: Bitmap, scale: Float): Bitmap {
        if (scale >= 0.999f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 在 frame 中匹配 template。
     * @param threshold NCC 阈值（0.5~0.98，越大越严格）
     * @param maxDim 缩放到的最大边长，用于加速（坐标会自动映射回原图）
     */
    fun match(frame: Bitmap, template: Bitmap, threshold: Float, maxDim: Int = 480): Result {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(frame.width, frame.height))
        val fBmp = scale(frame, scale)
        val tBmp = scale(template, scale)
        val fw = fBmp.width
        val fh = fBmp.height
        val tw = tBmp.width
        val th = tBmp.height
        if (tw > fw || th > fh) return Result(false, 0, 0, -2f)

        val f = gray(fBmp)
        val t = gray(tBmp)

        // 模板均值 / 标准差
        var tMean = 0f
        for (v in t) tMean += v
        tMean /= t.size
        var tVar = 0f
        for (v in t) tVar += (v - tMean) * (v - tMean)
        val tStd = sqrt(tVar)
        if (tStd < 1e-4f) return Result(false, 0, 0, -2f)

        val step = maxOf(1, fw / 180)
        var best = -2f
        var bx = 0
        var by = 0

        for (y in 0..fh - th step step) {
            for (x in 0..fw - tw step step) {
                // 窗口均值
                var fSum = 0f
                for (j in 0 until th) {
                    val base = (y + j) * fw + x
                    for (i in 0 until tw) fSum += f[base + i]
                }
                val fMean = fSum / (tw * th)
                // 互相关
                var num = 0f
                var fVar = 0f
                for (j in 0 until th) {
                    val fbase = (y + j) * fw + x
                    val tbase = j * tw
                    for (i in 0 until tw) {
                        val fd = f[fbase + i] - fMean
                        val td = t[tbase + i] - tMean
                        num += fd * td
                        fVar += fd * fd
                    }
                }
                val fStd = sqrt(fVar)
                val den = fStd * tStd
                val ncc = if (den > 1e-4f) num / den else -2f
                if (ncc > best) {
                    best = ncc
                    bx = x + tw / 2
                    by = y + th / 2
                }
            }
        }

        val found = best >= threshold
        val fullX = (bx / scale).toInt()
        val fullY = (by / scale).toInt()
        return Result(found, fullX, fullY, best)
    }
}
