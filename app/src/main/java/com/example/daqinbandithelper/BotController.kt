package com.example.daqinbandithelper

/** 跨服务共享的运行状态与停止入口。 */
object BotController {
    var captureService: CaptureService? = null
    var running: Boolean = false
    var captureMode: Boolean = false
    var pendingTemplate: String? = null

    fun stop() {
        running = false
        captureMode = false
        captureService?.requestStop()
    }
}
