package com.brivanelabs.notes.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.brivanelabs.notes.MainActivity

class NotesBridge(
    private val context: Context,
    private val webView: WebView
) {
    @JavascriptInterface
    fun scheduleReminder(noteId: String, title: String, triggerAt: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("note_id", noteId)
            putExtra("note_title", title)
        }
        val requestCode = noteId.hashCode()
        val pending = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    @JavascriptInterface
    fun cancelReminder(noteId: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, noteId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        alarm.cancel(pending)
        pending.cancel()
    }

    @JavascriptInterface
    fun openAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @JavascriptInterface
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is MainActivity) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
            }
        }
    }

    companion object {
        private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        fun openNoteInWebView(webView: WebView, noteId: String) {
            val js = "window.openNativeNote && window.openNativeNote(${JSONObjectEscaper.quote(noteId)})"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    private object JSONObjectEscaper {
        fun quote(value: String): String {
            return buildString {
                append('"')
                value.forEach { c ->
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(c)
                    }
                }
                append('"')
            }
        }
    }
}
