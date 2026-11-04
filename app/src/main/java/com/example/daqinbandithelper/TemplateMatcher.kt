package com.example.daqinbandithelper

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 纯 Kotlin 模板匹配（归一化互相关 NCC，与 OpenCV matchTemplate TM_CCOEFF_NORMED 同原理）。
 * 零原生依赖，避免 OpenCV 原生库加载失败导致崩溃。
 *
 * v4.0.10 重写要点（对应「多模板兜底 + 局部区域 ROI」优化思路）：
 *  1) 多模板：同一按钮可存 N 张不同状态变体（如讨伐的常态/高亮/半透明），matchMulti 遍历所有
 *     变体并取最高分——金色按钮在不同光效/弹窗层下差异大，单张永远低分，多张能覆盖绝大多数场景。
 *  2) ROI 局部搜索：传入 Rect 后只在区域内匹配，过滤地图/其他金色文字噪声，既提分又提速；
 *     对「讨伐」等只在屏幕固定区出现的按钮尤其有效，避免与「前往/出征」混淆。
 *  3) 尺度相对：以模板自身尺寸在工作图中的自然大小为锚，乘 0.3~3.0 穷举，覆盖地图缩放导致的大小变化。
 *  4) 灰度 NCC + 边缘(Sobel) NCC 双路取高：边缘对背景色/抗锯齿/渐变金字不敏感，等价于"特征/形状匹配"。
 *  5) 粗扫 + 最优点 ±step 精修；命中（达阈值）即提前退出。
 */
object TemplateMatcher {

    data class Result(
        val found: Boolean,
        val x: Int,        // 命中框中心 X（原图像素）
        val y: Int,        // 命中框中心 Y（原图像素）
        val score: Float,  // 最高 NCC 相关系数（-1~1）
        val w: Int,        // 命中框宽（原图像素）
        val h: Int,        // 命中框高（原图像素）
        val variant: Int = 0  // 命中的是哪一个模板变体（多模板时用于诊断）
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

    /** 返回 (均值, 离差平方和)。分母用 sqrt(fVar * tVar) 才是真正的归一化互相关 NCC（范围 -1~1）。
     *  旧实现 tStd=sqrt(var/n) 漏了 sqrt(n) 因子，导致分数被放大 sqrt(模板像素数) 倍（几十倍），
     *  阈值 0.8 形同虚设，满屏误检。 */
    private fun meanVar(a: FloatArray): Pair<Float, Float> {
        var m = 0f
        for (v in a) m += v
        m /= a.size
        var v = 0f
        for (x in a) v += (x - m) * (x - m)
        return m to v
    }

    /** 单模板便捷入口（委托给多模板） */
    fun match(frame: Bitmap, template: Bitmap, threshold: Float): Result =
        matchMulti(frame, listOf(template), threshold, null)

    /**
     * 在 frame 中匹配一组模板变体（尺度相对、灰度+边缘双路 NCC、可选 ROI）。
     * @param threshold NCC 阈值（0.5~0.98）。传 0 表示"只取最高分、不判命中"，用于自测/测试。
     * @param roi       可选搜索区域（原图像素）。非空则在区域内匹配并自动还原到原图坐标。
     */
    fun matchMulti(
        frame: Bitmap,
        templates: List<Bitmap>,
        threshold: Float,
        roi: Rect? = null
    ): Result {
        val frameMax = max(frame.width, frame.height)
        if (frameMax <= 0 || templates.isEmpty()) return Result(false, 0, 0, -2f, 0, 0)

        // ROI 裁剪：只在该区域搜索，过滤无关噪声、提速、降误匹配
        val (f0, ox, oy) = if (roi != null && roi.width() > 0 && roi.height() > 0) {
            val left = roi.left.coerceIn(0, frame.width - 1)
            val top = roi.top.coerceIn(0, frame.height - 1)
            val right = roi.right.coerceAtMost(frame.width)
            val bottom = roi.bottom.coerceAtMost(frame.height)
            if (right - left >= 8 && bottom - top >= 8)
                Triple(Bitmap.createBitmap(frame, left, top, right - left, bottom - top), left, top)
            else Triple(frame, 0, 0)
        } else Triple(frame, 0, 0)

        val fMax = max(f0.width, f0.height)
        val fs = min(1f, MAX_FRAME.toFloat() / fMax)
        val fBmp = scale(f0, fs)
        // 仅回收 ROI 裁出的副本；fs≈1 时 scale 会返回同一对象(f0===fBmp)，不可回收
        if (f0 !== frame && f0 !== fBmp) f0.recycle()
        val fw = fBmp.width
        val fh = fBmp.height
        if (fw < 8 || fh < 8) return Result(false, 0, 0, -2f, 0, 0)

        val fGray = gray(fBmp)
        val fEdge = edges(fGray, fw, fh)
        val (igS, igQ) = integral(fGray, fw, fh)
        val (ieS, ieQ) = integral(fEdge, fw, fh)
        val w1 = fw + 1

        var best = -2f
        var bx = 0
        var by = 0
        var bestTW = 0
        var bestTH = 0
        var bestVariant = 0

        outer@ for (s in SCALES) {
            for ((ti, template) in templates.withIndex()) {
                val tplW = template.width
                val tplH = template.height
                val tplMax = max(tplW, tplH)
                if (tplMax <= 0) continue
                // 以该模板自身自然大小为锚做尺度搜索
                val natural = tplMax * fs
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
                val (tgM, tgV) = meanVar(tGray)
                val (teM, teV) = meanVar(tEdge)
                if (tgV < 1e-2f && teV < 1e-2f) continue
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
                        val n = ncc(fGray, igS, igQ, w1, tGray, tgM, tgV, x, y, tw, th, fw, area)
                        val e = if (teV >= 1e-2f) ncc(fEdge, ieS, ieQ, w1, tEdge, teM, teV, x, y, tw, th, fw, area) else n
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
                        val n = ncc(fGray, igS, igQ, w1, tGray, tgM, tgV, xx, yy, tw, th, fw, area)
                        val e = if (teV >= 1e-2f) ncc(fEdge, ieS, ieQ, w1, tEdge, teM, teV, xx, yy, tw, th, fw, area) else n
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
                    bestVariant = ti
                }
                // 命中即提前退出（后续尺度几乎不会更准，且省时间）
                if (threshold > 0f && best >= threshold) break@outer
            }
        }

        if (best < -1.5f) return Result(false, 0, 0, -2f, 0, 0)

        val found = best >= threshold
        // 还原到原图坐标（含 ROI 偏移）
        val fullX = ((bx + bestTW / 2) / fs + ox).toInt()
        val fullY = ((by + bestTH / 2) / fs + oy).toInt()
        val fullW = (bestTW / fs).toInt()
        val fullH = (bestTH / fs).toInt()
        return Result(found, fullX, fullY, best, fullW, fullH, bestVariant)
    }

    /** 单个位置的 NCC（均值/方差走积分图，O(1)）；交叉项 O(tw·th) 无法省，靠小模板+步长控制耗时 */
    private fun ncc(
        f: FloatArray, iSum: FloatArray, iSq: FloatArray, w1: Int,
        t: FloatArray, tMean: Float, tVar: Float,
        x: Int, y: Int, tw: Int, th: Int, fw: Int, area: Float
    ): Float {
        val sum = iSum[(y + th) * w1 + (x + tw)] - iSum[y * w1 + (x + tw)] -
                iSum[(y + th) * w1 + x] + iSum[y * w1 + x]
        val sq = iSq[(y + th) * w1 + (x + tw)] - iSq[y * w1 + (x + tw)] -
                iSq[(y + th) * w1 + x] + iSq[y * w1 + x]
        val fMean = sum / area
        val fVar = sq - area * fMean * fMean
        if (fVar <= 1e-6f) return -2f
        // 真正 NCC 分母 = sqrt(Σ(f-fm)² · Σ(t-tm)²) = sqrt(fVar · tVar)
        val den = sqrt(fVar * tVar)
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
