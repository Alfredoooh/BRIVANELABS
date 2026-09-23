// app/src/main/java/com/brivanelabs/notes/bridge/NotesBridge.kt
package com.brivanelabs.notes.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.brivanelabs.notes.MainActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class NotesBridge(
    private val context: Context,
    private val webView: WebView
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("brivane_prefs", Context.MODE_PRIVATE)
    }
    private val lockPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("brivane_locks", Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun isInsideApp(): Boolean = true

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
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarm.canScheduleExactAlarms()
    }

    @JavascriptInterface
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is MainActivity) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1201)
            }
        }
    }

    @JavascriptInterface
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun isSystemDarkMode(): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * lightIcons=true significa "o fundo da app está claro" -> os ícones da
     * status bar devem ficar escuros para se lerem. É isto que corrige o bug:
     * o JS chama isto sempre que aplica o tema (claro/escuro/automático) e a
     * Activity deixa de o sobrepor sozinha no onResume.
     */
    @JavascriptInterface
    fun setStatusBarTheme(lightIcons: Boolean) {
        if (context is MainActivity) {
            webView.post { context.setStatusBarLight(lightIcons) }
        }
    }

    @JavascriptInterface
    fun getPreferences(): String {
        val out = JSONObject()
        prefs.getString("theme", null)?.let { out.put("theme", it) }
        prefs.getString("lang", null)?.let { out.put("lang", it) }
        return out.toString()
    }

    @JavascriptInterface
    fun setPreference(key: String, value: String) {
        if (key != "theme" && key != "lang") return
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun saveFile(name: String, content: String) {
        val dir = File(context.getExternalFilesDir(null), "Brivane Notes").apply { mkdirs() }
        File(dir, sanitizeFileName(name)).writeText(content)
    }

    /**
     * format: "image" | "pdf" | "txt"
     * base64PngOrNull: para "image" e "pdf", o PNG completo (altura real da
     * nota, sem cortes) gerado no lado JS via canvas.
     * Para "pdf" o bitmap é automaticamente paginado em folhas A4 dentro de
     * createPaginatedA4Pdf, preservando todo o conteúdo em várias páginas.
     */
    @JavascriptInterface
    fun shareTo(app: String, format: String, title: String, text: String, base64PngOrNull: String?) {
        when (format) {
            "image" -> {
                val bitmap = base64PngOrNull?.let { decodeBase64ToBitmap(it) } ?: return
                val file = writeSharedFile("nota_${System.currentTimeMillis()}.png") { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: return
                bitmap.recycle()
                sendShareIntent(app, title, text, file, "image/png")
            }
            "pdf" -> {
                val bitmap = base64PngOrNull?.let { decodeBase64ToBitmap(it) } ?: return
                val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                val file = File(dir, "nota_${System.currentTimeMillis()}.pdf")
                val activity = context as? MainActivity
                val ok = activity?.createPaginatedA4Pdf(bitmap, file.absolutePath) ?: false
                bitmap.recycle()
                if (!ok) return
                sendShareIntent(app, title, text, file, "application/pdf")
            }
            "txt" -> {
                val file = writeSharedFile("nota_${System.currentTimeMillis()}.txt") { out ->
                    out.write(text.toByteArray())
                } ?: return
                sendShareIntent(app, title, text, file, "text/plain")
            }
        }
    }

    private fun writeSharedFile(fileName: String, writer: (FileOutputStream) -> Unit): File? {
        return try {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { writer(it) }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun sendShareIntent(app: String, title: String, text: String, file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            packageForApp(app)?.let { setPackage(it) }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent.createChooser(intent.apply { setPackage(null) }, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }

    private fun packageForApp(app: String): String? = when (app) {
        "whatsapp" -> "com.whatsapp"
        "telegram" -> "org.telegram.messenger"
        "facebook" -> "com.facebook.katana"
        "x" -> "com.twitter.android"
        else -> null
    }

    private fun decodeBase64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]+"), "").trim().ifBlank { "nota" }
    }

    @JavascriptInterface
    fun setNotePin(noteId: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        lockPrefs.edit()
            .putString("pin_$noteId", Base64.encodeToString(hash, Base64.NO_WRAP))
            .putString("salt_$noteId", Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
    }

    @JavascriptInterface
    fun removeNotePin(noteId: String) {
        lockPrefs.edit().remove("pin_$noteId").remove("salt_$noteId").apply()
    }

    @JavascriptInterface
    fun hasNotePin(noteId: String): Boolean {
        return lockPrefs.contains("pin_$noteId")
    }

    @JavascriptInterface
    fun verifyNotePin(noteId: String, pin: String): Boolean {
        val storedHash = lockPrefs.getString("pin_$noteId", null) ?: return false
        val storedSalt = lockPrefs.getString("salt_$noteId", null) ?: return false
        val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
        val hash = hashPin(pin, salt)
        return Base64.encodeToString(hash, Base64.NO_WRAP) == storedHash
    }

    @JavascriptInterface
    fun isBiometricAvailable(): Boolean {
        return context is MainActivity && context.isBiometricAvailable()
    }

    @JavascriptInterface
    fun requestBiometricUnlock(noteId: String) {
        if (context is MainActivity) {
            webView.post { context.requestBiometricUnlock(noteId) }
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        fun openNoteInWebView(webView: WebView, noteId: String) {
            val js = "window.openNoteFromNative && window.openNoteFromNative(${JSONObjectEscaper.quote(noteId)})"
            webView.post { webView.evaluateJavascript(js, null) }
        }
        fun notifyBiometricResult(webView: WebView, noteId: String, success: Boolean) {
            val js = "window.onBiometricResult && window.onBiometricResult(${JSONObjectEscaper.quote(noteId)}, $success)"
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