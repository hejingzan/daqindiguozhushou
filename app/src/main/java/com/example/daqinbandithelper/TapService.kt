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
    }

    private var overlayView: View? = null
    private var tvStatus: TextView? = null
    private var wm: WindowManager? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideOverlay()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun showOverlay(status: String) {
        if (overlayView != null) {
            setStatus(status)
            return
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
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            wm!!.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("TapService", "add overlay failed", e)
        }
        setStatus(status)
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

    fun tap(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        p.lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 1))
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
