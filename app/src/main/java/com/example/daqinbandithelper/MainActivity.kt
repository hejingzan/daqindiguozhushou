package com.example.daqinbandithelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var textStatus: TextView
    private lateinit var textHint: TextView
    private lateinit var textDiag: TextView
    private lateinit var btnStart: Button
    private lateinit var spinnerTpl: Spinner
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction = ""  // "bot" | "capture"

    private val mediaProj = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Diag.log("录屏", "回调 resultCode=${result.resultCode} data=${result.data != null}")
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            Diag.log("录屏", "用户未授权录屏")
            Toast.makeText(this, "未授权录屏，无法继续", Toast.LENGTH_LONG).show()
        }
    }

    /** 从相册导入模板：完全不需要录屏权限与悬浮窗，最稳的采模板方式 */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            Diag.log("导入", "用户取消选择")
            return@registerForActivityResult
        }
        val tpl = currentTpl()
        try {
            val f = File(filesDir, "shot_tmp.png")
            contentResolver.openInputStream(uri)?.use { ins ->
                f.outputStream().use { outs -> ins.copyTo(outs) }
            }
            Diag.log("导入", "已读取图片，准备裁剪：${C.TPL_LABELS[tpl]}")
            startActivity(Intent(this, CropActivity::class.java).apply {
                putExtra("path", f.absolutePath)
                putExtra(C.EXTRA_TPL, tpl)
            })
        } catch (e: Exception) {
            Diag.log("导入", "失败：${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        textStatus = findViewById(R.id.text_status)
        textHint = findViewById(R.id.text_hint)
        textDiag = findViewById(R.id.text_diag)
        btnStart = findViewById(R.id.btn_start)
        spinnerTpl = findViewById(R.id.spinner_tpl)

        spinnerTpl.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            C.TPL_ORDER.map { C.TPL_LABELS[it] ?: it }
        )

        findViewById<Button>(R.id.btn_acc).setOnClickListener {
            Diag.log("界面", "打开无障碍设置")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_capture_bandit).setOnClickListener { startCaptureFlow(C.TPL_BANDIT) }
        findViewById<Button>(R.id.btn_capture_attack).setOnClickListener { startCaptureFlow(C.TPL_ATTACK) }
        findViewById<Button>(R.id.btn_capture_select_all).setOnClickListener { startCaptureFlow(C.TPL_SELECT_ALL) }
        findViewById<Button>(R.id.btn_capture_confirm_troop).setOnClickListener { startCaptureFlow(C.TPL_CONFIRM_TROOP) }
        findViewById<Button>(R.id.btn_capture_march).setOnClickListener { startCaptureFlow(C.TPL_MARCH) }
        findViewById<Button>(R.id.btn_capture_confirm).setOnClickListener { startCaptureFlow(C.TPL_CONFIRM) }
        findViewById<Button>(R.id.btn_capture_close).setOnClickListener { startCaptureFlow(C.TPL_CLOSE) }
        findViewById<Button>(R.id.btn_capture_home).setOnClickListener { startCaptureFlow(C.TPL_HOME) }

        findViewById<Button>(R.id.btn_import_gallery).setOnClickListener {
            Diag.log("界面", "从相册导入模板：${C.TPL_LABELS[currentTpl()]}")
            try {
                pickImage.launch("image/*")
            } catch (e: Exception) {
                Diag.log("导入", "无法打开相册：${e.message}")
                Toast.makeText(this, "无法打开相册：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btn_diag_clear).setOnClickListener {
            Diag.clear()
            refreshDiag()
        }

        btnStart.setOnClickListener { startBotFlow() }

        val cb = findViewById<CheckBox>(R.id.check_ocr)
        cb.isChecked = prefs.ocrEnabled
        cb.setOnCheckedChangeListener { _, isChecked -> prefs.ocrEnabled = isChecked }

        // 回填已存参数
        setText(R.id.edit_min_level, prefs.minLevel.toString())
        setText(R.id.edit_max_level, prefs.maxLevel.toString())
        setText(R.id.edit_stamina, prefs.staminaMin.toString())
        setText(R.id.edit_max_cycle, prefs.maxCycle.toString())
        setText(R.id.edit_threshold, prefs.matchThreshold.toString())
        setText(R.id.edit_delay, prefs.baseDelayMs.toString())
        setText(R.id.edit_sleep, prefs.sleepSec.toString())

        Diag.log("界面", "App 启动，系统=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT}")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.post(diagTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(diagTicker)
    }

    private val diagTicker = object : Runnable {
        override fun run() {
            refreshDiag()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refreshDiag() {
        try { textDiag.text = Diag.text() } catch (_: Exception) {}
    }

    private fun currentTpl(): String {
        val idx = spinnerTpl.selectedItemPosition
        return C.TPL_ORDER.getOrNull(idx) ?: C.TPL_BANDIT
    }

    private fun selectTpl(name: String) {
        val idx = C.TPL_ORDER.indexOf(name)
        if (idx >= 0) spinnerTpl.setSelection(idx)
    }

    private fun setText(id: Int, s: String) {
        findViewById<EditText>(id).setText(s)
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        val tapReady = TapService.instance != null
        val tpl = prefs.templateCount()
        textStatus.text = buildString {
            append("无障碍设置：")
            append(if (acc) "✅" else "❌")
            append("  无障碍服务实例：")
            append(if (tapReady) "✅" else "❌未连接")
            append("\n已采模板：$tpl/${C.TPL_ORDER.size}   ")
            append(if (BotController.running) "运行中" else "待机")
        }
        btnStart.text = if (BotController.running) "⏸ 停止挂机" else "▶ 开始挂机"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = packageName + "/" + TapService::class.java.canonicalName
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(service) == true
    }

    private fun startBotFlow() {
        if (BotController.running) {
            Diag.log("界面", "点击停止挂机")
            BotController.stop()
            refreshStatus()
            return
        }
        if (!isAccessibilityEnabled()) {
            Diag.log("界面", "未开启无障碍，终止")
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (prefs.templateCount() == 0) {
            Diag.log("界面", "模板数为 0，终止")
            Toast.makeText(this, "请先采集至少一个模板（建议 6 个都采）", Toast.LENGTH_LONG).show()
            return
        }
        saveParams()
        BotController.captureService?.requestStop()
        BotController.captureService = null
        pendingAction = "bot"
        handler.postDelayed({ launchCapture() }, 250)
    }

    private fun startCaptureFlow(tpl: String) {
        selectTpl(tpl)
        if (!isAccessibilityEnabled()) {
            Diag.log("界面", "未开启无障碍，终止采集")
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        BotController.pendingTemplate = tpl
        BotController.captureService?.requestStop()
        BotController.captureService = null
        pendingAction = "capture"
        textHint.text = "采集「${C.TPL_LABELS[tpl]}」：授权后左上角应浮出「📷截图」。\n" +
            "若没浮出，请改用下方「📥 从相册导入」（游戏里按系统截图键截图，再选该图片即可）。"
        Diag.log("界面", "开始采集模板 $tpl")
        handler.postDelayed({ launchCapture() }, 250)
    }

    private fun saveParams() {
        val min = findViewById<EditText>(R.id.edit_min_level).text.toString().toIntOrNull() ?: 1
        val max = findViewById<EditText>(R.id.edit_max_level).text.toString().toIntOrNull() ?: 99
        val sta = findViewById<EditText>(R.id.edit_stamina).text.toString().toIntOrNull() ?: 20
        val cyc = findViewById<EditText>(R.id.edit_max_cycle).text.toString().toIntOrNull() ?: 30
        val th = findViewById<EditText>(R.id.edit_threshold).text.toString().toFloatOrNull() ?: 0.8f
        val dl = findViewById<EditText>(R.id.edit_delay).text.toString().toLongOrNull() ?: 1200L
        val sl = findViewById<EditText>(R.id.edit_sleep).text.toString().toIntOrNull() ?: 300
        prefs.minLevel = min
        prefs.maxLevel = max
        prefs.staminaMin = sta
        prefs.maxCycle = cyc
        prefs.matchThreshold = th
        prefs.baseDelayMs = dl
        prefs.sleepSec = sl
    }

    private fun launchCapture() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            Diag.log("录屏", "弹出系统录屏授权")
            mediaProj.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Diag.log("录屏", "申请失败：${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "无法申请录屏：$e", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val action = if (pendingAction == "bot") C.ACTION_START else C.ACTION_CAPTURE
        val intent = Intent(this, CaptureService::class.java).apply {
            this.action = action
            if (pendingAction == "capture") {
                putExtra(C.EXTRA_TPL, BotController.pendingTemplate ?: C.TPL_BANDIT)
            }
            putExtra(C.EXTRA_RESULT_CODE, resultCode)
            putExtra(C.EXTRA_DATA, data)
        }
        try {
            // Android 8+ 需用 startForegroundService，否则退到后台后服务会被回收
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Diag.log("服务", "已请求启动 action=$action")
            if (pendingAction == "bot") {
                Toast.makeText(this, "已启动，进游戏即可挂机", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "采集模式已开启，去游戏点悬浮「📷截图」", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        } catch (e: Exception) {
            Diag.log("服务", "启动失败：${e.javaClass.simpleName}: ${e.message}")
            Toast.makeText(this, "启动失败：$e", Toast.LENGTH_LONG).show()
        }
    }
}
