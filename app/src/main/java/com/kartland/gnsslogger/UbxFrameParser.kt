package com.kartland.gnsslogger

/**
 * Incremental UBX frame extractor. USB serial reads arrive as arbitrary byte chunks that don't
 * respect message boundaries (a frame can be split across two reads, or one read can contain
 * several frames), so this is a small state machine fed one byte at a time rather than a
 * one-shot parser over a single buffer.
 *
 * Not thread-safe; intended to be owned and fed by a single reader thread/coroutine.
 */
class UbxFrameParser {

    private enum class State { WAIT_SYNC1, WAIT_SYNC2, CLASS, ID, LEN_LOW, LEN_HIGH, PAYLOAD, CK_A, CK_B }

    private var state = State.WAIT_SYNC1
    private var msgClass = 0
    private var msgId = 0
    private var length = 0
    private var payload = ByteArray(0)
    private var payloadIndex = 0
    private var runningA = 0
    private var runningB = 0
    private var frameCkA = 0

    /** Frames rejected for a bad checksum or an implausible length (noise, wrong baud, …). */
    var framesDropped: Long = 0
        private set

    /** Frames that passed the checksum. Used as the "this baud is correct" signal. */
    var validFrames: Long = 0
        private set

    /** Clears counters and parser state — call between baud-detection attempts. */
    fun reset() {
        state = State.WAIT_SYNC1
        payloadIndex = 0
        length = 0
        framesDropped = 0
        validFrames = 0
    }

    private fun resetToSync() {
        state = State.WAIT_SYNC1
    }

    private fun accumulate(byte: Int) {
        runningA = (runningA + byte) and 0xFF
        runningB = (runningB + runningA) and 0xFF
    }

    /** Feed a chunk of raw bytes read from the USB port; returns any complete, checksum-valid frames found. */
    fun feed(chunk: ByteArray, len: Int = chunk.size): List<UbxFrame> {
        val out = ArrayList<UbxFrame>()
        for (i in 0 until len) {
            feedByte(chunk[i].toInt() and 0xFF)?.let { out.add(it) }
        }
        return out
    }

    private fun feedByte(b: Int): UbxFrame? {
        when (state) {
            State.WAIT_SYNC1 -> {
                if (b == (UbxProtocol.SYNC_1.toInt() and 0xFF)) state = State.WAIT_SYNC2
            }
            State.WAIT_SYNC2 -> {
                state = if (b == (UbxProtocol.SYNC_2.toInt() and 0xFF)) {
                    runningA = 0; runningB = 0
                    State.CLASS
                } else {
                    // Not a real second sync byte: only restart the search if this byte isn't
                    // itself a sync1 (handles back-to-back 0xB5 0xB5 0x62 sequences correctly).
                    if (b == (UbxProtocol.SYNC_1.toInt() and 0xFF)) State.WAIT_SYNC2 else State.WAIT_SYNC1
                }
            }
            State.CLASS -> {
                msgClass = b
                accumulate(b)
                state = State.ID
            }
            State.ID -> {
                msgId = b
                accumulate(b)
                state = State.LEN_LOW
            }
            State.LEN_LOW -> {
                length = b
                accumulate(b)
                state = State.LEN_HIGH
            }
            State.LEN_HIGH -> {
                length = length or (b shl 8)
                accumulate(b)
                if (length > MAX_PAYLOAD) {
                    // Implausible length (corrupt frame) — bail out and resync rather than
                    // trying to allocate/wait for garbage.
                    framesDropped++
                    resetToSync()
                } else if (length == 0) {
                    payload = ByteArray(0)
                    state = State.CK_A
                } else {
                    payload = ByteArray(length)
                    payloadIndex = 0
                    state = State.PAYLOAD
                }
            }
            State.PAYLOAD -> {
                payload[payloadIndex++] = b.toByte()
                accumulate(b)
                if (payloadIndex >= length) state = State.CK_A
            }
            State.CK_A -> {
                frameCkA = b
                state = State.CK_B
            }
            State.CK_B -> {
                val ckB = b
                resetToSync()
                if (frameCkA == runningA && ckB == runningB) {
                    validFrames++
                    return UbxFrame(msgClass, msgId, payload)
                } else {
                    framesDropped++
                }
            }
        }
        return null
    }

    companion object {
        // NAV-PVT is 92 bytes; generous headroom for other message types without accepting
        // obviously-corrupt length fields.
        private const val MAX_PAYLOAD = 2048
    }
}

data class UbxFrame(val msgClass: Int, val msgId: Int, val payload: ByteArray)
