package com.example.daqinbandithelper

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 实现的模板匹配（归一化互相关 NCC，与 OpenCV matchTemplate TM_CCOEFF_NORMED 同原理）。
 * 零原生依赖，避免 OpenCV 原生库加载失败导致崩溃。
 *
 * 关键点（v4.0.8 重写）：
 * 旧版把帧图和模板按"同一比例 s"一起缩放，且 s 受 MIN_TPL/MAX_TPL 约束。
 * 当模板是在不同缩放比下截的（例如旧版本把截图压到 480 长边，模板只有 ~14px；而现版本
 * 原图 2772 长边，盗匪在屏上约 80px），s 被迫取 1，模板永远缩不上去，NCC 只有 0.3 左右，
 * 表现为"一直找不到盗匪"。
 *
 * 现改为【多尺度 / 尺度无关】匹配：
 *  1) 帧图只往下缩到 MAX_FRAME 以内（控制耗时）；
 *  2) 用"绝对目标边长"在【工作图坐标系】下穷举若干尺度（16~80px），把模板分别缩到这些边长
 *     去匹配——无论你当初把模板截大截小、游戏缩放多少，总能找到最贴合的一档；
 *  3) 粗搜 + 最优点 ±3px 精修，保证点击坐标准确；
 *  4) 未命中也返回全局最高分（score）与命中框尺寸（w,h），便于诊断"差多少 / 框在哪"。
 *  5) 命中即提前退出，正常匹配很快；只有真正找不到时才会扫完所有尺度。
 */
object TemplateMatcher {

    data class Result(
        val found: Boolean,
        val x: Int,        // 命中框中心 X（原图像素）
        val y: Int,        // 命中框中心 Y（原图像素）
        val score: Float,  // 最高 NCC 相关系数（-1~1）
        val w: Int,        // 命中框宽（原图像素）
        val h: Int         // 命中框高（原图像素）
    )

    /** 帧图长边上限（越大越准越慢） */
    private const val MAX_FRAME = 640

    /** 在工作图坐标系下尝试的模板长边（px）。覆盖小图标到大按钮，密集在小尺寸段。 */
    private val TARGET_LONGS = intArrayOf(16, 24, 34, 46, 60, 80)

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
        if (scale >= 0.999f && scale <= 1.001f) return src
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 在 frame 中匹配 template（尺度无关）。
     * @param threshold NCC 阈值（0.5~0.98，越大越严格）。传 0 表示"只取最高分、不判命中"，用于自测。
     * @return 未命中时 found=false，但 score 仍是全局最高相关系数，w/h 为最优点对应模板尺寸（原图坐标系）。
     */
    fun match(frame: Bitmap, template: Bitmap, threshold: Float): Result {
        val frameMax = max(frame.width, frame.height)
        val tplW = template.width
        val tplH = template.height
        val tplMax = max(tplW, tplH)
        if (frameMax <= 0 || tplMax <= 0) return Result(false, 0, 0, -2f, 0, 0)

        // ① 帧图只往下缩，模板保持原图（在各尺度下分别缩放去匹配）
        val fs = min(1f, MAX_FRAME.toFloat() / frameMax)
        val fBmp = scale(frame, fs)
        val fw = fBmp.width
        val fh = fBmp.height
        if (fw < 8 || fh < 8) return Result(false, 0, 0, -2f, 0, 0)
        val f = gray(fBmp)

        // 积分图：O(1) 求任意窗口和
        val w1 = fw + 1
        val ii = FloatArray(w1 * (fh + 1))
        for (y in 0 until fh) {
            var row = 0f
            val rowBase = y * fw
            for (x in 0 until fw) {
                row += f[rowBase + x]
                ii[(y + 1) * w1 + (x + 1)] = ii[y * w1 + (x + 1)] + row
            }
        }

        var best = -2f
        var bx = 0
        var by = 0
        var bestTW = 0
        var bestTH = 0
        var bestT: FloatArray? = null
        var bestTMean = 0f
        var bestTStd = 0f
        var bestArea = 0f

        for (target in TARGET_LONGS) {
            if (target > min(fw, fh) / 2) break
            val sc = target.toFloat() / tplMax
            if (sc <= 0f) continue
            val tw = max(6, (tplW * sc).toInt())
            val th = max(6, (tplH * sc).toInt())
            if (tw > fw || th > fh) continue

            val tBmp = scale(template, sc)
            val t = gray(tBmp)
            var tMean = 0f
            for (v in t) tMean += v
            tMean /= t.size
            var tVar = 0f
            for (v in t) tVar += (v - tMean) * (v - tMean)
            val tStd = sqrt(tVar)
            if (tStd < 1e-4f) continue
            val area = (tw * th).toFloat()

            // 大模板用更大步长，缩小扫描量
            val step = if (tw >= 64) max(2, fw / 80) else max(1, fw / 130)

            var lb = -2f
            var lx = 0
            var ly = 0
            var y = 0
            while (y <= fh - th) {
                var x = 0
                while (x <= fw - tw) {
                    val ncc = nccAt(f, ii, w1, t, tMean, tStd, x, y, tw, th, fw, area)
                    if (ncc > lb) {
                        lb = ncc
                        lx = x
                        ly = y
                    }
                    x += step
                }
                y += step
            }

            if (lb > best) {
                best = lb
                bx = lx
                by = ly
                bestTW = tw
                bestTH = th
                bestT = t
                bestTMean = tMean
                bestTStd = tStd
                bestArea = area
            }
            // 命中即提前退出（后续更大尺度几乎不会更准，且省时间）
            if (threshold > 0f && best >= threshold) break
        }

        if (best < -1.5f || bestT == null) return Result(false, 0, 0, -2f, 0, 0)

        // ② 精修：在最优尺度、最优点附近 ±3px 逐像素搜，保证点得准
        val ry0 = max(0, by - 3)
        val ry1 = min(fh - bestTH, by + 3)
        val rx0 = max(0, bx - 3)
        val rx1 = min(fw - bestTW, bx + 3)
        var ry = by
        var rx = bx
        for (yy in ry0..ry1) {
            for (xx in rx0..rx1) {
                val ncc = nccAt(f, ii, w1, bestT!!, bestTMean, bestTStd, xx, yy, bestTW, bestTH, fw, bestArea)
                if (ncc > best) {
                    best = ncc
                    rx = xx
                    ry = yy
                }
            }
        }

        val found = best >= threshold
        val fullX = ((rx + bestTW / 2) / fs).toInt()
        val fullY = ((ry + bestTH / 2) / fs).toInt()
        val fullW = (bestTW / fs).toInt()
        val fullH = (bestTH / fs).toInt()
        return Result(found, fullX, fullY, best, fullW, fullH)
    }

    /** 单个位置的 NCC（窗口均值走积分图） */
    private fun nccAt(
        f: FloatArray, ii: FloatArray, w1: Int,
        t: FloatArray, tMean: Float, tStd: Float,
        x: Int, y: Int, tw: Int, th: Int, fw: Int, area: Float
    ): Float {
        val sum = ii[(y + th) * w1 + (x + tw)] - ii[y * w1 + (x + tw)] -
                ii[(y + th) * w1 + x] + ii[y * w1 + x]
        val fMean = sum / area
        var num = 0f
        var fVar = 0f
        for (j in 0 until th) {
            val fbase = (y + j) * fw + x
            val tbase = j * tw
            for (i in 0 until tw) {
                val fd = f[fbase + i] - fMean
                num += fd * (t[tbase + i] - tMean)
                fVar += fd * fd
            }
        }
        val fStd = sqrt(fVar)
        val den = fStd * tStd
        return if (den > 1e-4f) num / den else -2f
    }
}
