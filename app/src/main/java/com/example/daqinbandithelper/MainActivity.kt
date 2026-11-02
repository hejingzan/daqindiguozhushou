package com.example.daqinbandithelper

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var textStatus: TextView
    private lateinit var textHint: TextView
    private lateinit var btnStart: Button
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAction = ""  // "bot" | "capture"

    private val mediaProj = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "未授权录屏，无法继续", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        textStatus = findViewById(R.id.text_status)
        textHint = findViewById(R.id.text_hint)
        btnStart = findViewById(R.id.btn_start)

        findViewById<Button>(R.id.btn_acc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_capture_bandit).setOnClickListener { startCaptureFlow(C.TPL_BANDIT) }
        findViewById<Button>(R.id.btn_capture_attack).setOnClickListener { startCaptureFlow(C.TPL_ATTACK) }
        findViewById<Button>(R.id.btn_capture_march).setOnClickListener { startCaptureFlow(C.TPL_MARCH) }
        findViewById<Button>(R.id.btn_capture_confirm).setOnClickListener { startCaptureFlow(C.TPL_CONFIRM) }
        findViewById<Button>(R.id.btn_capture_close).setOnClickListener { startCaptureFlow(C.TPL_CLOSE) }
        findViewById<Button>(R.id.btn_capture_home).setOnClickListener { startCaptureFlow(C.TPL_HOME) }

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
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setText(id: Int, s: String) {
        findViewById<EditText>(id).setText(s)
    }

    private fun refreshStatus() {
        val acc = isAccessibilityEnabled()
        if (!acc) {
            textStatus.text = "状态：❗请先点「开启无障碍权限」并在系统设置里启用本应用"
        } else {
            val tpl = prefs.templateCount()
            textStatus.text = "无障碍：✅  已采模板：$tpl/6  ${if (BotController.running) "运行中" else "待机"}"
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
            BotController.stop()
            refreshStatus()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (prefs.templateCount() == 0) {
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
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        BotController.pendingTemplate = tpl
        BotController.captureService?.requestStop()
        BotController.captureService = null
        pendingAction = "capture"
        textHint.text = "采集「${C.TPL_LABELS[tpl]}」：授权后会浮出「📷截图」，去游戏把该按钮露出来后点它，框选保存即可。"
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
            mediaProj.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "无法申请录屏：$e", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = if (pendingAction == "bot") C.ACTION_START else C.ACTION_CAPTURE
            if (pendingAction == "capture") {
                putExtra(C.EXTRA_TPL, BotController.pendingTemplate ?: C.TPL_BANDIT)
            }
            putExtra(C.EXTRA_RESULT_CODE, resultCode)
            putExtra(C.EXTRA_DATA, data)
        }
        try {
            startService(intent)
            if (pendingAction == "bot") {
                Toast.makeText(this, "已启动，进游戏即可挂机（悬浮窗可停止）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "采集模式已开启，去游戏点悬浮「📷截图」", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：$e", Toast.LENGTH_LONG).show()
        }
    }
}
