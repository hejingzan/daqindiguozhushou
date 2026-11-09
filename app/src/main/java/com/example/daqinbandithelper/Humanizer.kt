package com.example.daqinbandithelper

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 执行层 · 防封号行为模拟（全局单例，供决策层与执行层共用同一套随机源）。
 *
 * 真机 APK 与 PC 版的差异：
 *  - 点击必须是 60ms 短 stroke 才会被游戏识别（v4.0.11 实机验证），
 *    所以「多段贝塞尔轨迹」用于拖动地图这类本身是滑动的动作；
 *    点击采用「±3~6px 随机偏移 + 高斯延迟」。
 *  - 全部等待走高斯/均匀采样，业务代码里没有固定 sleep。
 */
object Humanizer {

    private val rng = java.util.Random()

    /** 00:00–07:00 夜间降频系数，写回黑板 exec.nightFactor */
    fun nightFactor(): Float {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isNight = hour >= BotConfig.NIGHT_START_HOUR && hour < BotConfig.NIGHT_END_HOUR
        val f = if (isNight)
            BotConfig.NIGHT_FACTOR_MIN + rng.nextFloat() * (BotConfig.NIGHT_FACTOR_MAX - BotConfig.NIGHT_FACTOR_MIN)
        else 1.0f
        Blackboard.set("exec.nightFactor", f)
        return f
    }

    /** 帧间隔：高斯抖动，杜绝固定周期；夜间放大 */
    fun frameDelayMs(): Long {
        val f = nightFactor()
        val v = (BotConfig.FRAME_DELAY_MU_MS.toFloat() +
                rng.nextGaussian().toFloat() * BotConfig.FRAME_DELAY_SIGMA_MS)
            .toLong()
            .coerceIn(BotConfig.FRAME_DELAY_MIN_MS, BotConfig.FRAME_DELAY_MAX_MS)
        return (v * f).toLong()
    }

    /** 点击间隔：1~2 秒高斯采样（夜间自动放慢） */
    fun actionDelayMs(): Long {
        val f = nightFactor()
        val v = (1500.0 + rng.nextGaussian() * 330.0).toLong()
            .coerceIn(BotConfig.ACTION_DELAY_MIN_MS, BotConfig.ACTION_DELAY_MAX_MS)
        return (v * f).toLong()
    }

    /** 每次点击独立采样：x / y 各自落在 [-6,-3] ∪ [3,6] */
    fun offset(): Pair<Int, Int> = axis() to axis()

    private fun axis(): Int {
        val raw = (4.5f + abs(rng.nextGaussian().toFloat()) * 1.2f)
        val mag = raw.coerceIn(BotConfig.OFFSET_MIN_PX.toFloat(), BotConfig.OFFSET_MAX_PX.toFloat())
        val v = Math.round(mag)
        return v * (if (rng.nextBoolean()) 1 else -1)
    }

    /** 每 3~6 轮讨伐随机触发一次发呆；用黑板记住下次触发轮次，避免同一轮反复命中 */
    fun shouldIdle(rounds: Int): Boolean {
        if (rounds <= 0) return false
        val next = Blackboard.i("exec.nextIdleRound", -1)
        if (next < 0) {
            Blackboard.set("exec.nextIdleRound", rounds + 3 + rng.nextInt(4))
            return false
        }
        if (rounds < next) return false
        Blackboard.set("exec.nextIdleRound", rounds + 3 + rng.nextInt(4))
        return true
    }

    /** 发呆 5~15 秒，夜间 ×1.25 */
    fun idleSeconds(): Float {
        val base = BotConfig.IDLE_MIN_S + rng.nextFloat() * (BotConfig.IDLE_MAX_S - BotConfig.IDLE_MIN_S)
        return base * (if (nightFactor() > 1f) 1.25f else 1f)
    }

    /** 盗匪刷新等待：8±2.5s，上限 20s */
    fun respawnSeconds(): Float {
        val v = (BotConfig.RESPAWN_MU_S + rng.nextGaussian().toFloat() * BotConfig.RESPAWN_SIGMA_S)
        return v.coerceIn(1f, BotConfig.RESPAWN_MAX_S)
    }

    /** 低概率误点空白区域，模拟人手失误 */
    fun shouldMisclick(): Boolean {
        val p = BotConfig.MISCLICK_PROB * (if (nightFactor() > 1f) 1.5f else 1f)
        return rng.nextFloat() < p
    }

    fun blankPoint(w: Int, h: Int): Pair<Int, Int> {
        val x = 40 + rng.nextInt(maxOf(1, w - 80))
        val y = 40 + rng.nextInt(maxOf(1, h - 80))
        return x to y
    }

    /**
     * 多段三次贝塞尔插值：每段随机控制点 + 8~16 个步长点。
     * 用于拖动地图的滑动路径，杜绝瞬移。
     */
    fun bezier(sx: Int, sy: Int, ex: Int, ey: Int): List<Pair<Int, Int>> {
        val segments = BotConfig.BEZIER_SEG_MIN +
                rng.nextInt(BotConfig.BEZIER_SEG_MAX - BotConfig.BEZIER_SEG_MIN + 1)
        val out = ArrayList<Pair<Int, Int>>()
        out.add(sx to sy)
        var curX = sx.toFloat()
        var curY = sy.toFloat()
        val dist = hypot((ex - sx).toDouble(), (ey - sy).toDouble()).toFloat()
        val amp = maxOf(6f, dist * 0.06f)
        for (i in 0 until segments) {
            val t0 = i.toFloat() / segments
            val t1 = (i + 1f) / segments
            val c1x = sx + (ex - sx) * t0 + (rng.nextFloat() * 2 - 1) * amp
            val c1y = sy + (ey - sy) * t0 + (rng.nextFloat() * 2 - 1) * amp
            val c2x = sx + (ex - sx) * t1 + (rng.nextFloat() * 2 - 1) * amp
            val c2y = sy + (ey - sy) * t1 + (rng.nextFloat() * 2 - 1) * amp
            val p3x = sx + (ex - sx) * t1
            val p3y = sy + (ey - sy) * t1
            val steps = BotConfig.BEZIER_STEP_MIN +
                    rng.nextInt(BotConfig.BEZIER_STEP_MAX - BotConfig.BEZIER_STEP_MIN + 1)
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1f - t
                val px = u * u * u * curX + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * p3x
                val py = u * u * u * curY + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * p3y
                out.add(px.toInt() to py.toInt())
            }
            curX = p3x
            curY = p3y
        }
        val last = out.last()
        if (last.first != ex || last.second != ey) out.add(ex to ey)
        return out
    }
}
