package com.example.daqinbandithelper

/** 跨服务共享的运行状态与停止入口。 */
object BotController {
    var captureService: CaptureService? = null
    /** 当前运行的 FSM，供主界面在挂机中一键切换战斗模式 */
    var fsm: FsmEngine? = null
    var running: Boolean = false
    var captureMode: Boolean = false
    var pendingTemplate: String? = null

    fun stop() {
        running = false
        captureMode = false
        captureService?.requestStop()
    }
}
