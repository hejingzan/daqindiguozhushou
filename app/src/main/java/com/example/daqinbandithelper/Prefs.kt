package com.example.daqinbandithelper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import java.io.File

/**
 * 配置读写：识别模板（盗匪 / 讨伐 / 出征）、运行参数、出征队伍选择。
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

    /** 录屏是否已授权（无障碍服务在拿到 MediaProjection 后写入） */
    fun isCaptureReady(): Boolean = sp.getBoolean("capture_ready", false)
    fun setCaptureReady(v: Boolean) = sp.edit().putBoolean("capture_ready", v).apply()

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
    fun setKeywords(v: Set<String>) = sp.edit().putStringSet("keywords", v).apply()

    /** 拟人化程度：0=轻，1=中，2=强（默认中） */
    fun getHumanizeLevel(): Int = sp.getInt("humanize", 1)
    fun setHumanizeLevel(v: Int) = sp.edit().putInt("humanize", v).apply()

    // ---------------- 出征队伍（选兵） ----------------

    /** 出征界面上各支队伍卡片的位置，校准时记录，格式 "x,y;x,y" */
    fun getTroopPoints(): List<Point> {
        val raw = sp.getString("troop_points", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { s ->
            val p = s.split(",")
            if (p.size != 2) return@mapNotNull null
            val x = p[0].toIntOrNull()
            val y = p[1].toIntOrNull()
            if (x == null || y == null) null else Point(x, y)
        }
    }

    fun setTroopPoints(points: List<Point>) {
        val s = points.joinToString(";") { "${it.x},${it.y}" }
        sp.edit().putString("troop_points", s).apply()
    }

    /** 出征时选几支队伍（1..队伍总数；0 表示不选） */
    fun getTroopCount(): Int = sp.getInt("troop_count", 0)
    fun setTroopCount(v: Int) = sp.edit().putInt("troop_count", v).apply()

    /** 是否启用“先选兵再出征” */
    fun isTroopSelectOn(): Boolean = sp.getBoolean("troop_on", true)
    fun setTroopSelectOn(v: Boolean) = sp.edit().putBoolean("troop_on", v).apply()

    /** 是否在出征前自动点「全选」（蓝色按钮）。关掉则用游戏里已记住的选择 */
    fun isSelectAllOn(): Boolean = sp.getBoolean("select_all", true)
    fun setSelectAllOn(v: Boolean) = sp.edit().putBoolean("select_all", v).apply()

    // ---------------- 按钮坐标兜底（模板匹配失败时直接点） ----------------

    fun getAttackPos(): Point? = readPoint("attack_pos")
    fun setAttackPos(x: Int, y: Int) = writePoint("attack_pos", x, y)

    fun getMarchPos(): Point? = readPoint("march_pos")
    fun setMarchPos(x: Int, y: Int) = writePoint("march_pos", x, y)

    /** 「全选 / 不选」蓝色按钮的位置（校准时记录） */
    fun getSelectPos(): Point? = readPoint("select_pos")
    fun setSelectPos(x: Int, y: Int) = writePoint("select_pos", x, y)

    /** 「确定」橙色按钮的位置（校准时记录） */
    fun getConfirmPos(): Point? = readPoint("confirm_pos")
    fun setConfirmPos(x: Int, y: Int) = writePoint("confirm_pos", x, y)

    // ---------------- 放射菜单（讨伐 / 战斗）相对盗匪的偏移 ----------------

    /** 讨伐按钮相对盗匪图标的偏移（校准时记录，比几何推算更准） */
    fun getAttackOffX(): Int = sp.getInt("attack_off_x", 0)
    fun setAttackOffX(v: Int) = sp.edit().putInt("attack_off_x", v).apply()

    fun getAttackOffY(): Int = sp.getInt("attack_off_y", 0)
    fun setAttackOffY(v: Int) = sp.edit().putInt("attack_off_y", v).apply()

    /** 战斗按钮相对盗匪图标的偏移 */
    fun getBattleOffX(): Int = sp.getInt("battle_off_x", 0)
    fun setBattleOffX(v: Int) = sp.edit().putInt("battle_off_x", v).apply()

    fun getBattleOffY(): Int = sp.getInt("battle_off_y", 0)
    fun setBattleOffY(v: Int) = sp.edit().putInt("battle_off_y", v).apply()

    // ---------------- 讨伐方式 ----------------

    /** 0 = 讨伐（消耗讨伐令，有额外奖励）；1 = 战斗（不消耗讨伐令） */
    fun getAttackMode(): Int = sp.getInt("attack_mode", 0)
    fun setAttackMode(v: Int) = sp.edit().putInt("attack_mode", v).apply()

    // ---------------- 活动时段（盗匪是限时活动） ----------------

    /** 活动开始时间（当天 0 点起的分钟数），-1 表示不限制 */
    fun getActivityStartMin(): Int = sp.getInt("act_start", -1)
    fun setActivityStartMin(v: Int) = sp.edit().putInt("act_start", v).apply()

    /** 活动结束时间（当天 0 点起的分钟数），-1 表示不限制 */
    fun getActivityEndMin(): Int = sp.getInt("act_end", -1)
    fun setActivityEndMin(v: Int) = sp.edit().putInt("act_end", v).apply()

    /** 是否启用活动时段限制 */
    fun isActivityOn(): Boolean = sp.getBoolean("act_on", true)
    fun setActivityOn(v: Boolean) = sp.edit().putBoolean("act_on", v).apply()

    /** 找不到盗匪时，等待刷新多少秒再找（活动间隔） */
    fun getNoBanditWaitSec(): Int = sp.getInt("nobandit_wait", 30)
    fun setNoBanditWaitSec(v: Int) = sp.edit().putInt("nobandit_wait", v).apply()

    // ---------------- 应用版本（用于一次性迁移） ----------------

    /** 已写入配置的应用版本号；小于当前版本时做一次迁移 */
    fun getAppVer(): Int = sp.getInt("app_ver", 0)
    fun setAppVer(v: Int) = sp.edit().putInt("app_ver", v).apply()

    // ---------------- 固定位置（村庄等）为中心搜索 ----------------

    /** 基准位置（村庄等固定位置）的屏幕坐标，-1 表示未设置 */
    fun getBaseX(): Int = sp.getInt("base_x", -1)
    fun setBaseX(v: Int) = sp.edit().putInt("base_x", v).apply()

    fun getBaseY(): Int = sp.getInt("base_y", -1)
    fun setBaseY(v: Int) = sp.edit().putInt("base_y", v).apply()

    fun hasBasePos(): Boolean = getBaseX() >= 0 && getBaseY() >= 0

    fun clearBasePos() = sp.edit().putInt("base_x", -1).putInt("base_y", -1).apply()

    // ---------------- 盗匪等级筛选 ----------------

    /** 等级标签相对盗匪图标的偏移（校准时记录） */
    fun getLevelOffX(): Int = sp.getInt("level_off_x", 0)
    fun setLevelOffX(v: Int) = sp.edit().putInt("level_off_x", v).apply()

    fun getLevelOffY(): Int = sp.getInt("level_off_y", 0)
    fun setLevelOffY(v: Int) = sp.edit().putInt("level_off_y", v).apply()

    /** 是否只打「指定等级」的盗匪 */
    fun isLevelOnly(): Boolean = sp.getBoolean("level_only", false)
    fun setLevelOnly(v: Boolean) = sp.edit().putBoolean("level_only", v).apply()

    // ---------------- 搜索方式 ----------------

    /** 0 = 屏幕内找最近盗匪（默认）；1 = 以固定位置为中心在范围内扫描（拖动地图） */
    fun getSearchMode(): Int = sp.getInt("search_mode", 0)
    fun setSearchMode(v: Int) = sp.edit().putInt("search_mode", v).apply()

    /** 搜索半径（里） */
    fun getSearchRadiusLi(): Int = sp.getInt("search_radius_li", 1000)
    fun setSearchRadiusLi(v: Int) = sp.edit().putInt("search_radius_li", v).apply()

    /** 每「里」对应的屏幕像素（校准换算系数，可微调） */
    fun getLiPixel(): Float = sp.getFloat("li_pixel", 3f)
    fun setLiPixel(v: Float) = sp.edit().putFloat("li_pixel", v).apply()

    private fun readPoint(key: String): Point? {
        val raw = sp.getString(key, "") ?: ""
        if (raw.isBlank()) return null
        val p = raw.split(",")
        if (p.size != 2) return null
        val x = p[0].toIntOrNull() ?: return null
        val y = p[1].toIntOrNull() ?: return null
        return Point(x, y)
    }

    private fun writePoint(key: String, x: Int, y: Int) {
        sp.edit().putString(key, "$x,$y").apply()
    }

    companion object {
        val defaultKeywords: Set<String> = setOf(
            "攻击", "讨伐", "出征", "出发", "击败", "剿灭", "扫荡", "挑战", "进攻"
        )
    }
}
