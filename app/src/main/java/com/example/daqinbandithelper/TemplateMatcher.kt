package com.example.daqinbandithelper

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 实现的模板匹配（归一化互相关 NCC，与 OpenCV matchTemplate TM_CCOEFF_NORMED 同原理）。
 * 零原生依赖，避免 OpenCV 原生库加载失败导致崩溃。
 *
 * 关键点（v4.0.6 修正）：
 * 旧版把帧图和模板一起按「帧图长边缩到 480」的比例缩放。1344x2772 的手机上该比例仅 0.17，
 * 盗匪头像模板被压成约 14x14 像素，细节全部丢失 → NCC 永远够不到阈值，表现为「一直找不到盗匪」。
 * 现改为：
 *  1) 缩放系数同时受两个约束：帧图长边 ≤ MAX_FRAME（控制耗时），
 *     且缩放后模板长边 ≥ MIN_TPL（保证模板仍有足够细节，NCC 才可靠）；
 *  2) 用积分图 O(1) 求窗口均值，把逐位置的开销减半；
 *  3) 粗搜后做一次 ±2 像素的精修，保证点击坐标准确；
 *  4) 未命中时也返回最高分（score），便于诊断"差多少"。
 */
object TemplateMatcher {

    data class Result(val found: Boolean, val x: Int, val y: Int, val score: Float)

    /** 帧图长边上限（越大越准越慢） */
    private const val MAX_FRAME = 900

    /** 缩放后模板长边下限（小于这个尺寸细节丢失，NCC 失效） */
    private const val MIN_TPL = 28

    /** 缩放后模板长边上限（模板太大搜索耗时暴涨） */
    private const val MAX_TPL = 48

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
     * @return 未命中时 found=false，但 score 仍是最高相关系数（用于诊断）
     */
    fun match(frame: Bitmap, template: Bitmap, threshold: Float): Result {
        val frameMax = max(frame.width, frame.height)
        val tplMax = max(template.width, template.height)
        if (frameMax <= 0 || tplMax <= 0) return Result(false, 0, 0, -2f)

        // ① 选缩放系数：帧图压到 MAX_FRAME 以内；模板缩放后落在 [MIN_TPL, MAX_TPL] 区间
        var s = min(1f, MAX_FRAME.toFloat() / frameMax)
        val minS = min(1f, MIN_TPL.toFloat() / tplMax)
        if (s < minS) s = minS
        val maxS = MAX_TPL.toFloat() / tplMax
        if (s > maxS) s = max(maxS, minS)
        if (s > 1f) s = 1f

        val fBmp = scale(frame, s)
        val tBmp = scale(template, s)
        val fw = fBmp.width
        val fh = fBmp.height
        val tw = tBmp.width
        val th = tBmp.height
        if (tw < 6 || th < 6 || tw > fw || th > fh) return Result(false, 0, 0, -2f)

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

        // ② 积分图：O(1) 求任意窗口的和
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

        val area = (tw * th).toFloat()
        val step = max(1, fw / 160)

        var best = -2f
        var bx = 0
        var by = 0

        // 粗搜
        var y = 0
        while (y <= fh - th) {
            var x = 0
            while (x <= fw - tw) {
                val ncc = nccAt(f, ii, w1, t, tMean, tStd, x, y, tw, th, fw, area)
                if (ncc > best) {
                    best = ncc
                    bx = x
                    by = y
                }
                x += step
            }
            y += step
        }
        if (best < -1.5f) return Result(false, 0, 0, -2f)

        // ③ 精修：在粗搜最优点附近 ±2 像素内逐像素搜索，保证点击坐标准确
        var ry = by
        var rx = bx
        val ry0 = max(0, by - 2)
        val ry1 = min(fh - th, by + 2)
        val rx0 = max(0, bx - 2)
        val rx1 = min(fw - tw, bx + 2)
        for (yy in ry0..ry1) {
            for (xx in rx0..rx1) {
                val ncc = nccAt(f, ii, w1, t, tMean, tStd, xx, yy, tw, th, fw, area)
                if (ncc > best) {
                    best = ncc
                    rx = xx
                    ry = yy
                }
            }
        }

        val found = best >= threshold
        // 映射回原图坐标（取窗口中心）
        val fullX = ((rx + tw / 2) / s).toInt()
        val fullY = ((ry + th / 2) / s).toInt()
        return Result(found, fullX, fullY, best)
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
