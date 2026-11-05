package com.example.daqinbandithelper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** 参数与模板存取（模板以 PNG 存于 app 私有目录）。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("daqin_bot_prefs", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "templates").also { it.mkdirs() }

    fun tplFile(name: String) = File(dir, "$name.png")

    fun hasTemplate(name: String): Boolean = templateCount(name) > 0

    /** 某模板已采集的变体数量（多模板兜底用） */
    fun templateCount(name: String): Int {
        val c = sp.getInt("tpl_count_$name", -1)
        if (c >= 0) return c
        // 兼容旧版单文件存储：存在 name.png 视为 1 张
        return if (tplFile(name).exists()) 1 else 0
    }

    /** 旧版单文件 name.png → 新多模板 name.0.png */
    private fun migrateIfNeeded(name: String) {
        val legacy = tplFile(name)
        if (sp.getInt("tpl_count_$name", -1) < 0
            && legacy.exists()
            && !File(dir, "$name.0.png").exists()
        ) {
            legacy.renameTo(File(dir, "$name.0.png"))
            sp.edit().putInt("tpl_count_$name", 1).apply()
        }
    }

    /** 追加一张同模板变体（建议对同一按钮截 2~3 张不同状态：常态/高亮/半透明） */
    fun saveTemplate(name: String, bmp: Bitmap) {
        migrateIfNeeded(name)
        val c = sp.getInt("tpl_count_$name", 0)
        File(dir, "$name.$c.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sp.edit().putInt("tpl_count_$name", c + 1).apply()
        Diag.log("模板", "保存变体 ${C.TPL_LABELS[name] ?: name} #$c（共 ${c + 1} 张）")
    }

    /** 读取某模板的全部变体 */
    fun loadTemplates(name: String): List<Bitmap> {
        migrateIfNeeded(name)
        val c = sp.getInt("tpl_count_$name", 0)
        val out = mutableListOf<Bitmap>()
        for (i in 0 until c) {
            val f = File(dir, "$name.$i.png")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { out.add(it) }
        }
        return out
    }

    fun loadTemplate(name: String): Bitmap? = loadTemplates(name).firstOrNull()

    fun removeTemplate(name: String) {
        val c = sp.getInt("tpl_count_$name", 0)
        for (i in 0 until c) File(dir, "$name.$i.png").delete()
        tplFile(name).delete()
        sp.edit().remove("tpl_count_$name").apply()
    }

    /** 已采集的模板“种类”数（用于界面显示与启动校验） */
    fun templateCount(): Int = C.TPL_ORDER.count { templateCount(it) > 0 }

    var minLevel: Int
        get() = sp.getInt("min_level", 1)
        set(v) = sp.edit().putInt("min_level", v).apply()
    var maxLevel: Int
        get() = sp.getInt("max_level", 99)
        set(v) = sp.edit().putInt("max_level", v).apply()
    var staminaMin: Int
        get() = sp.getInt("stamina_min", 20)
        set(v) = sp.edit().putInt("stamina_min", v).apply()
    var maxCycle: Int
        get() = sp.getInt("max_cycle", 30)
        set(v) = sp.edit().putInt("max_cycle", v).apply()
    var matchThreshold: Float
        get() = sp.getFloat("match_threshold", 0.8f)
        set(v) = sp.edit().putFloat("match_threshold", v.coerceIn(0.5f, 0.98f)).apply()
    /**
     * 点击间隔下限（毫秒）。每次实际间隔 = [baseDelayMs, 2000] 之间随机，整体落在 1~2 秒。
     * 默认 1000（即最短 1 秒）。
     */
    var baseDelayMs: Long
        get() = sp.getLong("base_delay", 1000L)
        set(v) = sp.edit().putLong("base_delay", v.coerceIn(1000, 2000)).apply()
    var sleepSec: Int
        get() = sp.getInt("sleep_sec", 300)
        set(v) = sp.edit().putInt("sleep_sec", v).apply()
    var ocrEnabled: Boolean
        get() = sp.getBoolean("ocr_enabled", false)
        set(v) = sp.edit().putBoolean("ocr_enabled", v).apply()
    var offsetPx: Int
        get() = sp.getInt("offset_px", 6)
        set(v) = sp.edit().putInt("offset_px", v.coerceIn(0, 40)).apply()
}
