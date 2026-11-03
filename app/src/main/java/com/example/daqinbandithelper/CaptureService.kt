package com.example.daqinbandithelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * 截图 + 控制中枢：
 *  - ACTION_START  → 机器人模式（MediaProjection 截图 → BotEngine 状态机 → TapService 点击）
 *  - ACTION_CAPTURE → 采集模式（MediaProjection 截图 → CropActivity 裁图存模板）
 *  - ACTION_STOP   → 停止
 *
 * 悬浮控制窗（停止 / 📷截图）由本服务自己用 TYPE_APPLICATION_OVERLAY 承载，
 * 不再依赖无障碍服务的连接时机，避免“授权后没有浮出按钮”的问题；
 * 无障碍服务仅用于真正下发触摸手势。
 */
class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var engine: BotEngine? = null
    private val handlerThread = HandlerThread("daqin-bot")
    private lateinit var handler: Handler

    private var mode = MODE_IDLE
    private var running = false
    private lateinit var prefs: Prefs

    private var w = 0
    private var h = 0

    // 自带悬浮控制窗
    private var overlayView: View? = null
    private var tvStatus: TextView? = null
    private var wm: WindowManager? = null

    // 主线程 handler：用于浮层重试（WindowManager 操作需在带 Looper 的线程）
    private val uiHandler = Handler(Looper.getMainLooper())
    private var overlayRetry = 0

    companion object {
        private const val MODE_IDLE = 0
        private const val MODE_BOT = 1
        private const val MODE_CAPTURE = 2
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            C.ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            C.ACTION_START -> startBot(intent)
            C.ACTION_CAPTURE -> startCapture(intent)
            else -> {
                requestStop()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startBot(intent: Intent) {
        val rc = intent.getIntExtra(C.EXTRA_RESULT_CODE, -1)
        val data = intent.getParcelableExtra<Intent>(C.EXTRA_DATA)
        Diag.log("服务", "startBot rc=$rc data=${data != null}")
        if (rc < 0 || data == null) {
            Diag.log("服务", "缺少录屏授权参数，已停止")
            Toast.makeText(this, "缺少录屏授权参数，已停止", Toast.LENGTH_LONG).show()
            requestStop()
            return
        }
        prefs = Prefs(this)
        startForegroundSafe()
        val ok = initProjectionSafe(rc, data)
        engine = BotEngine(this)
        running = true
        mode = MODE_BOT
        BotController.captureService = this
        BotController.running = true
        BotController.captureMode = false
        engine?.setStatusCallback { s -> setStatus(s) }
        overlayRetry = 0
        showOverlay(if (ok) "挂机中…（点击停止可结束）" else "⚠ 录屏失败，请看下方日志")
        if (ok) scheduleLoop()
    }

    private fun startCapture(intent: Intent) {
        val rc = intent.getIntExtra(C.EXTRA_RESULT_CODE, -1)
        val data = intent.getParcelableExtra<Intent>(C.EXTRA_DATA)
        Diag.log("服务", "startCapture rc=$rc data=${data != null} tpl=${BotController.pendingTemplate}")
        if (rc < 0 || data == null) {
            Diag.log("服务", "缺少录屏授权参数，已停止")
            Toast.makeText(this, "缺少录屏授权参数，已停止", Toast.LENGTH_LONG).show()
            requestStop()
            return
        }
        prefs = Prefs(this)
        startForegroundSafe()
        val ok = initProjectionSafe(rc, data)
        running = true
        mode = MODE_CAPTURE
        BotController.captureService = this
        BotController.captureMode = true
        overlayRetry = 0
        showOverlay(if (ok) "采集模式：去游戏点「📷截图」" else "⚠ 录屏初始化失败，请看下方日志")
    }

    /** 初始化投影；任何异常都记录到诊断日志并提示，绝不让服务静默崩溃。 */
    private fun initProjectionSafe(rc: Int, data: Intent): Boolean {
        return try {
            initProjection(rc, data)
            Diag.log("录屏", "投影初始化成功 ${w}x$h")
            true
        } catch (e: Exception) {
            Diag.log("录屏", "投影初始化失败：${e.javaClass.simpleName}: ${e.message}")
            Log.e("CaptureService", "initProjection failed", e)
            try {
                Toast.makeText(this, "录屏初始化失败：${e.message}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun initProjection(rc: Int, data: Intent) {
        if (!::handler.isInitialized) {
            handlerThread.start()
            handler = Handler(handlerThread.looper)
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Diag.log("录屏", "getMediaProjection…")
        mediaProjection = mpm.getMediaProjection(rc, data)
        if (mediaProjection == null) throw IllegalStateException("getMediaProjection 返回 null")
        val dm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        dm.defaultDisplay.getRealSize(size)
        w = size.x
        h = size.y
        val density = resources.displayMetrics.densityDpi
        imageReader = ImageReader.newInstance(
            w, h, android.graphics.PixelFormat.RGBA_8888, 2
        )
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Diag.log("录屏", "MediaProjection 被系统停止")
                handler.post { requestStop() }
            }
        }, handler)
        Diag.log("录屏", "创建 VirtualDisplay…")
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "daqin", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        if (virtualDisplay == null) throw IllegalStateException("createVirtualDisplay 返回 null")
    }

    private fun scheduleLoop() {
        handler.postDelayed({ loop() }, 500)
    }

    private fun loop() {
        if (!running || mode != MODE_BOT) return
        try {
            val img = imageReader?.acquireLatestImage()
            if (img != null) {
                val bmp = imageToBitmap(img)
                img.close()
                if (bmp != null) {
                    engine?.processFrame(bmp)
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("CaptureService", "loop error", e)
        }
        if (running && mode == MODE_BOT) scheduleLoop()
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            if (rowPadding == 0) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        }
    }

    /** 采集模式：截一张图保存到临时文件并打开裁图界面 */
    fun shoot() {
        try {
            Diag.log("采集", "收到截图指令")
            val img = imageReader?.acquireLatestImage()
            if (img == null) {
                Diag.log("采集", "取帧失败：imageReader 为空或无新帧")
                Toast.makeText(this, "取帧失败，请稍后再试", Toast.LENGTH_SHORT).show()
                return
            }
            val bmp = imageToBitmap(img)
            img.close()
            if (bmp == null) {
                Diag.log("采集", "位图转换失败")
                return
            }
            val f = java.io.File(filesDir, "shot_tmp.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            Diag.log("采集", "已截图 ${f.absolutePath}")
            val i = Intent(this, CropActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("path", f.absolutePath)
                putExtra(C.EXTRA_TPL, BotController.pendingTemplate ?: C.TPL_BANDIT)
            }
            startActivity(i)
        } catch (e: Exception) {
            Diag.log("采集", "截图异常：${e.javaClass.simpleName}: ${e.message}")
            Log.e("CaptureService", "shoot error", e)
        }
    }

    // ===================== 自带悬浮控制窗 =====================

    /** 显示悬浮控制窗：
     *  1) 优先用无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY（OEM 不拦截、且本应用已强制要求开启无障碍，最可靠）；
     *  2) 无障碍暂不可用时回退到本服务自己的 TYPE_APPLICATION_OVERLAY；
     *  3) 两者都失败时延迟重试（服务可能正在连接），仍失败则弹明确提示，绝不静默。 */
    fun showOverlay(status: String) {
        // 1) 优先无障碍浮层（OEM 不拦截、且本应用已强制要求开启无障碍，最可靠）
        val a11y = TapService.instance
        if (a11y != null) {
            try {
                if (a11y.showOverlay(status)) {
                    Diag.log("浮层", "使用无障碍浮层")
                    overlayRetry = 0
                    return
                }
            } catch (e: Exception) {
                Diag.log("浮层", "无障碍浮层异常：${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            Diag.log("浮层", "无障碍服务实例为空（未连接）")
        }
        // 2) 回退：本服务自己的浮层（需"显示在其他应用上"权限，部分国产 ROM 会拦截）
        try {
            ensureOverlay()
            setStatus(status)
            try { TapService.instance?.hideOverlay() } catch (_: Exception) {}
            overlayRetry = 0
            Diag.log("浮层", "使用应用自身浮层")
            return
        } catch (e: Exception) {
            Diag.log("浮层", "应用自身浮层失败：${e.javaClass.simpleName}: ${e.message}")
        }
        // 3) 两者都失败：可能是无障碍服务刚启用尚未连接，延迟重试
        if (overlayRetry < 6) {
            overlayRetry++
            Diag.log("浮层", "第 $overlayRetry 次重试")
            uiHandler.postDelayed({ showOverlay(status) }, 700)
            return
        }
        // 最终失败：明确提示，不再静默
        Diag.log("浮层", "全部失败，提示用户")
        try {
            Toast.makeText(
                this,
                "无法显示悬浮按钮：请在系统设置中确认已开启本应用的无障碍服务（并允许其显示在顶部/悬浮窗）",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
        }
    }

    private fun ensureOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            throw SecurityException("SYSTEM_ALERT_WINDOW not granted")
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_status, null)
        tvStatus = overlayView!!.findViewById(R.id.tv_status)
        val btnStop = overlayView!!.findViewById<Button>(R.id.btn_overlay_stop)
        val btnShoot = overlayView!!.findViewById<Button>(R.id.btn_overlay_shoot)
        btnStop.setOnClickListener { BotController.stop() }
        btnShoot.visibility = if (mode == MODE_CAPTURE) View.VISIBLE else View.GONE
        btnShoot.setOnClickListener { shoot() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        wm!!.addView(overlayView, params)
    }

    fun setStatus(s: String) {
        tvStatus?.text = s
    }

    fun hideOverlay() {
        overlayRetry = 0
        if (overlayView != null) {
            try {
                wm?.removeView(overlayView)
            } catch (_: Exception) {
            }
            overlayView = null
            tvStatus = null
        }
        // 一并清理可能残留的无障碍浮层
        try {
            TapService.instance?.hideOverlay()
        } catch (_: Exception) {
        }
    }

    fun requestStop() {
        if (!running && mode == MODE_IDLE) {
            hideOverlay()
            return
        }
        running = false
        mode = MODE_IDLE
        BotController.running = false
        BotController.captureMode = false
        BotController.captureService = null
        hideOverlay()
        try {
            handler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        try {
            createChannel()
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif: Notification = NotificationCompat.Builder(this, C.CHANNEL_ID)
                .setContentTitle("大秦打盗匪助手")
                .setContentText("运行中")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    C.NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(C.NOTIF_ID, notif)
            }
            Diag.log("服务", "前台服务已启动")
        } catch (e: Exception) {
            Diag.log("服务", "startForeground 失败：${e.javaClass.simpleName}: ${e.message}")
            Log.e("CaptureService", "startForeground failed (continuing)", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                C.CHANNEL_ID, "打盗匪助手",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }
}
