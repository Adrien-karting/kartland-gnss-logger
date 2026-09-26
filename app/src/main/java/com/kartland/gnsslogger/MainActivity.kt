package com.kartland.gnsslogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.kartland.gnsslogger.databinding.ActivityMainBinding
import java.util.ArrayDeque
import java.util.Locale

/**
 * Single-screen field tool: connect to the external GNSS board over USB, show what it is
 * actually sending, and log every solution to CSV.
 *
 * The screen is built around the first-contact problem rather than around pretty numbers —
 * connection state, detected baud and a raw-traffic window are all visible, because the first
 * time this runs it will be on a balcony with an unknown board and no debugger attached.
 */
class MainActivity : AppCompatActivity(), GnssListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gnss: UsbGnssManager
    private val csvLogger by lazy { CsvLogger(this) }

    private var lastFixRealtimeNs = 0L
    private var fixIntervalEmaMs = 0.0
    private val rawLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        gnss = UsbGnssManager(applicationContext, measRateMs = 40) // 40ms = 25Hz target
        gnss.listener = this

        binding.btnStartStop.setOnClickListener { toggleLogging() }
        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnReconnect.setOnClickListener {
            appendRaw("— reconnexion demandée —")
            gnss.disconnect()
            gnss.connect()
        }

        renderState(GnssState.DISCONNECTED, null)
    }

    override fun onStart() {
        super.onStart()
        gnss.registerReceivers()
        // Covers both "app launched by plugging the dongle in" and "dongle already plugged in".
        gnss.connect()
    }

    override fun onDestroy() {
        // Deliberately not in onStop(): a notification shade or a brief screen-off shouldn't
        // kill a running session. While logging we also hold the screen on (see toggleLogging),
        // which is what keeps the activity alive during a real test.
        if (csvLogger.isLogging) csvLogger.stop()
        gnss.disconnect()
        gnss.unregisterReceivers()
        super.onDestroy()
    }

    // ---- Actions ---------------------------------------------------------------------------

    private fun toggleLogging() {
        if (csvLogger.isLogging) {
            val count = csvLogger.stop()
            binding.btnStartStop.text = getString(R.string.btn_start)
            binding.btnStartStop.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.kart_green))
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, getString(R.string.toast_log_stopped, count), Toast.LENGTH_SHORT).show()
        } else {
            if (!gnss.isConnected) {
                Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_LONG).show()
                return
            }
            val file = csvLogger.start()
            binding.btnStartStop.text = getString(R.string.btn_stop)
            binding.btnStartStop.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.kart_red))
            binding.textLogFile.text = file.absolutePath
            // A logging session must survive the screen timeout — no foreground service yet.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, getString(R.string.toast_log_started, file.name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLog() {
        val file = csvLogger.currentFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.toast_no_log_to_share, Toast.LENGTH_SHORT).show()
            return
        }
        csvLogger.flush() // so sharing mid-session includes everything written so far

        val uri: Uri = FileProvider.getUriForFile(this, "com.kartland.gnsslogger.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)))
    }

    // ---- GnssListener ----------------------------------------------------------------------

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
        val nowNs = SystemClock.elapsedRealtimeNanos()

        if (csvLogger.isLogging) csvLogger.appendFix(fix, nowNs)

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
                "${getString(R.string.label_fixes_logged)} : ${csvLogger.fixCount}"
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
        binding.textDeviceName.text = gnss.deviceLabel

        binding.textStateDetail.text = when {
            detail != null -> detail
            state == GnssState.STREAMING && gnss.activeBaud > 0 ->
                String.format(
                    Locale.US,
                    "%d bauds — cadence soutenable ~%.0f Hz",
                    gnss.activeBaud, gnss.achievableRateHz
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
