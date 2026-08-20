package org.zzssg.llmchatapp.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.zzssg.llmchatapp.MainActivity
import org.zzssg.llmchatapp.R

/**
 * Keeps generation running while the app is not on screen.
 *
 * Two separate things stop an ordinary app from computing in the background, and
 * both were visible as the same symptom -- a reply that freezes on lock or on
 * task switch and resumes untouched the moment the app comes back:
 *
 *  - Once no activity is visible the process becomes *cached*, and from API 31
 *    the Cached Apps Freezer suspends every one of its threads, native ones
 *    included. A foreground service is the documented way out: a process with
 *    one is never cached.
 *  - With the screen off the CPU itself suspends. A foreground service does not
 *    hold the CPU awake, so a partial wake lock is needed alongside it.
 *
 * The service does no work of its own. It exists purely to hold those two
 * guarantees for as long as a reply is being written, and the notification it is
 * required to post doubles as the way to stop a long reply without unlocking
 * back into the app.
 */
class GenerationService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Reaches the running decode directly: the generation coroutine ends
            // through its normal completion path, which persists the reply.
            nativeStop()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        acquireWakeLock()
        // Not sticky: a reply that died with the process cannot be resumed, and
        // restarting the service without one would leave a notification with
        // nothing behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // A timeout the service will normally never reach. It is insurance:
            // a wake lock leaked by a crash would otherwise drain the battery
            // until the phone is rebooted.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun startForegroundCompat() {
        createChannel()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, GenerationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentText(getString(R.string.generation_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.generation_notification_stop),
                    stop,
                ).build()
            )
            // Progress with no known total: an indeterminate bar says "still
            // working" without pretending to know how long a reply will be.
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Starting a foreground service is refused if the app was already in
            // the background when the request was made. Generation always starts
            // from a tap, so this should not happen -- but losing the reply over
            // it would be worse than losing the background guarantee.
            Log.w(TAG, "could not enter the foreground; generation may be throttled", e)
            stopSelf()
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.generation_channel_name),
                // Low: the notification is a status line and a stop button, not
                // something worth a sound every time a reply starts.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.generation_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "GenerationService"
        private const val CHANNEL_ID = "generation"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "org.zzssg.llmchatapp.STOP_GENERATION"
        private const val WAKE_LOCK_TAG = "LlmChat:generation"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        /** Called as a reply starts. Safe to call when one is already running. */
        fun start(context: Context) {
            val intent = Intent(context, GenerationService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Background start restrictions (API 31+). The reply still runs;
                // it just loses the protection while the app is not on screen.
                Log.w(TAG, "could not start the generation service", e)
            }
        }

        /** Called as a reply ends, however it ended. */
        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }
    }
}
