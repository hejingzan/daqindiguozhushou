package com.example.daqinbandithelper

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 模板匹配（归一化互相关 NCC，与 OpenCV matchTemplate TM_CCOEFF_NORMED 同原理）。
 * 零原生依赖，避免 OpenCV 原生库加载失败导致崩溃。
 *
 * v4.0.9 重写要点（修复"缩放不一致 / 永远 0.7 分"）：
 * 旧版在【工作图】下用"绝对像素边长"(16~80px)穷举尺度，但用户截的模板是设备原分辨率
 * （例如 90px），缩到工作图后实际只有 ~20px，落在 16 与 24 两个档位之间，永远到不了真实大小，
 * 于是 NCC 卡在 0.3~0.7。
 *
 * 现改为【尺度相对】搜索：
 *  1) 帧图只往下缩到 MAX_FRAME 以内；
 *  2) 以模板"自身尺寸在工作图中的自然大小(natural = tplMax * fs)"为中心，乘以 0.3~3.0 倍穷举尺度，
 *     这样无论当初怎么截、游戏地图放大/缩小到什么程度，总能有一档贴近真实大小；
 *  3) 同时跑【灰度 NCC】和【边缘 NCC】，取两者较高者——边缘对背景色/抗锯齿不敏感，
 *     在图标/按钮大小随缩放变化、或模板带了一点背景时都更稳；
 *  4) 粗扫 + 最优点 ±step 逐像素精修，保证点得准；
 *  5) 命中（达到阈值）即提前退出，正常很快；只有真找不到时才扫完所有尺度。
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
    private const val MAX_FRAME = 600

    /**
     * 相对模板自身尺寸（经帧图缩放后）的尺度搜索：0.3x~3.0x。
     * 覆盖游戏中地图放大/缩小导致图标大小变化的全部情形。1.0 强制包含（最贴近原截尺寸）。
     */
    private val SCALES: FloatArray by lazy {
        val l = mutableListOf<Float>()
        var s = 0.30f
        while (s <= 3.01f) { l.add(s); s *= 1.2f }
        l.add(1.0f)
        l.sort()
        l.toFloatArray()
    }

    private fun gray(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        return g
    }

    /** Sobel 梯度幅值（边缘图）：对背景色/亮度变化不敏感，缩放也较 tolerant */
    private fun edges(g: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val gx = g[i + 1] - g[i - 1]
                val gy = g[i + w] - g[i - w]
                out[i] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    private fun scale(src: Bitmap, s: Float): Bitmap {
        if (s >= 0.999f && s <= 1.001f) return src
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 积分图：sum 与 sum²，用于 O(1) 求任意窗口均值与方差 */
    private fun integral(a: FloatArray, fw: Int, fh: Int): Pair<FloatArray, FloatArray> {
        val w1 = fw + 1
        val iSum = FloatArray(w1 * (fh + 1))
        val iSq = FloatArray(w1 * (fh + 1))
        for (y in 0 until fh) {
            var rs = 0f
            var rq = 0f
            val rb = y * fw
            for (x in 0 until fw) {
                val v = a[rb + x]
                rs += v
                rq += v * v
                iSum[(y + 1) * w1 + (x + 1)] = iSum[y * w1 + (x + 1)] + rs
                iSq[(y + 1) * w1 + (x + 1)] = iSq[y * w1 + (x + 1)] + rq
            }
        }
        return iSum to iSq
    }

    private fun meanStd(a: FloatArray): Pair<Float, Float> {
        var m = 0f
        for (v in a) m += v
        m /= a.size
        var v = 0f
        for (x in a) v += (x - m) * (x - m)
        return m to sqrt(v / a.size)
    }

    /**
     * 在 frame 中匹配 template（尺度相对、灰度+边缘双路 NCC）。
     * @param threshold NCC 阈值（0.5~0.98，越大越严格）。传 0 表示"只取最高分、不判命中"，用于自测。
     */
    fun match(frame: Bitmap, template: Bitmap, threshold: Float): Result {
        val frameMax = max(frame.width, frame.height)
        val tplW = template.width
        val tplH = template.height
        val tplMax = max(tplW, tplH)
        if (frameMax <= 0 || tplMax <= 0) return Result(false, 0, 0, -2f, 0, 0)

        // ① 帧图只往下缩；模板保持原图（在各尺度下分别缩放去匹配）
        val fs = min(1f, MAX_FRAME.toFloat() / frameMax)
        val fBmp = scale(frame, fs)
        val fw = fBmp.width
        val fh = fBmp.height
        if (fw < 8 || fh < 8) return Result(false, 0, 0, -2f, 0, 0)

        val fGray = gray(fBmp)
        val fEdge = edges(fGray, fw, fh)
        val (igS, igQ) = integral(fGray, fw, fh)
        val (ieS, ieQ) = integral(fEdge, fw, fh)
        val w1 = fw + 1

        // 模板在工作图中的"自然大小"——尺度搜索以它为中心（×0.3 ~ ×3.0）
        val natural = tplMax * fs

        var best = -2f
        var bx = 0
        var by = 0
        var bestTW = 0
        var bestTH = 0

        for (s in SCALES) {
            val target = natural * s
            if (target < 6f || target > min(fw, fh) * 0.95f) continue
            val sc = target / tplMax
            val tw = max(6, (tplW * sc).toInt())
            val th = max(6, (tplH * sc).toInt())
            if (tw > fw || th > fh) continue

            val tBmp = scale(template, sc)
            val tGray = gray(tBmp)
            val tEdge = edges(tGray, tw, th)
            tBmp.recycle()
            val (tgM, tgS) = meanStd(tGray)
            val (teM, teS) = meanStd(tEdge)
            if (tgS < 1e-3f && teS < 1e-3f) continue
            val area = (tw * th).toFloat()

            // 大模板用更大步长，缩小扫描量
            val step = max(2, tw / 4)

            var lb = -2f
            var lx = 0
            var ly = 0
            var y = 0
            while (y <= fh - th) {
                var x = 0
                while (x <= fw - tw) {
                    val n = ncc(fGray, igS, igQ, w1, tGray, tgM, tgS, x, y, tw, th, fw, area)
                    val e = if (teS >= 1e-3f) ncc(fEdge, ieS, ieQ, w1, tEdge, teM, teS, x, y, tw, th, fw, area) else n
                    val v = if (n > e) n else e
                    if (v > lb) {
                        lb = v
                        lx = x
                        ly = y
                    }
                    x += step
                }
                y += step
            }

            // ② 精修：最优点 ±step 范围内逐像素搜，保证点得准
            val ry0 = max(0, ly - step)
            val ry1 = min(fh - th, ly + step)
            val rx0 = max(0, lx - step)
            val rx1 = min(fw - tw, lx + step)
            for (yy in ry0..ry1) {
                for (xx in rx0..rx1) {
                    val n = ncc(fGray, igS, igQ, w1, tGray, tgM, tgS, xx, yy, tw, th, fw, area)
                    val e = if (teS >= 1e-3f) ncc(fEdge, ieS, ieQ, w1, tEdge, teM, teS, xx, yy, tw, th, fw, area) else n
                    val v = if (n > e) n else e
                    if (v > lb) {
                        lb = v
                        lx = xx
                        ly = yy
                    }
                }
            }

            if (lb > best) {
                best = lb
                bx = lx
                by = ly
                bestTW = tw
                bestTH = th
            }
            // 命中即提前退出（后续尺度几乎不会更准，且省时间）
            if (threshold > 0f && best >= threshold) break
        }

        if (best < -1.5f) return Result(false, 0, 0, -2f, 0, 0)

        val found = best >= threshold
        val fullX = ((bx + bestTW / 2) / fs).toInt()
        val fullY = ((by + bestTH / 2) / fs).toInt()
        val fullW = (bestTW / fs).toInt()
        val fullH = (bestTH / fs).toInt()
        return Result(found, fullX, fullY, best, fullW, fullH)
    }

    /** 单个位置的 NCC（均值/方差走积分图，O(1)）；交叉项 O(tw·th) 无法省，靠小模板+步长控制耗时 */
    private fun ncc(
        f: FloatArray, iSum: FloatArray, iSq: FloatArray, w1: Int,
        t: FloatArray, tMean: Float, tStd: Float,
        x: Int, y: Int, tw: Int, th: Int, fw: Int, area: Float
    ): Float {
        val sum = iSum[(y + th) * w1 + (x + tw)] - iSum[y * w1 + (x + tw)] -
                iSum[(y + th) * w1 + x] + iSum[y * w1 + x]
        val sq = iSq[(y + th) * w1 + (x + tw)] - iSq[y * w1 + (x + tw)] -
                iSq[(y + th) * w1 + x] + iSq[y * w1 + x]
        val fMean = sum / area
        val fVar = sq - area * fMean * fMean
        if (fVar <= 1e-6f) return -2f
        val fStd = sqrt(fVar)
        val den = fStd * tStd
        if (den <= 1e-4f) return -2f
        var num = 0f
        for (j in 0 until th) {
            val fbase = (y + j) * fw + x
            val tbase = j * tw
            for (i in 0 until tw) {
                num += (f[fbase + i] - fMean) * (t[tbase + i] - tMean)
            }
        }
        return num / den
    }
}
