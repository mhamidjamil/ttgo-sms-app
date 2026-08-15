package com.spotwire.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spotwire.app.MainActivity
import com.spotwire.app.R

/**
 * Tells the person when an alert did not get through.
 *
 * An arrival alert is sent while nobody is looking at the phone, so a failure
 * that only reaches the log is a failure they find out about by not being
 * alerted, which is exactly the thing this app exists to prevent. The monitoring
 * notification cannot carry this: it is silent by design and it is already
 * saying something else.
 */
object DeliveryNotifier {

    private const val TAG = "DeliveryNotifier"
    private const val CHANNEL_ID = "delivery_problems"
    // 1001 is the ongoing monitoring notification. These sit above it, one per
    // place, so two places failing do not overwrite each other.
    private const val BASE_ID = 2000

    fun notifyArrivalUndelivered(
        context: Context,
        placeLabel: String,
        failedCount: Int,
        reason: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        ensureChannel(manager)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val people = if (failedCount == 1) "1 person" else "$failedCount people"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Arrival alert not delivered")
            .setContentText("$placeLabel: $people were not reached. Send it yourself for now.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$placeLabel: $people were not reached. $reason\n\n" +
                        "Open History to try again, or message them yourself for now."
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()

        runCatching { manager.notify(BASE_ID + placeLabel.hashCode().and(0xFF), notification) }
            .onFailure { Log.w(TAG, "could not post the delivery warning: ${it.message}") }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Delivery problems",
                // Deliberately louder than the monitoring channel: this one is
                // worth interrupting for, because somebody was expecting a
                // message that never came.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "When an arrival alert could not be delivered" }
        )
    }
}
