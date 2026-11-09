package com.example.daqinbandithelper

/**
 * 决策层 → 执行层的唯一指令格式。
 * 一帧只发一个动作，杜绝线性循环里堆砌点击。
 */
data class Action(
    val kind: String,              // tap | swipe | back | wait | idle | stop
    val label: String = "",
    val x: Int = -1,
    val y: Int = -1,
    val x2: Int = -1,
    val y2: Int = -1,
    val seconds: Float = 0f,       // idle（发呆 / 刷新等待）秒数
    val durationMs: Long = 0L,     // swipe 时长
    val afterRound: Boolean = false
) {
    override fun toString(): String = when (kind) {
        "tap" -> "tap($label)@($x,$y)"
        "swipe" -> "swipe($label)($x,$y)->($x2,$y2)"
        "idle" -> "idle($label,${seconds}s)"
        else -> "$kind($label)"
    }
}
