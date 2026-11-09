package com.example.daqinbandithelper

import java.util.concurrent.ConcurrentHashMap

/**
 * 共享黑板：三层架构唯一的数据总线（全局字典，线程安全）。
 *
 * 分层写入约定：
 *  - 感知层只写 percept.* / hp.state / res.*（OCR 结果）
 *  - 决策层只读感知结果，只写 fsm.* / target.* / spot.* / decision.* / res.*（流程计数）
 *  - 执行层只写 exec.*，绝不修改 FSM 状态
 */
object Blackboard {

    private val map = ConcurrentHashMap<String, Any>()

    /** 变更订阅：用于把完整状态轨迹写进诊断日志 */
    @Volatile
    var onChanged: ((String, Any?, Any?) -> Unit)? = null

    fun set(key: String, value: Any) {
        val old = map.put(key, value)
        if (old != value) {
            try { onChanged?.invoke(key, old, value) } catch (_: Exception) {}
        }
    }

    fun update(values: Map<String, Any>) {
        for ((k, v) in values) set(k, v)
    }

    fun i(key: String, def: Int = 0): Int = (map[key] as? Number)?.toInt() ?: def
    fun l(key: String, def: Long = 0L): Long = (map[key] as? Number)?.toLong() ?: def
    fun f(key: String, def: Float = 0f): Float = (map[key] as? Number)?.toFloat() ?: def
    fun s(key: String, def: String = ""): String = (map[key] as? String) ?: def
    fun b(key: String, def: Boolean = false): Boolean = (map[key] as? Boolean) ?: def

    fun incr(key: String, delta: Int = 1): Int {
        val v = i(key, 0) + delta
        map[key] = v
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun list(key: String): MutableList<String> {
        val v = map[key]
        return if (v is MutableList<*>) v as MutableList<String>
        else mutableListOf<String>().also { map[key] = it }
    }

    fun snapshot(): Map<String, Any> = HashMap(map)

    /** 每次启动挂机时重置；reset 本身不触发订阅，避免刷屏 */
    fun reset() {
        val defaults: Map<String, Any> = mapOf(
            // 运行控制
            "run.running" to true,
            "run.tick" to 0,
            "run.startedAt" to System.currentTimeMillis(),
            "run.mode" to "punitive",
            "run.stopReason" to "",
            // 感知快照
            "percept.frameW" to 0,
            "percept.frameH" to 0,
            "percept.anchorOk" to false,
            "percept.anchorScale" to 1.0f,
            "percept.scene" to "unknown",
            "percept.banditCount" to 0,
            "percept.hp" to "GREEN",
            // 按钮命中坐标（感知层写入，决策层读取；-1 表示未命中，用归一化坐标兜底）
            "percept.btnAttackX" to -1, "percept.btnAttackY" to -1,
            "percept.btnFieldX" to -1, "percept.btnFieldY" to -1,
            "percept.btnMarchX" to -1, "percept.btnMarchY" to -1,
            "percept.btnCollectX" to -1, "percept.btnCollectY" to -1,
            "percept.btnHomeX" to -1, "percept.btnHomeY" to -1,
            "percept.btnSelectAllX" to -1, "percept.btnSelectAllY" to -1,
            "percept.btnConfirmTroopX" to -1, "percept.btnConfirmTroopY" to -1,
            // 目标与野点
            "target.x" to -1, "target.y" to -1,
            "target.type" to "", "target.level" to 0, "target.score" to 0f,
            "target.locked" to false, "target.spotId" to 0,
            "spot.currentId" to 0,
            "spot.dwellRounds" to 0,
            "spot.clusterCount" to 0,
            // 资源与战损
            "res.tokenLeft" to 20,
            "res.taskUsed" to 0,
            "res.taskLimit" to 5,
            "res.maxWinnableLevel" to 99,
            "hp.state" to "GREEN",
            "hp.pauseAttack" to false,
            // 决策
            "fsm.state" to "Idle",
            "fsm.prevState" to "",
            "fsm.enterAt" to System.currentTimeMillis(),
            "decision.action" to "",
            "decision.note" to "",
            // 执行 / 防封
            "exec.pauseUntil" to 0L,
            "exec.actions" to 0,
            "exec.nightFactor" to 1.0f,
            // 统计
            "stat.rounds" to 0,
            "stat.kills" to 0
        )
        map.clear()
        map.putAll(defaults)
        map["fsm.transitions"] = mutableListOf<String>()
    }
}
