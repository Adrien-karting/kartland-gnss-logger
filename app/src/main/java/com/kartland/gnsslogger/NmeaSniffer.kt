package com.kartland.gnsslogger

/**
 * Minimal NMEA 0183 sentence detector.
 *
 * Two jobs, both about the first minutes with an unknown board rather than about logging:
 *
 *  1. **Baud detection.** A receiver at the wrong baud produces garbage, so "did we see any
 *     structurally valid, checksum-correct sentence?" is a reliable yes/no test for "is this
 *     the right speed?". NMEA is the better probe than UBX here because a factory-default
 *     u-blox emits NMEA and nothing else until we configure it.
 *  2. **Showing signs of life.** Between plugging in and our UBX config taking effect, NMEA is
 *     all there is. Displaying it proves the wiring is good even before NAV-PVT arrives.
 *
 * Deliberately does not parse fields — position comes from NAV-PVT. This only answers
 * "is this a real sentence?".
 */
class NmeaSniffer {

    private val line = StringBuilder()

    var validSentences: Int = 0
        private set

    /** Last checksum-valid sentence seen, for display. */
    var lastSentence: String? = null
        private set

    fun reset() {
        line.setLength(0)
        validSentences = 0
        lastSentence = null
    }

    /** Feeds raw bytes; returns the number of newly completed, checksum-valid sentences. */
    fun feed(chunk: ByteArray, len: Int = chunk.size): Int {
        var found = 0
        for (i in 0 until len) {
            val c = (chunk[i].toInt() and 0xFF).toChar()
            when {
                c == '$' -> {
                    // A '$' always starts a new sentence: drop whatever partial line we had.
                    line.setLength(0)
                    line.append(c)
                }
                c == '\n' || c == '\r' -> {
                    if (line.isNotEmpty()) {
                        if (isValid(line.toString())) {
                            validSentences++
                            lastSentence = line.toString()
                            found++
                        }
                        line.setLength(0)
                    }
                }
                line.isNotEmpty() -> {
                    line.append(c)
                    // Runaway line (noise that happened to contain '$'): abandon it.
                    if (line.length > MAX_SENTENCE_LEN) line.setLength(0)
                }
                // Bytes outside any sentence are ignored (could be UBX binary interleaved).
            }
        }
        return found
    }

    /**
     * A sentence is valid when it looks like `$<talker><type>,<fields>*<HH>` and the two hex
     * digits match the XOR of every character between '$' and '*'.
     */
    private fun isValid(sentence: String): Boolean {
        if (sentence.length < MIN_SENTENCE_LEN || sentence[0] != '$') return false
        val star = sentence.lastIndexOf('*')
        if (star < 1 || star + 2 >= sentence.length + 1 || sentence.length < star + 3) return false

        val expected = sentence.substring(star + 1, star + 3)
        var checksum = 0
        for (i in 1 until star) {
            checksum = checksum xor sentence[i].code
        }
        return expected.equals(String.format("%02X", checksum), ignoreCase = true)
    }

    companion object {
        private const val MIN_SENTENCE_LEN = 9   // e.g. "$GPGGA,*hh" is about the shortest shape
        private const val MAX_SENTENCE_LEN = 128 // NMEA caps at 82; allow slack before giving up
    }
}
