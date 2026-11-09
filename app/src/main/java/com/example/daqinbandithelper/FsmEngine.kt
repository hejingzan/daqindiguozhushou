package com.example.daqinbandithelper

import android.content.Context

/**
 * 决策层 · 有限状态机（FSM）——只读黑板做判断，产出 Action 交给执行层，本层不点击。
 *
 * 5 大状态：Idle → Approach → Engage → Recover → Stop
 * 全局守卫（每帧最先执行，优先级最高）：
 *   G4 运行时长/手动停止 · G1 讨伐令不足 · G2 每日次数上限 → Stop
 *   G3 血条 RED（重度战损） → Recover
 *
 * 六条硬性游戏约束的落点：
 *   ① 分级打分 + 可战胜等级筛选 → onIdle()
 *   ② 讨伐/野战一键切换 → setMode() / onApproach()
 *   ③ 每日 4~5 次上限 → guards G2 / onEngage()
 *   ④ 扎堆优先 + 3 轮轮转 + 刷新等待 → onIdle() / rotateSpot()
 *   ⑤ 黄血暂停 / 红血回城 → onIdle() / guards G3 / onRecover()
 *   ⑥ 讨伐令校验 → guards G1 / onEngage()
 */
class FsmEngine(private val ctx: Context) {

    private val prefs = Prefs(ctx)
    private var state = "Idle"
    private var enterAt = System.currentTimeMillis()
    private var nextActionAt = 0L
    private val entered = HashSet<String>()
    private var statusCb: ((String) -> Unit)? = null

    fun setStatusCallback(cb: (String) -> Unit) {
        statusCb = cb
    }

    fun reset() {
        Blackboard.reset()
        Blackboard.set("run.mode", prefs.mode)
        Blackboard.set("res.taskLimit", prefs.taskLimit)
        Blackboard.set("res.tokenLeft", prefs.tokenLeft)
        Blackboard.set("res.maxWinnableLevel", prefs.maxLevel)
        state = "Idle"
        enterAt = System.currentTimeMillis()
        nextActionAt = 0L
        entered.clear()
        Blackboard.set("fsm.state", state)
        val label = if (prefs.mode == "field") "野战(不扣令)" else "讨伐(扣令)"
        status("FSM 重置完成，模式：$label")
    }

    /** ② 一键切换：punitive=讨伐(扣令) / field=野战(不扣令) */
    fun setMode(mode: String) {
        val m = if (mode == "field") "field" else "punitive"
        prefs.mode = m
        Blackboard.set("run.mode", m)
        val label = if (m == "punitive") "讨伐(扣令)" else "野战(不扣令)"
        status("② 模式切换：$label")
    }

    private fun status(s: String) {
        statusCb?.invoke(s)
        try { TapService.instance?.setStatus(s) } catch (_: Exception) {}
        Diag.log("FSM", s)
    }

    private fun transition(nxt: String, note: String = "") {
        if (nxt == state) return
        val rec = "$state -> $nxt" + (if (note.isNotEmpty()) "  [$note]" else "")
        Blackboard.set("fsm.prevState", state)
        Blackboard.set("fsm.state", nxt)
        Blackboard.set("fsm.enterAt", System.currentTimeMillis())
        Blackboard.set("decision.note", note)
        val tr = Blackboard.list("fsm.transitions")
        tr.add(rec)
        Blackboard.set("fsm.transitions", tr)
        state = nxt
        enterAt = System.currentTimeMillis()
        entered.clear()
        status("FSM $rec")
    }

    // ------------------------------------------------------------ 全局守卫
    private fun guards(p: Percept): Pair<String, Action>? {
        if (!Blackboard.b("run.running", true))
            return "Stop" to Action("stop", "手动停止")
        if (System.currentTimeMillis() -
            Blackboard.l("run.startedAt", System.currentTimeMillis()) > BotConfig.MAX_RUNTIME_MS)
            return "Stop" to Action("stop", "达到运行时长上限")
        // ⑥ G1：讨伐令不足（野战模式跳过）
        if (Blackboard.s("run.mode", "punitive") == "punitive" &&
            Blackboard.i("res.tokenLeft", 0) < BotConfig.TOKEN_MIN)
            return "Stop" to Action("stop", "讨伐令不足，安全终止")
        // ③ G2：每日村庄讨伐任务次数上限 4~5
        if (Blackboard.i("res.taskUsed", 0) >=
            Blackboard.i("res.taskLimit", BotConfig.TASK_LIMIT_DEFAULT))
            return "Stop" to Action("stop", "今日村庄讨伐次数已达上限，停止刷野")
        // ⑤ G3：血条 RED 优先保兵
        if (p.hp == "RED" && state != "Recover" && state != "Stop")
            return "Recover" to Action("wait", "重度战损，转入回城")
        return null
    }

    // ------------------------------------------------------------ 每帧入口
    fun step(p: Percept): Action {
        val g = guards(p)
        if (g != null) {
            transition(g.first, g.second.label)
            if (g.first == "Stop") {
                Blackboard.set("run.stopReason", g.second.label)
                Blackboard.set("run.running", false)
            }
            return g.second
        }

        val now = System.currentTimeMillis()
        if (now < Blackboard.l("exec.pauseUntil", 0L)) {
            return Action("wait", "防封发呆 / 刷新等待中")
        }

        val result: Pair<String, Action> = when (state) {
            "Idle" -> onIdle(p)
            "Approach" -> onApproach(p)
            "Engage" -> onEngage(p)
            "Recover" -> onRecover(p)
            else -> onStop()
        }
        transition(result.first)

        var a = result.second
        // 输入类动作统一受「1~2 秒随机间隔」冷却约束
        if (a.kind == "tap" || a.kind == "swipe" || a.kind == "back") {
            if (now < nextActionAt) {
                a = Action("wait", "点击间隔冷却中")
            } else {
                nextActionAt = now + Humanizer.actionDelayMs()
            }
        }
        Blackboard.set("decision.action", a.toString())
        return a
    }

    // ============================================================ ① Idle
    private fun onIdle(p: Percept): Pair<String, Action> {
        if (p.scene == C.SCENE_SETTLEMENT)
            return "Engage" to Action("wait", "检测到结算界面")

        // ⑤ 黄血：中度损耗，暂停进攻，不点任何目标
        if (p.hp == "YELLOW") {
            Blackboard.set("hp.pauseAttack", true)
            return "Idle" to Action("wait", "⑤ 中度战损，暂停进攻等待恢复")
        }
        Blackboard.set("hp.pauseAttack", false)
        if (p.scene == C.SCENE_PUNITIVE_DLG || p.scene == C.SCENE_TROOP_DLG ||
            p.scene == C.SCENE_TASK_DLG)
            return "Idle" to Action("back", "关闭残留弹窗")

        // ④ 无盗匪：高斯刷新等待 + 轮转野点，绝不死蹲
        if (p.bandits.isEmpty()) {
            val sec = Humanizer.respawnSeconds()
            rotateSpot()
            return "Idle" to Action("idle", "④ 同屏无盗匪，刷新等待并轮转野点", seconds = sec)
        }

        // ① 分级打分：等级越高收益越高，且只保留「可战胜」等级区间
        val maxLv = Blackboard.i("res.maxWinnableLevel", prefs.maxLevel)
        val minLv = prefs.minLevel
        val ranked = ArrayList<Bandit>()
        for (b in p.bandits) {
            if (b.level < minLv || b.level > maxLv) continue
            val s = (BotConfig.TYPE_WEIGHT[b.type] ?: 1f) + BotConfig.LEVEL_WEIGHT * b.level
            ranked.add(b.copy(score = s))
        }
        if (ranked.isEmpty()) {
            rotateSpot()
            return "Idle" to Action("idle", "① 无可战胜目标，轮转野点", seconds = 6f)
        }
        ranked.sortByDescending { it.score }

        // ④ 同屏 >=3 个盗匪 = 扎堆野点优先；单点蹲满 3 轮强制轮转
        val cluster = p.bandits.size >= BotConfig.CLUSTER_MIN
        Blackboard.set("spot.clusterCount", p.bandits.size)
        val dwell = Blackboard.i("spot.dwellRounds", 0)
        var pool: List<Bandit> = ranked
        if (dwell >= BotConfig.DWELL_MAX) {
            rotateSpot()
            val cur = Blackboard.i("spot.currentId", 0)
            val rotated = ranked.filter { it.spotId == cur }
            if (rotated.isNotEmpty()) pool = rotated
            status("④ 单点停留达上限，强制轮转野点 #$cur")
        } else if (cluster) {
            val cur = ranked[0].spotId
            Blackboard.set("spot.currentId", cur)
            pool = ranked.filter { it.spotId == cur }.ifEmpty { ranked }
        }
        val t = pool.maxByOrNull { it.score } ?: ranked[0]

        Blackboard.update(mapOf<String, Any>(
            "target.x" to t.x, "target.y" to t.y,
            "target.type" to t.type, "target.level" to t.level,
            "target.score" to t.score, "target.locked" to true,
            "target.spotId" to t.spotId
        ))
        val scoreText = "%.2f".format(t.score)
        status("① 选中 ${t.type} Lv${t.level} 分=$scoreText 野点#${t.spotId} @(${t.x},${t.y})")
        return "Approach" to Action("tap", "点选盗匪目标", t.x, t.y)
    }

    private fun rotateSpot() {
        val cur = Blackboard.i("spot.currentId", 0)
        val total = BotConfig.GRID_COLS * BotConfig.GRID_ROWS
        Blackboard.set("spot.currentId", (cur + 1) % total)
        Blackboard.set("spot.dwellRounds", 0)
    }

    // ============================================================ ② Approach
    private fun onApproach(p: Percept): Pair<String, Action> {
        if (System.currentTimeMillis() - enterAt > BotConfig.APPROACH_TIMEOUT_MS)
            return "Idle" to Action("back", "行军超时，回地图重搜")
        if (p.scene == C.SCENE_SETTLEMENT || p.scene == C.SCENE_MARCHING)
            return "Engage" to Action("wait", "已进入战斗/行军")
        if (p.scene == C.SCENE_TASK_DLG)
            return "Idle" to Action("back", "读取任务次数弹窗")

        if (p.scene == C.SCENE_PUNITIVE_DLG) {
            // ② 一键切换：讨伐扣令 / 野战不扣令
            if (Blackboard.s("run.mode", "punitive") == "punitive") {
                if (Blackboard.i("res.tokenLeft", 0) < BotConfig.TOKEN_MIN)
                    return "Stop" to Action("stop", "讨伐令不足")
                val (x, y) = btnPos(C.TPL_ATTACK, "percept.btnAttackX", "percept.btnAttackY", p)
                return "Approach" to Action("tap", "【讨伐】消耗讨伐令", x, y)
            }
            val (x, y) = btnPos(C.TPL_FIELD, "percept.btnFieldX", "percept.btnFieldY", p)
            return "Approach" to Action("tap", "【野战】不消耗讨伐令", x, y)
        }

        if (p.scene == C.SCENE_TROOP_DLG) {
            if (!entered.contains("selectAll")) {
                val sx = Blackboard.i("percept.btnSelectAllX", -1)
                if (sx >= 0) {
                    entered.add("selectAll")
                    return "Approach" to Action("tap", "全选(选队伍)", sx,
                        Blackboard.i("percept.btnSelectAllY", -1))
                }
            }
            val (x, y) = btnPos(C.TPL_CONFIRM_TROOP, "percept.btnConfirmTroopX",
                "percept.btnConfirmTroopY", p)
            return "Approach" to Action("tap", "确定(选队伍)", x, y)
        }

        if (!entered.contains("confirm")) {
            entered.add("confirm")
            val (x, y) = btnPos(C.TPL_MARCH, "percept.btnMarchX", "percept.btnMarchY", p)
            return "Approach" to Action("tap", "确认出征", x, y)
        }
        return "Approach" to Action("wait", "等待行军界面")
    }

    /** 优先用感知层命中的按钮坐标，未命中时退回归一化坐标（分辨率自适应） */
    private fun btnPos(name: String, kx: String, ky: String, p: Percept): Pair<Int, Int> {
        val x = Blackboard.i(kx, -1)
        val y = Blackboard.i(ky, -1)
        if (x >= 0 && y >= 0) return x to y
        val f = C.BTN_FALLBACK[name] ?: floatArrayOf(0.5f, 0.5f)
        return (f[0] * p.frameW).toInt() to (f[1] * p.frameH).toInt()
    }

    // ============================================================ ③ Engage
    private fun onEngage(p: Percept): Pair<String, Action> {
        if (System.currentTimeMillis() - enterAt > BotConfig.ENGAGE_TIMEOUT_MS)
            return "Idle" to Action("back", "结算超时，回地图")
        if (p.scene != C.SCENE_SETTLEMENT)
            return "Engage" to Action("wait", "等待战斗结算界面")

        val (x, y) = btnPos(C.TPL_CONFIRM, "percept.btnCollectX", "percept.btnCollectY", p)
        // ③⑥ 仅讨伐模式计入每日次数、扣减讨伐令（OCR 结果为准，流程计数做兜底）
        if (Blackboard.s("run.mode", "punitive") == "punitive") {
            Blackboard.incr("res.taskUsed", 1)
            Blackboard.incr("res.tokenLeft", -1)
        }
        val rounds = Blackboard.incr("stat.rounds", 1)
        Blackboard.incr("stat.kills", 1)
        Blackboard.incr("spot.dwellRounds", 1)

        // 防封：每 3~6 轮随机发呆 5~15 秒
        if (Humanizer.shouldIdle(rounds)) {
            val sec = Humanizer.idleSeconds()
            Blackboard.set("exec.pauseUntil", System.currentTimeMillis() + (sec * 1000f).toLong())
            val secText = "%.1f".format(sec)
            status("防封：完成第 $rounds 轮，发呆 ${secText}s")
        }
        return "Idle" to Action("tap", "领取战斗结算", x, y, afterRound = true)
    }

    // ============================================================ ④ Recover
    private fun onRecover(p: Percept): Pair<String, Action> {
        if (System.currentTimeMillis() - enterAt > BotConfig.RECOVER_TIMEOUT_MS)
            return "Stop" to Action("stop", "回城休整超时，安全终止")
        if (p.hp == "GREEN" && entered.contains("recover")) {
            Blackboard.set("hp.pauseAttack", false)
            Blackboard.set("spot.dwellRounds", 0)
            return "Idle" to Action("wait", "⑤ 血量回满，恢复讨伐")
        }
        if (!entered.contains("recover")) {
            entered.add("recover")
            val (x, y) = btnPos(C.TPL_HOME, "percept.btnHomeX", "percept.btnHomeY", p)
            return "Recover" to Action("tap", "回城休整", x, y)
        }
        return "Recover" to Action("wait", "等待部队恢复 / 补令校验")
    }

    // ============================================================ ⑤ Stop
    private fun onStop(): Pair<String, Action> =
        "Stop" to Action("stop", Blackboard.s("run.stopReason", "安全终止"))
}
