package com.example.daqinbandithelper

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.regex.Pattern

/**
 * S0=初始化(并入S1) → S1=检索盗匪 → S2=校验(等级) → S3=出征 → S4=行军等待 → S5=领奖 → S6=休眠/停止
 * 由 CaptureService 每个画面帧驱动 processFrame()。
 */
class BotEngine(private val ctx: android.content.Context) {

    private val prefs = Prefs(ctx)
    private val random = java.util.Random()

    private var state = C.S1_FIND
    private var cycle = 0
    private var notFound = 0
    private var nextActionAt = 0L
    private var enterTime = 0L
    private var lastW = 0
    private var lastH = 0
    private var stopped = false

    private var statusCb: ((String) -> Unit)? = null
    fun setStatusCallback(cb: (String) -> Unit) { statusCb = cb }

    private val now: Long get() = System.currentTimeMillis()
    private fun ready() = System.currentTimeMillis() >= nextActionAt

    private fun cooldown(extraMs: Long = 0) {
        val base = prefs.baseDelayMs
        val jitter = (base * 0.3f * (random.nextFloat() * 2 - 1)).toLong()
        nextActionAt = System.currentTimeMillis() + base + jitter + extraMs
    }

    private fun status(s: String) {
        statusCb?.invoke(s)
        TapService.instance?.setStatus(s)
        Diag.log("状态机", s)
    }

    fun processFrame(bmp: Bitmap) {
        if (stopped) return
        lastW = bmp.width
        lastH = bmp.height
        when (state) {
            C.S1_FIND -> stepFind(bmp)
            C.S2_VALIDATE -> stepValidate(bmp)
            C.S3_MARCH -> stepMarch(bmp)
            C.S4_WAIT -> stepWait(bmp)
            C.S5_COLLECT -> stepCollect(bmp)
            C.S6_SLEEP -> stepSleep()
        }
    }

    // ---------------- S1 检索盗匪 ----------------
    private fun stepFind(bmp: Bitmap) {
        if (!ready()) return
        val tpl = prefs.loadTemplate(C.TPL_BANDIT) ?: run {
            status("缺少盗匪模板，无法检索")
            return
        }
        val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
        if (m.found) {
            notFound = 0
            status("S1 发现盗匪 命中=${"%.2f".format(m.score)}")
            if (levelOk(bmp, m.x, m.y)) {
                tap(m.x, m.y, "盗匪")
                state = C.S2_VALIDATE
                enterTime = now
            } else {
                status("S1 等级不符，跳过")
            }
        } else {
            notFound++
            if (notFound > 12) {
                status("S1 连续 ${notFound} 次未找到盗匪，进入休眠")
                state = C.S6_SLEEP
            } else {
                randomDrag()
            }
        }
    }

    // ---------------- S2 校验 ----------------
    private fun stepValidate(bmp: Bitmap) {
        if (!ready()) return
        val tpl = prefs.loadTemplate(C.TPL_ATTACK)
        if (tpl == null) {
            state = C.S3_MARCH
            enterTime = now
            return
        }
        val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
        if (m.found) {
            status("S2 找到讨伐 命中=${"%.2f".format(m.score)}")
            tap(m.x, m.y, "讨伐")
            state = C.S3_MARCH
            enterTime = now
        } else if (now - enterTime > 4000) {
            status("S2 未出现讨伐面板，返回地图")
            closePanel(bmp)
            state = C.S1_FIND
        }
    }

    // ---------------- S3 出征 ----------------
    private fun stepMarch(bmp: Bitmap) {
        if (!ready()) return
        val tpl = prefs.loadTemplate(C.TPL_MARCH)
        if (tpl == null) {
            state = C.S4_WAIT
            enterTime = now
            return
        }
        val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
        if (m.found) {
            status("S3 确认出征 命中=${"%.2f".format(m.score)}")
            tap(m.x, m.y, "确认出征")
            state = C.S4_WAIT
            enterTime = now
        } else {
            if (prefs.ocrEnabled) {
                val text = OcrEngine.recognize(bmp)
                if (text != null && (text.contains("体力不足") || staminaLow(text))) {
                    status("S3 体力不足，休眠")
                    state = C.S6_SLEEP
                    return
                }
            }
            if (now - enterTime > 4000) {
                status("S3 未出现确认出征，返回地图")
                closePanel(bmp)
                state = C.S1_FIND
            }
        }
    }

    // ---------------- S4 行军等待 ----------------
    private fun stepWait(bmp: Bitmap) {
        val tpl = prefs.loadTemplate(C.TPL_CONFIRM)
        if (tpl != null) {
            val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
            if (m.found) {
                status("S4 出现领奖 命中=${"%.2f".format(m.score)}")
                if (ready()) tap(m.x, m.y, "确定领奖")
                cycle++
                status("✔ 完成第 $cycle 轮")
                state = C.S5_COLLECT
                enterTime = now
                return
            }
        }
        if (now - enterTime > 120000) {
            status("S4 战斗超时，返回地图")
            TapService.instance?.back()
            cooldown(400)
            state = C.S1_FIND
            notFound = 0
        }
    }

    // ---------------- S5 领奖后回到检索 ----------------
    private fun stepCollect(bmp: Bitmap) {
        if (!ready()) return
        if (cycle >= prefs.maxCycle) {
            status("已达最大轮次 $cycle，停止")
            BotController.stop()
            return
        }
        // 若仍有确定弹窗，补点一次
        val tpl = prefs.loadTemplate(C.TPL_CONFIRM)
        if (tpl != null) {
            val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
            if (m.found) tap(m.x, m.y, "确定领奖")
        }
        state = C.S1_FIND
        notFound = 0
    }

    // ---------------- S6 休眠/停止 ----------------
    private fun stepSleep() {
        status("S6 已停止（休眠/异常），可重新点「开始挂机」")
        stopped = true
        BotController.stop()
    }

    // ---------------- 辅助 ----------------
    private fun tap(x: Int, y: Int, label: String) {
        val tap = TapService.instance ?: run {
            status("⚠ 无障碍未开启，无法点击")
            return
        }
        val ox = ((random.nextFloat() * 2 - 1) * prefs.offsetPx).toInt()
        val oy = ((random.nextFloat() * 2 - 1) * prefs.offsetPx).toInt()
        tap.tap(x + ox, y + oy)
        cooldown()
        status("点击 $label @ ($x,$y)")
    }

    private fun randomDrag() {
        val tap = TapService.instance ?: return
        val cx = lastW / 2
        val cy = lastH / 2
        val dx = ((random.nextFloat() * 2 - 1) * lastW * 0.25).toInt()
        val dy = ((random.nextFloat() * 2 - 1) * lastH * 0.25).toInt()
        tap.swipe(cx, cy, cx + dx, cy + dy, 350)
        cooldown(400)
        status("S1 拖动地图刷新 (${notFound})")
    }

    private fun closePanel(bmp: Bitmap) {
        val tpl = prefs.loadTemplate(C.TPL_CLOSE)
        if (tpl != null) {
            val m = TemplateMatcher.match(bmp, tpl, prefs.matchThreshold)
            if (m.found) {
                tap(m.x, m.y, "关闭")
                return
            }
        }
        TapService.instance?.back()
    }

    /** 围绕点裁一块区域用于 OCR */
    private fun safeCrop(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val left = (x - w / 2).coerceIn(0, bmp.width - 1)
        val top = (y - h / 2).coerceIn(0, bmp.height - 1)
        val right = (left + w).coerceAtMost(bmp.width)
        val bottom = (top + h).coerceAtMost(bmp.height)
        if (right - left < 4 || bottom - top < 4) return null
        return try {
            Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }

    private fun levelOk(bmp: Bitmap, x: Int, y: Int): Boolean {
        if (!prefs.ocrEnabled) return true
        val region = safeCrop(bmp, x, y, 240, 200) ?: return true
        val text = OcrEngine.recognize(region)
        region.recycle()
        val lv = parseLevel(text)
        return if (lv != null) lv in prefs.minLevel..prefs.maxLevel else true
    }

    private val lvPattern = Pattern.compile("(?:Lv|LV|等级|Lv\\.)\\s*[:：]?\\s*(\\d{1,3})")

    private fun parseLevel(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = lvPattern.matcher(text)
        if (m.find()) return m.group(1)?.toIntOrNull()
        // 兜底：取第一个 1~3 位数字
        val m2 = Pattern.compile("(\\d{1,3})").matcher(text)
        return if (m2.find()) m2.group(1)?.toIntOrNull() else null
    }

    private fun staminaLow(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val m = Pattern.compile("体力[^\\d]*(\\d+)").matcher(text)
        if (m.find()) {
            val v = m.group(1)?.toIntOrNull()
            if (v != null) return v < prefs.staminaMin
        }
        return false
    }
}
