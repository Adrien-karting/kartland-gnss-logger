package com.kartland.gnsslogger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.concurrent.thread

/** Where we are in the connect → detect → configure → stream sequence. */
enum class GnssState {
    DISCONNECTED,
    SEARCHING,      // looking for a USB-serial device / waiting for permission
    DETECTING_BAUD, // trying candidate speeds until the data makes sense
    CONFIGURING,    // sending CFG-PRT / CFG-MSG / CFG-RATE
    STREAMING,      // NAV-PVT arriving
    ERROR,
}

interface GnssListener {
    fun onStateChanged(state: GnssState, detail: String?)
    fun onNavPvt(fix: NavPvt)
    /** Human-readable preview of raw traffic, for the diagnostic window. */
    fun onRawPreview(preview: String)
    fun onError(message: String)
}

/**
 * Owns the USB-serial link to the external GNSS board.
 *
 * The awkward part this class exists to solve is that we are talking to a board whose factory
 * settings we do not know. A generic NEO-M9N breakout ships at whatever baud its maker chose
 * (9600 and 38400 are both common), speaking NMEA, at 1Hz. Rather than hard-coding a guess and
 * failing silently on a bench with no debugger, the connect sequence *discovers* the link:
 * it sweeps candidate speeds until the bytes parse as real sentences or real UBX frames, then
 * reconfigures the receiver from there.
 *
 * All of it runs on a worker thread — the reads block, and the detection sweep takes seconds.
 */
class UsbGnssManager(
    private val context: Context,
    /** Solution interval in ms requested from the receiver — 40ms = 25Hz target. */
    private val measRateMs: Int = 40,
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.kartland.gnsslogger.USB_PERMISSION"

        /**
         * Ordered by likelihood for a u-blox board: 38400 is the common factory default on M8/M9
         * breakouts, 9600 the classic legacy default, 115200 what a vendor may have preset (and
         * what we will switch to anyway).
         */
        private val CANDIDATE_BAUDS = intArrayOf(38400, 9600, 115200, 57600, 230400, 4800)

        /** Speed we move the receiver to when its default is too slow for the target rate. */
        private const val TARGET_BAUD = 115200

        private const val DETECT_WINDOW_MS = 1200L
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 500
        private const val READ_BUFFER_SIZE = 4096
        private const val CONFIG_SETTLE_MS = 150L
        private const val BAUD_SWITCH_SETTLE_MS = 300L
        private const val VERIFY_WINDOW_MS = 1500L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val parser = UbxFrameParser()
    private val nmea = NmeaSniffer()

    private var port: UsbSerialPort? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var receiverRegistered = false

    var listener: GnssListener? = null

    // These are written on the worker thread and read on the UI thread, hence @Volatile.
    @Volatile
    var state: GnssState = GnssState.DISCONNECTED
        private set

    /** USB device description (chip / VID / PID), shown in the UI and needed for device_filter.xml. */
    @Volatile
    var deviceLabel: String = ""
        private set

    /** Baud finally in use on the link, once detection (and any switch) has settled. */
    @Volatile
    var activeBaud: Int = 0
        private set

    /** Rate the link can actually sustain at [activeBaud] — may be below the 25Hz target. */
    @Volatile
    var achievableRateHz: Double = 0.0
        private set

    private fun setState(newState: GnssState, detail: String? = null) {
        state = newState
        listener?.onStateChanged(newState, detail)
    }

    // ---- USB plumbing ----------------------------------------------------------------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        startWorkerFor(device)
                    } else {
                        setState(GnssState.ERROR, "Permission USB refusée")
                        listener?.onError("Permission USB refusée pour ${device?.deviceName ?: "le périphérique"}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null && device == port?.driver?.device) disconnect()
                }
            }
        }
    }

    fun registerReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    fun unregisterReceivers() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        receiverRegistered = false
    }

    /** Finds an attached USB-serial device, asks for permission if needed, and connects. */
    fun connect() {
        if (running) return
        setState(GnssState.SEARCHING)

        val drivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            setState(GnssState.DISCONNECTED, "aucun périphérique")
            listener?.onError("Aucun périphérique USB série détecté. Vérifiez le câble USB-C OTG et l'adaptateur.")
            return
        }

        val device = drivers[0].device
        if (usbManager.hasPermission(device)) {
            startWorkerFor(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun startWorkerFor(device: UsbDevice) {
        if (running) return
        running = true
        worker = thread(name = "gnss-usb") {
            try {
                if (openPort(device)) {
                    val baud = detectBaud()
                    if (baud == null) {
                        // Guarded: detection also returns null on a normal disconnect, and after
                        // a read error that already reported itself — don't stack a second message.
                        if (running) {
                            fail(
                                "Aucune donnée exploitable à aucune vitesse testée. Vérifiez le câblage " +
                                    "(TX/RX croisés ?), l'alimentation 5V et la soudure des 4 fils."
                            )
                        }
                        return@thread
                    }
                    val finalBaud = raiseBaudIfNeeded(baud)
                    activeBaud = finalBaud
                    achievableRateHz = UbxProtocol.sustainableRateHz(finalBaud)
                    configureReceiver()
                    streamLoop()
                }
            } catch (e: Exception) {
                if (running) fail("Erreur USB : ${e.message}")
            } finally {
                closePortQuietly()
                running = false
            }
        }
    }

    private fun fail(message: String) {
        setState(GnssState.ERROR, message)
        listener?.onError(message)
        running = false
    }

    private fun openPort(device: UsbDevice): Boolean {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            fail("Périphérique non reconnu comme port série.")
            return false
        }
        if (driver.ports.isEmpty()) {
            fail("Le pilote USB série n'expose aucun port.")
            return false
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            fail("Impossible d'ouvrir le périphérique USB (permission ?).")
            return false
        }
        val serialPort = driver.ports[0]
        return try {
            serialPort.open(connection)
            port = serialPort
            // VID/PID in hex is exactly what device_filter.xml needs, so surface it rather than
            // making someone dig it out of Android's USB dialog later.
            deviceLabel = "%s — VID 0x%04X / PID 0x%04X".format(
                driver.javaClass.simpleName.removeSuffix("SerialDriver"),
                device.vendorId,
                device.productId,
            )
            listener?.onRawPreview("USB : $deviceLabel")
            true
        } catch (e: Exception) {
            fail("Échec d'ouverture du port série : ${e.message}")
            false
        }
    }

    // ---- Baud detection --------------------------------------------------------------------

    /**
     * Sweeps [CANDIDATE_BAUDS] and returns the first speed at which the incoming bytes actually
     * parse — either as checksum-valid NMEA sentences or as checksum-valid UBX frames. Wrong
     * speeds yield framing noise, which fails both tests, so this is a reliable discriminator.
     */
    private fun detectBaud(): Int? {
        val serialPort = port ?: return null
        val buffer = ByteArray(READ_BUFFER_SIZE)

        for (baud in CANDIDATE_BAUDS) {
            if (!running) return null
            setState(GnssState.DETECTING_BAUD, "test à $baud bauds…")

            try {
                serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (_: Exception) {
                continue // this adapter refuses that speed; try the next
            }

            drain(serialPort, buffer)
            parser.reset()
            nmea.reset()
            // Ask for a UBX reply rather than only listening: a receiver left UBX-only with no
            // periodic message enabled (e.g. by an interrupted earlier session) sends nothing
            // unprompted. Sent twice to meet the 2-frame threshold; at a wrong speed the
            // receiver just sees noise and ignores it.
            sendPoll(serialPort)
            sendPoll(serialPort)

            val deadline = System.currentTimeMillis() + DETECT_WINDOW_MS
            var bytesSeen = 0
            while (running && System.currentTimeMillis() < deadline) {
                val n = readQuietly(serialPort, buffer)
                if (n > 0) {
                    bytesSeen += n
                    nmea.feed(buffer, n)
                    parser.feed(buffer, n)
                    if (nmea.validSentences >= 2 || parser.validFrames >= 2) {
                        val proto = if (parser.validFrames >= 2) "UBX" else "NMEA"
                        setState(GnssState.DETECTING_BAUD, "$baud bauds — $proto détecté")
                        nmea.lastSentence?.let { listener?.onRawPreview(it) }
                        return baud
                    }
                }
            }
            if (bytesSeen > 0) {
                // Bytes arrive but parse as nothing: almost always the wrong speed.
                listener?.onRawPreview("$baud bauds : $bytesSeen octets illisibles (mauvaise vitesse ?)")
            }
        }
        return null
    }

    /**
     * If the discovered speed cannot carry the target rate, pushes the receiver to
     * [TARGET_BAUD] with CFG-PRT and follows it there. Falls back to the original speed (and a
     * correspondingly lower rate) if the receiver does not come back at the new setting, so a
     * board that ignores CFG-PRT still logs — just slower.
     */
    private fun raiseBaudIfNeeded(detectedBaud: Int): Int {
        val serialPort = port ?: return detectedBaud
        val targetRateHz = 1000 / measRateMs
        val needed = UbxProtocol.minimumBaudFor(targetRateHz)
        if (detectedBaud >= needed) return detectedBaud

        setState(
            GnssState.CONFIGURING,
            "$detectedBaud bauds insuffisant pour ${targetRateHz}Hz → passage à $TARGET_BAUD"
        )

        return try {
            serialPort.write(UbxProtocol.cfgPrtUartFrame(TARGET_BAUD), WRITE_TIMEOUT_MS)
            Thread.sleep(BAUD_SWITCH_SETTLE_MS)
            serialPort.setParameters(TARGET_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            Thread.sleep(BAUD_SWITCH_SETTLE_MS)

            if (linkAliveAt(serialPort)) {
                TARGET_BAUD
            } else {
                // Receiver didn't follow — go back rather than end up with a dead link.
                serialPort.setParameters(detectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                listener?.onError(
                    "Le module n'a pas suivi le changement de vitesse : on reste à $detectedBaud bauds " +
                        "(cadence limitée à ~${"%.0f".format(UbxProtocol.sustainableRateHz(detectedBaud))}Hz)."
                )
                detectedBaud
            }
        } catch (e: Exception) {
            listener?.onError("Changement de vitesse impossible (${e.message}) — on reste à $detectedBaud bauds.")
            try {
                serialPort.setParameters(detectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (_: Exception) {
            }
            detectedBaud
        }
    }

    /**
     * Confirms the link survived a baud change: polls the receiver, then listens briefly for
     * anything that parses — the poll reply, or the NMEA still flowing at this stage.
     */
    private fun linkAliveAt(serialPort: UsbSerialPort): Boolean {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        parser.reset()
        nmea.reset()
        sendPoll(serialPort)
        val deadline = System.currentTimeMillis() + VERIFY_WINDOW_MS
        while (running && System.currentTimeMillis() < deadline) {
            val n = readQuietly(serialPort, buffer)
            if (n > 0) {
                nmea.feed(buffer, n)
                parser.feed(buffer, n)
                if (nmea.validSentences >= 1 || parser.validFrames >= 1) return true
            }
        }
        return false
    }

    /** Silences NMEA, enables NAV-PVT, then sets the solution rate. */
    private fun configureReceiver() {
        val serialPort = port ?: return
        setState(GnssState.CONFIGURING, "configuration UBX à $activeBaud bauds…")
        for (frame in UbxProtocol.startupFrames(measRateMs)) {
            if (!running) return
            try {
                serialPort.write(frame, WRITE_TIMEOUT_MS)
                Thread.sleep(CONFIG_SETTLE_MS)
            } catch (e: Exception) {
                listener?.onError("Échec d'envoi d'une trame de configuration : ${e.message}")
            }
        }
        parser.reset()
        nmea.reset()
    }

    // ---- Streaming -------------------------------------------------------------------------

    private fun streamLoop() {
        val serialPort = port ?: return
        setState(GnssState.STREAMING, "$activeBaud bauds")

        val buffer = ByteArray(READ_BUFFER_SIZE)
        var lastPreviewAt = 0L
        var lastDataAt = System.currentTimeMillis()

        while (running) {
            val n = readQuietly(serialPort, buffer)
            val now = System.currentTimeMillis()

            if (n > 0) {
                lastDataAt = now
                nmea.feed(buffer, n)
                for (frame in parser.feed(buffer, n)) {
                    if (frame.msgClass == UbxProtocol.CLASS_NAV && frame.msgId == UbxProtocol.ID_NAV_PVT) {
                        UbxProtocol.parseNavPvt(frame.payload)?.let { listener?.onNavPvt(it) }
                    }
                }
                // Throttled raw preview so the diagnostic window stays readable at 25Hz.
                if (now - lastPreviewAt > 500) {
                    lastPreviewAt = now
                    listener?.onRawPreview(previewOf(buffer, n))
                }
            } else if (now - lastDataAt > 5000) {
                lastDataAt = now
                listener?.onError("Plus aucune donnée depuis 5s — câble débranché ou module muet ?")
            }
        }
    }

    /** Hex for binary traffic, the NMEA sentence when that is what is flowing. */
    private fun previewOf(buffer: ByteArray, n: Int): String {
        nmea.lastSentence?.let { return it }
        val shown = minOf(n, 24)
        return buildString {
            for (i in 0 until shown) append("%02X ".format(buffer[i]))
            if (n > shown) append("… (+${n - shown} octets)")
        }
    }

    private fun readQuietly(serialPort: UsbSerialPort, buffer: ByteArray): Int =
        try {
            serialPort.read(buffer, READ_TIMEOUT_MS)
        } catch (e: Exception) {
            if (running) {
                fail("Erreur de lecture USB : ${e.message}")
            }
            -1
        }

    /** Best effort: a failed poll just means we fall back to listening for unsolicited traffic. */
    private fun sendPoll(serialPort: UsbSerialPort) {
        try {
            serialPort.write(UbxProtocol.cfgPrtPollFrame(), WRITE_TIMEOUT_MS)
        } catch (_: Exception) {
        }
    }

    /** Reads and discards whatever is already buffered, so a test starts on a clean stream. */
    private fun drain(serialPort: UsbSerialPort, buffer: ByteArray) {
        val deadline = System.currentTimeMillis() + 150
        while (System.currentTimeMillis() < deadline) {
            if (readQuietly(serialPort, buffer) <= 0) break
        }
    }

    private fun closePortQuietly() {
        try {
            port?.close()
        } catch (_: Exception) {
        }
        port = null
    }

    fun disconnect() {
        if (!running && port == null) return
        running = false
        try {
            worker?.join(1500)
        } catch (_: InterruptedException) {
        }
        worker = null
        closePortQuietly()
        activeBaud = 0
        setState(GnssState.DISCONNECTED)
    }

    val isConnected: Boolean get() = state == GnssState.STREAMING
}
