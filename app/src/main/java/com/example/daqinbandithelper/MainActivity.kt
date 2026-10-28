package com.example.daqinbandithelper

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var textStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnCalib: Button
    private lateinit var editWait: EditText
    private lateinit var editThresh: EditText
    private lateinit var radioHuman: RadioGroup

    private val calibReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BanditAccessibilityService.ACTION_CALIB_DONE) {
                refreshStatus()
                Toast.makeText(
                    this@MainActivity,
                    "校准完成，可直接点「▶ 开始打盗匪」",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val mediaProj = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val rc = result.resultCode
            val data = result.data!!
            val capture = Intent(this, BanditAccessibilityService::class.java).apply {
                action = BanditAccessibilityService.ACTION_START_CAPTURE
                putExtra("result_code", rc)
                putExtra("data", data)
            }
            startService(capture)
            if (pendingCalibrate) {
                pendingCalibrate = false
                startService(Intent(this, BanditAccessibilityService::class.java).apply {
                    action = BanditAccessibilityService.ACTION_CALIBRATE
                })
                Toast.makeText(this, "请按提示点 盗匪 → 讨伐 → 出征", Toast.LENGTH_LONG).show()
            } else {
                startService(Intent(this, BanditAccessibilityService::class.java).apply {
                    action = BanditAccessibilityService.ACTION_START
                })
                textStatus.text = "状态：挂机运行中（通知栏可停止）"
            }
        }
    }

    private var pendingCalibrate = false

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
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        textStatus = findViewById(R.id.text_status)
        btnStart = findViewById(R.id.btn_start)
        btnCalib = findViewById(R.id.btn_calib)
        editWait = findViewById(R.id.edit_wait)
        editThresh = findViewById(R.id.edit_thresh)
        radioHuman = findViewById(R.id.radio_human)
        when (prefs.getHumanizeLevel()) {
            0 -> radioHuman.check(R.id.rb_low)
            2 -> radioHuman.check(R.id.rb_high)
            else -> radioHuman.check(R.id.rb_mid)
        }

        editWait.setText((prefs.getBattleWaitMs() / 1000).toString())
        editThresh.setText(prefs.getMatchThreshold().toString())

        btnStart.setOnClickListener { onStartClicked() }
        btnCalib.setOnClickListener { onCalibClicked() }

        val filter = IntentFilter(BanditAccessibilityService.ACTION_CALIB_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(calibReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(calibReceiver, filter)
        }
        ensureNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(calibReceiver)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun refreshStatus() {
        if (!isAccessibilityEnabled()) {
            textStatus.text = "状态：请先开启「大秦打盗匪助手」无障碍权限"
            return
        }
        if (!prefs.isCalibrated()) {
            textStatus.text = "状态：未校准（点「① 一键校准」）"
        } else {
            textStatus.text = "状态：已校准，可一键挂机"
        }
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
        if (!prefs.isCalibrated()) {
            Toast.makeText(this, "请先点「① 一键校准」", Toast.LENGTH_LONG).show()
            return
        }
        saveAdvanced()
        launchCapture()
    }

    private fun onCalibClicked() {
        if (!ensureAccessibility()) return
        saveAdvanced()
        pendingCalibrate = true
        launchCapture()
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
    }

    private fun launchCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProj.launch(mpm.createScreenCaptureIntent())
    }
}
