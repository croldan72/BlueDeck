package com.bluedeck.data.obd.transport

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothLeObdTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : ObdTransport {

    companion object {
        private const val TAG = "BlueDeckBLE"

        private val SERVICE_UUID: UUID =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val NOTIFY_UUID: UUID =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID =
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var deviceAddress: String? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val incomingBytes = LinkedBlockingQueue<Byte>()

    @Volatile
    override var isConnected: Boolean = false
        private set

    fun configure(address: String) {
        deviceAddress = address
        Log.d(TAG, "configure(): address=$address")
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        checkBluetoothPermission()

        val address = deviceAddress
            ?: throw IllegalStateException("BLE adapter address not configured")

        Log.d(TAG, "connect(): starting; address=$address")

        disconnect()
        incomingBytes.clear()

        val bluetoothManager =
            context.getSystemService(BluetoothManager::class.java)
                ?: throw IllegalStateException("Bluetooth manager unavailable")

        val adapter = bluetoothManager.adapter
            ?: throw IllegalStateException("Bluetooth adapter unavailable")

        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is turned off")
        }

        val device = adapter.getRemoteDevice(address)
        Log.d(TAG, "connect(): remote device name=${device.name ?: "<unknown>"} address=$address")

        val connected = CompletableDeferred<Unit>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                Log.d(TAG, "onConnectionStateChange(): status=$status newState=$newState (${stateName(newState)})")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    isConnected = false
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(
                            IllegalStateException("BLE GATT connection failed: status=$status")
                        )
                    }
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        try {
                            checkBluetoothPermission()
                            Log.d(TAG, "GATT connected; requesting MTU 512")
                            val mtuStarted = gatt.requestMtu(512)
                            Log.d(TAG, "requestMtu(512) started=$mtuStarted")

                            Log.d(TAG, "Starting service discovery")
                            val discoveryStarted = gatt.discoverServices()
                            Log.d(TAG, "discoverServices() started=$discoveryStarted")

                            if (!discoveryStarted && !connected.isCompleted) {
                                connected.completeExceptionally(
                                    IllegalStateException("BLE service discovery could not be started")
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error after BLE connection", e)
                            if (!connected.isCompleted) connected.completeExceptionally(e)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        isConnected = false
                        Log.w(TAG, "GATT disconnected")
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(
                                IllegalStateException("BLE device disconnected during connect")
                            )
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "onMtuChanged(): mtu=$mtu status=$status")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "onServicesDiscovered(): status=$status")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(
                            IllegalStateException("BLE service discovery failed: status=$status")
                        )
                    }
                    return
                }

                try {
                    dumpGattDatabase(gatt)

                    val service = gatt.getService(SERVICE_UUID)
                    if (service == null) {
                        val message = "Expected BLE UART service $SERVICE_UUID not found. See BlueDeckBLE log for discovered services."
                        Log.e(TAG, message)
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(IllegalStateException(message))
                        }
                        return
                    }

                    val notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID)
                    if (notifyCharacteristic == null) {
                        val message = "Notify characteristic $NOTIFY_UUID not found"
                        Log.e(TAG, message)
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(IllegalStateException(message))
                        }
                        return
                    }

                    val writeChar = service.getCharacteristic(WRITE_UUID)
                    if (writeChar == null) {
                        val message = "Write characteristic $WRITE_UUID not found"
                        Log.e(TAG, message)
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(IllegalStateException(message))
                        }
                        return
                    }

                    writeCharacteristic = writeChar
                    Log.d(TAG, "Selected service=$SERVICE_UUID notify=$NOTIFY_UUID write=$WRITE_UUID")

                    checkBluetoothPermission()
                    val notificationEnabled =
                        gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    Log.d(TAG, "setCharacteristicNotification($NOTIFY_UUID,true)=$notificationEnabled")

                    if (!notificationEnabled) {
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(
                                IllegalStateException("Could not enable local BLE notifications")
                            )
                        }
                        return
                    }

                    val descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID)
                    if (descriptor == null) {
                        val message = "Notification descriptor $CCCD_UUID not found on $NOTIFY_UUID"
                        Log.e(TAG, message)
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(IllegalStateException(message))
                        }
                        return
                    }

                    Log.d(TAG, "Writing CCCD notification descriptor")
                    val descriptorStarted = writeNotificationDescriptor(gatt, descriptor)
                    Log.d(TAG, "writeDescriptor() started=$descriptorStarted")

                    if (!descriptorStarted && !connected.isCompleted) {
                        connected.completeExceptionally(
                            IllegalStateException("Could not start BLE notification descriptor write")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing discovered GATT services", e)
                    if (!connected.isCompleted) connected.completeExceptionally(e)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                Log.d(TAG, "onDescriptorWrite(): uuid=${descriptor.uuid} status=$status")

                if (descriptor.uuid != CCCD_UUID) return

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    isConnected = true
                    Log.d(TAG, "BLE transport ready; notifications enabled")
                    if (!connected.isCompleted) connected.complete(Unit)
                } else {
                    isConnected = false
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(
                            IllegalStateException("BLE notification descriptor write failed: status=$status")
                        )
                    }
                }
            }

            @Deprecated("Deprecated in Android API")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val payload = characteristic.value ?: return
                handleRx(characteristic.uuid, payload)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleRx(characteristic.uuid, value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.d(TAG, "onCharacteristicWrite(): uuid=${characteristic.uuid} status=$status")
            }
        }

        checkBluetoothPermission()

        val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(
                context,
                false,
                callback,
                android.bluetooth.BluetoothDevice.TRANSPORT_LE
            )
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }

        gatt = newGatt
        connected.await()
        incomingBytes.clear()
        Log.d(TAG, "connect(): completed successfully")
        Unit
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        checkBluetoothPermission()
        isConnected = false

        val currentGatt = gatt
        if (currentGatt != null) {
            runCatching { currentGatt.disconnect() }
                .onFailure { Log.w(TAG, "disconnect() failed", it) }
            runCatching { currentGatt.close() }
                .onFailure { Log.w(TAG, "close() failed", it) }
        }

        gatt = null
        writeCharacteristic = null
        incomingBytes.clear()
        Log.d(TAG, "BLE transport disconnected")
        Unit
    }

    override suspend fun write(command: String) = withContext(Dispatchers.IO) {
        checkBluetoothPermission()

        val currentGatt = gatt
            ?: throw IllegalStateException("BLE GATT connection unavailable")
        val characteristic = writeCharacteristic
            ?: throw IllegalStateException("BLE write characteristic unavailable")

        val normalized = command.trimEnd('\r', '\n') + "\r"
        val payload = normalized.toByteArray(StandardCharsets.US_ASCII)

        Log.d(TAG, "TX text='${escapeForLog(normalized)}'")
        Log.d(TAG, "TX hex=${payload.toHex()}")

        val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = currentGatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            Log.d(TAG, "writeCharacteristic() result=$result")
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }

        if (!writeStarted) {
            throw IllegalStateException("BLE write could not be started")
        }
        Unit
    }

    override suspend fun readLine(timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "readLine(timeoutMs=$timeoutMs) waiting for ELM prompt")

            val deadline = System.currentTimeMillis() + timeoutMs
            val response = StringBuilder()

            while (System.currentTimeMillis() < deadline) {
                val remaining =
                    (deadline - System.currentTimeMillis()).coerceAtLeast(1)

                val byte =
                    incomingBytes.poll(
                        remaining,
                        TimeUnit.MILLISECONDS
                    ) ?: break

                val c = (byte.toInt() and 0xFF).toChar()

                if (c == '>') {
                    val result =
                        response
                            .toString()
                            .replace("\r", "\n")
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                            .takeIf { it.isNotEmpty() }

                    Log.d(TAG, "readLine complete prompt response=[$result]")
                    return@withContext result
                }

                response.append(c)
            }

            val partial =
                response
                    .toString()
                    .replace("\r", "\n")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                    .takeIf { it.isNotEmpty() }

            Log.w(TAG, "readLine timed out before prompt; partial=[$partial]")
            null
        }

    private fun handleRx(uuid: UUID, payload: ByteArray) {
        Log.d(
            TAG,
            "RX uuid=$uuid bytes=${payload.size} text='${escapeForLog(payload.toString(StandardCharsets.US_ASCII))}'"
        )
        Log.d(TAG, "RX hex=${payload.toHex()}")

        if (uuid != NOTIFY_UUID) {
            Log.w(TAG, "RX arrived on unexpected characteristic $uuid")
        }

        payload.forEach { incomingBytes.offer(it) }
    }

    private fun dumpGattDatabase(gatt: BluetoothGatt) {
        val services = gatt.services.orEmpty()
        Log.d(TAG, "GATT database: ${services.size} services")

        services.forEach { service ->
            Log.d(
                TAG,
                "SERVICE ${service.uuid} type=${service.type} chars=${service.characteristics.size}"
            )

            service.characteristics.forEach { characteristic ->
                Log.d(
                    TAG,
                    "  CHAR ${characteristic.uuid} props=0x${characteristic.properties.toString(16)} " +
                            "permissions=0x${characteristic.permissions.toString(16)} descriptors=${characteristic.descriptors.size}"
                )

                characteristic.descriptors.forEach { descriptor ->
                    Log.d(
                        TAG,
                        "    DESC ${descriptor.uuid} permissions=0x${descriptor.permissions.toString(16)}"
                    )
                }
            }
        }
    }

    private fun writeNotificationDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ): Boolean {
        checkBluetoothPermission()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun checkBluetoothPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Bluetooth permission is required to use the BLE OBD adapter")
        }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN"
    }

    private fun escapeForLog(value: String): String =
        value.replace("\r", "\\r").replace("\n", "\\n")

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
