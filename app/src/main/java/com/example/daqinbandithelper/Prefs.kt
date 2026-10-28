package com.example.daqinbandithelper

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 配置读写：三个识别模板（盗匪 / 讨伐按钮 / 出征按钮）、运行参数。
 * 模板以 PNG 形式保存在 app 私有 files 目录，无需手动坐标。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("daqin_prefs", Context.MODE_PRIVATE)
    private val base: File = context.filesDir

    fun isEnabled(): Boolean = sp.getBoolean("enabled", false)
    fun setEnabled(v: Boolean) = sp.edit().putBoolean("enabled", v).apply()

    fun isCalibrated(): Boolean = sp.getBoolean("calibrated", false)
    fun setCalibrated(v: Boolean) = sp.edit().putBoolean("calibrated", v).apply()

    /** 模板文件：bandit / attack / march */
    fun tplFile(name: String): File = File(base, "$name.png")
    fun hasTemplate(name: String): Boolean = tplFile(name).exists()

    fun getIntervalMs(): Long = sp.getLong("interval", 3000L)
    fun setIntervalMs(v: Long) = sp.edit().putLong("interval", v).apply()

    /** 每次出征后等待战斗结束的时间（毫秒） */
    fun getBattleWaitMs(): Long = sp.getLong("battle_wait", 20000L)
    fun setBattleWaitMs(v: Long) = sp.edit().putLong("battle_wait", v).apply()

    /** 匹配灵敏度（0-255 量级，越大越容易匹配；建议 35-70） */
    fun getMatchThreshold(): Int = sp.getInt("match_thresh", 55)
    fun setMatchThreshold(v: Int) = sp.edit().putInt("match_thresh", v).apply()

    fun getKeywords(): Set<String> =
        sp.getStringSet("keywords", defaultKeywords) ?: defaultKeywords
    fun setKeywords(s: Set<String>) = sp.edit().putStringSet("keywords", s).apply()

    /** 拟人化程度：0=轻，1=中，2=强（默认中） */
    fun getHumanizeLevel(): Int = sp.getInt("humanize", 1)
    fun setHumanizeLevel(v: Int) = sp.edit().putInt("humanize", v).apply()

    companion object {
        val defaultKeywords: Set<String> = setOf(
            "攻击", "讨伐", "出征", "出发", "击败", "剿灭", "扫荡", "挑战", "进攻"
        )
    }
}
