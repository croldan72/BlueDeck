package com.bluedeck.automation

import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bluedeck.data.repository.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service that keeps walk-away monitoring visible and
 * dynamically registers for Bluetooth connection/disconnection events while
 * walk-away lock is enabled.
 */
class WalkAwayBluetoothMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vehicleConnectionReceiver = VehicleConnectionReceiver()
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Walk-away monitor onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Walk-away monitor stop requested")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // Must call startForeground promptly after startForegroundService.
                promoteToForeground(deviceName = null)

                serviceScope.launch {
                    val prefs = PreferencesManager(applicationContext)
                    val enabled = prefs.walkAwayLockEnabled.first()
                    val address = prefs.walkAwayBluetoothAddress.first()

                    Log.d(
                        TAG,
                        "Walk-away prefs: enabled=$enabled, address=${address ?: "<null>"}"
                    )

                    if (!enabled || address.isNullOrBlank()) {
                        Log.w(
                            TAG,
                            "Stopping walk-away monitor: enabled=$enabled, address=${address ?: "<null>"}"
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }

                    if (!registerVehicleConnectionReceiver()) {
                        Log.e(TAG, "Stopping walk-away monitor because Bluetooth receiver registration failed")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }

                    val deviceName = prefs.walkAwayBluetoothName.first()

                    Log.d(
                        TAG,
                        "Walk-away monitor active for ${deviceName ?: "Vehicle Bluetooth"} ($address)"
                    )

                    promoteToForeground(deviceName)
                }
            }
        }

        return START_STICKY
    }

    private fun registerVehicleConnectionReceiver(): Boolean {
        synchronized(this) {
            if (receiverRegistered) {
                Log.d(TAG, "Walk-away Bluetooth receiver already registered")
                return true
            }

            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            }

            return try {
                ContextCompat.registerReceiver(
                    this,
                    vehicleConnectionReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                receiverRegistered = true
                Log.d(TAG, "Walk-away Bluetooth receiver registered dynamically")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Unable to register walk-away Bluetooth receiver", e)
                false
            }
        }
    }

    private fun unregisterVehicleConnectionReceiver() {
        synchronized(this) {
            if (!receiverRegistered) return

            try {
                unregisterReceiver(vehicleConnectionReceiver)
                Log.d(TAG, "Walk-away Bluetooth receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to unregister walk-away Bluetooth receiver", e)
            } finally {
                receiverRegistered = false
            }
        }
    }

    private fun promoteToForeground(deviceName: String?) {
        val notification =
            WalkAwayNotifications.monitorNotification(this, deviceName)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    WalkAwayNotifications.MONITOR_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(
                    WalkAwayNotifications.MONITOR_NOTIFICATION_ID,
                    notification
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Could not start walk-away foreground service; stopping monitor",
                e
            )
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Walk-away monitor service destroyed")
        unregisterVehicleConnectionReceiver()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalkAwayLock"
        private const val ACTION_STOP =
            "com.bluedeck.automation.WALK_AWAY_STOP"

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent =
                Intent(appContext, WalkAwayBluetoothMonitorService::class.java)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Unable to start walk-away monitor service",
                    e
                )
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext

            try {
                val intent =
                    Intent(
                        appContext,
                        WalkAwayBluetoothMonitorService::class.java
                    ).setAction(ACTION_STOP)

                appContext.startService(intent)
            } catch (_: Exception) {
                appContext.stopService(
                    Intent(
                        appContext,
                        WalkAwayBluetoothMonitorService::class.java
                    )
                )
            }
        }
    }
}
