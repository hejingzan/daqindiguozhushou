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

    val TPL_ORDER = listOf(TPL_BANDIT, TPL_ATTACK, TPL_MARCH, TPL_CONFIRM, TPL_CLOSE, TPL_HOME)
    val TPL_LABELS = mapOf(
        TPL_BANDIT to "盗匪图标",
        TPL_ATTACK to "讨伐",
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
    const val S3_MARCH = 3
    const val S4_WAIT = 4
    const val S5_COLLECT = 5
    const val S6_SLEEP = 6
}
