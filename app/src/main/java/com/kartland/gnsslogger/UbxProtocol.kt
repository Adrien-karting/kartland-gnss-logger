package com.kartland.gnsslogger

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * u-blox UBX binary protocol: frame building (with Fletcher-8 checksum), the two config
 * messages we send at startup (CFG-RATE, CFG-MSG), and the NAV-PVT payload parser.
 *
 * Frame layout: 0xB5 0x62 | class(1) | id(1) | length LE16 | payload(length) | CK_A | CK_B
 * Checksum (8-bit Fletcher) is computed over class, id, length, and payload — NOT the sync bytes.
 */
object UbxProtocol {

    const val SYNC_1: Byte = 0xB5.toByte()
    const val SYNC_2: Byte = 0x62.toByte()

    const val CLASS_NAV = 0x01
    const val CLASS_CFG = 0x06

    /** NMEA standard message class — used only to switch those sentences OFF. */
    const val CLASS_NMEA_STD = 0xF0

    const val ID_NAV_PVT = 0x07
    const val ID_CFG_PRT = 0x00
    const val ID_CFG_MSG = 0x01
    const val ID_CFG_RATE = 0x08

    /** UART mode word for 8 data bits, no parity, 1 stop bit (the u-blox default framing). */
    const val UART_MODE_8N1 = 0x000008D0

    const val PROTO_UBX = 0x0001
    const val PROTO_NMEA = 0x0002

    /** A NAV-PVT frame on the wire: 6-byte header + 92-byte payload + 2-byte checksum. */
    const val NAV_PVT_FRAME_BYTES = 8 + 92

    /** 8-bit Fletcher checksum over [msgClass, msgId, lenLow, lenHigh, ...payload]. */
    private fun fletcher8(bytes: ByteArray): Pair<Byte, Byte> {
        var ckA = 0
        var ckB = 0
        for (b in bytes) {
            ckA = (ckA + (b.toInt() and 0xFF)) and 0xFF
            ckB = (ckB + ckA) and 0xFF
        }
        return Pair(ckA.toByte(), ckB.toByte())
    }

    /** Builds a complete UBX frame (sync bytes through checksum) ready to write to the USB port. */
    fun buildFrame(msgClass: Int, msgId: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(msgClass.toByte())
            put(msgId.toByte())
            putShort(payload.size.toShort())
        }.array()

        val checksummed = header + payload
        val (ckA, ckB) = fletcher8(checksummed)

        return ByteBuffer.allocate(2 + checksummed.size + 2).apply {
            put(SYNC_1)
            put(SYNC_2)
            put(checksummed)
            put(ckA)
            put(ckB)
        }.array()
    }

    /**
     * UBX-CFG-RATE (0x06 0x08), 6-byte payload: measRate(U2 ms), navRate(U2, in measurement
     * cycles — leave at 1 so every measurement produces a solution), timeRef(U2, 0 = UTC).
     *
     * measRateMs is the solution interval: 40ms = 25Hz, the cadence we're targeting with the
     * external chip (vs. the phone's internal GNSS 1Hz ceiling measured at Kartland).
     */
    fun cfgRateFrame(measRateMs: Int, navRate: Int = 1, timeRef: Int = 0): ByteArray {
        val payload = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(measRateMs.toShort())
            putShort(navRate.toShort())
            putShort(timeRef.toShort())
        }.array()
        return buildFrame(CLASS_CFG, ID_CFG_RATE, payload)
    }

    /**
     * UBX-CFG-MSG (0x06 0x01), simple 3-byte form: msgClass, msgID, rate. Rate is "send every
     * N navigation solutions" on the port that received this command (our USB port) — 1 = every
     * solution. We use this (rather than the 8-byte per-port table or the CFG-VALSET key
     * interface) to keep the set of magic constants we depend on to a minimum.
     */
    fun cfgMsgFrame(msgClass: Int, msgId: Int, rate: Int): ByteArray {
        val payload = byteArrayOf(msgClass.toByte(), msgId.toByte(), rate.toByte())
        return buildFrame(CLASS_CFG, ID_CFG_MSG, payload)
    }

    /**
     * UBX-CFG-PRT (0x06 0x00), 20-byte UART payload — reconfigures the receiver's OWN serial
     * port, chiefly its baud rate.
     *
     * This is not cosmetic: a NAV-PVT frame is 100 bytes on the wire, so 25Hz needs
     * 2500 byte/s ≈ 25 kbit/s of payload alone. At the usual u-blox factory default of 9600
     * (960 byte/s) the link physically cannot carry 25Hz, and even 38400 leaves no headroom.
     * So when we detect a slow default we push the receiver up to 115200 with this message,
     * then re-open our own port at the new speed.
     *
     * NMEA is deliberately kept ON in the output mask here. This message only changes the speed;
     * silencing NMEA is the job of [nmeaDisableFrames], sent afterwards. Dropping NMEA output at
     * this point would leave a receiver with no UBX message enabled yet completely mute at the
     * new speed — nothing to confirm the switch with, so it would be wrongly judged as "didn't
     * follow" and we'd fall back to a speed it is no longer listening on.
     */
    fun cfgPrtUartFrame(
        baudRate: Int,
        portId: Int = 1,
        inProtoMask: Int = PROTO_UBX or PROTO_NMEA,
        outProtoMask: Int = PROTO_UBX or PROTO_NMEA,
    ): ByteArray {
        val payload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(portId.toByte())            // portID
            put(0)                          // reserved1
            putShort(0)                     // txReady
            putInt(UART_MODE_8N1)           // mode
            putInt(baudRate)                // baudRate
            putShort(inProtoMask.toShort())  // inProtoMask
            putShort(outProtoMask.toShort()) // outProtoMask
            putShort(0)                     // flags
            putShort(0)                     // reserved2
        }.array()
        return buildFrame(CLASS_CFG, ID_CFG_PRT, payload)
    }

    /**
     * UBX-CFG-PRT poll request (1-byte payload: portID). The receiver answers with its current
     * CFG-PRT frame, so this is a way to get a checksum-valid UBX reply on demand — including
     * from a receiver whose periodic output is entirely switched off.
     */
    fun cfgPrtPollFrame(portId: Int = 1): ByteArray =
        buildFrame(CLASS_CFG, ID_CFG_PRT, byteArrayOf(portId.toByte()))

    /**
     * The default NMEA sentences (GGA/GLL/GSA/GSV/RMC/VTG), switched off by setting their
     * CFG-MSG rate to 0. Mandatory before going to 25Hz: left on, this text traffic competes
     * with NAV-PVT for the same UART bandwidth — GSV alone can be several hundred bytes per
     * epoch — and would starve the binary stream we actually want.
     */
    fun nmeaDisableFrames(): List<ByteArray> =
        (0x00..0x05).map { id -> cfgMsgFrame(CLASS_NMEA_STD, id, rate = 0) }

    /**
     * Startup sequence, in a deliberate order: silence NMEA first (free the bandwidth), then
     * enable NAV-PVT, and only then raise the solution rate — so the link is never asked to
     * carry high-rate binary and chatty text at the same time.
     */
    fun startupFrames(measRateMs: Int): List<ByteArray> =
        nmeaDisableFrames() +
            cfgMsgFrame(CLASS_NAV, ID_NAV_PVT, rate = 1) +
            cfgRateFrame(measRateMs = measRateMs)

    /**
     * Lowest serial speed that can carry [rateHz] of NAV-PVT with headroom.
     *
     * 8N1 framing costs 10 bits per byte, and we keep 40% spare for the receiver's other
     * traffic and for scheduling jitter — a link run at 100% of theory drops frames in practice.
     */
    fun minimumBaudFor(rateHz: Int): Int {
        val payloadBitsPerSecond = rateHz * NAV_PVT_FRAME_BYTES * 10
        return (payloadBitsPerSecond / 0.6).toInt()
    }

    /** Sustainable NAV-PVT rate at [baud], with the same 40% headroom. */
    fun sustainableRateHz(baud: Int): Double = (baud / 10.0) / NAV_PVT_FRAME_BYTES * 0.6

    /**
     * Parses a UBX-NAV-PVT payload (92 bytes, M8/M9 receivers). Offsets per the public u-blox
     * interface description, cross-checked against PX4-GPSDrivers' ubx.h. Returns null if the
     * payload is the wrong length (older firmware without the full field set, or a corrupt frame
     * that slipped past the checksum somehow).
     */
    fun parseNavPvt(payload: ByteArray): NavPvt? {
        if (payload.size < 92) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val iTOW = buf.getInt(0).toLong() and 0xFFFFFFFFL
        val year = buf.getShort(4).toInt() and 0xFFFF
        val month = payload[6].toInt() and 0xFF
        val day = payload[7].toInt() and 0xFF
        val hour = payload[8].toInt() and 0xFF
        val min = payload[9].toInt() and 0xFF
        val sec = payload[10].toInt() and 0xFF
        val valid = payload[11].toInt() and 0xFF
        val tAcc = buf.getInt(12).toLong() and 0xFFFFFFFFL
        val nano = buf.getInt(16)
        val fixType = payload[20].toInt() and 0xFF
        val flags = payload[21].toInt() and 0xFF
        val numSV = payload[23].toInt() and 0xFF
        val lon = buf.getInt(24)
        val lat = buf.getInt(28)
        val height = buf.getInt(32)
        val hMSL = buf.getInt(36)
        val hAcc = buf.getInt(40).toLong() and 0xFFFFFFFFL
        val vAcc = buf.getInt(44).toLong() and 0xFFFFFFFFL
        val velN = buf.getInt(48)
        val velE = buf.getInt(52)
        val velD = buf.getInt(56)
        val gSpeed = buf.getInt(60)
        val headMot = buf.getInt(64)
        val sAcc = buf.getInt(68).toLong() and 0xFFFFFFFFL
        val headAcc = buf.getInt(72).toLong() and 0xFFFFFFFFL
        val pDOP = buf.getShort(76).toInt() and 0xFFFF

        return NavPvt(
            iTowMs = iTOW,
            year = year, month = month, day = day, hour = hour, minute = min, second = sec,
            valid = valid,
            tAccNs = tAcc,
            nanoOffsetNs = nano,
            fixType = fixType,
            flags = flags,
            numSV = numSV,
            lonDeg = lon * 1e-7,
            latDeg = lat * 1e-7,
            heightMm = height,
            hMslMm = hMSL,
            hAccMm = hAcc,
            vAccMm = vAcc,
            velNMmPerS = velN,
            velEMmPerS = velE,
            velDMmPerS = velD,
            gSpeedMmPerS = gSpeed,
            headMotDeg = headMot * 1e-5,
            sAccMmPerS = sAcc,
            headAccDeg = headAcc * 1e-5,
            pDop = pDOP * 0.01,
        )
    }
}

/** One decoded UBX-NAV-PVT solution, with raw u-blox units converted to sane SI-ish units. */
data class NavPvt(
    val iTowMs: Long,
    val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int,
    val valid: Int,
    val tAccNs: Long,
    val nanoOffsetNs: Int,
    val fixType: Int,
    val flags: Int,
    val numSV: Int,
    val lonDeg: Double,
    val latDeg: Double,
    val heightMm: Int,
    val hMslMm: Int,
    val hAccMm: Long,
    val vAccMm: Long,
    val velNMmPerS: Int,
    val velEMmPerS: Int,
    val velDMmPerS: Int,
    /** Ground speed (2-D), derived from Doppler — this is the "vitesse Doppler" field. */
    val gSpeedMmPerS: Int,
    val headMotDeg: Double,
    /** Speed accuracy estimate, also Doppler-derived. */
    val sAccMmPerS: Long,
    val headAccDeg: Double,
    val pDop: Double,
) {
    val fixOk: Boolean get() = (flags and 0x01) != 0
    val speedMps: Double get() = gSpeedMmPerS / 1000.0
    val speedKmh: Double get() = speedMps * 3.6
    val hAccM: Double get() = hAccMm / 1000.0
    val sAccMps: Double get() = sAccMmPerS / 1000.0

    val fixTypeLabel: String get() = when (fixType) {
        0 -> "no fix"
        1 -> "dead reckoning"
        2 -> "2D"
        3 -> "3D"
        4 -> "3D+DR"
        5 -> "time only"
        else -> "? ($fixType)"
    }

    /** UTC instant this solution refers to, as an ISO-8601 string with millisecond precision. */
    val utcIso: String get() {
        val millis = (nanoOffsetNs / 1_000_000.0).let { if (second + it / 1000.0 >= 60) 999 else Math.round(it) }
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            year, month, day, hour, minute, second, millis.coerceIn(0, 999)
        )
    }
}
