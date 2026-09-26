package com.kartland.gnsslogger

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Writes one CSV row per UBX-NAV-PVT solution. Column set is deliberately close to the
 * GnssLogger-style logs already used by the existing Python analysis pipeline (traces.py /
 * gate.py — no gate/waypoint columns here, this is the "sans point de passage" raw log), plus a
 * few UBX-specific accuracy fields those tools didn't need for phone-internal GNSS but that are
 * useful now that we control the receiver directly.
 *
 * If the exact column names the pipeline expects differ, this format is close enough that a
 * short pandas rename/adapter should bridge it — see the README's "adaptateur de chargement" note.
 */
class CsvLogger(private val context: Context) {

    companion object {
        private val HEADER = listOf(
            "utc_iso",              // solution UTC time from NAV-PVT (year..sec + nano), ISO-8601
            "phone_elapsed_ns",     // SystemClock.elapsedRealtimeNanos() at the moment this fix was parsed
            "itow_ms",              // receiver time-of-week, ms — best field for relative cadence analysis
            "fix_type",             // 0=none 1=DR 2=2D 3=3D 4=3D+DR 5=time-only
            "fix_ok",               // gnssFixOK flag (0/1)
            "num_sv",
            "lat_deg",
            "lon_deg",
            "height_m",             // ellipsoidal height
            "hmsl_m",               // height above mean sea level
            "h_acc_m",
            "v_acc_m",
            "vel_n_mps",
            "vel_e_mps",
            "vel_d_mps",
            "speed_mps",            // gSpeed, ground speed (2D), Doppler-derived
            "heading_deg",          // headMot
            "speed_acc_mps",        // sAcc, Doppler-derived
            "heading_acc_deg",
            "pdop",
        )
        private val FILENAME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private var writer: BufferedWriter? = null
    var currentFile: File? = null
        private set
    var fixCount: Int = 0
        private set

    /** Starts a new log file under <app external files>/logs/. Returns the created file. */
    fun start(): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()

        val name = "gnss_${FILENAME_FORMAT.format(System.currentTimeMillis())}.csv"
        val file = File(dir, name)
        val w = BufferedWriter(FileWriter(file))
        w.write(HEADER.joinToString(","))
        w.newLine()
        w.flush()

        writer = w
        currentFile = file
        fixCount = 0
        return file
    }

    /** Appends one decoded fix. [phoneElapsedRealtimeNs] should be SystemClock.elapsedRealtimeNanos(). */
    fun appendFix(fix: NavPvt, phoneElapsedRealtimeNs: Long) {
        val w = writer ?: return
        val row = listOf(
            fix.utcIso,
            phoneElapsedRealtimeNs.toString(),
            fix.iTowMs.toString(),
            fix.fixType.toString(),
            if (fix.fixOk) "1" else "0",
            fix.numSV.toString(),
            fix.latDeg.toString(),
            fix.lonDeg.toString(),
            (fix.heightMm / 1000.0).toString(),
            (fix.hMslMm / 1000.0).toString(),
            fix.hAccM.toString(),
            (fix.vAccMm / 1000.0).toString(),
            (fix.velNMmPerS / 1000.0).toString(),
            (fix.velEMmPerS / 1000.0).toString(),
            (fix.velDMmPerS / 1000.0).toString(),
            fix.speedMps.toString(),
            fix.headMotDeg.toString(),
            fix.sAccMps.toString(),
            fix.headAccDeg.toString(),
            fix.pDop.toString(),
        )
        w.write(row.joinToString(","))
        w.newLine()
        fixCount++

        // Flush periodically rather than every row (fsync on every 40ms-period row would be
        // needlessly hard on flash) but often enough that a crash mid-session doesn't lose much.
        if (fixCount % 25 == 0) w.flush()
    }

    /** Forces buffered rows to disk — used before sharing a file mid-session. */
    fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
            // best effort; a failure here doesn't invalidate the session
        }
    }

    /** Flushes and closes the current file. Returns the number of fixes written. */
    fun stop(): Int {
        val count = fixCount
        try {
            writer?.flush()
            writer?.close()
        } finally {
            writer = null
        }
        return count
    }

    val isLogging: Boolean get() = writer != null
}
