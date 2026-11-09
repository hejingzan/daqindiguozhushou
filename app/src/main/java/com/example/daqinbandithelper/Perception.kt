package com.example.daqinbandithelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import java.util.regex.Pattern

/** 感知层输出的一个盗匪目标 */
data class Bandit(
    val type: String,       // normal | elite | boss
    val level: Int,
    val x: Int,
    val y: Int,
    val score: Float,      // NCC 匹配分数
    val spotId: Int        // 所在网格编号，用于野点轮转
)

/** 感知层一帧的完整快照 */
data class Percept(
    val frameW: Int,
    val frameH: Int,
    val anchorOk: Boolean,
    val anchorScale: Float,
    val scene: String,
    val bandits: List<Bandit>,
    val hp: String,         // GREEN | YELLOW | RED
    val hpRatio: Float
)

/**
 * 感知层：只负责"看懂画面"，不决策、不点击，结果统一写黑板 percept.* 段。
 *
 * 分辨率自适应：所有区域/坐标用归一化 0..1 或按帧宽高比例换算；
 * 锚点（主城/地图图标）识别结果写入黑板用于诊断。
 *
 * 真机性能取舍：OCR 默认关闭；开启时等级识别做缓存、资源识别每 20 帧一次，
 * 避免 ML Kit 阻塞帧循环。
 */
class Perception(private val ctx: Context) {

    private val prefs = Prefs(ctx)
    private val levelCache = HashMap<Int, Int>()
    private var frameCount = 0

    fun sense(frame: Bitmap): Percept {
        frameCount++
        val w = frame.width
        val h = frame.height
        val thr = prefs.matchThreshold
        val scale = w / BotConfig.DESIGN_W.toFloat()

        // ① 锚点识别（UI 固定锚点：主城/地图图标）
        val homeTpls = prefs.loadTemplates(C.TPL_HOME)
        val anchorOk = homeTpls.isNotEmpty() &&
                TemplateMatcher.matchMulti(frame, homeTpls, thr, null).found
        Blackboard.update(mapOf<String, Any>(
            "percept.frameW" to w,
            "percept.frameH" to h,
            "percept.anchorOk" to anchorOk,
            "percept.anchorScale" to scale
        ))

        // ② 盗匪目标：3×2 网格逐格匹配，天然得到同屏多目标 + 野点编号
        val bandits = detectBandits(frame, thr)

        // ③ 按钮模板命中坐标（决策层读黑板；-1 时用归一化坐标兜底）
        detectButtons(frame, thr)

        // ④ 界面识别 / ⑤ HSV 血条 / ⑥ 资源 OCR
        val scene = detectScene()
        val (hp, hpRatio) = classifyHp(frame)
        readResources(frame)

        Blackboard.update(mapOf<String, Any>(
            "percept.scene" to scene,
            "percept.banditCount" to bandits.size,
            "percept.hp" to hp,
            "hp.state" to hp
        ))
        return Percept(w, h, anchorOk, scale, scene, bandits, hp, hpRatio)
    }

    // ------------------------------------------------------------ ① 分级盗匪识别
    private fun detectBandits(frame: Bitmap, thr: Float): List<Bandit> {
        val tpls = ArrayList<Bitmap>()
        val variantType = ArrayList<String>()
        val defs = listOf(
            C.TPL_BANDIT_NORMAL to "normal",
            C.TPL_BANDIT_ELITE to "elite",
            C.TPL_BANDIT_BOSS to "boss"
        )
        for ((name, type) in defs) {
            for (b in prefs.loadTemplates(name)) {
                tpls.add(b)
                variantType.add(type)
            }
        }
        // 未采分级模板 → 回退旧通用模板，统一按普通盗匪处理
        if (tpls.isEmpty()) {
            for (b in prefs.loadTemplates(C.TPL_BANDIT)) {
                tpls.add(b)
                variantType.add("normal")
            }
        }
        if (tpls.isEmpty()) return emptyList()

        val out = ArrayList<Bandit>()
        val cols = BotConfig.GRID_COLS
        val rows = BotConfig.GRID_ROWS
        val gw = frame.width / cols
        val gh = frame.height / rows
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = col * gw
                val top = row * gh
                val right = if (col == cols - 1) frame.width else (col + 1) * gw
                val bottom = if (row == rows - 1) frame.height else (row + 1) * gh
                val roi = Rect(left, top, right, bottom)
                val r = TemplateMatcher.matchMulti(frame, tpls, thr, roi)
                if (!r.found) continue
                val type = variantType.getOrElse(r.variant) { "normal" }
                val spotId = row * cols + col
                val level = readLevel(frame, r.x, r.y, spotId)
                out.add(Bandit(type, level, r.x, r.y, r.score, spotId))
            }
        }
        return out
    }

    /** 等级 OCR：默认关闭；开启时按网格缓存，避免每帧重复识别 */
    private fun readLevel(frame: Bitmap, x: Int, y: Int, spotId: Int): Int {
        if (!prefs.ocrEnabled) return BotConfig.DEFAULT_LEVEL
        val key = spotId * 1000 + (x / 40) % 1000
        val cached = levelCache[key]
        if (cached != null && frameCount % 10 != 0) return cached
        val roi = crop(frame, x - 80, y - 50, x + 80, y + 50)
        if (roi == null) return cached ?: BotConfig.DEFAULT_LEVEL
        val text = OcrEngine.recognize(roi)
        roi.recycle()
        val lv = firstInt(text)
        if (lv != null) levelCache[key] = lv
        return lv ?: cached ?: BotConfig.DEFAULT_LEVEL
    }

    // ------------------------------------------------------------ ③ 按钮定位
    private fun detectButtons(frame: Bitmap, thr: Float) {
        val defs = listOf(
            Triple(C.TPL_ATTACK, "percept.btnAttackX", "percept.btnAttackY"),
            Triple(C.TPL_FIELD, "percept.btnFieldX", "percept.btnFieldY"),
            Triple(C.TPL_MARCH, "percept.btnMarchX", "percept.btnMarchY"),
            Triple(C.TPL_CONFIRM, "percept.btnCollectX", "percept.btnCollectY"),
            Triple(C.TPL_SELECT_ALL, "percept.btnSelectAllX", "percept.btnSelectAllY"),
            Triple(C.TPL_CONFIRM_TROOP, "percept.btnConfirmTroopX", "percept.btnConfirmTroopY"),
            Triple(C.TPL_HOME, "percept.btnHomeX", "percept.btnHomeY")
        )
        for ((name, kx, ky) in defs) {
            val tpls = prefs.loadTemplates(name)
            var px = -1
            var py = -1
            if (tpls.isNotEmpty()) {
                val r = TemplateMatcher.matchMulti(frame, tpls, thr, roiFor(name, frame.width, frame.height))
                if (r.found) {
                    px = r.x
                    py = r.y
                }
            }
            Blackboard.set(kx, px)
            Blackboard.set(ky, py)
        }
    }

    private fun roiFor(name: String, w: Int, h: Int): Rect? {
        val r = C.TPL_ROI[name] ?: return null
        return Rect(
            (r[0] * w).toInt(), (r[1] * h).toInt(),
            (r[2] * w).toInt(), (r[3] * h).toInt()
        )
    }

    // ------------------------------------------------------------ ④ 界面识别
    private fun detectScene(): String {
        if (Blackboard.i("percept.btnCollectX", -1) >= 0) return C.SCENE_SETTLEMENT
        if (Blackboard.i("percept.btnAttackX", -1) >= 0 ||
            Blackboard.i("percept.btnFieldX", -1) >= 0) return C.SCENE_PUNITIVE_DLG
        if (Blackboard.i("percept.btnSelectAllX", -1) >= 0 ||
            Blackboard.i("percept.btnConfirmTroopX", -1) >= 0) return C.SCENE_TROOP_DLG
        if (Blackboard.i("percept.btnMarchX", -1) >= 0) return C.SCENE_MARCHING
        return C.SCENE_IDLE
    }

    // ------------------------------------------------------------ ⑤ HSV 血条
    private fun classifyHp(frame: Bitmap): Pair<String, Float> {
        val r = C.HP_ROI
        val left = (r[0] * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (r[1] * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (r[2] * frame.width).toInt().coerceAtMost(frame.width)
        val bottom = (r[3] * frame.height).toInt().coerceAtMost(frame.height)
        if (right - left < 4 || bottom - top < 4) return "GREEN" to 1f

        var green = 0
        var yellow = 0
        var red = 0
        var total = 0
        val hsv = FloatArray(3)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = frame.getPixel(x, y)
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
                if (hsv[1] * 255f >= BotConfig.HP_S_MIN && hsv[2] * 255f >= BotConfig.HP_V_MIN) {
                    val hue = hsv[0]
                    when {
                        hue >= 35f && hue < 90f -> { green++; total++ }
                        hue >= 15f && hue < 35f -> { yellow++; total++ }
                        hue < 15f || hue >= 170f -> { red++; total++ }  // 红色跨 0/180 边界
                    }
                }
                x += 3
            }
            y += 3
        }
        if (total == 0) return "GREEN" to 1f
        val best = maxOf(green, yellow, red)
        val state = when (best) {
            red -> "RED"
            yellow -> "YELLOW"
            else -> "GREEN"
        }
        return state to (best.toFloat() / total)
    }

    // ------------------------------------------------------------ ⑥ 资源 OCR（低频）
    private fun readResources(frame: Bitmap) {
        if (!prefs.ocrEnabled || frameCount % 20 != 0) return
        val tok = cropNorm(frame, C.TOKEN_ROI)
        if (tok != null) {
            val v = firstInt(OcrEngine.recognize(tok))
            tok.recycle()
            if (v != null) Blackboard.set("res.tokenLeft", v)
        }
        val task = cropNorm(frame, C.TASK_ROI)
        if (task != null) {
            val t = OcrEngine.recognize(task)
            task.recycle()
            val used = parseTaskCount(t)
            if (used != null) Blackboard.set("res.taskUsed", used)
        }
    }

    private val digits = Pattern.compile("(\\d{1,3})")
    private val taskPattern = Pattern.compile("(\\d{1,2})\\s*[/／]\\s*(\\d{1,2})")

    private fun firstInt(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = digits.matcher(text)
        return if (m.find()) m.group(1)?.toIntOrNull() else null
    }

    /** 从 "3/5" 或 "已用 3 次" 中取已用次数；识别不到返回 null，沿用流程计数 */
    private fun parseTaskCount(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = taskPattern.matcher(text)
        if (m.find()) return m.group(1)?.toIntOrNull()
        if (text.contains("次")) return firstInt(text)
        return null
    }

    // ------------------------------------------------------------ 裁图工具
    private fun crop(frame: Bitmap, l: Int, t: Int, r: Int, b: Int): Bitmap? {
        val x1 = l.coerceIn(0, frame.width - 1)
        val y1 = t.coerceIn(0, frame.height - 1)
        val x2 = r.coerceAtMost(frame.width)
        val y2 = b.coerceAtMost(frame.height)
        if (x2 - x1 < 4 || y2 - y1 < 4) return null
        return try {
            Bitmap.createBitmap(frame, x1, y1, x2 - x1, y2 - y1)
        } catch (e: Exception) {
            null
        }
    }

    private fun cropNorm(frame: Bitmap, r: FloatArray): Bitmap? = crop(
        frame,
        (r[0] * frame.width).toInt(), (r[1] * frame.height).toInt(),
        (r[2] * frame.width).toInt(), (r[3] * frame.height).toInt()
    )
}
