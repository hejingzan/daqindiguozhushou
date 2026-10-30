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

    /**
     * 放射菜单半径（像素 @ REF_W=1280 宽）。即「点盗匪后，讨伐/战斗按钮离盗匪多远」。
     * 不同手机/游戏 UI 缩放下数值不同；「锁定盗匪」实测后会直接覆盖偏移量，此值仅作几何兜底。
     * 默认 24，可在 APP「高级设置」里改（用户实测约 24px@1280）。
     */
    fun getRadialRadius(): Int = sp.getInt("radial_radius", 24)
    fun setRadialRadius(v: Int) = sp.edit().putInt("radial_radius", v.coerceIn(8, 400)).apply()

    /** 是否通过「锁定盗匪」或校准实测过 讨伐/战斗 按钮相对盗匪的偏移（比几何推测更准） */
    fun hasBtnOffsets(): Boolean = sp.getBoolean("btn_off_measured", false)
    fun setHasBtnOffsets(v: Boolean) = sp.edit().putBoolean("btn_off_measured", v).apply()

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

    // ---------------- 盗匪名称文字识别 ----------------

    /** 盗匪名称关键词（无障碍节点文字识别用，逗号分隔多个）。默认含「盗匪喽啰/盗匪精英/盗匪」 */
    fun getBanditNameKeywords(): Set<String> =
        sp.getStringSet("bandit_names", defaultBanditNames) ?: defaultBanditNames
    fun setBanditNameKeywords(v: Set<String>) = sp.edit().putStringSet("bandit_names", v).apply()

    // ---------------- 盗匪名字标签 → 实体点击点 偏移 ----------------

    /**
     * 文字识别拿到的是「盗匪头顶名字标签」的位置，而真正可点击的盗匪实体在名字标签下方。
     * 此值 = 从名字标签中心向下偏移的「屏幕高度比例」（默认 0.05 ≈ 5% 屏高），
     * 让点击点落到盗匪实体上而不是名字文字上。不同游戏/分辨率可在此微调。
     */
    fun getNameBodyOffsetRatio(): Float = sp.getFloat("name_body_offset", 0.05f)
    fun setNameBodyOffsetRatio(v: Float) =
        sp.edit().putFloat("name_body_offset", v.coerceIn(0f, 0.3f)).apply()

    // ---------------- 屏幕搜索范围与固定点 ----------------

    /**
     * 是否全屏搜索：开启时忽略范围限制，扫描整个屏幕上的所有盗匪
     * （仅按「固定点」挑选最近的去打）。默认开启。
     */
    fun isFullScreenSearch(): Boolean = sp.getBoolean("full_screen_search", true)
    fun setFullScreenSearch(v: Boolean) =
        sp.edit().putBoolean("full_screen_search", v).apply()

    /**
     * 固定点 X：屏幕宽度比例（0~1，0.5=屏幕中线）。
     * 用于「挑选最近盗匪」的参考中心，可在 APP 里修改，把挂机重心对准你拉兵的位置。
     */
    fun getSearchCenterXRatio(): Float = sp.getFloat("search_center_x", 0.5f)
    fun setSearchCenterXRatio(v: Float) =
        sp.edit().putFloat("search_center_x", v.coerceIn(0f, 1f)).apply()

    /**
     * 固定点 Y：屏幕高度比例（0~1，0.5=屏幕中线）。
     */
    fun getSearchCenterYRatio(): Float = sp.getFloat("search_center_y", 0.5f)
    fun setSearchCenterYRatio(v: Float) =
        sp.edit().putFloat("search_center_y", v.coerceIn(0f, 1f)).apply()

    /**
     * 屏幕中心搜索半径：占「屏幕较短边」的比例（0.1~0.95）。
     * 仅在「全屏搜索」关闭时生效，用于限定只打固定点附近一片区域的盗匪。
     * 游戏内「里」是随地图缩放变化的，无法精确换算成像素，
     * 因此用「屏幕中心周围像素半径」来近似「屏幕中心周围约 1000 里」。
     * 默认 0.48 ≈ 覆盖屏幕中心附近大半个可见区域。
     */
    fun getCenterRadiusRatio(): Float = sp.getFloat("center_radius_ratio", 0.48f)
    fun setCenterRadiusRatio(v: Float) =
        sp.edit().putFloat("center_radius_ratio", v.coerceIn(0.1f, 0.95f)).apply()

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
        /** 盗匪名称关键词（无障碍文字识别用）：游戏里盗匪头顶的名字标签 */
        val defaultBanditNames: Set<String> = setOf(
            "盗匪喽啰", "盗匪精英", "盗匪"
        )
    }
}
