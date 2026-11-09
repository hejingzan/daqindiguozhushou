package com.example.daqinbandithelper

/** 全局常量、模板名、状态机状态、广播 Action。 */
object C {
    // 模板名
    const val TPL_BANDIT = "bandit"
    const val TPL_ATTACK = "attack"
    const val TPL_MARCH = "march"
    const val TPL_CONFIRM = "confirm"
    const val TPL_CLOSE = "close"
    const val TPL_HOME = "home"
    // 讨伐后弹出"选队伍"面板：先点【全选】再点【确定】
    const val TPL_SELECT_ALL = "select_all"
    const val TPL_CONFIRM_TROOP = "confirm_troop"

    // v5.0.0 三层架构新增：三类盗匪分级图标 + 野战按钮。
    // 用户若未采集分级模板，感知层自动回退到旧 TPL_BANDIT（统一按 normal 处理），不影响可用性。
    const val TPL_BANDIT_NORMAL = "bandit_normal"
    const val TPL_BANDIT_ELITE = "bandit_elite"
    const val TPL_BANDIT_BOSS = "bandit_boss"
    const val TPL_FIELD = "field_battle"

    val TPL_ORDER = listOf(
        TPL_BANDIT, TPL_BANDIT_NORMAL, TPL_BANDIT_ELITE, TPL_BANDIT_BOSS,
        TPL_ATTACK, TPL_FIELD, TPL_SELECT_ALL, TPL_CONFIRM_TROOP,
        TPL_MARCH, TPL_CONFIRM, TPL_CLOSE, TPL_HOME
    )

    /** 按钮模板未命中时的归一化兜底坐标（0..1，相对整帧），任何分辨率自适应 */
    val BTN_FALLBACK: Map<String, FloatArray> = mapOf(
        TPL_ATTACK to floatArrayOf(0.77f, 0.76f),
        TPL_FIELD to floatArrayOf(0.77f, 0.86f),
        TPL_MARCH to floatArrayOf(0.50f, 0.87f),
        TPL_CONFIRM to floatArrayOf(0.50f, 0.81f),
        TPL_SELECT_ALL to floatArrayOf(0.20f, 0.80f),
        TPL_CONFIRM_TROOP to floatArrayOf(0.50f, 0.88f),
        TPL_CLOSE to floatArrayOf(0.93f, 0.10f),
        TPL_HOME to floatArrayOf(0.88f, 0.16f)
    )

    /** ⑤ HSV 血条区域 / ⑥ 讨伐令 OCR 区域 / ③ 任务次数 OCR 区域（归一化） */
    val HP_ROI = floatArrayOf(0.30f, 0.06f, 0.70f, 0.16f)
    val TOKEN_ROI = floatArrayOf(0.55f, 0.01f, 0.98f, 0.09f)
    val TASK_ROI = floatArrayOf(0.18f, 0.16f, 0.82f, 0.32f)

    // 场景枚举
    const val SCENE_IDLE = "idle_map"
    const val SCENE_MARCHING = "marching"
    const val SCENE_SETTLEMENT = "settlement"
    const val SCENE_PUNITIVE_DLG = "punitive_dlg"
    const val SCENE_TASK_DLG = "task_dlg"
    const val SCENE_TROOP_DLG = "troop_dlg"
    const val SCENE_RETURN_CITY = "return_city"
    const val SCENE_UNKNOWN = "unknown"

    /**
     * 每个模板的搜索区域（归一化 0..1，相对整帧；null=全屏搜索）。
     * 思路：讨伐/选队伍/出征/领奖等按钮只会出现在屏幕固定区域，限制 ROI 可大幅减少地图噪声、
     * 避免与“前往/出征”等金色文字混淆，并显著提升匹配速度与分数。
     * 注意：TPL_ATTACK（讨伐）为 null，由感知层全屏匹配定位按钮坐标。
     */
    val TPL_ROI: Map<String, FloatArray?> = mapOf(
        TPL_SELECT_ALL to floatArrayOf(0.06f, 0.16f, 0.94f, 0.96f),
        TPL_CONFIRM_TROOP to floatArrayOf(0.06f, 0.16f, 0.94f, 0.96f),
        TPL_MARCH to floatArrayOf(0.12f, 0.45f, 0.96f, 0.98f),
        TPL_CONFIRM to floatArrayOf(0.12f, 0.35f, 0.96f, 0.98f),
        TPL_CLOSE to floatArrayOf(0.0f, 0.0f, 0.6f, 0.5f),
        TPL_FIELD to floatArrayOf(0.5f, 0.5f, 0.99f, 0.99f)
        // TPL_ATTACK / TPL_BANDIT / 分级模板 / TPL_HOME 不在表中 → 全屏搜索
    )
    val TPL_LABELS = mapOf(
        TPL_BANDIT to "盗匪图标(通用)",
        TPL_BANDIT_NORMAL to "普通盗匪",
        TPL_BANDIT_ELITE to "精英盗匪",
        TPL_BANDIT_BOSS to "盗匪首领",
        TPL_FIELD to "野战(不扣令)",
        TPL_ATTACK to "讨伐",
        TPL_SELECT_ALL to "全选(选队伍)",
        TPL_CONFIRM_TROOP to "确定(选队伍)",
        TPL_MARCH to "确认出征",
        TPL_CONFIRM to "确定领奖",
        TPL_CLOSE to "关闭叉号",
        TPL_HOME to "主城/地图"
    )

    // 服务 Action
    const val ACTION_START = "com.example.daqinbandithelper.START_BOT"
    const val ACTION_CAPTURE = "com.example.daqinbandithelper.CAPTURE_MODE"
    const val ACTION_STOP = "com.example.daqinbandithelper.STOP"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_DATA = "data"
    const val EXTRA_TPL = "tpl"

    const val NOTIF_ID = 1001
    const val CHANNEL_ID = "daqin_bot_channel"

    // 状态机
    const val S1_FIND = 1
    const val S2_VALIDATE = 2
    const val S25_SELECT = 25   // 讨伐后选队伍：全选 → 确定
    const val S3_MARCH = 3
    const val S4_WAIT = 4
    const val S5_COLLECT = 5
    const val S6_SLEEP = 6
}
