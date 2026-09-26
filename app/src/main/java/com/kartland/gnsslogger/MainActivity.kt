package com.kartland.gnsslogger

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kartland.gnsslogger.databinding.ActivityMainBinding
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Single-screen field tool: connect to the external GNSS board over USB, show what it is
 * actually sending, and log every solution to CSV.
 *
 * The screen is built around the first-contact problem rather than around pretty numbers —
 * connection state, detected baud and a raw-traffic window are all visible, because the first
 * time this runs it will be on a balcony with an unknown board and no debugger attached.
 *
 * The USB link and the CSV log themselves live in [GnssLoggingService]; this activity binds to
 * it for its whole lifetime and is only the UI on top.
 */
class MainActivity : AppCompatActivity(), GnssListener {

    private lateinit var binding: ActivityMainBinding

    /** Null until the bind completes (it is asynchronous), and again after unbind. */
    private var service: GnssLoggingService? = null

    private var lastFixRealtimeNs = 0L
    private var fixIntervalEmaMs = 0.0
    private val rawLines = ArrayDeque<String>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as GnssLoggingService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            // A recording may already be running (activity recreated mid-session): pick its
            // state up rather than assuming a fresh start.
            syncWithService(s)
            // Covers both "app launched by plugging the dongle in" and "dongle already plugged in".
            s.connect()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Same process, so this only happens if the service crashed.
            service = null
        }
    }

    /**
     * Android 13+ notification permission. Whatever the answer, logging goes ahead — without it
     * the foreground service still runs, its notification is just not shown.
     */
    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startLogging() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartStop.setOnClickListener { onStartStopClicked() }
        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnReconnect.setOnClickListener {
            appendRaw("— reconnexion demandée —")
            service?.reconnect()
        }

        renderState(GnssState.DISCONNECTED, null)

        // Bound for the activity's whole lifetime (not onStart/onStop), so the USB connection
        // keeps the same scope it had when the activity owned it: a notification shade or a
        // brief screen-off doesn't drop the link even when not recording.
        bindService(Intent(this, GnssLoggingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        // On first launch the bind hasn't completed yet; onServiceConnected handles that case.
        service?.connect()
    }

    override fun onDestroy() {
        // An active recording is not stopped here: the service is started in the foreground for
        // the session and outlives this unbind. Only when idle does unbinding end the service
        // (and with it the USB connection), as before.
        service?.listener = null
        service = null
        unbindService(serviceConnection)
        super.onDestroy()
    }

    // ---- Actions ---------------------------------------------------------------------------

    private fun onStartStopClicked() {
        if (service?.isLogging == true) {
            confirmStop()
        } else {
            requestNotificationPermissionThenStart()
        }
    }

    private fun requestNotificationPermissionThenStart() {
        if (service?.isConnected != true) {
            // Don't bother with the permission prompt if we can't record anyway.
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Once permanently denied, the system answers immediately without showing anything.
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startLogging()
        }
    }

    private fun startLogging() {
        val s = service
        // Re-checked: the link may have dropped while the permission dialog was up.
        if (s == null || !s.isConnected) {
            Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        if (s.isLogging) return
        val file = s.startLogging()
        showRecordingUi(file, s.recordingStartElapsedMs)
        Toast.makeText(this, getString(R.string.toast_log_started, file.name), Toast.LENGTH_SHORT).show()
    }

    /**
     * A confirmation gate before actually stopping: the phone spends most of a session in a
     * pocket or on a mount, and a stray touch on this button mid-run would otherwise silently
     * end the log with no way to resume it and waste the whole karting session. Starting has
     * no such gate — only stopping is the hard-to-undo action.
     */
    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_stop_title)
            .setMessage(R.string.dialog_stop_message)
            .setPositiveButton(R.string.dialog_stop_confirm) { _, _ -> stopLogging() }
            .setNegativeButton(R.string.dialog_stop_cancel, null)
            .show()
    }

    private fun stopLogging() {
        val s = service ?: return
        val count = s.stopLogging()
        showIdleUi()
        Toast.makeText(this, getString(R.string.toast_log_stopped, count), Toast.LENGTH_SHORT).show()
    }

    /** Brings the whole screen in line with the service, e.g. after a rebind mid-session. */
    private fun syncWithService(s: GnssLoggingService) {
        renderState(s.state, null)
        val file = s.currentFile
        if (s.isLogging && file != null) {
            showRecordingUi(file, s.recordingStartElapsedMs)
        } else {
            showIdleUi()
        }
        file?.let { binding.textLogFile.text = it.absolutePath }
        binding.textFixesLogged.text = "${getString(R.string.label_fixes_logged)} : ${s.fixCount}"
    }

    private fun showRecordingUi(file: File, startElapsedMs: Long) {
        binding.btnStartStop.text = getString(R.string.btn_stop)
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.kart_red))
        binding.textLogFile.text = file.absolutePath
        // Screen stays on as long as possible while logging. The foreground service is the
        // safety net if it goes off anyway (power button in a pocket, app backgrounded).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startTimer(startElapsedMs)
    }

    private fun showIdleUi() {
        binding.btnStartStop.text = getString(R.string.btn_start)
        binding.btnStartStop.backgroundTintList =
            android.content.res.ColorStateList.valueOf(getColor(R.color.kart_green))
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopTimer()
    }

    /** [startElapsedMs] comes from the service, so a recreated activity resumes the count. */
    private fun startTimer(startElapsedMs: Long) {
        binding.chronometerRecording.base = startElapsedMs
        binding.chronometerRecording.start()
        binding.timerContainer.visibility = View.VISIBLE
    }

    private fun stopTimer() {
        binding.chronometerRecording.stop()
        binding.timerContainer.visibility = View.GONE
    }

    private fun shareLog() {
        val s = service
        val file = s?.currentFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.toast_no_log_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        s.flush() // so sharing mid-session includes everything written so far

        val uri: Uri = FileProvider.getUriForFile(this, "com.kartland.gnsslogger.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)))
    }

    // ---- GnssListener (forwarded by the service) ------------------------------------------

    override fun onStateChanged(state: GnssState, detail: String?) {
        runOnUiThread { renderState(state, detail) }
    }

    override fun onRawPreview(preview: String) {
        runOnUiThread { appendRaw(preview) }
    }

    override fun onError(message: String) {
        runOnUiThread {
            appendRaw("⚠ $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onNavPvt(fix: NavPvt) {
        // CSV writing already happened in the service; this is display only.
        val nowNs = SystemClock.elapsedRealtimeNanos()

        if (lastFixRealtimeNs != 0L) {
            val intervalMs = (nowNs - lastFixRealtimeNs) / 1_000_000.0
            fixIntervalEmaMs =
                if (fixIntervalEmaMs == 0.0) intervalMs else 0.8 * fixIntervalEmaMs + 0.2 * intervalMs
        }
        lastFixRealtimeNs = nowNs

        runOnUiThread {
            binding.textFixType.text =
                "${fix.fixTypeLabel}${if (fix.fixOk) "" else " (non valide)"}"
            binding.textNumSv.text = fix.numSV.toString()
            binding.textHAcc.text = String.format(Locale.US, "%.2f m", fix.hAccM)
            binding.textSpeed.text =
                String.format(Locale.US, "%.2f km/h (±%.2f m/s)", fix.speedKmh, fix.sAccMps)
            binding.textRate.text = if (fixIntervalEmaMs > 0) {
                String.format(Locale.US, "%.1f Hz (%.0f ms)", 1000.0 / fixIntervalEmaMs, fixIntervalEmaMs)
            } else {
                "—"
            }
            binding.textPosition.text =
                String.format(Locale.US, "%.6f, %.6f", fix.latDeg, fix.lonDeg)
            binding.textFixesLogged.text =
                "${getString(R.string.label_fixes_logged)} : ${service?.fixCount ?: 0}"
        }
    }

    // ---- Rendering -------------------------------------------------------------------------

    private fun renderState(state: GnssState, detail: String?) {
        val (label, color) = when (state) {
            GnssState.DISCONNECTED -> getString(R.string.state_disconnected) to R.color.kart_red
            GnssState.SEARCHING -> getString(R.string.state_searching) to R.color.kart_amber
            GnssState.DETECTING_BAUD -> getString(R.string.state_detecting) to R.color.kart_amber
            GnssState.CONFIGURING -> getString(R.string.state_configuring) to R.color.kart_amber
            GnssState.STREAMING -> getString(R.string.state_streaming) to R.color.kart_green
            GnssState.ERROR -> getString(R.string.state_error) to R.color.kart_red
        }
        binding.textState.text = label
        binding.textState.setTextColor(getColor(color))
        val s = service
        binding.textDeviceName.text = s?.deviceLabel ?: ""

        binding.textStateDetail.text = when {
            detail != null -> detail
            state == GnssState.STREAMING && s != null && s.activeBaud > 0 ->
                String.format(
                    Locale.US,
                    "%d bauds — cadence soutenable ~%.0f Hz",
                    s.activeBaud, s.achievableRateHz
                )
            else -> ""
        }
    }

    /** Keeps a short rolling window of raw traffic / events — the field debugging aid. */
    private fun appendRaw(line: String) {
        rawLines.addLast(line)
        while (rawLines.size > MAX_RAW_LINES) rawLines.removeFirst()
        binding.textRawLog.text = rawLines.joinToString("\n")
    }

    companion object {
        private const val MAX_RAW_LINES = 8
    }
}
