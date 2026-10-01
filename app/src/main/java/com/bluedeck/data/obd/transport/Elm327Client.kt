package com.bluedeck.data.obd.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Elm327Client @Inject constructor() {

    private val commandMutex = Mutex()

    suspend fun initialize(transport: ObdTransport) {
        commandMutex.withLock {
            android.util.Log.d("BlueDeckOBD", "Sending ATZ")
            sendCommandUnlocked(
                transport = transport,
                command = "ATZ",
                delayAfterMs = 1000,
                responseTimeoutMs = 3000
            )
            android.util.Log.d("BlueDeckOBD", "ATZ complete")

            android.util.Log.d("BlueDeckOBD", "Sending ATE0")
            sendCommandUnlocked(transport, "ATE0")
            android.util.Log.d("BlueDeckOBD", "ATE0 complete")

            android.util.Log.d("BlueDeckOBD", "Sending ATL0")
            sendCommandUnlocked(transport, "ATL0")
            android.util.Log.d("BlueDeckOBD", "ATL0 complete")

            android.util.Log.d("BlueDeckOBD", "Sending ATH1")
            sendCommandUnlocked(transport, "ATH1")
            android.util.Log.d("BlueDeckOBD", "ATH1 complete")

            android.util.Log.d("BlueDeckOBD", "Sending ATSP0")
            sendCommandUnlocked(transport, "ATSP0")
            android.util.Log.d("BlueDeckOBD", "ATSP0 complete")

            android.util.Log.d("BlueDeckOBD", "Sending ATST64")
            sendCommandUnlocked(transport, "ATST64")
            android.util.Log.d("BlueDeckOBD", "ATST64 complete")
        }
    }

    suspend fun sendCommand(
        transport: ObdTransport,
        command: String,
        delayAfterMs: Long = 150,
        responseTimeoutMs: Long = 2000
    ): String = commandMutex.withLock {
        sendCommandUnlocked(
            transport = transport,
            command = command,
            delayAfterMs = delayAfterMs,
            responseTimeoutMs = responseTimeoutMs
        )
    }

    suspend fun queryPid(
        transport: ObdTransport,
        header: String,
        modeAndPid: String
    ): String = commandMutex.withLock {
        sendCommandUnlocked(
            transport = transport,
            command = "ATSH$header"
        )

        sendCommandUnlocked(
            transport = transport,
            command = modeAndPid,
            delayAfterMs = 300,
            responseTimeoutMs = 10000
        )
    }

    suspend fun queryStandardPid(
        transport: ObdTransport,
        pid: String
    ): String = commandMutex.withLock {
        sendCommandUnlocked(
            transport = transport,
            command = "ATSH7E0"
        )

        sendCommandUnlocked(
            transport = transport,
            command = "01$pid",
            delayAfterMs = 300,
            responseTimeoutMs = 10000
        )
    }

    private suspend fun sendCommandUnlocked(
        transport: ObdTransport,
        command: String,
        delayAfterMs: Long = 150,
        responseTimeoutMs: Long = 2000
    ): String {
        android.util.Log.d(
            "BlueDeckOBD",
            "CMD start: $command timeout=${responseTimeoutMs}ms"
        )

        transport.write(command)
        delay(delayAfterMs)

        val responses = mutableListOf<String>()

        val firstResponse = transport.readLine(
            timeoutMs = responseTimeoutMs
        )

        if (!firstResponse.isNullOrBlank()) {
            responses += firstResponse
        }

        var extraReads = 0

        while (extraReads < 31) {
            val extra = transport.readLine(timeoutMs = 75) ?: break
            extraReads++

            if (extra.isNotBlank() && extra != ">") {
                responses += extra
            }

            if (extra.contains(">")) {
                break
            }
        }

        val result = responses.joinToString("\n").trim()

        android.util.Log.d(
            "BlueDeckOBD",
            "CMD complete: $command response=[$result]"
        )

        return result
    }
}
