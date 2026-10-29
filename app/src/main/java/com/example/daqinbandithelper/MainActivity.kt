package com.example.daqinbandithelper

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 主界面只做三件事：
 *  ① 引导开启无障碍权限；
 *  ② 申请录屏授权（Android 要求弹窗必须由界面发起，拿到授权后悬浮窗才能识别画面）；
 *  ③ 提供校准入口与队伍数设置。
 * 授权完成后，所有实际操作都在游戏内的悬浮控制台上完成，不用再来回切应用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var textStatus: TextView
    private lateinit var textTroop: TextView
    private lateinit var btnStart: Button
    private lateinit var btnCalib: Button
    private lateinit var btnAcc: Button
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var editWait: EditText
    private lateinit var editThresh: EditText
    private lateinit var radioHuman: RadioGroup
    private lateinit var radioScan: RadioGroup
    private lateinit var radioLevel: RadioGroup
    private lateinit var radioMode: RadioGroup
    private lateinit var editRadius: EditText
    private lateinit var editLiPixel: EditText
    private lateinit var editBanditName: EditText
    private lateinit var editCenterRatio: EditText
    private lateinit var editActStart: EditText
    private lateinit var editActEnd: EditText
    private lateinit var editNoBandit: EditText
    private lateinit var textBase: TextView

    /** 授权录屏成功后的下一步动作 */
    private var pendingAction = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val calibReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BanditAccessibilityService.ACTION_CALIB_DONE) {
                refreshStatus()
                refreshTroopUi()
                refreshBaseUi()
                Toast.makeText(
                    this@MainActivity,
                    "校准完成！切到游戏点悬浮窗「开始」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 服务发来：录屏授权已失效，需要重新申请 */
    private val needCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BanditAccessibilityService.ACTION_NEED_CAPTURE) {
                pendingAction = ACTION_START
                Toast.makeText(
                    this@MainActivity,
                    "录屏授权已失效，请重新授权以开始",
                    Toast.LENGTH_LONG
                ).show()
                launchCapture()
            }
        }
    }

    private val mediaProj = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            sendCaptureToService(result.resultCode, result.data!!)
            // 录屏授权拿到后，再执行用户点的功能
            when (pendingAction) {
                ACTION_CALIBRATE -> {
                    handlerPost {
                        sendService(BanditAccessibilityService.ACTION_CALIBRATE)
                        Toast.makeText(
                            this,
                            "切到游戏，按提示依次点：盗匪 → 讨伐 → 全选 → 确定",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                ACTION_START -> {
                    handlerPost {
                        sendService(BanditAccessibilityService.ACTION_START)
                        Toast.makeText(
                            this,
                            "已启动，切到游戏即可自动打盗匪（悬浮窗可停止）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            pendingAction = ""
            handlerPost { refreshStatus() }
        } else {
            Toast.makeText(this, "未授权录屏，无法识别画面", Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限授权与否都不影响核心功能 */ }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        try {
            initUi()
        } catch (e: Throwable) {
            saveCrashLog(e)
            showFatal(e)
        }
    }

    private fun initUi() {
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        textStatus = findViewById(R.id.text_status)
        textTroop = findViewById(R.id.text_troop)
        btnStart = findViewById(R.id.btn_start)
        btnCalib = findViewById(R.id.btn_calib)
        btnAcc = findViewById(R.id.btn_acc)
        btnMinus = findViewById(R.id.btn_minus)
        btnPlus = findViewById(R.id.btn_plus)
        editWait = findViewById(R.id.edit_wait)
        editThresh = findViewById(R.id.edit_thresh)
        radioHuman = findViewById(R.id.radio_human)
        radioScan = findViewById(R.id.radio_scan)
        radioLevel = findViewById(R.id.radio_level)
        radioMode = findViewById(R.id.radio_mode)
        editRadius = findViewById(R.id.edit_radius)
        editLiPixel = findViewById(R.id.edit_lipixel)
        editBanditName = findViewById(R.id.edit_banditname)
        editCenterRatio = findViewById(R.id.edit_center_ratio)
        editActStart = findViewById(R.id.edit_act_start)
        editActEnd = findViewById(R.id.edit_act_end)
        editNoBandit = findViewById(R.id.edit_nobandit)
        textBase = findViewById(R.id.text_base)

        when (prefs.getHumanizeLevel()) {
            0 -> radioHuman.check(R.id.rb_low)
            2 -> radioHuman.check(R.id.rb_high)
            else -> radioHuman.check(R.id.rb_mid)
        }
        if (prefs.getSearchMode() == 1) radioScan.check(R.id.rb_scan_area)
        else radioScan.check(R.id.rb_scan_all)
        if (prefs.isLevelOnly()) radioLevel.check(R.id.rb_level_only)
        else radioLevel.check(R.id.rb_level_any)
        if (prefs.getAttackMode() == 1) radioMode.check(R.id.rb_mode_battle)
        else radioMode.check(R.id.rb_mode_attack)

        editWait.setText((prefs.getBattleWaitMs() / 1000).toString())
        editThresh.setText(prefs.getMatchThreshold().toString())
        editRadius.setText(prefs.getSearchRadiusLi().toString())
        editLiPixel.setText(trimFloat(prefs.getLiPixel()))
        editNoBandit.setText(prefs.getNoBanditWaitSec().toString())
        editBanditName.setText(prefs.getBanditNameKeywords().joinToString(","))
        editCenterRatio.setText(trimFloat(prefs.getCenterRadiusRatio()))
        val actStart = prefs.getActivityStartMin()
        val actEnd = prefs.getActivityEndMin()
        if (actStart >= 0) editActStart.setText(fmtMin(actStart))
        if (actEnd >= 0) editActEnd.setText(fmtMin(actEnd))

        btnStart.setOnClickListener { onStartClicked() }
        btnCalib.setOnClickListener { onCalibClicked() }
        btnAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnMinus.setOnClickListener { changeTroopCount(-1) }
        btnPlus.setOnClickListener { changeTroopCount(1) }

        val filter = IntentFilter(BanditAccessibilityService.ACTION_CALIB_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(calibReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(calibReceiver, filter)
        }
        val ncFilter = IntentFilter(BanditAccessibilityService.ACTION_NEED_CAPTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(needCaptureReceiver, ncFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(needCaptureReceiver, ncFilter)
        }
        showLastCrashIfAny()
        ensureNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        if (!::textStatus.isInitialized) return
        refreshStatus()
        refreshTroopUi()
        refreshBaseUi()
    }

    // ---------------- 崩溃捕获（不再直接闪退，错误会保存并可查看） ----------------

    private fun crashFile() = File(filesDir, "crash.log")

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { saveCrashLog(e) } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
    }

    private fun saveCrashLog(e: Throwable) {
        try {
            val time = java.text.SimpleDateFormat(
                "MM-dd HH:mm:ss", java.util.Locale.CHINA
            ).format(java.util.Date())
            val log = buildString {
                append("时间：").append(time)
                append("\n错误类型：").append(e.javaClass.name)
                append("\n错误信息：").append(e.message)
                append("\n\n堆栈：\n")
                append(android.util.Log.getStackTraceString(e))
            }
            crashFile().writeText(log)
        } catch (_: Exception) { }
    }

    private fun showLastCrashIfAny() {
        try {
            val f = crashFile()
            if (f.exists()) {
                val logText = f.readText().take(2000)
                findViewById<TextView>(R.id.text_crash).apply {
                    visibility = View.VISIBLE
                    text = "⚠ 上次运行出错（长按可复制，请发给开发者）：\n$logText"
                }
                f.delete()
            }
        } catch (_: Exception) { }
    }

    private fun showFatal(e: Throwable) {
        try {
            val tv = TextView(this)
            tv.text = "启动出错：${e.javaClass.simpleName}\n${e.message}\n\n" +
                "错误已保存，请重新打开本应用查看红字内容并截图发给开发者。"
            tv.setPadding(48, 80, 48, 48)
            setContentView(tv)
        } catch (_: Exception) { }
    }

    private fun refreshBaseUi() {
        textBase.text = if (prefs.hasBasePos()) {
            "固定位置：已设置（${prefs.getBaseX()}, ${prefs.getBaseY()}）· 校准第 1 步可重新指定"
        } else {
            "固定位置：未设置（校准第 1 步点村庄）"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(calibReceiver)
        } catch (e: Exception) { /* ignore */ }
        try {
            unregisterReceiver(needCaptureReceiver)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        if (!isAccessibilityEnabled()) {
            sb.append("状态：❗请先开启「无障碍权限」")
        } else {
            sb.append("无障碍：✅  ")
            sb.append(if (prefs.isCaptureReady()) "录屏：✅已授权  " else "录屏：❌未授权  ")
            sb.append(if (prefs.isCalibrated()) "校准：✅" else "校准：❌")
        }
        textStatus.text = sb.toString()
        btnAcc.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
    }

    private fun refreshTroopUi() {
        val max = prefs.getTroopPoints().size
        if (max == 0) {
            textTroop.text = "自选队伍：未校准（校准第 6 步设置，或直接用「全选」）"
            return
        }
        var c = prefs.getTroopCount()
        if (c <= 0) c = max
        textTroop.text = "自选队伍：$c / $max"
    }

    private fun changeTroopCount(delta: Int) {
        val max = prefs.getTroopPoints().size
        if (max == 0) {
            Toast.makeText(this, "还没校准自选队伍，请先点「② 校准」", Toast.LENGTH_SHORT).show()
            return
        }
        var c = prefs.getTroopCount()
        if (c <= 0) c = max
        c = (c + delta).coerceIn(1, max)
        prefs.setTroopCount(c)
        refreshTroopUi()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = packageName + "/" + BanditAccessibilityService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(service) == true
    }

    private fun ensureAccessibility(): Boolean {
        if (isAccessibilityEnabled()) return true
        Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return false
    }

    private fun onStartClicked() {
        if (!ensureAccessibility()) return
        saveAdvanced()
        pendingAction = ACTION_START
        if (prefs.isCaptureReady()) {
            sendService(BanditAccessibilityService.ACTION_START)
            Toast.makeText(
                this,
                "已启动，切到游戏即可自动打盗匪（悬浮窗可停止）",
                Toast.LENGTH_LONG
            ).show()
        } else {
            launchCapture()
        }
    }

    private fun onCalibClicked() {
        if (!ensureAccessibility()) return
        saveAdvanced()
        pendingAction = ACTION_CALIBRATE
        if (prefs.isCaptureReady()) {
            sendService(BanditAccessibilityService.ACTION_CALIBRATE)
            Toast.makeText(
                this,
                "切到游戏，按提示依次点：盗匪 → 讨伐 → 全选 → 确定",
                Toast.LENGTH_LONG
            ).show()
        } else {
            launchCapture()
        }
    }

    private fun saveAdvanced() {
        val waitSec = editWait.text.toString().toLongOrNull()?.coerceIn(5, 120) ?: 20
        prefs.setBattleWaitMs(waitSec * 1000)
        val th = editThresh.text.toString().toIntOrNull()?.coerceIn(20, 120) ?: 55
        prefs.setMatchThreshold(th)
        val level = when (radioHuman.checkedRadioButtonId) {
            R.id.rb_low -> 0
            R.id.rb_high -> 2
            else -> 1
        }
        prefs.setHumanizeLevel(level)

        // 搜索方式
        val scanMode = if (radioScan.checkedRadioButtonId == R.id.rb_scan_area) 1 else 0
        if (scanMode == 1 && !prefs.hasBasePos()) {
            Toast.makeText(this, "还没设置固定位置，请先「校准」第 1 步点村庄", Toast.LENGTH_LONG).show()
        } else {
            prefs.setSearchMode(scanMode)
        }
        val radius = editRadius.text.toString().toIntOrNull()?.coerceIn(50, 100000) ?: 1000
        prefs.setSearchRadiusLi(radius)
        val lp = editLiPixel.text.toString().toFloatOrNull()?.coerceIn(0.2f, 50f) ?: 3f
        prefs.setLiPixel(lp)

        // 盗匪名称关键词（无障碍文字识别用）
        val names = editBanditName.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            .ifEmpty { Prefs.defaultBanditNames }
        prefs.setBanditNameKeywords(names)
        // 屏幕中心搜索范围（占屏比例，≈游戏内1000里）
        val ratio = editCenterRatio.text.toString().toFloatOrNull()?.coerceIn(0.1f, 0.95f) ?: 0.48f
        prefs.setCenterRadiusRatio(ratio)

        // 等级筛选
        val lv = if (radioLevel.checkedRadioButtonId == R.id.rb_level_only) 1 else 0
        if (lv == 1 && !prefs.hasTemplate("level")) {
            Toast.makeText(this, "还没校准等级标签，请先「校准」第 3 步", Toast.LENGTH_LONG).show()
        } else {
            prefs.setLevelOnly(lv == 1)
        }

        // 讨伐方式
        prefs.setAttackMode(
            if (radioMode.checkedRadioButtonId == R.id.rb_mode_battle) 1 else 0
        )

        // 活动时段
        val sMin = parseHm(editActStart.text.toString())
        val eMin = parseHm(editActEnd.text.toString())
        if (sMin == null && eMin == null) {
            prefs.setActivityStartMin(-1)
            prefs.setActivityEndMin(-1)
            prefs.setActivityOn(false)
        } else if (sMin == null || eMin == null) {
            Toast.makeText(this, "活动时段要同时填开始和结束时间", Toast.LENGTH_LONG).show()
        } else {
            prefs.setActivityStartMin(sMin)
            prefs.setActivityEndMin(eMin)
            prefs.setActivityOn(true)
        }

        // 找不到盗匪时的等待
        val nb = editNoBandit.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 30
        prefs.setNoBanditWaitSec(nb)

        refreshStatus()
        refreshTroopUi()
    }

    /** 把 "20:30" 解析成当天分钟数；解析不出来返回 null */
    private fun parseHm(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val p = t.split(":")
        if (p.size != 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun fmtMin(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return String.format("%02d:%02d", h, mm)
    }

    private fun trimFloat(v: Float): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun launchCapture() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProj.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "无法申请录屏授权：$e", Toast.LENGTH_LONG).show()
        }
    }

    /** 把录屏授权结果交给无障碍服务 */
    private fun sendCaptureToService(resultCode: Int, data: Intent) {
        val capture = Intent(this, BanditAccessibilityService::class.java).apply {
            action = BanditAccessibilityService.ACTION_START_CAPTURE
            putExtra("result_code", resultCode)
            putExtra("data", data)
        }
        try {
            startService(capture)
        } catch (e: Exception) {
            Toast.makeText(this, "服务启动失败：$e", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendService(action: String) {
        val i = Intent(this, BanditAccessibilityService::class.java).apply {
            this.action = action
        }
        try {
            startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "服务启动失败：$e", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlerPost(block: () -> Unit) {
        mainHandler.post { block() }
    }

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_CALIBRATE = "calibrate"
    }
}
