package com.example.daqinbandithelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
    /** 点击可视化统一走主线程添加/移除 WindowManager */
    private val mainHandler = Handler(Looper.getMainLooper())

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
        // 帧循环在后台线程，TextView 必须回主线程更新，否则 CalledFromWrongThreadException
        mainHandler.post { tvStatus?.text = s }
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
    fun tap(x: Int, y: Int, label: String = ""): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        p.lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, TAP_MS))
            .build()
        val ok = dispatchGesture(g, null, null)
        showTapViz(x, y)
        Diag.log("执行", "点击 $label @($x,$y)")
        return ok
    }

    /**
     * 执行层入口：消费决策层产出的单个 Action，加防封包装后落到无障碍手势。
     * 只写黑板 exec.*，不感知、不改 FSM 状态。
     */
    fun execute(a: Action) {
        when (a.kind) {
            "tap" -> {
                // 低概率误点空白区域，模拟人手失误（不构成有效指令）
                if (Humanizer.shouldMisclick()) {
                    val w = Blackboard.i("percept.frameW", 1080)
                    val h = Blackboard.i("percept.frameH", 1920)
                    val (bx, by) = Humanizer.blankPoint(w, h)
                    Diag.log("防封", "误点空白 ($bx,$by)")
                    tap(bx, by, "误点空白")
                }
                // 每次点击 ±3~6px 随机偏移
                val (dx, dy) = Humanizer.offset()
                tap(a.x + dx, a.y + dy, "${a.label} 偏移($dx,$dy)")
                Blackboard.incr("exec.actions")
            }
            "swipe" -> {
                val ok = swipeBezier(a.x, a.y, a.x2, a.y2, a.durationMs)
                Diag.log("执行", "拖动地图 (${a.x},${a.y})->(${a.x2},${a.y2}) ok=$ok")
                Blackboard.incr("exec.actions")
            }
            "back" -> {
                back()
                Diag.log("执行", "返回 / 关闭弹窗")
                Blackboard.incr("exec.actions")
            }
            "idle" -> {
                val ms = (a.seconds * 1000f).toLong()
                Blackboard.set("exec.pauseUntil", System.currentTimeMillis() + ms)
                val secText = "%.1f".format(a.seconds)
                Diag.log("执行", "${a.label}，暂停 ${secText}s")
            }
            "wait" -> Unit
            "stop" -> {
                Diag.log("执行", "⑤ 安全终止：${a.label}")
                BotController.stop()
            }
        }
    }

    /** 多段贝塞尔轨迹拖动地图：用手势路径逐点插值，杜绝瞬移 */
    private fun swipeBezier(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long): Boolean {
        val pts = Humanizer.bezier(x1, y1, x2, y2)
        val p = Path()
        p.moveTo(pts[0].first.toFloat(), pts[0].second.toFloat())
        for (i in 1 until pts.size) {
            p.lineTo(pts[i].first.toFloat(), pts[i].second.toFloat())
        }
        val d = if (dur > 0) dur else BotConfig.SWIPE_DURATION_MS
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, d))
            .build()
        return dispatchGesture(g, null, null)
    }

    /**
     * 在屏幕坐标 (x,y) 处显示一次点击波纹（扩散绿环 + 中心点），约 500ms 后自动消失。
     * 用 TYPE_ACCESSIBILITY_OVERLAY + NOT_TOUCHABLE，本身不拦截任何触摸，纯视觉反馈。
     */
    fun showTapViz(x: Int, y: Int) {
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val size = 120
            val view = TapVizView(this)
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = x - size / 2
            params.y = y - size / 2
            try {
                wm.addView(view, params)
                view.play { try { wm.removeView(view) } catch (_: Exception) {} }
            } catch (e: Exception) {
                Log.e("TapService", "tap viz failed", e)
            }
        }
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

/**
 * 点击落点可视化：扩散的绿环 + 中心实心点，随动画推进半径变大、透明度降低，
 * 约 500ms 播完后通过 onDone 回调让调用方移除自身。坐标以视图中心 (60,60) 为落点。
 */
class TapVizView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#3DFF9E")
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3DFF9E")
    }
    private var progress = 0f
    private val c = 60f   // 视图尺寸 120 的一半，即落点
    private var onDone: (() -> Unit)? = null

    fun play(onDone: () -> Unit) {
        this.onDone = onDone
        val anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 500 }
        anim.addUpdateListener {
            progress = it.animatedValue as Float
            postInvalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                onDone()
            }
        })
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
        val r = 12f + progress * 44f
        ringPaint.alpha = alpha
        canvas.drawCircle(c, c, r, ringPaint)
        dotPaint.alpha = alpha
        canvas.drawCircle(c, c, 7f, dotPaint)
    }
}
