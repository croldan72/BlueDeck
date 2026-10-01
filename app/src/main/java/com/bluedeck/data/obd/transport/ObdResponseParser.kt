package com.bluedeck.data.obd.transport

object ObdResponseParser {

    fun parseHexPayload(response: String): ByteArray? {
        val cleaned = response
            .replace("SEARCHING...", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .uppercase()

        val tokens = cleaned
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return null

        val payload = mutableListOf<Int>()
        var expectedLength: Int? = null
        var i = 0

        while (i < tokens.size) {
            val token = tokens[i]

            // Hyundai/Kia CAN response ID, e.g. 7EC.
            if (token.length == 3 &&
                token.all { it in '0'..'9' || it in 'A'..'F' }
            ) {
                i++

                if (i >= tokens.size) break

                val pci = tokens[i].toIntOrNull(16)
                if (pci == null) {
                    i++
                    continue
                }

                when (pci and 0xF0) {
                    0x10 -> {
                        // ISO-TP first frame:
                        // 10 LL 62 PID_HI PID_LO ...
                        if (i + 1 >= tokens.size) break

                        val lowLength = tokens[i + 1].toIntOrNull(16) ?: 0
                        expectedLength = ((pci and 0x0F) shl 8) or lowLength

                        i += 2

                        while (i < tokens.size &&
                            tokens[i].length == 2 &&
                            tokens[i].all { it in '0'..'9' || it in 'A'..'F' }
                        ) {
                            payload += tokens[i].toInt(16)
                            i++
                        }

                        continue
                    }

                    0x20 -> {
                        // ISO-TP consecutive frame: 21, 22, 23...
                        i++

                        while (i < tokens.size &&
                            tokens[i].length == 2 &&
                            tokens[i].all { it in '0'..'9' || it in 'A'..'F' }
                        ) {
                            payload += tokens[i].toInt(16)
                            i++
                        }

                        continue
                    }
                }
            }

            i++
        }

        if (payload.isEmpty()) {
            // Fall back to the original-style single-frame response.
            val dataStart = tokens.indexOfFirst {
                it == "62" || it == "41"
            }

            if (dataStart < 0) return null

            val pidLength = when (tokens[dataStart]) {
                "62" -> 3   // 62 + two-byte PID
                "41" -> 2   // 41 + one-byte PID
                else -> return null
            }

            return tokens.drop(dataStart + pidLength)
                .takeWhile {
                    it.length == 2 &&
                            it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' }
                }
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()
        }

        // ISO-TP payload begins with the positive response and PID.
        val modeIndex = payload.indexOfFirst { it == 0x62 || it == 0x41 }
        if (modeIndex < 0) return null

        val headerLength = when (payload[modeIndex]) {
            0x62 -> 3   // 62 + PID high + PID low
            0x41 -> 2   // 41 + PID
            else -> return null
        }

        val data = payload.drop(modeIndex + headerLength)

        return if (expectedLength != null) {
            // expectedLength includes the 62/PID bytes we removed above.
            val dataLength = (expectedLength!! - headerLength).coerceAtLeast(0)
            data.take(dataLength).map { it.toByte() }.toByteArray()
        } else {
            data.map { it.toByte() }.toByteArray()
        }
    }

    fun isPositiveResponse(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains(" 62 ") || upper.contains("62 ") || upper.contains(" 41 ") || upper.contains("41 ")
    }

    fun isNoData(response: String): Boolean =
        response.contains("NO DATA", ignoreCase = true) ||
            response.contains("UNABLE TO CONNECT", ignoreCase = true) ||
            response.contains("ERROR", ignoreCase = true) && !isPositiveResponse(response)
}
