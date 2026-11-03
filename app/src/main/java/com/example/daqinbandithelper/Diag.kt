package com.example.daqinbandithelper

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内诊断日志。
 * 现场排查时没有 logcat 也能看到真实错误：MainActivity 把它渲染到界面，
 * 用户截一张图即可回传。服务/Activity 崩溃时也能留下记录（静态对象不随实例销毁）。
 */
object Diag {

    private const val MAX = 80
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(tag: String, msg: String) {
        val t = fmt.format(Date())
        lines.add("$t [$tag] $msg")
        while (lines.size > MAX) lines.removeAt(0)
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "(暂无日志)" else lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
