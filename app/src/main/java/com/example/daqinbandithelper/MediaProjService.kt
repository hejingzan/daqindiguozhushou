package com.example.daqinbandithelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 轻量前台服务：仅用于在「模板测试」界面里临时持有 MEDIA_PROJECTION 前台服务类型，
 * 满足 Android 14+ 在调用 getMediaProjection() 前必须已有该类型前台服务运行的要求，
 * 否则会抛 SecurityException: Media projections require a foreground service of type ...。
 * 真正的截图/匹配由 TemplateTestActivity 复用本服务持有的 projection 完成。
 */
class MediaProjService : Service() {

    companion object {
        var instance: MediaProjService? = null
        private const val NOTIF_ID = 1002
    }

    var projection: MediaProjection? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val rc = intent?.getIntExtra(C.EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(C.EXTRA_DATA)
        if (rc == android.app.Activity.RESULT_OK && data != null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(rc, data)
                Diag.log("模板测试", "录屏投影已创建")
            } catch (e: Exception) {
                Diag.log("模板测试", "创建投影失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return START_STICKY
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                C.CHANNEL_ID, "打盗匪助手",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, C.CHANNEL_ID)
            .setContentTitle("模板测试录屏")
            .setContentText("正在截屏用于测试模板")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        super.onDestroy()
    }
}
