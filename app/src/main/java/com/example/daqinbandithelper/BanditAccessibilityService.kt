package com.example.daqinbandithelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.random.Random

/**
 * 核心无障碍服务（傻瓜式 + 模板匹配）：
 *  - dispatchGesture 在屏幕任意坐标模拟真实触摸（对 OpenGL/Unity 游戏有效）。
 *  - MediaProjection 截图 + 模板匹配：自动在屏幕中找到「盗匪 / 讨伐按钮 / 出征按钮」。
 *    由于游戏里点盗匪后弹出面板的位置会随盗匪在屏幕上的位置变化，固定坐标会失效，
 *    因此全部改为模板匹配。
 *  - 三步校准：用户依次点【盗匪】→【讨伐】→【出征】，服务把点击转发给游戏并自动
 *    截取对应元素作为模板（校准时临时隐藏自身浮层，保证模板为纯净游戏画面）。
 *  - 悬浮窗 + 通知栏均可开始 / 停止。
 */
class BanditAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_CAPTURE = "start_capture"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_CALIBRATE = "calibrate"
        const val ACTION_CALIB_DONE = "com.example.daqinbandithelper.CALIB_DONE"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "daqin_channel"
        private const val TAG = "DaQinHelper"
        private const val MATCH_WIDTH = 360
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var running = false
    private var calibrating = false
    private var calibBusy = false
    private var keywords: Set<String> = emptySet()
    private var intervalMs: Long = 3000
    private var battleWaitMs: Long = 20000
    private var matchThreshold = 55

    // 拟人化强度参数（由 humanizeLevel 决定，0=轻 1=中 2=强）
    private var humanJitter = 7
    private var humanStartSpread = 24
    private var humanMidMin = 1
    private var humanMidMax = 2
    private var humanIntervalRatio = 0.4
    private var humanPauseProb = 0.08

    private var banditTpl: Bitmap? = null
    private var attackTpl: Bitmap? = null
    private var marchTpl: Bitmap? = null

    // 媒体投影
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // 浮层
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var calibOverlay: View? = null
    private var calibStep = 0
    private var missCount = 0

    // 防抖
    private var lastClickKey = ""
    private var lastClickTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info

        createNotificationChannel()
        startForegroundSafe()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadConfig()
        showOverlay()
    }

    private fun startForegroundSafe() {
        val notif = buildNotification("准备中", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val rc = intent.getIntExtra("result_code", 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("data")
                }
                if (data != null) setupMediaProjection(rc, data)
            }
            ACTION_START -> startLoop()
            ACTION_STOP -> stopLoop()
            ACTION_CALIBRATE -> startCalibration()
        }
        return START_STICKY
    }

    private fun loadConfig() {
        keywords = prefs.getKeywords()
        intervalMs = prefs.getIntervalMs()
        battleWaitMs = prefs.getBattleWaitMs()
        matchThreshold = prefs.getMatchThreshold()
        applyHumanLevel(prefs.getHumanizeLevel())
        banditTpl = loadTpl("bandit")
        attackTpl = loadTpl("attack")
        marchTpl = loadTpl("march")
    }

    private fun loadTpl(name: String): Bitmap? {
        val f = prefs.tplFile(name)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /** 根据强度档位设置拟人化参数（0=轻 1=中 2=强） */
    private fun applyHumanLevel(level: Int) {
        when (level) {
            0 -> { // 轻
                humanJitter = 3
                humanStartSpread = 15
                humanMidMin = 0
                humanMidMax = 1
                humanIntervalRatio = 0.2
                humanPauseProb = 0.03
            }
            2 -> { // 强
                humanJitter = 12
                humanStartSpread = 40
                humanMidMin = 2
                humanMidMax = 3
                humanIntervalRatio = 0.7
                humanPauseProb = 0.15
            }
            else -> { // 中（默认）
                humanJitter = 7
                humanStartSpread = 24
                humanMidMin = 1
                humanMidMax = 2
                humanIntervalRatio = 0.4
                humanPauseProb = 0.08
            }
        }
    }    }

    // ---------------- 媒体投影 / 截图 ----------------

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            return
        }

        val metrics = DisplayMetrics()
        val display = windowManager!!.defaultDisplay
        display.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DaQinCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val w = screenWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(w, screenHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } finally {
            bitmap.recycle()
        }
    }

    /** 截取 (cx,cy) 周围 radius 区域，存为识别模板（截取前隐藏浮层以保证纯净） */
    private fun captureTemplateFile(name: String, cx: Int, cy: Int, radius: Int) {
        overlayView?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        if (screen == null) return
        try {
            val x0 = (cx - radius).coerceIn(0, screen.width - 1)
            val y0 = (cy - radius).coerceIn(0, screen.height - 1)
            val w = (radius * 2).coerceAtMost(screen.width - x0)
            val h = (radius * 2).coerceAtMost(screen.height - y0)
            if (w <= 0 || h <= 0) { screen.recycle(); return }
            val patch = Bitmap.createBitmap(screen, x0, y0, w, h)
            screen.recycle()
            prefs.tplFile(name).outputStream().use {
                patch.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            patch.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "captureTemplateFile error", e)
            screen.recycle()
        }
    }

    /** 在全屏截图中匹配给定模板，返回中心坐标；匹配前隐藏浮层 */
    private fun matchTemplate(tpl: Bitmap?): Point? {
        if (tpl == null) return null
        overlayView?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        if (screen == null) return null
        try {
            val scale = MATCH_WIDTH.toFloat() / screen.width
            val sh = (screen.height * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(screen, MATCH_WIDTH, sh, true)
            val rw = (tpl.width * scale).toInt().coerceAtLeast(1)
            val rh = (tpl.height * scale).toInt().coerceAtLeast(1)
            val smallTpl = Bitmap.createScaledBitmap(tpl, rw, rh, true)

            var best = Int.MAX_VALUE
            var bx = -1
            var by = -1
            val stepX = 3
            val stepY = 3
            for (y in 0..sh - rh step stepY) {
                for (x in 0..MATCH_WIDTH - rw step stepX) {
                    val s = sad(small, smallTpl, x, y)
                    if (s < best) { best = s; bx = x; by = y }
                }
            }
            val pixelCount = rw * rh
            val threshold = pixelCount * matchThreshold
            small.recycle()
            smallTpl.recycle()
            screen.recycle()
            if (best > threshold || bx < 0) return null
            val cx = ((bx + rw / 2) / scale).toInt()
            val cy = ((by + rh / 2) / scale).toInt()
            return Point(cx, cy)
        } catch (e: Exception) {
            Log.e(TAG, "matchTemplate error", e)
            screen.recycle()
            return null
        }
    }

    private fun sad(screen: Bitmap, ref: Bitmap, x: Int, y: Int): Int {
        var sum = 0
        val rw = ref.width
        val rh = ref.height
        for (j in 0 until rh) {
            for (i in 0 until rw) {
                val sp = screen.getPixel(x + i, y + j)
                val rp = ref.getPixel(i, j)
                sum += kotlin.math.abs(Color.red(sp) - Color.red(rp)) +
                        kotlin.math.abs(Color.green(sp) - Color.green(rp)) +
                        kotlin.math.abs(Color.blue(sp) - Color.blue(rp))
            }
        }
        return sum
    }

    // ---------------- 点击 ----------------

    // ---------------- 点击（拟人化节奏，减少机器特征） ----------------

    /**
     * 拟人化点击：坐标加随机抖动，并带轻微滑入手势，而不是每次都落在同一像素。
     * 抖动幅度 / 滑入路点数 由当前拟人化强度档位决定。
     * 说明：这只是让操作更接近真人手感，并非“反检测”——游戏服务端的行为分析仍可能识别自动化。
     */
    private fun dispatchTap(x: Int, y: Int, onDone: (() -> Unit)? = null) {
        if (x <= 0 || y <= 0) { onDone?.invoke(); return }
        val jx = x + Random.nextInt(-humanJitter, humanJitter + 1)
        val jy = y + Random.nextInt(-humanJitter, humanJitter + 1)
        val startX = (jx + Random.nextInt(-humanStartSpread, humanStartSpread + 1)).toFloat()
        val startY = (jy + Random.nextInt(-humanStartSpread, humanStartSpread + 1)).toFloat()
        val path = Path().apply {
            moveTo(startX, startY)
            val mid = Random.nextInt(humanMidMin, humanMidMax + 1)
            for (k in 1..mid) {
                val mx = startX + (jx - startX) * k / (mid + 1) + Random.nextInt(-5, 6)
                val my = startY + (jy - startY) * k / (mid + 1) + Random.nextInt(-5, 6)
                lineTo(mx, my)
            }
            lineTo(jx.toFloat(), jy.toFloat())
        }
        val duration = Random.nextLong(55, 150)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureDescription.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, handler)
    }

    /** 拟人化随机延迟：在基准值上做 ±ratio 抖动，避免固定节奏的机器特征（ratio 随强度档位变化） */
    private fun humanDelay(base: Long): Long {
        val jitter = (base * Random.nextDouble(-humanIntervalRatio, humanIntervalRatio)).toLong()
        return (base + jitter).coerceAtLeast(400)
    }

    /** 拖动地图以刷新视野中的盗匪 */
    private fun dragMap() {
        if (screenWidth == 0 || screenHeight == 0) return
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val dx = (Math.random() * screenWidth * 0.3 - screenWidth * 0.15).toInt()
        val dy = (Math.random() * screenHeight * 0.3 - screenHeight * 0.15).toInt()
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo((cx + dx).toFloat(), (cy + dy).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ---------------- 主循环（模板匹配三步） ----------------

    private fun startLoop() {
        if (running || calibrating) return
        loadConfig()
        if (imageReader == null) {
            updateNotif("无法运行：请重启APP并授权录屏", false)
            return
        }
        if (banditTpl == null || attackTpl == null || marchTpl == null) {
            updateNotif("请先一键校准", false)
            return
        }
        running = true
        prefs.setEnabled(true)
        missCount = 0
        updateNotif("挂机运行中", true)
        setOverlayToggle(true)
        runStep(0)
    }

    private fun stopLoop() {
        running = false
        prefs.setEnabled(false)
        handler.removeCallbacksAndMessages(null)
        updateNotif("已停止", false)
        setOverlayToggle(false)
    }

    private fun runStep(step: Int) {
        if (!running) return
        // 拟人化：按强度档位的概率额外停顿 2~5 秒，模拟真人看屏幕的节奏
        if (Random.nextDouble() < humanPauseProb) {
            handler.postDelayed({ runStep(step) }, Random.nextLong(2000, 5000))
            return
        }
        when (step) {
            0 -> { // 找盗匪
                val p = matchTemplate(banditTpl)
                if (p != null) {
                    missCount = 0
                    dispatchTap(p.x, p.y)
                    updateNotif("已点击盗匪，等待面板")
                    handler.postDelayed({ runStep(1) }, humanDelay(1000))
                } else {
                    missCount++
                    if (missCount >= 3) {
                        updateNotif("未找到盗匪，挪动地图…")
                        dragMap()
                        missCount = 0
                    } else {
                        updateNotif("未找到盗匪，重试")
                    }
                    handler.postDelayed({ runStep(0) }, humanDelay(intervalMs))
                }
            }
            1 -> { // 找讨伐按钮
                val p = matchTemplate(attackTpl)
                if (p != null) {
                    dispatchTap(p.x, p.y)
                    updateNotif("已点击讨伐，等待出征界面")
                    handler.postDelayed({ runStep(2) }, humanDelay(1300))
                } else {
                    updateNotif("未找到讨伐按钮，重试")
                    handler.postDelayed({ runStep(1) }, humanDelay(1000))
                }
            }
            2 -> { // 找出征按钮
                val p = matchTemplate(marchTpl)
                if (p != null) {
                    dispatchTap(p.x, p.y)
                    updateNotif("已出征，等待战斗结束")
                    handler.postDelayed({ runStep(0) }, humanDelay(battleWaitMs))
                } else {
                    updateNotif("未找到出征按钮，重试")
                    handler.postDelayed({ runStep(2) }, humanDelay(1000))
                }
            }
        }
    }

    // ---------------- 三步校准（模板采集） ----------------

    private fun startCalibration() {
        if (calibrating) return
        if (imageReader == null) {
            updateNotif("校准失败：请先授权录屏权限", false)
            return
        }
        calibrating = true
        calibBusy = false
        calibStep = 1
        banditTpl = null
        attackTpl = null
        marchTpl = null

        val tv = TextView(this)
        tv.setBackgroundColor(Color.argb(60, 0, 0, 0))
        tv.setTextColor(Color.WHITE)
        tv.textSize = 20f
        tv.gravity = Gravity.CENTER
        tv.setPadding(24, 24, 24, 24)
        tv.text = "第 1 步：在屏幕上点一下【盗匪】（部队或“盗匪·喽啰”标签）"
        tv.setOnTouchListener { _, event ->
            if (!calibBusy && event.action == MotionEvent.ACTION_DOWN) {
                onCalibTap(event.x.toInt(), event.y.toInt())
                true
            } else false
        }
        calibOverlay = tv

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(tv, params)
        } catch (e: Exception) {
            Log.e(TAG, "calib overlay failed", e)
        }
    }

    private fun onCalibTap(x: Int, y: Int) {
        if (calibBusy) return
        calibBusy = true
        // 先隐藏校准浮层，等待一帧让系统合成（确保截到的是纯净游戏画面），
        // 再截取“点击前”的元素作为模板 —— 这样挂机时在大地图上找的也是未选中状态的盗匪，外观一致。
        calibOverlay?.visibility = View.GONE
        handler.postDelayed({
            when (calibStep) {
                1 -> {
                    captureTemplateFile("bandit", x, y, 60)
                    banditTpl = loadTpl("bandit")
                }
                2 -> {
                    captureTemplateFile("attack", x, y, 50)
                    attackTpl = loadTpl("attack")
                }
                3 -> {
                    captureTemplateFile("march", x, y, 50)
                    marchTpl = loadTpl("march")
                }
            }
            dispatchTap(x, y) {
                when (calibStep) {
                    1 -> {
                        calibStep = 2
                        (calibOverlay as? TextView)?.text =
                            "第 2 步：点一下右侧绿色【讨伐】按钮"
                    }
                    2 -> {
                        calibStep = 3
                        (calibOverlay as? TextView)?.text =
                            "第 3 步：在出征界面点一下【出征】按钮"
                    }
                    3 -> {
                        finishCalibration()
                        return@dispatchTap
                    }
                }
                calibOverlay?.visibility = View.VISIBLE
                calibBusy = false
            }
        }, 150)
    }

    private fun finishCalibration() {
        calibrating = false
        prefs.setCalibrated(true)
        calibOverlay?.let { windowManager?.removeView(it) }
        calibOverlay = null
        updateNotif("校准完成，可一键挂机", false)
        val i = Intent(ACTION_CALIB_DONE)
        i.setPackage(packageName)
        sendBroadcast(i)
    }

    // ---------------- 无障碍事件：关键词点击（原生按钮兜底） ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (calibrating || !running) return
        val root = rootInActiveWindow ?: return
        try {
            clickByKeywords(root)
        } finally {
            root.recycle()
        }
    }

    private fun clickByKeywords(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val queue: MutableList<AccessibilityNodeInfo> = mutableListOf(root)
        var i = 0
        while (i < queue.size) {
            val node = queue[i]
            i++
            try {
                for (c in 0 until node.childCount) {
                    node.getChild(c)?.let { queue.add(it) }
                }
            } catch (e: Exception) { /* node 已失效 */ }

            if (node.isClickable) {
                val text = (node.text ?: node.contentDescription)?.toString() ?: ""
                if (keywords.any { text.contains(it) }) {
                    val key = text + (node.className ?: "")
                    if (key == lastClickKey && now - lastClickTime < 1500) continue
                    lastClickKey = key
                    lastClickTime = now
                    try {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "clicked keyword node: $text")
                    } catch (e: Exception) { /* ignore */ }
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ---------------- 悬浮窗 ----------------

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 10
        params.y = 120

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_window, null)
        overlayView?.findViewById<Button>(R.id.btn_toggle)?.setOnClickListener {
            if (running) stopLoop() else startLoop()
        }
        overlayView?.findViewById<Button>(R.id.btn_exit)?.setOnClickListener {
            stopLoop()
            stopSelf()
        }
        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "add overlay failed", e)
        }
    }

    private fun setOverlayToggle(isRunning: Boolean) {
        overlayView?.findViewById<Button>(R.id.btn_toggle)?.text = if (isRunning) "停止" else "开始"
    }

    // ---------------- 通知 ----------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "打盗匪助手",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, showStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("大秦打盗匪助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
        if (showStop) {
            val stopIntent = Intent(this, BanditAccessibilityService::class.java).apply {
                action = ACTION_STOP
            }
            val pi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "停止", pi
            )
        }
        return builder.build()
    }

    private fun updateNotif(text: String, showStop: Boolean = false) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text, showStop))
        } catch (e: Exception) { /* ignore */ }
    }

    // ---------------- 清理 ----------------

    override fun onDestroy() {
        super.onDestroy()
        running = false
        calibrating = false
        calibBusy = false
        handler.removeCallbacksAndMessages(null)
        overlayView?.let { windowManager?.removeView(it) }
        calibOverlay?.let { windowManager?.removeView(it) }
        banditTpl?.recycle()
        attackTpl?.recycle()
        marchTpl?.recycle()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) { /* ignore */ }
    }
}
