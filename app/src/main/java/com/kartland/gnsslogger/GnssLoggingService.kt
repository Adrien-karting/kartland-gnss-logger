package com.kartland.gnsslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Owns the USB link and the CSV log, so that a recording no longer depends on the activity
 * being alive or on screen.
 *
 * Two lifecycles overlap here on purpose:
 * - **Bound** by MainActivity for as long as it exists. That is what keeps the USB connection
 *   up while the module is merely plugged in — same scope as before, when the activity owned it.
 * - **Started + foreground** only between startLogging() and stopLogging(). That is the part
 *   that survives a screen-off in a pocket, the app being backgrounded, or the activity being
 *   destroyed mid-session: Android does not suspend or kill a foreground service's worker thread
 *   the way it may for a background app.
 *
 * The activity still holds FLAG_KEEP_SCREEN_ON while logging; this service is the safety net for
 * when the screen goes off anyway, not a replacement for it.
 */
class GnssLoggingService : Service(), GnssListener {

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Notification refresh period — the point count doesn't need 25Hz updates. */
        private const val NOTIFICATION_UPDATE_MS = 5000L
    }

    inner class LocalBinder : Binder() {
        val service: GnssLoggingService get() = this@GnssLoggingService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var gnss: UsbGnssManager
    private val csvLogger by lazy { CsvLogger(this) }

    /**
     * start/stop run on the main thread, appendFix on the USB worker thread: without this lock,
     * a stop landing mid-row would write to a closed stream and surface as a bogus USB error.
     */
    private val csvLock = Any()

    /** UI-side listener, attached while an activity is bound. Everything is forwarded to it. */
    @Volatile
    var listener: GnssListener? = null

    /**
     * SystemClock.elapsedRealtime() at the moment logging started — the reference the activity's
     * Chronometer resyncs to after being recreated, instead of restarting from zero.
     */
    var recordingStartElapsedMs: Long = 0L
        private set

    override fun onCreate() {
        super.onCreate()
        gnss = UsbGnssManager(applicationContext, measRateMs = 40) // 40ms = 25Hz target
        gnss.listener = this
        gnss.registerReceivers()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only ever started through startForegroundService(), so startForeground() must come
        // first and unconditionally: the system kills the app if it doesn't arrive within a few
        // seconds, even if we are about to stop again.
        if (!enterForeground()) {
            // Logging carries on as a plain bound service (the activity still holds the screen
            // on); stopping the started state now avoids the "did not call startForeground" crash.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isLogging) {
            mainHandler.removeCallbacks(notificationUpdater)
            mainHandler.postDelayed(notificationUpdater, NOTIFICATION_UPDATE_MS)
        } else {
            // stopLogging() won the race against this start command — nothing to keep alive.
            leaveForeground()
            stopSelf(startId)
        }
        // Not sticky: a restart by the system would have no USB permission context and no file
        // to append to — better no session than a silently empty one.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(csvLock) {
            if (csvLogger.isLogging) csvLogger.stop()
        }
        gnss.listener = null
        gnss.disconnect()
        gnss.unregisterReceivers()
        super.onDestroy()
    }

    // ---- API for the bound activity --------------------------------------------------------

    val state: GnssState get() = gnss.state
    val isConnected: Boolean get() = gnss.isConnected
    val deviceLabel: String get() = gnss.deviceLabel
    val activeBaud: Int get() = gnss.activeBaud
    val achievableRateHz: Double get() = gnss.achievableRateHz

    val isLogging: Boolean get() = synchronized(csvLock) { csvLogger.isLogging }
    val fixCount: Int get() = csvLogger.fixCount
    val currentFile: File? get() = csvLogger.currentFile

    fun connect() = gnss.connect()

    fun reconnect() {
        gnss.disconnect()
        gnss.connect()
    }

    /**
     * Opens a new CSV file and promotes the service to foreground for the duration of the
     * session. Caller is expected to have checked [isConnected].
     */
    fun startLogging(): File {
        val file = synchronized(csvLock) { csvLogger.start() }
        recordingStartElapsedMs = SystemClock.elapsedRealtime()

        try {
            ContextCompat.startForegroundService(
                this, Intent(this, GnssLoggingService::class.java)
            )
        } catch (e: IllegalStateException) {
            // Background-start restrictions (ForegroundServiceStartNotAllowedException extends
            // this). Shouldn't happen from a button tap, but never worth losing the session over.
            listener?.onError(getString(R.string.error_foreground_unavailable))
        }
        return file
    }

    /** Finalises the CSV file and drops back to a bound-only service. Returns fixes written. */
    fun stopLogging(): Int {
        mainHandler.removeCallbacks(notificationUpdater)
        val count = synchronized(csvLock) { csvLogger.stop() }
        leaveForeground()
        // Still bound by the activity, so this only ends the started/foreground state — the USB
        // connection stays up until the activity itself goes away.
        stopSelf()
        return count
    }

    fun flush() {
        synchronized(csvLock) { csvLogger.flush() }
    }

    // ---- GnssListener ----------------------------------------------------------------------

    override fun onStateChanged(state: GnssState, detail: String?) {
        listener?.onStateChanged(state, detail)
    }

    override fun onRawPreview(preview: String) {
        listener?.onRawPreview(preview)
    }

    override fun onError(message: String) {
        listener?.onError(message)
    }

    override fun onNavPvt(fix: NavPvt) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        synchronized(csvLock) {
            if (csvLogger.isLogging) csvLogger.appendFix(fix, nowNs)
        }
        listener?.onNavPvt(fix)
    }

    // ---- Foreground / notification ---------------------------------------------------------

    private fun enterForeground(): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: Exception) {
            // Android 14 refuses a connectedDevice service without a matching prerequisite (here,
            // the USB permission). Logging still works in the foreground app; just say so.
            listener?.onError(getString(R.string.error_foreground_unavailable))
            false
        }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // In case a periodic refresh re-posted it after the foreground state ended.
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (!isLogging) return
            // Without POST_NOTIFICATIONS this is silently dropped by the system — no crash.
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_MS)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW, // persistent but silent
        ).apply {
            description = getString(R.string.notif_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // The notification's chronometer counts in wall-clock time; derive it from the
        // elapsedRealtime reference so it matches the in-app timer exactly.
        val startWallClockMs =
            System.currentTimeMillis() - (SystemClock.elapsedRealtime() - recordingStartElapsedMs)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, csvLogger.fixCount))
            .setWhen(startWallClockMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .build()
    }
}
