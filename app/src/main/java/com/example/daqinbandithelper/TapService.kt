package com.example.daqinbandithelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView

/**
 * 无障碍服务：负责真正的"点击/滑动"（dispatchGesture），并承载状态悬浮窗。
 * 悬浮窗的「停止」按钮调用 BotController.stop()；「📷截图」按钮（采集模式）调用 CaptureService.shoot()。
 */
class TapService : AccessibilityService() {

    companion object {
        var instance: TapService? = null
        private const val TAP_MS = 60L
    }

    private var overlayView: View? = null
    private var tvStatus: TextView? = null
    private var wm: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        // 早于 onServiceConnected 先挂上，避免此刻发起调用时 instance 还是 null
        instance = this
        Diag.log("无障碍", "服务 onCreate")
    }

    override fun onServiceConnected() {
        instance = this
        Diag.log("无障碍", "服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Diag.log("无障碍", "服务已断开")
        hideOverlay()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** 显示无障碍浮层。返回 false 表示没挂上（调用方需回退到其它方式）。 */
    fun showOverlay(status: String): Boolean {
        if (overlayView != null) {
            setStatus(status)
            return true
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_status, null)
        tvStatus = overlayView!!.findViewById(R.id.tv_status)
        val btnStop = overlayView!!.findViewById<Button>(R.id.btn_overlay_stop)
        val btnShoot = overlayView!!.findViewById<Button>(R.id.btn_overlay_shoot)
        btnStop.setOnClickListener { BotController.stop() }
        btnShoot.visibility = if (BotController.captureMode) View.VISIBLE else View.GONE
        btnShoot.setOnClickListener { BotController.captureService?.shoot() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 必须加 NOT_TOUCH_MODAL，否则该窗口是"触摸模态"的，会吞掉整个屏幕的触摸，
            // 导致游戏完全点不动；NOT_FOCUSABLE 避免抢走输入焦点。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        return try {
            wm!!.addView(overlayView, params)
            Diag.log("浮层", "无障碍浮层已挂上")
            setStatus(status)
            true
        } catch (e: Exception) {
            Log.e("TapService", "add overlay failed", e)
            Diag.log("浮层", "无障碍浮层挂载失败：${e.javaClass.simpleName}: ${e.message}")
            overlayView = null
            tvStatus = null
            false
        }
    }

    fun setStatus(s: String) {
        tvStatus?.text = s
    }

    fun hideOverlay() {
        if (overlayView != null) {
            try {
                wm?.removeView(overlayView)
            } catch (_: Exception) {
            }
            overlayView = null
            tvStatus = null
        }
    }

    /** 单次点击。时长过短（如 1ms）部分游戏吃不到，这里用 60ms 保证被识别为一次有效点击。 */
    fun tap(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        p.lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, TAP_MS))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long): Boolean {
        val p = Path()
        p.moveTo(x1.toFloat(), y1.toFloat())
        p.lineTo(x2.toFloat(), y2.toFloat())
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
