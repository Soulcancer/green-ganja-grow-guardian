package de.greenganja.growguardian.grow.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Lokale Benachrichtigungen für Alarme, eine je Box. Ab Android 13 braucht die App dafür die
 * Berechtigung POST_NOTIFICATIONS; ohne sie wird still nichts angezeigt.
 */
class GrowNotifier(context: Context) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /**
     * Einfarbiges Icon für die Statusleiste: `res/drawable/ic_stat_grow.xml` (weißes Blatt auf transparent).
     * Fehlt es, nimmt die App ihr Launcher-Icon, das auf manchen Geräten als weißes Quadrat erscheint.
     */
    private val smallIcon: Int
        get() = context.resources.getIdentifier("ic_stat_grow", "drawable", context.packageName)
            .takeIf { it != 0 } ?: context.applicationInfo.icon

    val canNotify: Boolean get() = manager.areNotificationsEnabled()

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Grow-Alarme")
                .setDescription("Wenn ein Messwert aus dem Zielbereich läuft oder ein Sensor offline ist")
                .build(),
        )
    }

    fun show(alert: BoxAlert) {
        if (!canNotify) return
        ensureChannel()
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BOX_ID, alert.boxId)
        }
        val pending = open?.let {
            PendingIntent.getActivity(context, alert.boxId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(alert.boxName)
        alert.lines.forEach { style.addLine(it) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(alert.boxName)
            .setContentText(alert.lines.first() + if (alert.lines.size > 1) " (+${alert.lines.size - 1})" else "")
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            manager.notify(notificationId(alert.boxId), notification)
        } catch (_: SecurityException) {
            // Berechtigung inzwischen entzogen
        }
    }

    fun cancel(boxId: String) = manager.cancel(notificationId(boxId))

    private fun notificationId(boxId: String) = 0x6700 + (boxId.hashCode() and 0x3FFF)

    companion object {
        const val CHANNEL_ID = "grow_alarms"

        /** Hängt an dem Intent, mit dem die App aus einer Benachrichtigung startet. */
        const val EXTRA_BOX_ID = "growBoxId"
    }
}
