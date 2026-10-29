package com.example.daqinbandithelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * 核心无障碍服务（傻瓜式 + 模板匹配 + 游戏内悬浮控制台）。
 *
 * 操作流程按《大秦帝国之帝国烽烟》实机录像还原：
 *   ① 地图上点【盗匪】→  弹出「放射菜单」（讨伐 / 战斗 / 部队信息）
 *   ② 点放射菜单里的【讨伐】或【战斗】
 *   ③ 右侧滑出部队面板 → 点蓝色【全选】（再点会变成「不选」）
 *   ④ 点橙色【确定】→ 部队出征
 *   ⑤ 等战斗结束 → 回到 ①
 *
 * 关键技术：
 *  - dispatchGesture 模拟真实触摸（对 OpenGL/Unity 游戏有效）。
 *  - MediaProjection 截图 + 模板匹配：自动定位盗匪、讨伐/战斗、全选、确定。
 *  - 放射菜单以盗匪图标为圆心展开（半径 100px@1280 宽），用几何公式推算按钮位置。
 *  - 「全选/确定」按钮用颜色特征实时定位（蓝色=全选，橙色=确定），不依赖固定坐标。
 */
class BanditAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_START_CAPTURE = "start_capture"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_CALIBRATE = "calibrate"
        const val ACTION_CALIB_DONE = "com.example.daqinbandithelper.CALIB_DONE"
        const val ACTION_NEED_CAPTURE = "need_capture"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "daqin_channel"
        private const val TAG = "DaQinHelper"
        private const val MATCH_WIDTH = 360

        // 校准步骤（全部在游戏内点选，共 7 步）
        private const val CAL_BASE = 1      // 固定位置（村庄等，可跳过）
        private const val CAL_BANDIT = 2    // 盗匪图标
        private const val CAL_LEVEL = 3     // 等级数字标签（可跳过）
        private const val CAL_ATTACK = 4    // 放射菜单里的「讨伐」
        private const val CAL_SELECT = 5    // 蓝色「全选」
        private const val CAL_TROOP = 6     // 自选队伍（可跳过）
        private const val CAL_CONFIRM = 7   // 橙色「确定」

        private const val SCAN_ROWS = 4
        private const val SCAN_COLS = 8

        // —— 实机录像（1280x620）实测几何参数 ——
        private const val REF_W = 1280.0
        private const val RADIAL_R = 100.0   // 放射菜单半径
        private const val ANG_ATTACK = -69.0  // 「讨伐」相对盗匪的角度
        private const val ANG_BATTLE = -9.0   // 「战斗」相对盗匪的角度
        private const val BTN_DX = 61.0       // 战斗 - 讨伐 的 x 偏移
        private const val BTN_DY = 78.0       // 战斗 - 讨伐 的 y 偏移

        // 阶段
        private const val ST_FIND = 0      // 找盗匪
        private const val ST_ATTACK = 1    // 点讨伐 / 战斗
        private const val ST_SELECT = 2    // 点全选
        private const val ST_CONFIRM = 3   // 点确定
        private const val ST_WAIT = 4      // 等战斗

        private const val UI_WAIT_MS = 7000L   // 等 UI 出现的超时
        private const val SELECT_TPL_R = 20    // 全选按钮模板半径
        private const val STATE_DIFF_MAX = 4.0 // 全分辨率比对的「同一状态」阈值（每通道平均差）
        private const val WAIT_POLL_MS = 6000L // 部队未就绪时，重新试探的间隔
        private const val WAIT_READY_MAX = 8   // 试探上限（约1分钟），超过按失败换目标

        // 保障层：安全 / 稳定性（对应设计文档第七阶段）
        private const val GUARD_INTERVAL_MS = 3000L        // 守护定时器轮询间隔
        private const val CIRCUIT_LIMIT = 6                // 连续异常上限，超过即熔断停止
        private const val STAGE_HARD_TIMEOUT_MS = 60000L   // 任一阶段硬超时（兜底，战斗等待除外）
        private const val MARKER_SIZE = 120                // 盗匪检测标记的尺寸（像素）
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    private var running = false
    private var calibrating = false
    private var calibBusy = false
    private var calibStep = 0
    private var calibTroops = mutableListOf<Point>()
    private var calibBanditX = 0
    private var calibBanditY = 0
    private var keywords: Set<String> = emptySet()
    private var banditNameKeywords: Set<String> = emptySet()
    private var calibrated = false
    private var intervalMs: Long = 3000
    private var battleWaitMs: Long = 20000
    private var matchThreshold = 55
    private var captureReady = false

    // 拟人化参数（0=轻 1=中 2=强）
    private var humanJitter = 3
    private var humanIntervalRatio = 0.4
    private var humanPauseProb = 0.08

    // 模板
    private var banditTpl: Bitmap? = null
    private var attackTpl: Bitmap? = null
    private var battleTpl: Bitmap? = null
    private var selectTpl: Bitmap? = null      // 「全选」状态
    private var selectOffTpl: Bitmap? = null    // 「不选」状态（校准时自动采集）
    private var confirmTpl: Bitmap? = null
    private var baseTpl: Bitmap? = null
    private var levelTpl: Bitmap? = null

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
    private var overlayParams: WindowManager.LayoutParams? = null
    private var calibParams: WindowManager.LayoutParams? = null
    private var overlayStatus: TextView? = null
    private var overlayTroop: TextView? = null
    private var overlayTroopToggle: Button? = null
    private var overlaySearch: Button? = null
    private var overlayLevel: Button? = null
    private var overlayMode: Button? = null
    private var overlayAct: Button? = null
    private var calibOverlay: View? = null
    private var calibText: TextView? = null
    private var calibBtn: Button? = null
    private var calibSkip: Button? = null
    private var missCount = 0

    // 盗匪检测标记（屏幕上的绿圈十字，便于直观看清锁定位置；不拦截触摸）
    private var markerView: MarkerView? = null
    private var markerParams: WindowManager.LayoutParams? = null
    private var markerHasTarget = false

    // 阶段机
    private var stage = ST_FIND
    private var stageTry = 0
    private var stageDeadline = 0L
    private var lastBandit: Point? = null
    private var selectTries = 0
    private var manualIdx = 0
    private var failPos: Point? = null
    private var failUntil = 0L
    private var noBanditStreak = 0

    // 出征后等待部队恢复：true = 正在轮询试探部队是否全部可选
    private var waitReady = false
    private var waitReadyTries = 0

    // 周边扫描状态：视野中心相对「固定位置」的偏移（像素）
    private var scanDx = 0
    private var scanDy = 0
    private var scanIndex = 0
    private var levelSkipStreak = 0
    private var levelFailOpen = false

    // ---------------- 保障层：安全 / 稳定性 ----------------
    // 对应设计文档「第七阶段」：超时、异常、随机化、日志、熔断。
    private var lastStage = ST_FIND
    private var stageEnteredAt = 0L
    private var errorStreak = 0
    private var recoveryPending = false
    private val botLogFile by lazy { File(filesDir, "bot.log") }

    // 拖动悬浮窗用
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0

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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadConfig()
        // 服务重启后旧的录屏通道已经失效，必须重新授权
        if (imageReader == null) {
            captureReady = false
            prefs.setCaptureReady(false)
        }
        showOverlay()
        initMarkerView()
        updateOverlayStatus("未授权录屏 · 回APP授权")
        updateNotif("助手已就绪：请在APP里点「授权录屏」", false)
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
                if (data != null) prepareCapture(rc, data)
            }
            ACTION_START -> startLoop()
            ACTION_STOP -> stopLoop()
            ACTION_CALIBRATE -> startCalibration()
        }
        return START_STICKY
    }

    private fun loadConfig() {
        // v15 迁移：默认关闭「拖动地图周边搜索」，改为「屏幕内找最近盗匪」
        if (prefs.getAppVer() < 15) {
            prefs.setSearchMode(0)
            prefs.setAppVer(15)
        }
        keywords = prefs.getKeywords()
        banditNameKeywords = prefs.getBanditNameKeywords()
        calibrated = prefs.isCalibrated()
        intervalMs = prefs.getIntervalMs()
        battleWaitMs = prefs.getBattleWaitMs()
        matchThreshold = prefs.getMatchThreshold()
        applyHumanLevel(prefs.getHumanizeLevel())
        banditTpl = loadTpl("bandit")
        attackTpl = loadTpl("attack")
        battleTpl = loadTpl("battle")
        selectTpl = loadTpl("select")
        selectOffTpl = loadTpl("select_off")
        confirmTpl = loadTpl("confirm")
        baseTpl = loadTpl("base")
        levelTpl = loadTpl("level")
    }

    private fun loadTpl(name: String): Bitmap? {
        val f = prefs.tplFile(name)
        return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    /** 根据强度档位设置拟人化参数（0=轻 1=中 2=强） */
    private fun applyHumanLevel(level: Int) {
        when (level) {
            0 -> {
                humanJitter = 2
                humanIntervalRatio = 0.2
                humanPauseProb = 0.03
            }
            2 -> {
                humanJitter = 4
                humanIntervalRatio = 0.7
                humanPauseProb = 0.15
            }
            else -> {
                humanJitter = 3
                humanIntervalRatio = 0.4
                humanPauseProb = 0.08
            }
        }
    }

    // ---------------- 媒体投影 / 截图 ----------------

    /**
     * 拿到录屏授权。
     * 关键：Android 14（targetSdk 34）下，必须先拿到用户的录屏授权，再以前台服务
     * 类型 mediaProjection 进入前台，然后才能调用 getMediaProjection。
     * 如果「服务一连上就带 mediaProjection 类型进前台」，系统会直接抛
     * SecurityException 把服务打死 —— 表现就是悬浮窗按钮点了没反应。
     */
    private fun prepareCapture(resultCode: Int, data: Intent) {
        try {
            startCaptureForeground()
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(mediaProjection) failed", e)
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            toast("录屏授权失败，请重新授权")
            updateNotif("录屏授权失败", false)
            updateOverlayStatus("录屏授权失败")
            return
        }

        try {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    captureReady = false
                    prefs.setCaptureReady(false)
                    stopLoop()
                    toast("录屏已停止，请回APP重新授权")
                    updateOverlayStatus("录屏失效 · 回APP授权")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "registerCallback failed", e)
        }

        val metrics = DisplayMetrics()
        val display = windowManager?.defaultDisplay
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            val dm = resources.displayMetrics
            metrics.widthPixels = dm.widthPixels
            metrics.heightPixels = dm.heightPixels
            metrics.densityDpi = dm.densityDpi
        }
        // 横屏适配：录屏缓冲必须与「真实显示方向」一致，否则截图坐标和 dispatchGesture
        // 的屏幕坐标对不上（典型现象就是「总是点左下角」）。
        // 若当前是横屏（旋转 90/270）却取到 宽<高，则交换，保证 宽>高。
        var capW = metrics.widthPixels
        var capH = metrics.heightPixels
        val rot = display?.rotation ?: Surface.ROTATION_0
        val landscape = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270)
        if (landscape && capW < capH) {
            val t = capW; capW = capH; capH = t
        }
        screenWidth = capW
        screenHeight = capH
        screenDensity = metrics.densityDpi

        Log.i(TAG, "capture sized ${screenWidth}x${screenHeight} rotation=$rot landscape=$landscape density=$screenDensity")

        try {
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DaQinCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            toast("录屏启动失败，请重试")
            return
        }

        captureReady = true
        prefs.setCaptureReady(true)
        updateNotif("录屏已授权：切到游戏用悬浮窗「开始」/「校准」", false)
        updateOverlayStatus("录屏就绪 · 可开始")
        toast("录屏已授权 ✅")
    }

    /** 以 mediaProjection 类型进入前台（必须在用户授权之后调用） */
    private fun startCaptureForeground() {
        val notif = buildNotification("录屏授权中…", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null
        // 以截图真实尺寸为准校正屏幕坐标，保证「检测坐标」与「点击坐标」始终同一空间。
        // （横屏时系统给的 metrics 偶尔是竖屏尺寸，会导致两者偏差 → 点错位置）
        val iw = image.width
        val ih = image.height
        if (iw <= 0 || ih <= 0) { image.close(); return null }
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * iw
        val w = iw + rowPadding / pixelStride
        val full = Bitmap.createBitmap(w, ih, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        image.close()
        if (iw != screenWidth || ih != screenHeight) {
            screenWidth = iw
            screenHeight = ih
        }
        return try {
            Bitmap.createBitmap(full, 0, 0, iw, ih)
        } finally {
            full.recycle()
        }
    }

    /** 截取 (cx,cy) 周围 radius 区域，存为识别模板（截取前隐藏浮层以保证纯净） */
    private fun captureTemplateFile(name: String, cx: Int, cy: Int, radius: Int) {
        overlayView?.visibility = View.GONE
        calibOverlay?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        calibOverlay?.visibility = View.VISIBLE
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

    // ---------------- 模板匹配 ----------------

    /** 全屏匹配模板，返回中心坐标 */
    private fun matchTemplate(tpl: Bitmap?): Point? =
        matchTemplateIn(tpl, 0, screenWidth, 0, screenHeight)

    /** 在指定区域内匹配模板（区域为原图像素坐标），返回中心坐标 */
    private fun matchTemplateIn(
        tpl: Bitmap?,
        rx0: Int,
        rx1: Int,
        ry0: Int,
        ry1: Int
    ): Point? {
        if (tpl == null || screenWidth <= 0) return null
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

            val maxX = (MATCH_WIDTH - rw).coerceAtLeast(0)
            val maxY = (sh - rh).coerceAtLeast(0)
            val sx0 = ((rx0.coerceIn(0, screen.width - 1) * scale).toInt()).coerceIn(0, maxX)
            val sx1 = ((rx1.coerceIn(0, screen.width - 1) * scale).toInt()).coerceIn(sx0, maxX)
            val sy0 = ((ry0.coerceIn(0, screen.height - 1) * scale).toInt()).coerceIn(0, maxY)
            val sy1 = ((ry1.coerceIn(0, screen.height - 1) * scale).toInt()).coerceIn(sy0, maxY)

            val step = if (sx1 - sx0 > 120) 3 else 2
            var best = Int.MAX_VALUE
            var bx = -1
            var by = -1
            var y = sy0
            while (y <= sy1) {
                var x = sx0
                while (x <= sx1) {
                    val s = sad(small, smallTpl, x, y)
                    if (s < best) { best = s; bx = x; by = y }
                    x += step
                }
                y += step
            }
            val sampled = ((rw + 1) / 2) * ((rh + 1) / 2)
            val threshold = sampled * matchThreshold
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

    /** 在 (cx,cy) 附近 radius 像素内匹配模板 */
    private fun matchNear(tpl: Bitmap?, cx: Int, cy: Int, radius: Int): Point? =
        matchTemplateIn(tpl, cx - radius, cx + radius, cy - radius, cy + radius)

    /** 采样 SAD（隔点取样，速度提升约 4 倍） */
    private fun sad(screen: Bitmap, ref: Bitmap, x: Int, y: Int): Int {
        var sum = 0
        val rw = ref.width
        val rh = ref.height
        var j = 0
        while (j < rh) {
            var i = 0
            while (i < rw) {
                val sp = screen.getPixel(x + i, y + j)
                val rp = ref.getPixel(i, j)
                sum += kotlin.math.abs(Color.red(sp) - Color.red(rp)) +
                        kotlin.math.abs(Color.green(sp) - Color.green(rp)) +
                        kotlin.math.abs(Color.blue(sp) - Color.blue(rp))
                i += 2
            }
            j += 2
        }
        return sum
    }

    // ---------------- 颜色定位（按钮实时查找） ----------------

    /**
     * 找到「全选 / 不选」蓝色按钮的中心（蓝色像素的包围盒中心）。
     * 实机录像实测：面板关闭时蓝像素数为 0，打开时约 2000 —— 是个非常干净的二值信号，
     * 比模板匹配更稳，也不受分辨率影响。
     * 用包围盒中心（而不是质心）是为了和校准时截取的模板像素对齐。
     */
    private fun blueCenterIn(screen: Bitmap): Point? {
        val x0 = (screen.width * 0.72).toInt()
        val x1 = (screen.width * 0.95).toInt()
        val y0 = (screen.height * 0.50).toInt()
        val y1 = (screen.height * 0.82).toInt()
        var n = 0
        var minX = Int.MAX_VALUE; var maxX = -1
        var minY = Int.MAX_VALUE; var maxY = -1
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = screen.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (b - r > 60 && b > 140 && g > 90) {
                    n++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (n < 120) return null
        val bw = maxX - minX
        val bh = maxY - minY
        // 按钮应是一块紧凑且填满的矩形，排除误检（河流、蓝色建筑等）
        if (bw > screen.width * 0.22) return null
        if (bh > screen.height * 0.16) return null
        val area = bw * bh
        if (area <= 0) return null
        // n 是隔点采样的数量，实际像素约 4 倍，换算成真实填充率
        if (n.toDouble() * 4.0 / area < 0.45) return null
        return Point((minX + maxX) / 2, (minY + maxY) / 2)
    }

    /** 找到「确定」橙色按钮的中心 */
    private fun orangeCenterIn(screen: Bitmap): Point? {
        val x0 = (screen.width * 0.90).toInt()
        val x1 = screen.width
        val y0 = (screen.height * 0.50).toInt()
        val y1 = (screen.height * 0.82).toInt()
        var n = 0
        var minX = Int.MAX_VALUE; var maxX = -1
        var minY = Int.MAX_VALUE; var maxY = -1
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = screen.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                // 高饱和的金橙色：g-b 和 r-b 都要大，这样部队头像的肤色不会被算进来
                // （实测：面板关闭时命中约 13 像素，打开时约 1400 像素）
                if (r > 190 && g > 150 && (g - b) > 70 && (r - b) > 110) {
                    n++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (n < 120) return null
        val bw = maxX - minX
        val bh = maxY - minY
        if (bw > screen.width * 0.20) return null
        if (bh > screen.height * 0.16) return null
        val area = bw * bh
        if (area <= 0) return null
        if (n.toDouble() * 4.0 / area < 0.45) return null
        return Point((minX + maxX) / 2, (minY + maxY) / 2)
    }

    private fun findBlueButton(): Point? {
        overlayView?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        if (screen == null) return null
        try {
            return blueCenterIn(screen)
        } catch (e: Exception) {
            Log.e(TAG, "findBlueButton error", e)
            return null
        } finally {
            screen.recycle()
        }
    }

    private fun findOrangeButton(): Point? {
        overlayView?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        if (screen == null) return null
        try {
            return orangeCenterIn(screen)
        } catch (e: Exception) {
            Log.e(TAG, "findOrangeButton error", e)
            return null
        } finally {
            screen.recycle()
        }
    }

    private data class BanditCand(val x: Int, val y: Int, val score: Int)

    /**
     * 自动找盗匪（无模板也能用）：在地图主区域扫描「红褐色密集小团」，
     * 把所有局部密集点都当成候选盗匪，再交给 pickNearestBandit 选出离部队最近的那个。
     * 这样符合「搜索最近盗匪」的要求，而不是只抓最红的一坨（可能很远）。
     */
    private fun findBanditCandidates(): List<BanditCand> {
        overlayView?.visibility = View.GONE
        val screen = captureBitmap() ?: return emptyList()
        val cands = mutableListOf<BanditCand>()
        try {
            val x0 = (screen.width * 0.04).toInt()
            val x1 = (screen.width * 0.96).toInt()
            val y0 = (screen.height * 0.12).toInt()
            // 排除底部约 16% 的操作栏 / 状态栏区域：那里常有常驻的红褐色 UI，
            // 会被误当成盗匪，导致「总是点左下角」之类的固定误点。
            val y1 = (screen.height * 0.84).toInt()
            val cell = 16
            val cols = ((x1 - x0) / cell).coerceAtLeast(1)
            val rows = ((y1 - y0) / cell).coerceAtLeast(1)
            val grid = Array(rows) { IntArray(cols) }
            var y = y0
            while (y < y1) {
                val ri = ((y - y0) / cell).coerceIn(0, rows - 1)
                var x = x0
                while (x < x1) {
                    val ci = ((x - x0) / cell).coerceIn(0, cols - 1)
                    val c = screen.getPixel(x, y)
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    // 红褐色盗匪标记：红明显强于蓝、红略强于绿，且整体足够亮（排除暗红砖块/地形）。
                    // 比旧版更严格，减少地图红褐色背景造成的误检。
                    if (r > 140 && (r - b) > 55 && (r - g) > 12 && r > g + 10) grid[ri][ci]++
                    x += 3
                }
                y += 3
            }
            // 取局部最大值作为候选（同一盗匪团可能被切成相邻几格，下面再合并）
            val raw = mutableListOf<BanditCand>()
            for (i in 0 until rows) {
                for (j in 0 until cols) {
                    val s = grid[i][j]
                    if (s < 10) continue
                    var local = true
                    for (di in -1..1) {
                        for (dj in -1..1) {
                            val ni = i + di; val nj = j + dj
                            if (ni in 0 until rows && nj in 0 until cols && grid[ni][nj] > s) local = false
                        }
                    }
                    if (local) raw.add(BanditCand(x0 + j * cell + cell / 2, y0 + i * cell + cell / 2, s))
                }
            }
            // 合并距离很近的候选，避免同一盗匪出多个点
            val merged = mutableListOf<BanditCand>()
            for (c in raw.sortedByDescending { it.score }) {
                if (merged.any { Math.abs(it.x - c.x) < cell * 2 && Math.abs(it.y - c.y) < cell * 2 }) continue
                merged.add(c)
            }
            cands.addAll(merged)
        } catch (e: Exception) {
            Log.e(TAG, "findBanditCandidates err", e)
        } finally {
            overlayView?.visibility = View.VISIBLE
            screen.recycle()
        }
        return cands
    }

    /** 从候选里挑离参考点 (refX,refY) 最近的盗匪（参考点默认屏幕中心=部队位置） */
    private fun pickNearestBandit(cands: List<BanditCand>, refX: Int, refY: Int): BanditCand? {
        if (cands.isEmpty()) return null
        var best: BanditCand? = null
        var bestD = Int.MAX_VALUE
        for (c in cands) {
            val d = (c.x - refX) * (c.x - refX) + (c.y - refY) * (c.y - refY)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    /** 搜索中心：可在 APP 里修改的「固定点」（屏幕宽/高比例，默认屏幕中线） */
    private fun centerX(): Int =
        if (screenWidth > 0) (screenWidth * prefs.getSearchCenterXRatio()).toInt() else 0
    private fun centerY(): Int =
        if (screenHeight > 0) (screenHeight * prefs.getSearchCenterYRatio()).toInt() else 0

    /** 屏幕中心周围搜索半径（像素），只在「全屏搜索」关闭时生效，对应游戏内约 1000 里 */
    private fun centerRadiusPx(): Int {
        val r = prefs.getCenterRadiusRatio().coerceIn(0.1f, 0.95f)
        return (Math.min(screenWidth, screenHeight) * r).toInt().coerceAtLeast(150)
    }

    /** 仅保留 (cx,cy) 周围 radius 像素内的候选；全屏搜索时直接返回全部 */
    private fun filterWithin(cands: List<BanditCand>, cx: Int, cy: Int, radius: Int): List<BanditCand> {
        if (prefs.isFullScreenSearch()) return cands
        val r2 = radius.toLong() * radius
        return cands.filter { (it.x - cx).toLong() * (it.x - cx) + (it.y - cy).toLong() * (it.y - cy) <= r2 }
    }

    /**
     * 通过无障碍节点文字识别盗匪：遍历当前窗口所有节点，
     * 文字（或 contentDescription）含「盗匪喽啰 / 盗匪精英 / 盗匪」等关键词的，取其屏幕中心坐标。
     * 这是游戏里盗匪头顶的名字标签，比颜色扫描稳定得多，且天然免模板。
     */
    private fun findBanditsByText(): List<BanditCand> {
        val root = rootInActiveWindow ?: return emptyList()
        val found = mutableListOf<AccessibilityNodeInfo>()
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeFirst()
                val txt = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val s = "$txt $desc"
                if (banditNameKeywords.any { kw -> s.contains(kw) }) {
                    found.add(node)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findBanditsByText traverse err", e)
        }
        val cands = mutableListOf<BanditCand>()
        for (n in found) {
            val rect = Rect()
            try { n.getBoundsInScreen(rect) } catch (_: Exception) { continue }
            if (rect.isEmpty) continue
            // 文字节点是「盗匪头顶名字标签」；可点击的盗匪实体在名字标签下方。
            // 向下偏移 = 名字标签高度 * 1.5 + 屏高 * 偏移比例，让点击点落到实体而非名字上。
            val bx = rect.centerX()
            val off = (rect.height() * 1.5 + screenHeight * prefs.getNameBodyOffsetRatio()).toInt()
            val by = (rect.bottom + off).coerceIn(2, (screenHeight - 2).coerceAtLeast(3))
            cands.add(BanditCand(bx, by, 9999))
            n.recycle()
        }
        return cands
    }

    /**
     * 实时重新定位最近盗匪：重新扫描屏幕、范围过滤、取离中心最近者。
     * 用于「点击前 / 进攻击前」刷新坐标，使点击点跟随盗匪实时移动。
     * 返回已是「实体坐标」（findBanditsByText 已向下偏移到实体）。
     */
    private fun reacquireBandit(): Point? {
        val all = findBanditCandidatesAll()
        val cands = filterWithin(all.first, centerX(), centerY(), centerRadiusPx())
        val np = pickNearestBandit(cands, centerX(), centerY())
        return np?.let { Point(it.x, it.y) }
    }

    /**
     * 综合找盗匪：优先用文字识别（最稳），识别不到再退回颜色扫描。
     * 返回经「屏幕中心半径」过滤后的候选，以及识别来源（"文字"/"颜色"）用于诊断。
     */
    /** 当前搜索模式标签（用于悬浮窗诊断） */
    private fun searchModeLabel(): String =
        if (prefs.isFullScreenSearch()) "全屏" else "范围"

    private fun findBanditCandidatesAll(): Pair<List<BanditCand>, String> {
        val byText = findBanditsByText()
        if (byText.isNotEmpty()) {
            updateOverlayStatus("文字识别盗匪 ${byText.size} 个 · ${searchModeLabel()}")
            return Pair(byText, "文字")
        }
        val byColor = findBanditCandidates()
        if (byColor.isNotEmpty()) {
            updateOverlayStatus("颜色识别盗匪 ${byColor.size} 个 · ${searchModeLabel()}")
        }
        return Pair(byColor, "颜色")
    }

    /** 兼容旧调用：返回屏幕上离中心最近的盗匪点（自动校准时也能用） */
    private fun scanBanditByColor(): Point? {
        val cands = findBanditCandidates()
        val best = pickNearestBandit(cands, screenWidth / 2, screenHeight / 2)
        return best?.let { Point(it.x, it.y) }
    }

    /**
     * 按颜色定位按钮中心，截取模板存盘。
     * 校准时用，能保证模板和运行时的比对位置像素级对齐。
     */
    private fun captureTemplateByColor(name: String, blue: Boolean, radius: Int) {
        overlayView?.visibility = View.GONE
        calibOverlay?.visibility = View.GONE
        val screen = captureBitmap()
        overlayView?.visibility = View.VISIBLE
        calibOverlay?.visibility = View.VISIBLE
        if (screen == null) return
        try {
            val c = if (blue) blueCenterIn(screen) else orangeCenterIn(screen)
            if (c == null) {
                Log.w(TAG, "captureTemplateByColor: 未找到按钮 $name")
                return
            }
            val patch = cropAround(screen, c.x, c.y, radius)
            prefs.tplFile(name).outputStream().use {
                patch.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            patch.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "captureTemplateByColor error", e)
        } finally {
            screen.recycle()
        }
    }

    private fun cropAround(screen: Bitmap, cx: Int, cy: Int, radius: Int): Bitmap {
        val x0 = (cx - radius).coerceIn(0, screen.width - 1)
        val y0 = (cy - radius).coerceIn(0, screen.height - 1)
        val w = (radius * 2).coerceAtMost(screen.width - x0)
        val h = (radius * 2).coerceAtMost(screen.height - y0)
        if (w <= 0 || h <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return Bitmap.createBitmap(screen, x0, y0, w, h)
    }

    /**
     * 全分辨率比对：返回屏幕 (cx,cy) 处与模板的平均每通道差异（0~255）。
     *
     * 「全选 / 不选」只差一个字，小图模板匹配（缩到 360 宽）分辨不出来；
     * 实测同状态差异约 0.02/像素、异状态约 11/像素，全分辨率下区分度极高。
     */
    private fun diffAt(tpl: Bitmap, cx: Int, cy: Int): Double {
        val screen = captureBitmap() ?: return 999.0
        try {
            val w = tpl.width
            val h = tpl.height
            val x0 = cx - w / 2
            val y0 = cy - h / 2
            if (x0 < 0 || y0 < 0 || x0 + w > screen.width || y0 + h > screen.height) return 999.0
            var sum = 0L
            var n = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = screen.getPixel(x0 + x, y0 + y)
                    val b = tpl.getPixel(x, y)
                    sum += (abs(Color.red(a) - Color.red(b)) +
                            abs(Color.green(a) - Color.green(b)) +
                            abs(Color.blue(a) - Color.blue(b))).toLong()
                    n += 3
                }
            }
            return if (n == 0) 999.0 else sum.toDouble() / n
        } catch (e: Exception) {
            return 999.0
        } finally {
            screen.recycle()
        }
    }

    // ---------------- 点击 ----------------

    /**
     * 真正的点击。
     *
     * 关键修正：旧版会先从旁边「滑入」目标点，路径上还带随机抖动，游戏会把它当成
     * 拖动/滑动而不是点击，结果就是「点了没反应」或者地图被拖跑。
     * 现在 Path 只含一个点（按下与抬起在同一像素），时长 38~70ms，等同真人轻点。
     * 拟人化只体现在「点击前的停顿」和 ±2~4px 的落点上。
     */
    private fun dispatchTap(x: Int, y: Int, onDone: (() -> Unit)? = null) {
        if (x <= 0 || y <= 0 || screenWidth <= 0 || screenHeight <= 0 ||
            x >= screenWidth || y >= screenHeight
        ) {
            onDone?.invoke()
            return
        }
        val jx = (x + Random.nextInt(-humanJitter, humanJitter + 1))
            .coerceIn(2, screenWidth - 3)
        val jy = (y + Random.nextInt(-humanJitter, humanJitter + 1))
            .coerceIn(2, screenHeight - 3)
        // 只有起点、没有中间点 —— 保证手指没有位移，不会被判成滑动
        val path = Path().apply { moveTo(jx.toFloat(), jy.toFloat()) }
        val duration = Random.nextLong(38, 70)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        // 目标点若被悬浮窗压住，注入的手势会被浮窗吃掉 —— 先让它不拦截触摸
        val covered = isCoveredByOverlay(jx, jy)
        if (covered) setTouchable(overlayView, overlayParams, false)
        dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (covered) setTouchable(overlayView, overlayParams, true)
                onDone?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (covered) setTouchable(overlayView, overlayParams, true)
                onDone?.invoke()
            }
        }, handler)
    }

    /** 目标点是否落在悬浮窗范围内 */
    private fun isCoveredByOverlay(x: Int, y: Int): Boolean {
        val v = overlayView ?: return false
        try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val l = loc[0]
            val t = loc[1]
            return x >= l && x <= l + v.width && y >= t && y <= t + v.height
        } catch (e: Exception) {
            return false
        }
    }

    /** 动态切换窗口是否拦截触摸（FLAG_NOT_TOUCHABLE） */
    private fun setTouchable(view: View?, params: WindowManager.LayoutParams?, touchable: Boolean) {
        if (view == null || params == null) return
        try {
            params.flags = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "updateViewLayout flags failed", e)
        }
    }

    /** 拟人化随机延迟 */
    private fun humanDelay(base: Long): Long {
        val jitter = (base * Random.nextDouble(-humanIntervalRatio, humanIntervalRatio)).toLong()
        return (base + jitter).coerceAtLeast(400)
    }

    /** 步骤之间的拟人化间隔：1~2 秒不等（用户要求每步之间停顿） */
    private fun stepDelay(): Long = Random.nextLong(1000, 2000)

    // ===================== 保障层：安全 / 稳定性 =====================
    // 按设计文档第七阶段实现：状态机守护、熔断、操作日志、弹窗处理、调试截图。

    /** 操作日志：追加到 filesDir/bot.log，并同步输出到 logcat（便于事后回溯卡点） */
    private fun logOp(msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [${stageName(stage)}] $msg\n"
        try { botLogFile.appendText(line) } catch (e: Exception) { }
        Log.i(TAG, line.trimEnd())
    }

    private fun stageName(s: Int): String = when (s) {
        ST_FIND -> "找盗匪"
        ST_ATTACK -> "点讨伐"
        ST_SELECT -> "点全选"
        ST_CONFIRM -> "点确定"
        ST_WAIT -> "战斗等待"
        else -> "未知($s)"
    }

    /** 调试截图：把当前屏幕存到 filesDir/debug/，便于事后分析「停在哪个界面」 */
    private fun saveDebugShot(tag: String) {
        try {
            overlayView?.visibility = View.GONE
            markerView?.visibility = View.GONE
            val b = captureBitmap()
            overlayView?.visibility = View.VISIBLE
            if (markerHasTarget) markerView?.visibility = View.VISIBLE
            if (b == null) return
            val dir = File(filesDir, "debug"); dir.mkdirs()
            val f = File(dir, "dbg_${System.currentTimeMillis()}_$tag.png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
            b.recycle()
            logOp("DEBUG SHOT -> ${f.name}")
        } catch (e: Exception) { Log.e(TAG, "saveDebugShot err", e) }
    }

    /**
     * 全局弹窗优先处理：任何状态下先尝试关闭挡住操作的弹窗（活动公告 / 网络提示等）。
     * 仅点击明确是「关闭」语义的安全按钮（关闭 / ✕ / × / X），
     * 绝不点「确定 / 确认」等可能触发动作的按钮，避免误操作。
     */
    private fun dismissBlockingPopups(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val closeWords = setOf("关闭", "✕", "×", "X", "x")
            val targets = mutableListOf<AccessibilityNodeInfo>()
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
            while (stack.isNotEmpty()) {
                val node = stack.removeFirst()
                if (node.isClickable) {
                    val t = node.text?.toString()?.trim() ?: ""
                    if (t in closeWords) targets.add(node)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
            }
            if (targets.isEmpty()) return false
            // 取最靠上的可点关闭按钮（通常是弹窗自身的关闭键）
            val best = targets.maxByOrNull { n ->
                val rr = Rect(); n.getBoundsInScreen(rr); rr.top
            } ?: return false
            val ok = best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            logOp("dismiss popup (${best.text}) -> $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "dismissBlockingPopups err", e); false
        }
    }

    /** 阶段超时后回到「找盗匪」重新来过（去重，避免重复排程） */
    private fun backToSafe() {
        if (recoveryPending) return
        recoveryPending = true
        stage = ST_FIND
        lastBandit = null
        stageTry = 0
        stageDeadline = 0L
        stageEnteredAt = System.currentTimeMillis()
        hideBanditMarker()
        handler.postDelayed({
            recoveryPending = false
            if (running) stageFind()
        }, 800)
    }

    /** 守护定时器：弹窗处理 → 状态切换日志 → 阶段超时恢复 → 连续异常熔断 */
    private val guardTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                dismissBlockingPopups()
                if (stage != lastStage) {
                    logOp("STATE ${stageName(lastStage)} -> ${stageName(stage)}")
                    lastStage = stage
                    stageEnteredAt = System.currentTimeMillis()
                    if (stage == ST_WAIT) errorStreak = 0   // 进入战斗 = 有进展，清零连续异常
                }
                val now = System.currentTimeMillis()
                val dead = stageDeadline > 0 && now > stageDeadline
                val hard = now - stageEnteredAt > STAGE_HARD_TIMEOUT_MS
                if ((dead || hard) && stage != ST_WAIT) {
                    errorStreak++
                    logOp("STAGE TIMEOUT ${stageName(stage)} (dead=$dead hard=$hard) streak=$errorStreak")
                    saveDebugShot("to_${stageName(stage)}")
                    backToSafe()
                    if (errorStreak >= CIRCUIT_LIMIT) {
                        logOp("CIRCUIT BREAKER 连续异常过多，已停止")
                        saveDebugShot("circuit")
                        toast("连续异常过多，已自动停止（看 bot.log / debug/）")
                        updateNotif("熔断：连续异常，已停止", false)
                        stopLoop()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "guardTick err", e)
            }
            handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    private fun startGuard() {
        lastStage = stage
        stageEnteredAt = System.currentTimeMillis()
        errorStreak = 0
        recoveryPending = false
        handler.removeCallbacks(guardTick)
        handler.postDelayed(guardTick, GUARD_INTERVAL_MS)
        logOp("GUARD START")
    }

    private fun stopGuard() {
        handler.removeCallbacks(guardTick)
        logOp("GUARD STOP")
    }

    /** 精确拖动地图（手指位移 fx,fy）。慢速拖动，减少游戏地图的惯性滑动误差 */
    private fun dragBy(fx: Int, fy: Int) {
        if (screenWidth == 0 || screenHeight == 0) return
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo((cx + fx).toFloat(), (cy + fy).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 900)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 随机拖动地图（全图模式下刷新视野） */
    private fun dragMap() {
        if (screenWidth == 0 || screenHeight == 0) return
        val cx = screenWidth / 2
        val cy = screenHeight / 2
        val dx = (Math.random() * screenWidth * 0.3 - screenWidth * 0.15).toInt()
        val dy = (Math.random() * screenHeight * 0.3 - screenHeight * 0.15).toInt()
        dragBy(dx, dy)
    }

    // ---------------- 主循环：盗匪 → 讨伐 → 全选 → 确定 → 等战斗 ----------------

    /**
     * 首次运行自动校准：点「开始」后程序自动完成——自动扫描地图找到盗匪 → 截取盗匪模板
     * → 标记已校准，之后挂机循环会自动补采「全选/不选/确定」模板。
     *
     * 关键修正：旧版只扫描一次，而 MediaProjection 的虚拟屏需要一点时间才出第一帧，
     * 首次 acquireLatestImage() 常常返回 null → 直接 return → 表现就是「点开始完全不动」。
     * 现在改成重试多次（让截图帧到位），仍找不到再弹出全屏提示让用户「点一下盗匪」，
     * 不会 silently 失败。
     */
    private var autoTries = 0
    private val AUTO_MAX = 6
    private var pickMode = false
    private var pickOverlay: View? = null
    private var pickParams: WindowManager.LayoutParams? = null

    private fun autoCalibrate() {
        if (running || calibrating) return
        running = true
        prefs.setEnabled(true)
        missCount = 0
        noBanditStreak = 0
        levelSkipStreak = 0
        levelFailOpen = false
        scanIndex = 0
        scanDx = 0
        scanDy = 0
        failPos = null
        failUntil = 0L
        waitReady = false
        waitReadyTries = 0
        autoTries = 0
        updateNotif("自动识别盗匪中…", true)
        updateOverlayStatus("自动识别中…(第1次)")
        handler.postDelayed({ autoCalibrateStep() }, 900)
    }

    /** 自动识别盗匪：多次重试，并给出「截图是否就绪」的诊断 */
    private fun autoCalibrateStep() {
        if (!running) return
        autoTries++
        // 先探一下截图是否就绪（虚拟屏首帧常延迟）
        val probe = captureBitmap()
        probe?.recycle()
        val shotOk = probe != null
        // 优先用无障碍文字识别盗匪（最稳，免模板）
        val txt = findBanditsByText()
        if (txt.isNotEmpty()) {
            finishAutoCalibrate(Point(txt[0].x, txt[0].y))
            return
        }
        val p = scanBanditByColor()
        if (p != null) {
            finishAutoCalibrate(p)
            return
        }
        if (autoTries < AUTO_MAX) {
            val diag = if (shotOk) "没找到盗匪" else "截图未就绪"
            updateOverlayStatus("自动识别中…(第${autoTries}次·$diag)")
            handler.postDelayed({ autoCalibrateStep() }, 1300)
            return
        }
        // 自动识别失败：请用户手动点一下盗匪
        updateNotif("自动没找到盗匪，请点一下地图盗匪", false)
        updateOverlayStatus("自动未找到 · 请点一下盗匪")
        enterPickBanditMode()
    }

    /** 自动识别成功：采盗匪模板并进入挂机循环 */
    private fun finishAutoCalibrate(p: Point) {
        captureTemplateFile("bandit", p.x, p.y, 55)
        banditTpl = loadTpl("bandit")
        if (!prefs.hasBasePos()) {
            prefs.setBaseX(screenWidth / 2)
            prefs.setBaseY(screenHeight / 2)
            captureTemplateFile("base", screenWidth / 2, screenHeight / 2, 60)
            baseTpl = loadTpl("base")
        }
        prefs.setCalibrated(true)
        calibrated = true
        updateNotif("自动识别完成，开始挂机", true)
        setOverlayToggle(true)
        updateOverlayStatus("自动识别✅ · 找盗匪…")
        stage = ST_FIND
        handler.postDelayed({ stageFind() }, 700)
    }

    /** 自动识别失败时，弹全屏提示让用户点一下盗匪的位置，从该点采模板继续 */
    private fun enterPickBanditMode() {
        pickMode = true
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.argb(140, 0, 0, 0))
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.setPadding(48, 90, 48, 48)
        tv.text = "自动没找到盗匪。\n\n" +
                "请直接点一下地图上的任意一个【盗匪】（红褐色小兵那一队），\n" +
                "APP 会记住它的样子并继续自动挂机。\n\n" +
                "（点哪里都行，我们取你点的位置来识别）"
        root.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                onPickBandit(event.x.toInt(), event.y.toInt())
                true
            } else false
        }
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
            windowManager?.addView(root, params)
            pickOverlay = root
            pickParams = params
        } catch (e: Exception) {
            Log.e(TAG, "pick overlay failed", e)
            // 连拾取浮层都建不了，退回重试自动识别
            autoTries = 0
            handler.postDelayed({ autoCalibrateStep() }, 1500)
        }
    }

    /** 用户点了一下盗匪：从该点截取模板并进入挂机循环 */
    private fun onPickBandit(x: Int, y: Int) {
        pickMode = false
        removePickOverlay()
        updateOverlayStatus("已选盗匪 · 识别中…")
        // 等浮层消失后再截图，保证模板干净（不混入半透明蒙层）
        handler.postDelayed({
            if (!running) return@postDelayed
            val p = Point(x, y)
            captureTemplateFile("bandit", p.x, p.y, 55)
            banditTpl = loadTpl("bandit")
            if (!prefs.hasBasePos()) {
                prefs.setBaseX(screenWidth / 2)
                prefs.setBaseY(screenHeight / 2)
                captureTemplateFile("base", screenWidth / 2, screenHeight / 2, 60)
                baseTpl = loadTpl("base")
            }
            prefs.setCalibrated(true)
            calibrated = true
            setOverlayToggle(true)
            updateNotif("已识别盗匪，开始挂机", true)
            updateOverlayStatus("已识别✅ · 找盗匪…")
            stage = ST_FIND
            handler.postDelayed({ stageFind() }, 700)
        }, 250)
    }

    private fun removePickOverlay() {
        try {
            pickOverlay?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        pickOverlay = null
        pickParams = null
    }

    /** 通知 APP 重新申请录屏授权（授权过期但 APP 以为还有效时） */
    private fun sendNeedCapture() {
        val i = Intent(ACTION_NEED_CAPTURE)
        i.setPackage(packageName)
        try {
            sendBroadcast(i)
        } catch (e: Exception) {
            Log.e(TAG, "sendNeedCapture failed", e)
        }
    }

    private fun startLoop() {
        if (calibrating) {
            toast("正在校准中，请先完成校准")
            return
        }
        if (running) {
            toast("已经在运行了")
            return
        }
        loadConfig()
        if (!captureReady || imageReader == null) {
            toast("录屏未就绪：请回APP重新授权")
            updateNotif("录屏未就绪，请回APP重新授权", false)
            updateOverlayStatus("录屏未就绪 · 请求授权")
            sendNeedCapture()
            return
        }
        if (!calibrated) {
            // 首次运行：自动识别盗匪（文字/颜色/手动点选），之后循环复用
            toast("首次运行：自动识别盗匪中…")
            updateOverlayStatus("自动识别中…")
            autoCalibrate()
            return
        }
        running = true
        prefs.setEnabled(true)
        missCount = 0
        noBanditStreak = 0
        levelSkipStreak = 0
        levelFailOpen = false
        scanIndex = 0
        scanDx = 0
        scanDy = 0
        failPos = null
        failUntil = 0L
        waitReady = false
        waitReadyTries = 0
        initScanOrigin()
        updateNotif("挂机运行中", true)
        setOverlayToggle(true)
        updateOverlayStatus("运行中 · 找盗匪…")
        stage = ST_FIND
        logOp("START 挂机开始")
        startGuard()
        handler.postDelayed({ stageFind() }, 400)
    }

    private fun stopLoop() {
        running = false
        pickMode = false
        prefs.setEnabled(false)
        handler.removeCallbacksAndMessages(null)
        stopGuard()
        removePickOverlay()
        hideBanditMarker()
        updateNotif("已停止", false)
        setOverlayToggle(false)
        updateOverlayStatus("已停止 · 点「开始」继续")
        logOp("STOP 已停止")
    }

    /** 是否处于活动时段内（盗匪是限时活动） */
    private fun inActivityWindow(): Boolean {
        if (!prefs.isActivityOn()) return true
        val s = prefs.getActivityStartMin()
        val e = prefs.getActivityEndMin()
        if (s < 0 || e < 0) return true
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (s <= e) (now >= s && now < e) else (now >= s || now < e)
    }

    /** 该盗匪刚刚失败过，暂时不再尝试 */
    private fun isBlacklisted(p: Point): Boolean {
        val fp = failPos ?: return false
        if (System.currentTimeMillis() > failUntil) return false
        return abs(fp.x - p.x) < 60 && abs(fp.y - p.y) < 60
    }

    // —— 阶段 1：找盗匪 ——

    private fun stageFind() {
        if (!running) return
        if (!inActivityWindow()) {
            updateOverlayStatus("非活动时段 · 待命中")
            updateNotif("不在活动时段，等待中")
            handler.postDelayed({ stageFind() }, 30000)
            return
        }
        if (Random.nextDouble() < humanPauseProb) {
            handler.postDelayed({ stageFind() }, Random.nextLong(1500, 4000))
            return
        }
        val (candsRaw, src) = findBanditCandidatesAll()
        val cands = filterWithin(candsRaw, centerX(), centerY(), centerRadiusPx())
        val np = pickNearestBandit(cands, centerX(), centerY())
        val p = np?.let { Point(it.x, it.y) }
        updateOverlayStatus("运行中 · 找盗匪…($src 候选${cands.size} · ${searchModeLabel()} · 固定点${centerX()},${centerY()})")
        if (p != null && !isBlacklisted(p)) {
            if (!levelAccept(p)) {
                updateNotif("盗匪等级不符，继续找")
                updateOverlayStatus("等级不符 · 继续找")
                hideBanditMarker()
                handler.postDelayed({ stageFind() }, humanDelay(700))
                return
            }
            missCount = 0
            noBanditStreak = 0
            lastBandit = p
            showBanditMarker(p.x, p.y, "锁定 候选${cands.size}·$src")
            updateNotif("已锁定盗匪，点击前重新定位")
            updateOverlayStatus("已锁定盗匪 · 准备点击(跟随移动)")
            // 点击前再实时重新定位一次：盗匪在 1~2s 停顿期间会移动，必须跟随最新位置
            handler.postDelayed({
                if (!running) return@postDelayed
                val live = reacquireBandit() ?: p
                lastBandit = live
                showBanditMarker(live.x, live.y, "点击盗匪")
                updateOverlayStatus("点击盗匪 @(${live.x},${live.y})")
                dispatchTap(live.x, live.y) {
                    hideBanditMarker()
                    stage = ST_ATTACK
                    stageTry = 0
                    stageDeadline = System.currentTimeMillis() + UI_WAIT_MS
                    handler.postDelayed({ stageAttack() }, stepDelay())
                }
            }, stepDelay())
            return
        }
        hideBanditMarker()
        missCount++
        noBanditStreak++
        if (missCount >= 3) {
            missCount = 0
            if (useAreaSearch()) {
                advanceScan()
            } else {
                updateNotif("未找到盗匪，挪动地图…")
                dragMap()
                handler.postDelayed({ stageFind() }, humanDelay(intervalMs))
            }
            return
        }
        // 长时间找不到（活动可能还没刷）→ 放慢节奏等待刷新
        if (noBanditStreak >= 3) {
            val waitSec = prefs.getNoBanditWaitSec().coerceIn(5, 600)
            updateOverlayStatus("等盗匪刷新 ${waitSec}s")
            updateNotif("没找到盗匪，等待刷新")
            handler.postDelayed({ stageFind() }, waitSec * 1000L)
            return
        }
        handler.postDelayed({ stageFind() }, humanDelay(intervalMs))
    }

    // —— 阶段 2：点放射菜单里的「讨伐 / 战斗」 ——

    /**
     * 放射菜单是以盗匪图标为圆心展开的（实测：半径 100px@1280 宽，
     * 讨伐在 -69°、战斗在 -9°），所以按钮位置必须相对盗匪算，不能用固定坐标。
     */
    private fun radialPos(bandit: Point, angleDeg: Double): Point {
        val k = if (screenWidth > 0) screenWidth / REF_W else 1.0
        val r = RADIAL_R * k
        val rad = Math.toRadians(angleDeg)
        val x = bandit.x + (r * Math.cos(rad)).toInt()
        val y = bandit.y + (r * Math.sin(rad)).toInt()
        return Point(
            x.coerceIn(2, (screenWidth - 2).coerceAtLeast(3)),
            y.coerceIn(2, (screenHeight - 2).coerceAtLeast(3))
        )
    }

    private data class BtnCand(val x: Int, val y: Int, val score: Int)

    /**
     * 识别盗匪点开后弹出的「放射菜单」按钮：在盗匪周围一圈环形区域里找
     * 高饱和、明亮的紧凑色块（菜单按钮通常是鲜艳的小图标，背景是暗色圆盘，不会命中）。
     * 返回按显著程度排序的候选点；stageAttack 会逐个尝试，并用「全选面板是否出现」来校验。
     */
    private fun detectMenuButtons(bandit: Point): List<BtnCand> {
        overlayView?.visibility = View.GONE
        val screen = captureBitmap() ?: return emptyList()
        val list = mutableListOf<BtnCand>()
        try {
            val k = if (screenWidth > 0) screenWidth / REF_W else 1.0
            val rMin = (RADIAL_R * 0.45 * k).toInt().coerceAtLeast(28)
            val rMax = (RADIAL_R * 2.0 * k).toInt().coerceAtLeast(rMin + 20)
            val cx = bandit.x; val cy = bandit.y
            val pts = mutableListOf<Point>()
            var y = cy - rMax
            while (y <= cy + rMax) {
                var x = cx - rMax
                while (x <= cx + rMax) {
                    val dxp = x - cx; val dyp = y - cy
                    val dist = Math.sqrt((dxp * dxp + dyp * dyp).toDouble())
                    if (dist >= rMin && dist <= rMax) {
                        val c = screen.getPixel(x, y)
                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                        val maxc = Math.max(r, Math.max(g, b))
                        val minc = Math.min(r, Math.min(g, b))
                        val vivid = maxc - minc
                        // 鲜艳的按钮图标：亮度够高且饱和度够高
                        if (maxc > 150 && vivid > 70) pts.add(Point(x, y))
                    }
                    x += 4
                }
                y += 4
            }
            // 把零散命中聚成团块
            val used = BooleanArray(pts.size)
            for (i in pts.indices) {
                if (used[i]) continue
                var sx = 0; var sy = 0; var n = 0
                for (j in pts.indices) {
                    if (used[j]) continue
                    if (Math.abs(pts[i].x - pts[j].x) <= 14 && Math.abs(pts[i].y - pts[j].y) <= 14) {
                        sx += pts[j].x; sy += pts[j].y; n++; used[j] = true
                    }
                }
                if (n >= 3) list.add(BtnCand(sx / n, sy / n, n))
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectMenuButtons err", e)
        } finally {
            overlayView?.visibility = View.VISIBLE
            screen.recycle()
        }
        return list.sortedByDescending { it.score }
    }

    private fun stageAttack() {
        if (!running) return
        // 进入攻击/菜单识别阶段，隐藏绿圈标记（避免被录屏截到、干扰按钮颜色识别）
        hideBanditMarker()
        // 盗匪可能移动了，进入攻击阶段先重新定位一次，菜单扫描中心跟随最新位置
        val live = reacquireBandit()
        if (live != null) lastBandit = live
        val bandit = lastBandit
        if (bandit == null) {
            stage = ST_FIND
            handler.postDelayed({ stageFind() }, 500)
            return
        }
        val wantBattle = prefs.getAttackMode() == 1
        val label = if (wantBattle) "战斗" else "讨伐"
        val exp = radialPos(bandit, if (wantBattle) ANG_BATTLE else ANG_ATTACK)

        // 1) 有模板就按几何位置附近精确匹配（最快最准）
        if (attackTpl != null) {
            val near = matchNear(attackTpl, exp.x, exp.y, 110)
            if (near != null) {
                updateOverlayStatus("命中菜单按钮模板")
                dispatchTap(near.x, near.y) { afterAttack(label) }
                return
            }
        }
        // 2) 没有模板：环形扫描找出菜单按钮，逐个点并用面板出现来校验
        val btns = detectMenuButtons(bandit)
        updateOverlayStatus("菜单按钮候选 ${btns.size} 个")
        if (btns.isNotEmpty()) {
            attemptAttackButton(btns, 0, bandit, label)
            return
        }
        // 3) 兜底：直接点几何推算位置（试探，不拉黑）
        if (stageTry >= 2) {
            dispatchTap(exp.x, exp.y) { afterAttack(label) }
            return
        }
        stageTry++
        handler.postDelayed({ stageAttack() }, 500)
    }

    /**
     * 依次尝试菜单按钮候选；点完看「全选」面板是否弹出，弹出=点对了。
     * 这样即使不知道按钮确切颜色/位置，也能靠校验自动锁定正确按键。
     */
    private fun attemptAttackButton(btns: List<BtnCand>, idx: Int, bandit: Point, label: String) {
        if (!running) return
        // 重新定位盗匪，确保收尾「点盗匪收起菜单」落在最新位置（跟随移动）
        val cur = reacquireBandit() ?: bandit
        if (idx >= btns.size) {
            // 所有候选都试过仍没弹出面板：先收起菜单，再重试或几何兜底
            if (stageTry >= 2) {
                dispatchTap(cur.x, cur.y) { backToWaiting("菜单按钮未点开") }
                return
            }
            dispatchTap(cur.x, cur.y) {
                stageTry++
                handler.postDelayed({ stageAttack() }, 500)
            }
            return
        }
        val b = btns[idx]
        updateOverlayStatus("点菜单按钮 #${idx + 1}/${btns.size}")
        dispatchTap(b.x, b.y) {
            handler.postDelayed({
                val blue = findBlueButton()
                if (blue != null) {
                    // 面板弹出了，说明点到了正确的菜单按钮，顺手采下模板下次直接用
                    if (attackTpl == null) {
                        captureTemplateFile("attack", b.x, b.y, 34)
                        attackTpl = loadTpl("attack")
                    }
                    afterAttack(label)
                } else {
                    // 没弹出面板，换下一个候选再试
                    attemptAttackButton(btns, idx + 1, bandit, label)
                }
            }, 900)
        }
    }

    private fun afterAttack(label: String) {
        if (!running) return
        updateNotif("已点$label，等选兵面板")
        updateOverlayStatus("已点$label · 等面板")
        stage = ST_SELECT
        stageTry = 0
        selectTries = 0
        manualIdx = 0
        stageDeadline = System.currentTimeMillis() + UI_WAIT_MS
        handler.postDelayed({ stageSelect() }, stepDelay())
    }

    // —— 阶段 3：点「全选」 ——

    /**
     * 全选按钮是个切换按钮：点过之后文字从「全选」变成「不选」。
     * 所以要靠颜色（面板是否打开）+ 全分辨率像素比对（当前是哪种文字）来判断该不该点。
     * 实测：同状态差异约 0.02/像素，异状态约 11/像素，阈值取 4 很安全。
     */
    private fun selectButtonState(bluePos: Point): Int {
        // 1 = 显示「全选」（需要点），2 = 显示「不选」（已全选，不用点），0 = 未知
        val tpl = selectTpl ?: return 0
        if (diffAt(tpl, bluePos.x, bluePos.y) <= STATE_DIFF_MAX) return 1
        val off = selectOffTpl
        if (off != null && diffAt(off, bluePos.x, bluePos.y) <= STATE_DIFF_MAX) return 2
        return 0
    }

    /** 是否使用「自选队伍」模式（校准时记录过要点哪几支） */
    private fun manualTroopMode(): Boolean {
        val pts = prefs.getTroopPoints()
        return prefs.isTroopSelectOn() && pts.isNotEmpty()
    }

    private fun stageSelect() {
        if (!running) return
        val blue = findBlueButton()
        // 首次自动采「全选」模板（之后比对其状态更稳定）
        if (blue != null && selectTpl == null) {
            captureTemplateByColor("select", true, SELECT_TPL_R)
            selectTpl = loadTpl("select")
        }
        if (blue == null) {
            // 面板还没弹出来
            if (System.currentTimeMillis() > stageDeadline) {
                if (waitReady) {
                    // 试探模式：面板弹不出（可能部队全在打仗）→ 不拉黑盗匪，回轮询
                    val orangeStill = findOrangeButton()
                    if (orangeStill != null) {
                        // 面板其实开着（确定按钮在）→ 按返回键安全关闭
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    backToWaiting("面板未出现")
                    return
                }
                updateNotif("选兵面板没出来，换下一个")
                updateOverlayStatus("面板未出现 · 换目标")
                blacklistLast()
                stage = ST_FIND
                handler.postDelayed({ stageFind() }, humanDelay(1200))
            } else {
                handler.postDelayed({ stageSelect() }, 400)
            }
            return
        }
        val state = selectButtonState(blue)

        // 模式一：自选队伍 —— 依次点校准时记录的队伍
        if (manualTroopMode() && state != 2) {
            val pts = prefs.getTroopPoints()
            var n = prefs.getTroopCount()
            if (n <= 0) n = pts.size
            n = n.coerceAtMost(pts.size)
            if (manualIdx < n && n > 0) {
                val p = pts[manualIdx]
                manualIdx++
                updateOverlayStatus("选兵 $manualIdx/$n")
                dispatchTap(p.x, p.y) {
                    handler.postDelayed({ stageSelect() }, 550)
                }
                return
            }
            afterSelect()
            return
        }

        // 模式二：点「全选」
        if (!manualTroopMode() && prefs.isSelectAllOn()) {
            if (state == 2) {
                // 已经是「不选」= 全部选好了，不用再点
                updateOverlayStatus("已全选 · 点确定")
                afterSelect()
                return
            }
            if (selectTries >= 3) {
                afterSelect()
                return
            }
            selectTries++
            updateOverlayStatus("点全选 ($selectTries)")
            dispatchTap(blue.x, blue.y) {
                // 点完「全选」后文字变成「不选」，把这一态也采下来
                if (selectOffTpl == null) {
                    captureTemplateByColor("select_off", true, SELECT_TPL_R)
                    selectOffTpl = loadTpl("select_off")
                }
                handler.postDelayed({
                    val b2 = findBlueButton()
                    val s2 = if (b2 != null) selectButtonState(b2) else 0
                    if (s2 == 1 && selectTries < 3) {
                        // 文字仍是「全选」→ 刚才那下没生效（或点成了取消），再点一次
                        handler.postDelayed({ stageSelect() }, 250)
                    } else {
                        afterSelect()
                    }
                }, 550)
            }
            return
        }

        // 状态未知（面板开着但文字没认出来）
        if (waitReady && selectTpl != null) {
            // 试探模式：全选按钮认不出，很可能是部队没回来按钮变灰。
            // 面板确定开着（蓝色按钮已找到）→ 先点一下（灰按钮无反应），
            // 仍认不出就按返回键关面板（面板开着时返回键=关面板，安全）。
            if (selectTries < 1) {
                selectTries++
                dispatchTap(blue.x, blue.y) {
                    handler.postDelayed({ stageSelect() }, 600)
                }
                return
            }
            updateOverlayStatus("部队未就绪 · 关面板再等")
            performGlobalAction(GLOBAL_ACTION_BACK)
            backToWaiting("按钮未就绪")
            return
        }
        afterSelect()
    }

    private fun afterSelect() {
        if (!running) return
        waitReady = false   // 部队已恢复可选并完成选兵，退出试探模式
        stage = ST_CONFIRM
        stageTry = 0
        stageDeadline = System.currentTimeMillis() + UI_WAIT_MS
        handler.postDelayed({ stageConfirm() }, stepDelay())
    }

    // —— 阶段 4：点「确定」出征 ——

    private fun stageConfirm() {
        if (!running) return
        val orange = findOrangeButton()
        if (orange != null) {
            if (confirmTpl == null) {
                captureTemplateByColor("confirm", false, 22)
                confirmTpl = loadTpl("confirm")
            }
            dispatchTap(orange.x, orange.y) {
                updateNotif("已出征，等待战斗结束")
                updateOverlayStatus("已出征 · 等战斗")
                stage = ST_WAIT
                handler.postDelayed({ stageWait() }, humanDelay(battleWaitMs))
            }
            return
        }
        val tplP = matchTemplate(confirmTpl)
        if (tplP != null) {
            dispatchTap(tplP.x, tplP.y) {
                updateNotif("已出征，等待战斗结束")
                updateOverlayStatus("已出征 · 等战斗")
                stage = ST_WAIT
                handler.postDelayed({ stageWait() }, humanDelay(battleWaitMs))
            }
            return
        }
        if (System.currentTimeMillis() > stageDeadline) {
            if (waitReady) {
                // 试探模式：面板开着但确定按钮没出现（选兵可能没成功）
                performGlobalAction(GLOBAL_ACTION_BACK)
                backToWaiting("确定未出现")
                return
            }
            updateNotif("没找到确定按钮，换目标")
            updateOverlayStatus("确定未出现 · 换目标")
            blacklistLast()
            stage = ST_FIND
            handler.postDelayed({ stageFind() }, humanDelay(1200))
            return
        }
        handler.postDelayed({ stageConfirm() }, 450)
    }

    // —— 阶段 5：出征后等待部队全部恢复可选 ——

    /**
     * 按视频流程：出征后不死等固定时间，而是「保底等待 + 轮询试探」——
     * 等到 battleWaitMs 后开始试探：重新走 找盗匪 → 点盗匪 → 讨伐/战斗 → 看选兵面板，
     * 面板正常（部队能选）就继续出征；面板弹不出/按钮变灰 = 部队还没回来，
     * 等 WAIT_POLL_MS 后再试，直到所有队伍恢复可选。
     */
    private fun stageWait() {
        if (!running) return
        stage = ST_WAIT
        waitReady = true
        waitReadyTries = 0
        updateNotif("等待部队返回，恢复后自动继续")
        updateOverlayStatus("等部队返回 · 试探中")
        handler.postDelayed({ stageFind() }, 500)
    }

    /** 试探失败：部队还没就绪，等一下再来；超过上限按普通失败换目标 */
    private fun backToWaiting(reason: String) {
        if (!running) return
        waitReadyTries++
        if (waitReadyTries >= WAIT_READY_MAX) {
            updateNotif("长时间等不到部队，换个目标")
            updateOverlayStatus("部队久未就绪 · 换目标")
            waitReady = false
            waitReadyTries = 0
            blacklistLast()
            stage = ST_FIND
            handler.postDelayed({ stageFind() }, humanDelay(1200))
            return
        }
        updateNotif("部队未就绪（$reason），${WAIT_POLL_MS / 1000}秒后再试")
        updateOverlayStatus("部队未就绪(${waitReadyTries}) · 等待中")
        stage = ST_WAIT
        handler.postDelayed({ stageFind() }, WAIT_POLL_MS)
    }

    private fun blacklistLast() {
        lastBandit?.let {
            failPos = Point(it.x, it.y)
            failUntil = System.currentTimeMillis() + 60000
        }
    }

    // ---------------- 盗匪等级筛选 ----------------

    /**
     * 判断找到的盗匪是否符合等级要求。
     * 规则：开启「只打指定等级」且有等级模板时，先在「盗匪图标 + 校准偏移」附近
     * 匹配等级标签；匹配上 = 符合（打）。
     * 匹配不上时再全屏找一次等级标签，仍对不上则判为「等级不符」跳过；
     * 但连续 5 次判不符会自动降级为放行，避免模板不稳导致永远不打。
     */
    private fun levelAccept(banditPos: Point): Boolean {
        if (!prefs.isLevelOnly() || levelFailOpen) return true
        val tpl = levelTpl ?: return true
        val ox = prefs.getLevelOffX()
        val oy = prefs.getLevelOffY()
        val ex = banditPos.x + ox
        val ey = banditPos.y + oy

        val near = matchNear(tpl, ex, ey, 120)
        if (near != null) {
            levelSkipStreak = 0
            updateOverlayStatus("等级匹配 ✓ · 点盗匪")
            return true
        }
        val any = matchTemplate(tpl)
        if (any != null && (abs(any.x - ex) + abs(any.y - ey)) <= 240) {
            levelSkipStreak = 0
            updateOverlayStatus("等级匹配 ✓ · 点盗匪")
            return true
        }
        levelSkipStreak++
        if (levelSkipStreak >= 5) {
            levelFailOpen = true
            toast("等级识别不稳定，已自动放行（照打）")
            updateOverlayStatus("等级识别不稳 · 已放行")
            return true
        }
        updateOverlayStatus("等级不符 ✗ · 换下一个")
        return false
    }

    // ---------------- 固定位置周边扫描 ----------------

    private fun useAreaSearch(): Boolean =
        prefs.getSearchMode() == 1 && prefs.hasBasePos() && screenWidth > 0

    /** 搜索半径（像素）= 半径(里) × 每里像素 */
    private fun scanRadiusPx(): Int {
        val li = prefs.getSearchRadiusLi().coerceAtLeast(50)
        val k = prefs.getLiPixel().coerceIn(0.2f, 50f)
        return (li * k).toInt().coerceAtLeast(500)
    }

    /** 扫描开始前确定基准：先在屏幕上找到固定位置（村庄），算出视野中心相对它的偏移 */
    private fun initScanOrigin() {
        scanDx = 0
        scanDy = 0
        if (!useAreaSearch()) return
        val tpl = baseTpl ?: return
        val p = matchTemplate(tpl)
        if (p == null) {
            toast("未找到固定位置，按当前视野为中心搜索")
            updateNotif("未找到固定位置，按当前视野搜索")
            return
        }
        scanDx = screenWidth / 2 - p.x
        scanDy = screenHeight / 2 - p.y
        updateNotif("已定位固定位置，开始周边搜索")
    }

    /** 第 index 个扫描点相对固定位置的偏移（蛇形遍历） */
    private fun scanTarget(idx: Int): Pair<Int, Int> {
        val r = scanRadiusPx()
        val row = idx / SCAN_COLS
        val col = idx % SCAN_COLS
        val rowH = if (SCAN_ROWS > 1) r / (SCAN_ROWS - 1) else r
        val colW = (2 * r) / SCAN_COLS
        var tx = -r + col * colW
        if (row % 2 == 1) tx = r - col * colW
        var ty = -r + row * rowH
        if (ty > r) ty = r
        return Pair(tx, ty)
    }

    /** 推进到下一个扫描点 */
    private fun advanceScan() {
        if (!running) return
        val total = SCAN_ROWS * SCAN_COLS
        if (scanIndex >= total) {
            // 一轮扫完 → 回到起点重新扫
            scanIndex = 0
            if (noBanditStreak == 0) noBanditStreak = 1
            updateOverlayStatus("周边搜索 · 重新开始")
            moveToTarget(0, 0) { handler.postDelayed({ stageFind() }, 400) }
            return
        }
        val (tx, ty) = scanTarget(scanIndex)
        scanIndex++
        updateOverlayStatus("周边搜索 ${scanIndex}/$total")
        updateNotif("周边搜索 ${scanIndex}/$total")
        moveToTarget(tx, ty) { handler.postDelayed({ stageFind() }, 400) }
    }

    /** 把视野移动到「固定位置 + 目标偏移」，多段拖动并实时记录偏移 */
    private fun moveToTarget(tx: Int, ty: Int, onDone: () -> Unit) {
        if (screenWidth == 0 || screenHeight == 0) { onDone(); return }
        val stepX = (screenWidth / 4).coerceAtLeast(80)
        val stepY = (screenHeight / 4).coerceAtLeast(80)
        // 手指位移 = 当前偏移 - 目标偏移（地图内容与手指同向，视野中心反向移动）
        var needX = scanDx - tx
        var needY = scanDy - ty
        val moves = ArrayList<IntArray>()
        while (abs(needX) > stepX || abs(needY) > stepY) {
            val mx = needX.coerceIn(-stepX, stepX)
            val my = needY.coerceIn(-stepY, stepY)
            moves.add(intArrayOf(mx, my))
            needX -= mx
            needY -= my
        }
        if (needX != 0 || needY != 0) moves.add(intArrayOf(needX, needY))
        if (moves.isEmpty()) { onDone(); return }
        performMoves(moves, 0, onDone)
    }

    private fun performMoves(moves: List<IntArray>, i: Int, onDone: () -> Unit) {
        if (i >= moves.size) {
            handler.postDelayed({
                calibrateScanOffset()
                onDone()
            }, 700)
            return
        }
        val m = moves[i]
        dragBy(m[0], m[1])
        // 内容位移与手指位移相反
        scanDx -= m[0]
        scanDy -= m[1]
        handler.postDelayed({ performMoves(moves, i + 1, onDone) }, 700)
    }

    /** 用固定位置模板实时校正偏移，避免拖动累积误差把搜索带出范围 */
    private fun calibrateScanOffset() {
        val tpl = baseTpl ?: return
        if (abs(scanDx) > screenWidth || abs(scanDy) > screenHeight) return
        val p = matchTemplate(tpl) ?: return
        scanDx = screenWidth / 2 - p.x
        scanDy = screenHeight / 2 - p.y
    }

    // ---------------- 六步校准（全程在游戏内完成） ----------------

    private fun startCalibration() {
        if (calibrating) {
            toast("已经在校准了")
            return
        }
        if (!captureReady || imageReader == null) {
            toast("请先回APP点「授权录屏并启动」")
            updateNotif("校准失败：未授权录屏", false)
            updateOverlayStatus("未授权录屏 · 回APP授权")
            return
        }
        if (running) stopLoop()

        calibrating = true
        calibBusy = false
        calibStep = CAL_BASE
        calibTroops = mutableListOf()
        banditTpl = null
        attackTpl = null
        battleTpl = null
        selectTpl = null
        selectOffTpl = null
        confirmTpl = null
        baseTpl = null
        levelTpl = null

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.argb(80, 0, 0, 0))

        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 17f
        tv.setPadding(28, 70, 28, 28)
        tv.text = calibTextFor(CAL_BASE)
        root.addView(
            tv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(12, 0, 12, 24)

        val btnSkip = Button(this)
        btnSkip.text = "跳过此步"
        btnSkip.textSize = 14f
        row.addView(
            btnSkip,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val btnOk = Button(this)
        btnOk.text = "✔ 完成选兵"
        btnOk.textSize = 14f
        row.addView(
            btnOk,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val btnCancel = Button(this)
        btnCancel.text = "✖ 取消"
        btnCancel.textSize = 14f
        row.addView(
            btnCancel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row)

        calibText = tv
        calibBtn = btnOk
        calibSkip = btnSkip
        applyCalibStepUi()

        btnSkip.setOnClickListener {
            haptic()
            when (calibStep) {
                CAL_BASE -> {
                    prefs.clearBasePos()
                    baseTpl = null
                    gotoCalibStep(CAL_BANDIT)
                }
                CAL_LEVEL -> {
                    prefs.setLevelOnly(false)
                    prefs.setLevelOffX(0)
                    prefs.setLevelOffY(0)
                    levelTpl = null
                    gotoCalibStep(CAL_ATTACK)
                }
                CAL_TROOP -> {
                    // 放弃自选队伍 → 运行时用「全选」
                    prefs.setTroopPoints(emptyList())
                    prefs.setTroopCount(0)
                    prefs.setTroopSelectOn(false)
                    gotoCalibStep(CAL_CONFIRM)
                }
            }
        }
        btnOk.setOnClickListener {
            if (calibStep == CAL_TROOP) {
                val pts = calibTroops.toList()
                prefs.setTroopPoints(pts)
                prefs.setTroopCount(pts.size)
                prefs.setTroopSelectOn(pts.isNotEmpty())
                haptic()
                gotoCalibStep(CAL_CONFIRM)
            }
        }
        btnCancel.setOnClickListener {
            haptic()
            cancelCalibration()
        }

        // 除按钮以外的触摸 = 在游戏里点一下
        root.setOnTouchListener { _, event ->
            if (!calibBusy && event.action == MotionEvent.ACTION_DOWN) {
                onCalibTap(event.x.toInt(), event.y.toInt())
                true
            } else {
                false
            }
        }

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
            windowManager?.addView(root, params)
            calibOverlay = root
            calibParams = params
            updateOverlayStatus("校准中：按屏幕提示操作")
            toast("校准开始：第 1 步 · 固定位置")
        } catch (e: Exception) {
            Log.e(TAG, "calib overlay failed", e)
            calibrating = false
            toast("校准浮层创建失败")
        }
    }

    private fun calibTextFor(step: Int): String = when (step) {
        CAL_BASE ->
            "第 1/7 步 · 固定位置\n" +
                    "在大地图上点一下你的【村庄】（或任意固定位置）\n" +
                    "它就是周边搜索的中心。不想限定范围就点【跳过此步】"
        CAL_BANDIT ->
            "第 2/7 步 · 盗匪\n" +
                    "在屏幕上点一下任意一个【盗匪】（红褐色小兵的那一队）"
        CAL_LEVEL ->
            "第 3/7 步 · 等级\n" +
                    "点一下盗匪名字前面那个【圆形等级数字】（例如 60）\n" +
                    "之后只打这一类；不需要筛选就点【跳过此步】"
        CAL_ATTACK ->
            "第 4/7 步 · 讨伐\n" +
                    "点一下盗匪旁边弹出的圆形菜单里的【讨伐】\n" +
                    "（左上方齿轮状的那个图标）"
        CAL_SELECT ->
            "第 5/7 步 · 全选\n" +
                    "部队面板右下角有蓝色【全选】和橙色【确定】\n" +
                    "点一下蓝色的【全选】"
        CAL_TROOP ->
            "第 6/7 步 · 自选队伍（可选）\n" +
                    "想只出某几支队伍，就依次点它们（点几支就是几支）\n" +
                    "点完点下方【✔ 完成选兵】；想全部出动就点【跳过此步】"
        else ->
            "第 7/7 步 · 确定\n" +
                    "点一下右下角橙色的【确定】按钮\n" +
                    "点完即完成校准，之后悬浮窗点「开始」就能挂机"
    }

    private fun applyCalibStepUi() {
        val step = calibStep
        calibText?.text = calibTextFor(step)
        calibSkip?.visibility =
            if (step == CAL_BASE || step == CAL_LEVEL || step == CAL_TROOP) View.VISIBLE else View.GONE
        calibBtn?.visibility = if (step == CAL_TROOP) View.VISIBLE else View.GONE
    }

    private fun gotoCalibStep(step: Int) {
        calibStep = step
        applyCalibStepUi()
    }

    private fun onCalibTap(x: Int, y: Int) {
        if (calibBusy) return
        calibBusy = true

        if (calibStep == CAL_TROOP) {
            // 自选队伍：记录坐标并把点击转发给游戏（队伍才会被勾上）
            calibTroops.add(Point(x, y))
            haptic()
            calibBtn?.text = "✔ 完成选兵（已选 ${calibTroops.size} 队）"
            dispatchTap(x, y) { calibBusy = false }
            return
        }

        if (calibStep == CAL_BASE) {
            // 固定位置：只记录，不点游戏（避免弹出村庄面板挡住地图）
            prefs.setBaseX(x)
            prefs.setBaseY(y)
            captureTemplateFile("base", x, y, 60)
            baseTpl = loadTpl("base")
            haptic()
            gotoCalibStep(CAL_BANDIT)
            calibBusy = false
            return
        }

        if (calibStep == CAL_LEVEL) {
            // 等级标签：只记录位置与相对盗匪图标的偏移，不点游戏（避免关掉放射菜单）
            captureTemplateFile("level", x, y, 40)
            levelTpl = loadTpl("level")
            prefs.setLevelOffX(x - calibBanditX)
            prefs.setLevelOffY(y - calibBanditY)
            prefs.setLevelOnly(true)
            haptic()
            gotoCalibStep(CAL_ATTACK)
            calibBusy = false
            return
        }

        // 先隐藏校准浮层并让它不拦截触摸，等一帧让系统合成，截到的是纯净游戏画面，
        // 注入的点击也能真正落到游戏里
        calibOverlay?.visibility = View.GONE
        setTouchable(calibOverlay, calibParams, false)
        handler.postDelayed({
            when (calibStep) {
                CAL_BANDIT -> {
                    captureTemplateFile("bandit", x, y, 55)
                    banditTpl = loadTpl("bandit")
                    calibBanditX = x
                    calibBanditY = y
                }
                CAL_ATTACK -> {
                    captureTemplateFile("attack", x, y, 34)
                    attackTpl = loadTpl("attack")
                    prefs.setAttackPos(x, y)
                    prefs.setAttackOffX(x - calibBanditX)
                    prefs.setAttackOffY(y - calibBanditY)
                    // 「战斗」按钮在同一张画面里，按实测偏移一并采集
                    val k = if (screenWidth > 0) screenWidth / REF_W else 1.0
                    val bx = (x + BTN_DX * k).toInt()
                    val by = (y + BTN_DY * k).toInt()
                    captureTemplateFile("battle", bx, by, 34)
                    battleTpl = loadTpl("battle")
                    prefs.setBattleOffX(bx - calibBanditX)
                    prefs.setBattleOffY(by - calibBanditY)
                }
                CAL_SELECT -> {
                    // 用颜色定位按钮中心截取，保证与运行时的像素比对完全对齐
                    captureTemplateByColor("select", true, SELECT_TPL_R)
                    selectTpl = loadTpl("select")
                    prefs.setSelectPos(x, y)
                }
                CAL_TROOP -> {
                    // 自选队伍步骤由上面的分支处理，这里不会走到
                }
                CAL_CONFIRM -> {
                    captureTemplateByColor("confirm", false, 22)
                    confirmTpl = loadTpl("confirm")
                    prefs.setConfirmPos(x, y)
                }
            }
            dispatchTap(x, y) {
                when (calibStep) {
                    CAL_BANDIT -> {
                        gotoCalibStep(CAL_LEVEL)
                        handler.postDelayed({ applyCalibStepUi() }, 700)
                    }
                    CAL_ATTACK -> {
                        gotoCalibStep(CAL_SELECT)
                        handler.postDelayed({ applyCalibStepUi() }, 1000)
                    }
                    CAL_SELECT -> {
                        // 点过之后按钮变成「不选」，把这一状态也按同样方式存下来
                        handler.postDelayed({
                            captureTemplateByColor("select_off", true, SELECT_TPL_R)
                            selectOffTpl = loadTpl("select_off")
                            gotoCalibStep(CAL_TROOP)
                            handler.postDelayed({ applyCalibStepUi() }, 500)
                            calibOverlay?.visibility = View.VISIBLE
                            setTouchable(calibOverlay, calibParams, true)
                            calibBusy = false
                        }, 800)
                        return@dispatchTap
                    }
                    else -> {
                        finishCalibration()
                        return@dispatchTap
                    }
                }
                calibOverlay?.visibility = View.VISIBLE
                setTouchable(calibOverlay, calibParams, true)
                calibBusy = false
            }
        }, 150)
    }

    private fun finishCalibration() {
        calibrating = false
        calibBusy = false
        prefs.setCalibrated(true)
        removeCalibOverlay()
        updateNotif("校准完成，悬浮窗点「开始」即可挂机", false)
        updateOverlayStatus("校准完成 ✅ 点「开始」")
        refreshTroopUi()
        refreshModeUi()
        toast("校准完成！")
        val i = Intent(ACTION_CALIB_DONE)
        i.setPackage(packageName)
        sendBroadcast(i)
    }

    private fun cancelCalibration() {
        calibrating = false
        calibBusy = false
        removeCalibOverlay()
        loadConfig()
        refreshTroopUi()
        refreshModeUi()
        updateOverlayStatus("已取消校准")
        toast("已取消校准")
    }

    private fun removeCalibOverlay() {
        try {
            calibOverlay?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        calibOverlay = null
        calibText = null
        calibBtn = null
        calibSkip = null
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

    // ---------------- 游戏内悬浮控制台 ----------------

    private fun showOverlay() {
        val wm = windowManager ?: return
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
        params.gravity = Gravity.TOP or Gravity.START
        // 放在左上角：右下角是「全选 / 确定」按钮所在区域，浮窗压在上面会吃掉点击
        params.x = 8
        params.y = 96

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_window, null)
        val btnToggle = view.findViewById<Button>(R.id.btn_toggle)
        val btnCalib = view.findViewById<Button>(R.id.btn_calib)
        val btnExit = view.findViewById<Button>(R.id.btn_exit)
        val btnMinus = view.findViewById<Button>(R.id.btn_minus)
        val btnPlus = view.findViewById<Button>(R.id.btn_plus)
        val btnTroopToggle = view.findViewById<Button>(R.id.btn_troop_toggle)
        val btnSearch = view.findViewById<Button>(R.id.btn_search)
        val btnLevel = view.findViewById<Button>(R.id.btn_level)
        val btnMode = view.findViewById<Button>(R.id.btn_mode)
        val btnAct = view.findViewById<Button>(R.id.btn_act)
        val title = view.findViewById<TextView>(R.id.tv_title)
        overlayStatus = view.findViewById(R.id.tv_status)
        overlayTroop = view.findViewById(R.id.tv_troop)
        overlayTroopToggle = btnTroopToggle
        overlaySearch = btnSearch
        overlayLevel = btnLevel
        overlayMode = btnMode
        overlayAct = btnAct

        btnToggle.setOnClickListener {
            haptic()
            if (calibrating) {
                toast("正在校准中，请先完成校准")
                return@setOnClickListener
            }
            if (running) stopLoop() else startLoop()
        }
        btnCalib.setOnClickListener {
            haptic()
            if (running) stopLoop()
            startCalibration()
        }
        btnExit.setOnClickListener {
            haptic()
            stopLoop()
            try {
                wm.removeView(view)
            } catch (e: Exception) { /* ignore */ }
            overlayView = null
            toast("助手已退出")
            stopSelf()
        }
        btnMinus.setOnClickListener {
            haptic()
            changeTroopCount(-1)
        }
        btnPlus.setOnClickListener {
            haptic()
            changeTroopCount(1)
        }
        btnTroopToggle.setOnClickListener {
            haptic()
            val on = !prefs.isSelectAllOn()
            prefs.setSelectAllOn(on)
            refreshTroopUi()
            toast(if (on) "已开启：出征前自动点「全选」" else "已关闭：不点全选，用游戏记住的选择")
        }
        btnSearch.setOnClickListener {
            haptic()
            toggleSearchMode()
        }
        btnLevel.setOnClickListener {
            haptic()
            toggleLevelFilter()
        }
        btnMode.setOnClickListener {
            haptic()
            toggleAttackMode()
        }
        btnAct.setOnClickListener {
            haptic()
            toggleActivityWindow()
        }

        // 标题栏可拖动
        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    dragOriginX = params.x
                    dragOriginY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragOriginX + (event.rawX - dragStartX).toInt()
                    params.y = dragOriginY + (event.rawY - dragStartY).toInt()
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (e: Exception) { /* ignore */ }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
            overlayParams = params
        } catch (e: Exception) {
            Log.e(TAG, "add overlay failed", e)
            toast("悬浮窗创建失败，请关闭无障碍后重开")
        }
        refreshTroopUi()
        refreshModeUi()
    }

    private fun setOverlayToggle(isRunning: Boolean) {
        overlayView?.findViewById<Button>(R.id.btn_toggle)?.text =
            if (isRunning) "⏸ 停止" else "▶ 开始"
    }

    // ---------------- 盗匪检测标记（可视化锁定位置） ----------------

    /** 创建屏幕上的「绿圈十字」标记层（不拦截触摸，避免吃掉点击） */
    private fun initMarkerView() {
        val wm = windowManager ?: return
        if (markerView != null) return
        val p = WindowManager.LayoutParams(
            MARKER_SIZE, MARKER_SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = -MARKER_SIZE
        p.y = -MARKER_SIZE
        val v = MarkerView(this)
        try {
            wm.addView(v, p)
            markerView = v
            markerParams = p
        } catch (e: Exception) {
            Log.e(TAG, "add marker failed", e)
        }
    }

    /** 在 (x,y) 处显示标记；只用于「找盗匪」阶段，进入攻击后由 hideBanditMarker 隐藏，
     *  以免绿圈被录屏截到、干扰「放射菜单按钮」等颜色识别。 */
    private fun showBanditMarker(x: Int, y: Int, label: String) {
        val v = markerView ?: return
        val p = markerParams ?: return
        v.labelText = label
        v.invalidate()
        p.x = (x - MARKER_SIZE / 2).coerceIn(0, (screenWidth - MARKER_SIZE).coerceAtLeast(0))
        p.y = (y - MARKER_SIZE / 2).coerceIn(0, (screenHeight - MARKER_SIZE).coerceAtLeast(0))
        markerHasTarget = true
        v.visibility = View.VISIBLE
        try { windowManager?.updateViewLayout(v, p) } catch (e: Exception) { /* ignore */ }
    }

    private fun hideBanditMarker() {
        markerHasTarget = false
        markerView?.visibility = View.GONE
    }

    private fun updateOverlayStatus(text: String) {
        overlayStatus?.text = text
    }

    /** 队伍数加减 */
    private fun changeTroopCount(delta: Int) {
        val max = prefs.getTroopPoints().size
        if (max == 0) {
            toast("还没校准队伍，请先点「校准」")
            refreshTroopUi()
            return
        }
        var c = prefs.getTroopCount()
        if (c <= 0) c = max
        c = (c + delta).coerceIn(1, max)
        prefs.setTroopCount(c)
        refreshTroopUi()
        toast("本次出战 $c 队")
    }

    /** 切换搜索方式：全图随机 / 固定位置周边扫描 */
    private fun toggleSearchMode() {
        val cur = prefs.getSearchMode()
        if (cur == 1) {
            prefs.setSearchMode(0)
            toast("已切换：全图随机找盗匪")
        } else {
            if (!prefs.hasBasePos()) {
                toast("还没设置固定位置，请先「校准」第 1 步")
                return
            }
            prefs.setSearchMode(1)
            scanIndex = 0
            scanDx = 0
            scanDy = 0
            if (running) initScanOrigin()
            toast("已切换：固定位置周边 ${prefs.getSearchRadiusLi()} 里内搜索")
        }
        refreshModeUi()
    }

    /** 切换盗匪等级筛选 */
    private fun toggleLevelFilter() {
        if (levelTpl == null) {
            toast("还没校准等级标签，请先「校准」第 3 步")
            return
        }
        val on = !prefs.isLevelOnly()
        prefs.setLevelOnly(on)
        levelSkipStreak = 0
        levelFailOpen = false
        refreshModeUi()
        toast(if (on) "已开启：只打指定等级的盗匪" else "已关闭：不限等级")
    }

    /** 切换讨伐方式：讨伐（消耗令）/ 战斗（不消耗令） */
    private fun toggleAttackMode() {
        val cur = prefs.getAttackMode()
        val nxt = if (cur == 1) 0 else 1
        prefs.setAttackMode(nxt)
        refreshModeUi()
        toast(
            if (nxt == 1) "已切换：战斗（不消耗讨伐令）"
            else "已切换：讨伐（消耗讨伐令，奖励更好）"
        )
    }

    /** 活动时段开关：全天 ↔ APP 里设置的时间段 */
    private fun toggleActivityWindow() {
        val s = prefs.getActivityStartMin()
        val e = prefs.getActivityEndMin()
        if (s < 0 || e < 0) {
            toast("还没设置活动时段，请到APP里填开始/结束时间")
            return
        }
        if (prefs.isActivityOn()) {
            prefs.setActivityOn(false)
            toast("已关闭活动时段限制：全天运行")
        } else {
            prefs.setActivityOn(true)
            toast("已开启：只在 ${fmtMin(s)}-${fmtMin(e)} 之间运行")
        }
        refreshModeUi()
    }

    private fun fmtMin(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return String.format("%02d:%02d", h, mm)
    }

    private fun refreshTroopUi() {
        val pts = prefs.getTroopPoints()
        val max = pts.size
        if (max == 0) {
            overlayTroop?.text = "自选队伍：未设"
        } else {
            var c = prefs.getTroopCount()
            if (c <= 0) c = max
            overlayTroop?.text = "自选 $c / $max 队"
        }
        overlayTroopToggle?.text =
            "全选：" + if (prefs.isSelectAllOn()) "开" else "关"
    }

    private fun refreshModeUi() {
        val mode = prefs.getSearchMode()
        val radius = prefs.getSearchRadiusLi()
        overlaySearch?.text =
            if (mode == 1) "周边 $radius 里" else "全图"
        overlayLevel?.text =
            "等级：" + if (prefs.isLevelOnly()) "指定" else "不限"
        overlayMode?.text =
            if (prefs.getAttackMode() == 1) "战斗" else "讨伐"
        val actStart = prefs.getActivityStartMin()
        val actEnd = prefs.getActivityEndMin()
        overlayAct?.text = when {
            !prefs.isActivityOn() -> "时段：全天"
            actStart < 0 || actEnd < 0 -> "时段：全天"
            else -> "时段：" + fmtMin(actStart) + "-" + fmtMin(actEnd)
        }
    }

    // ---------------- 反馈工具 ----------------

    private fun toast(msg: String) {
        handler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun haptic() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateO(v)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(40)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun vibrateO(v: Vibrator) {
        v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
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
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* ignore */ }
        overlayView = null
        removeCalibOverlay()
        banditTpl?.recycle()
        attackTpl?.recycle()
        battleTpl?.recycle()
        selectTpl?.recycle()
        selectOffTpl?.recycle()
        confirmTpl?.recycle()
        baseTpl?.recycle()
        levelTpl?.recycle()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) { /* ignore */ }
    }
}

/**
 * 盗匪检测标记：一个绿圈 + 十字准星 + 文字标签，画在屏幕绝对坐标 (x,y) 处，
 * 用于直观显示「当前锁定的盗匪位置」。不拦截触摸（FLAG_NOT_TOUCHABLE），
 * 所以不会吃掉游戏点击。只挂在「找盗匪」阶段，进入攻击即隐藏，避免被录屏截到。
 */
private class MarkerView(context: Context) : View(context) {
    private val ring = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val cross = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val txt = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        textSize = 30f
        isAntiAlias = true
    }
    var labelText: String = ""

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f - 8f, ring)
        canvas.drawLine(w / 2f, 10f, w / 2f, h - 10f, cross)
        canvas.drawLine(10f, h / 2f, w - 10f, h / 2f, cross)
        if (labelText.isNotEmpty()) {
            canvas.drawText(labelText, 12f, 36f, txt)
        }
    }
}
