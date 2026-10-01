package com.bluedeck.data.obd.transport

import android.Manifest
import android.Manifest.permission.BLUETOOTH_SCAN
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import javax.inject.Inject

class BluetoothClassicObdTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : ObdTransport {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStreamWriter? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    override suspend fun connect() = withContext(Dispatchers.IO) {
        error("Use connect(address) for Bluetooth transport")
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        ensureBluetoothConnectPermission()
        disconnect()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth not available")
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is turned off")
        }
        val device = adapter.getRemoteDevice(address)
        val connectedSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            adapter.cancelDiscovery()
        }
        try {
            android.util.Log.d("BlueDeckOBD", "Starting RFCOMM connection")
            connectedSocket.connect()
            android.util.Log.d("BlueDeckOBD", "RFCOMM connection succeeded")
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "Bluetooth permission is required to connect to the OBD adapter",
                e
            )
        }
        socket = connectedSocket
        reader = BufferedReader(InputStreamReader(connectedSocket.inputStream))
        writer = OutputStreamWriter(connectedSocket.outputStream)
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }

    override suspend fun write(command: String) = withContext(Dispatchers.IO) {
        val w = writer ?: throw IllegalStateException("Not connected")
        w.write(command)
        w.write("\r")
        w.flush()
    }

    override suspend fun readLine(timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        val r = reader ?: return@withContext null
        val deadline = System.currentTimeMillis() + timeoutMs
        val response = StringBuilder()

        while (System.currentTimeMillis() < deadline) {
            if (r.ready()) {
                val ch = r.read()
                if (ch == -1) {
                    break
                }

                val c = ch.toChar()

                if (c == '>') {
                    response.append(c)
                    return@withContext response.toString().trim()
                }

                if (c == '\r' || c == '\n') {
                    if (response.isNotEmpty()) {
                        return@withContext response.toString().trim()
                    }
                } else {
                    response.append(c)
                }
            } else {
                Thread.sleep(25)
            }
        }

        response.toString().trim().ifEmpty { null }
    }

    private fun ensureBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            throw IllegalStateException(
                "Bluetooth permission is required to connect to the OBD adapter"
            )
        }
    }
}
