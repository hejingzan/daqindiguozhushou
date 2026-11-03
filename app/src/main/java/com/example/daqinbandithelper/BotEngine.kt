package com.example.daqinbandithelper

import android.graphics.Bitmap
import android.graphics.Rect
import java.util.regex.Pattern

/**
 * S0=初始化(并入S1) → S1 检索盗匪 → S2 讨伐 → S25 选队伍(全选→确定) → S3 出征
 *   → S4 行军等待 → S5 领奖 → S6 休眠/停止
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

    /** 选队伍步骤里是否已点过【全选】 */
    private var selectedAll = false

    /** 连续未命中次数 / 阈值放宽档位（0=用设定阈值，最多放宽 2 档） */
    private var missCount = 0
    private var relax = 0

    private var statusCb: ((String) -> Unit)? = null
    fun setStatusCallback(cb: (String) -> Unit) { statusCb = cb }

    private val now: Long get() = System.currentTimeMillis()
    private fun ready() = System.currentTimeMillis() >= nextActionAt

    private fun cooldown(extraMs: Long = 0) {
        val base = prefs.baseDelayMs
        val jitter = (base * 0.3f * (random.nextFloat() * 2 - 1)).toLong()
        nextActionAt = System.currentTimeMillis() + base + jitter + extraMs
    }

    /** 当前生效阈值：连续找不到时逐档放宽，最多放宽 0.10 */
    private fun thr(): Float = (prefs.matchThreshold - 0.05f * relax).coerceAtLeast(0.55f)

    private fun onHit() {
        missCount = 0
        relax = 0
    }

    /** 记录一次未命中并返回当前（可能已放宽的）阈值 */
    private fun onMiss(): Float {
        missCount++
        if (missCount >= 6 && relax < 2) {
            relax++
            missCount = 0
        }
        return thr()
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
            C.S25_SELECT -> stepSelect(bmp)
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
        val m = TemplateMatcher.match(bmp, tpl, thr())
        if (m.found) {
            onHit()
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
            val t = onMiss()
            if (notFound > 12) {
                val hint = if (m.score < 0.5)
                    "盗匪模板最高分仅 ${"%.2f".format(m.score)}（远低于阈值），多半是模板没截好/缩放下不一致。请回主界面用【模板测试】截一张游戏里盗匪的图，看红框是否框中盗匪；没框中就重截「盗匪图标」。"
                else
                    "盗匪模板最高分 ${"%.2f".format(m.score)} 仍低于阈值 ${"%.2f".format(t)}，可略微调低『匹配阈值』参数后重试。"
                status("S1 连续 $notFound 次未找到盗匪。$hint")
                state = C.S6_SLEEP
            } else {
                randomDrag("S1 未命中 best=${"%.2f".format(m.score)} thr=${"%.2f".format(t)} 拖动地图刷新 ($notFound)")
            }
        }
    }

    // ---------------- S2 讨伐 ----------------
    private fun stepValidate(bmp: Bitmap) {
        if (!ready()) return
        val tpl = prefs.loadTemplate(C.TPL_ATTACK)
        if (tpl == null) {
            state = C.S25_SELECT
            enterTime = now
            return
        }
        val m = TemplateMatcher.match(bmp, tpl, thr())
        if (m.found) {
            onHit()
            status("S2 找到讨伐 命中=${"%.2f".format(m.score)}")
            tap(m.x, m.y, "讨伐")
            selectedAll = false
            state = C.S25_SELECT
            enterTime = now
        } else if (now - enterTime > 6000) {
            status("S2 未出现讨伐面板（最高分=${"%.2f".format(m.score)}），返回地图")
            closePanel(bmp)
            state = C.S1_FIND
            notFound = 0
        }
    }

    // ---------------- S25 选队伍：全选 → 确定 ----------------
    private fun stepSelect(bmp: Bitmap) {
        if (!ready()) return

        // ① 先点【全选】
        val tplAll = prefs.loadTemplate(C.TPL_SELECT_ALL)
        if (!selectedAll && tplAll != null) {
            val m = TemplateMatcher.match(bmp, tplAll, thr())
            if (m.found) {
                onHit()
                status("S25 找到全选 命中=${"%.2f".format(m.score)}")
                tap(m.x, m.y, "全选")
                selectedAll = true
                return
            }
        }

        // ② 再点【确定】（选队伍面板里的确定）
        val tplOk = prefs.loadTemplate(C.TPL_CONFIRM_TROOP)
        if (tplOk != null) {
            val m = TemplateMatcher.match(bmp, tplOk, thr())
            if (m.found) {
                onHit()
                status("S25 找到确定(选队伍) 命中=${"%.2f".format(m.score)}")
                tap(m.x, m.y, "确定队伍")
                selectedAll = false
                state = C.S3_MARCH
                enterTime = now
                return
            }
        }

        // ③ 超时兜底：没采这两个模板或没匹配上，直接进入出征确认
        if (now - enterTime > 6000) {
            status("S25 未找到全选/确定(选队伍)，直接进入出征确认")
            selectedAll = false
            state = C.S3_MARCH
            enterTime = now
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
        val m = TemplateMatcher.match(bmp, tpl, thr())
        if (m.found) {
            onHit()
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
            if (now - enterTime > 5000) {
                // 有些版本点【确定】即直接出征，没有单独的"确认出征"面板
                status("S3 未出现确认出征（最高分=${"%.2f".format(m.score)}），视为已出征，进入等待")
                state = C.S4_WAIT
                enterTime = now
            }
        }
    }

    // ---------------- S4 行军等待 ----------------
    private fun stepWait(bmp: Bitmap) {
        val tpl = prefs.loadTemplate(C.TPL_CONFIRM)
        if (tpl != null) {
            val m = TemplateMatcher.match(bmp, tpl, thr())
            if (m.found) {
                onHit()
                status("S4 出现领奖 命中=${"%.2f".format(m.score)}")
                if (ready()) tap(m.x, m.y, "确定领奖")
                cycle++
                status("✔ 完成第 $cycle 轮")
                state = C.S5_COLLECT
                enterTime = now
                return
            }
        }
        if (now - enterTime > 180000) {
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
            val m = TemplateMatcher.match(bmp, tpl, thr())
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

    private fun randomDrag(msg: String) {
        val tap = TapService.instance ?: return
        val cx = lastW / 2
        val cy = lastH / 2
        val dx = ((random.nextFloat() * 2 - 1) * lastW * 0.25).toInt()
        val dy = ((random.nextFloat() * 2 - 1) * lastH * 0.25).toInt()
        tap.swipe(cx, cy, cx + dx, cy + dy, 350)
        cooldown(400)
        status(msg)
    }

    private fun closePanel(bmp: Bitmap) {
        val tpl = prefs.loadTemplate(C.TPL_CLOSE)
        if (tpl != null) {
            val m = TemplateMatcher.match(bmp, tpl, thr())
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
