package com.example.daqinbandithelper

/**
 * 三层架构的静态参数：游戏机制约束 + 防封行为参数。
 * 与用户可调项（Prefs）分开：Prefs 存界面设置，这里存算法常量。
 */
object BotConfig {

    // ---------- 感知 / 分辨率自适应 ----------
    const val GRID_COLS = 3
    const val GRID_ROWS = 2
    const val DESIGN_W = 1280
    const val DESIGN_H = 720

    // ---------- ① 盗匪分级 ----------
    val TYPE_WEIGHT = mapOf("normal" to 1.0f, "elite" to 1.35f, "boss" to 1.7f)
    const val LEVEL_WEIGHT = 0.12f
    const val DEFAULT_LEVEL = 20

    // ---------- ③ 每日上限 / ⑥ 资源 ----------
    const val TASK_LIMIT_DEFAULT = 5
    const val TOKEN_MIN = 1

    // ---------- ④ 刷野效率 ----------
    const val CLUSTER_MIN = 3          // 同屏 >=3 个盗匪视为扎堆野点
    const val DWELL_MAX = 3            // 单点最多蹲 3 轮，强制轮转
    const val RESPAWN_MU_S = 8f
    const val RESPAWN_SIGMA_S = 2.5f
    const val RESPAWN_MAX_S = 20f

    // ---------- ⑤ 血条 HSV ----------
    const val HP_S_MIN = 90f
    const val HP_V_MIN = 90f

    // ---------- 状态超时 / 运行时长 ----------
    const val APPROACH_TIMEOUT_MS = 90_000L
    const val ENGAGE_TIMEOUT_MS = 120_000L
    const val RECOVER_TIMEOUT_MS = 180_000L
    const val MAX_RUNTIME_MS = 5 * 60 * 60 * 1000L

    // ---------- 防封行为参数（交付物 ③） ----------
    const val OFFSET_MIN_PX = 3        // 每次点击 ±3~6px
    const val OFFSET_MAX_PX = 6
    const val ACTION_DELAY_MIN_MS = 1000L   // 点击间隔 1~2 秒
    const val ACTION_DELAY_MAX_MS = 2000L
    const val FRAME_DELAY_MU_MS = 420L      // 帧间隔也随机化，打破固定周期
    const val FRAME_DELAY_SIGMA_MS = 130f
    const val FRAME_DELAY_MIN_MS = 250L
    const val FRAME_DELAY_MAX_MS = 800L
    const val IDLE_EVERY_MIN_ROUNDS = 3
    const val IDLE_EVERY_MAX_ROUNDS = 6
    const val IDLE_MIN_S = 5f
    const val IDLE_MAX_S = 15f
    const val MISCLICK_PROB = 0.03f        // 低概率误点空白
    const val NIGHT_START_HOUR = 0
    const val NIGHT_END_HOUR = 7
    const val NIGHT_FACTOR_MIN = 1.6f
    const val NIGHT_FACTOR_MAX = 2.2f
    const val BEZIER_SEG_MIN = 3
    const val BEZIER_SEG_MAX = 5
    const val BEZIER_STEP_MIN = 8
    const val BEZIER_STEP_MAX = 16
    const val SWIPE_DURATION_MS = 380L
}
