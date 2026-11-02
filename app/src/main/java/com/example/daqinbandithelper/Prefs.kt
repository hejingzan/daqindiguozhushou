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
    fun hasTemplate(name: String) = tplFile(name).exists()
    fun saveTemplate(name: String, bmp: Bitmap) =
        tplFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    fun loadTemplate(name: String): Bitmap? {
        val f = tplFile(name)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }
    fun templateCount(): Int = C.TPL_ORDER.count { hasTemplate(it) }

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
    var baseDelayMs: Long
        get() = sp.getLong("base_delay", 1200L)
        set(v) = sp.edit().putLong("base_delay", v.coerceAtLeast(300L)).apply()
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
