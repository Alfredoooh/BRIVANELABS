package com.brivanelabs.notes.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.brivanelabs.notes.MainActivity
import com.brivanelabs.notes.R

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getStringExtra("note_id") ?: return
        val title = intent.getStringExtra("note_title") ?: "Brivane Notes"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Lembretes", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_note_id", noteId)
        }
        val pending = PendingIntent.getActivity(context, noteId.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle("Lembrete")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(noteId.hashCode(), notification)
    }
    private fun immutableFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    companion object { const val CHANNEL_ID = "brivane_notes_reminders" }
}
