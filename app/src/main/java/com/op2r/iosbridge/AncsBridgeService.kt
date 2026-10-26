package com.op2r.iosbridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent Foreground Service for the OnePlus Watch 2R (OPWWE234) running Wear OS 5.0 (Android 14 / API 34).
 * Bridges iPhone 14 notifications and incoming calls via Apple's native ANCS over BLE,
 * includes automatic post-bond CCCD re-subscription waves, serialized Control Point queue locking,
 * immediate 8-byte Notification Source call/alert fallbacks, and live NS/DS packet telemetry.
 */
@SuppressLint("MissingPermission")
class AncsBridgeService : Service() {

    companion object {
        private const val TAG = "AncsBridgeService"

        // Advertised Peripheral Device Name
        const val ADVERTISED_DEVICE_NAME = "OP2R-Bridge"

        // Exact Apple ANCS UUIDs
        val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Standard Bluetooth SIG Service UUIDs for Mode A Peripheral Accessory Emulation
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_CHAR_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
        val CURRENT_TIME_SERVICE_UUID: UUID = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
        val CURRENT_TIME_CHAR_UUID: UUID = UUID.fromString("00002A2B-0000-1000-8000-00805f9b34fb")

        // Service Control Actions
        const val ACTION_START_PERIPHERAL = "com.op2r.iosbridge.action.START_PERIPHERAL"
        const val ACTION_START_SCAN = "com.op2r.iosbridge.action.START_SCAN"
        const val ACTION_RESET_BLE = "com.op2r.iosbridge.action.RESET_BLE"
        const val ACTION_RESYNC_ANCS = "com.op2r.iosbridge.action.RESYNC_ANCS"
        const val ACTION_TRIGGER_TEST_ALERT = "com.op2r.iosbridge.action.TRIGGER_TEST_ALERT"
        const val ACTION_SET_IGNORE_PRE_EXISTING = "com.op2r.iosbridge.action.SET_IGNORE_PRE_EXISTING"
        const val ACTION_PERFORM_CALL_ACTION = "com.op2r.iosbridge.action.PERFORM_CALL_ACTION"

        // Broadcast UI State & Full-Screen Message Popup Updates
        const val ACTION_STATE_BROADCAST = "com.op2r.iosbridge.action.STATE_BROADCAST"
        const val ACTION_NEW_NOTIFICATION_BROADCAST = "com.op2r.iosbridge.action.NEW_NOTIFICATION_BROADCAST"
        const val ACTION_DISMISS_NOTIFICATION_BROADCAST = "com.op2r.iosbridge.action.DISMISS_NOTIFICATION_BROADCAST"

        const val EXTRA_CONNECTION_STATE = "extra_connection_state"
        const val EXTRA_IGNORE_PRE_EXISTING = "extra_ignore_pre_existing"
        const val EXTRA_NS_COUNT = "extra_ns_count"
        const val EXTRA_DS_COUNT = "extra_ds_count"
        const val EXTRA_LAST_EVENT = "extra_last_event"
        const val EXTRA_NOTIFICATION_UID = "extra_notification_uid"
        const val EXTRA_ANCS_ACTION_ID = "extra_ancs_action_id"
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_IS_CALL = "extra_is_call"

        // SharedPreferences Keys
        const val PREFS_NAME = "op2r_ancs_bridge_prefs"
        const val PREF_IGNORE_PRE_EXISTING = "pref_ignore_pre_existing"

        // Notification Channels & IDs
        private const val CHANNEL_FOREGROUND_SERVICE = "op2r_ancs_fgs_channel"
        private const val CHANNEL_ALERTS = "op2r_ancs_alerts_channel_v3"
        private const val CHANNEL_CALLS = "op2r_ancs_calls_channel_v3"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_BASE_ID = 2000

        // Proximity Scan RSSI Threshold (Touch watch to back of iPhone)
        private const val PROXIMITY_RSSI_THRESHOLD_DBM = -65
        private const val GATT_ERROR_133 = 133
        private const val TARGET_MTU = 247

        @Volatile
        var currentStatusText: String = "Disconnected"
            private set

        @Volatile
        var nsPacketCount: Int = 0
            private set

        @Volatile
        var dsPacketCount: Int = 0
            private set

        @Volatile
        var lastEventSummary: String = "Idle"
            private set
    }

    // Sealed class representing sequential, thread-safe GATT operations
    private sealed class GattOperation {
        data class RequestMtu(val mtu: Int) : GattOperation()
        data class WriteDescriptor(
            val characteristic: BluetoothGattCharacteristic,
            val descriptor: BluetoothGattDescriptor,
            val payload: ByteArray
        ) : GattOperation()
        data class RequestNotificationAttributes(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray,
            val notificationUid: Int
        ) : GattOperation()
        data class WriteActionCommand(
            val characteristic: BluetoothGattCharacteristic,
            val payload: ByteArray
        ) : GattOperation()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLockRenewalJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var postBondResyncJob: Job? = null
    private var gattOperationWatchdogJob: Job? = null

    // Tracks 850ms fallback jobs per NotificationUID in case iOS Data Source is delayed
    private val pendingFallbackJobs = ConcurrentHashMap<Int, Job>()
    // Tracks whether a full Data Source notification has already been displayed for a UID
    private val completedDataSourceUids = ConcurrentHashMap.newKeySet<Int>()

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var activeBluetoothGatt: BluetoothGatt? = null
    private var connectedIphoneDevice: BluetoothDevice? = null

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private var partialWakeLock: PowerManager.WakeLock? = null

    private val gattQueue = ConcurrentLinkedQueue<GattOperation>()
    private val isGattOperationInFlight = AtomicBoolean(false)
    private var waitingForDataSourceUid: Int? = null
    private var initialSubscriptionTimestampMs: Long = 0L

    private var isAdvertising = false
    private var isScanning = false
    private var isBondReceiverRegistered = false
    private var ignorePreExistingAlerts = true

    private val ancsPacketParser = AncsPacketParser { assembledNotification ->
        onDataSourceNotificationAssembled(assembledNotification)
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ignorePreExistingAlerts = prefs.getBoolean(PREF_IGNORE_PRE_EXISTING, true)

        createNotificationChannels()
        startPersistentForegroundService()
        acquireAndMaintainPartialWakeLock()
        registerBondStateReceiver()
        updateAndBroadcastStatus("Disconnected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PERIPHERAL -> {
                stopModeBScanning()
                startModeAPeripheralAdvertising()
                tryAutoConnectBondedIphone()
            }
            ACTION_START_SCAN -> {
                stopModeAPeripheralAdvertising()
                startModeBProximityScanning()
            }
            ACTION_RESYNC_ANCS -> {
                resyncAncsSubscriptions("Manual Re-Sync")
            }
            ACTION_TRIGGER_TEST_ALERT -> {
                triggerLocalDiagnosticTestAlert()
                resyncAncsSubscriptions("Test + Re-Sync")
            }
            ACTION_RESET_BLE -> {
                resetAndUnbindBle()
            }
            ACTION_SET_IGNORE_PRE_EXISTING -> {
                val ignore = intent.getBooleanExtra(EXTRA_IGNORE_PRE_EXISTING, true)
                ignorePreExistingAlerts = ignore
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_IGNORE_PRE_EXISTING, ignore)
                    .apply()
                updateAndBroadcastStatus(currentStatusText)
            }
            ACTION_PERFORM_CALL_ACTION -> {
                val notificationUid = intent.getIntExtra(EXTRA_NOTIFICATION_UID, -1)
                val actionId = intent.getByteExtra(EXTRA_ANCS_ACTION_ID, AncsPacketParser.ACTION_ID_NEGATIVE)
                if (notificationUid != -1) {
                    performAncsCallAction(notificationUid, actionId)
                }
            }
            else -> {
                // Service started on app launch: if an iPhone is already bonded, auto-connect
                tryAutoConnectBondedIphone()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterBondStateReceiver()
        stopModeAPeripheralAdvertising()
        stopModeBScanning()
        closeActiveGattClient()
        closeLocalGattServer()
        releasePartialWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =========================================================================================
    // 1. ANTI-KILL COUNTERMEASURES (OnePlus Watch 2R Wear OS 5.0 Dual-Engine)
    // =========================================================================================

    private fun startPersistentForegroundService() {
        val notification = buildForegroundServiceNotification(currentStatusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun acquireAndMaintainPartialWakeLock() {
        if (partialWakeLock == null) {
            partialWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OP2RBridge::AncsCoreWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        acquireWakeLockWindow()

        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = serviceScope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                acquireWakeLockWindow()
            }
        }
    }

    private fun acquireWakeLockWindow() {
        try {
            partialWakeLock?.acquire(20 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire PARTIAL_WAKE_LOCK", e)
        }
    }

    private fun releasePartialWakeLock() {
        wakeLockRenewalJob?.cancel()
        try {
            if (partialWakeLock?.isHeld == true) {
                partialWakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release PARTIAL_WAKE_LOCK", e)
        }
    }

    // =========================================================================================
    // 2. MODE A: WATCH AS PERIPHERAL (GATT SERVER + BLE ADVERTISER SOLICITING ANCS)
    // =========================================================================================

    private fun startModeAPeripheralAdvertising() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateAndBroadcastStatus("Bluetooth Disabled")
            return
        }

        try {
            adapter.name = ADVERTISED_DEVICE_NAME
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set Bluetooth adapter name", e)
        }

        setupLocalGattServer()

        bluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            updateAndBroadcastStatus("BLE Advertiser Unsupported")
            return
        }

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advertiseDataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))

        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Solicit Apple's ANCS Service UUID so iOS treats OP2R-Bridge as an ANCS consumer
            scanResponseBuilder.addServiceSolicitationUuid(ParcelUuid(ANCS_SERVICE_UUID))
        } else {
            scanResponseBuilder.addServiceUuid(ParcelUuid(ANCS_SERVICE_UUID))
        }

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            bluetoothLeAdvertiser?.startAdvertising(
                advertiseSettings,
                advertiseDataBuilder.build(),
                scanResponseBuilder.build(),
                advertiseCallback
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE advertising", e)
            updateAndBroadcastStatus("Advertise Failed: ${e.message}")
        }
    }

    private fun setupLocalGattServer() {
        if (bluetoothGattServer != null) return
        try {
            bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

            // Standard Heart Rate Service (0x180D) so iOS recognizes watch as a valid accessory
            val heartRateService = BluetoothGattService(
                HEART_RATE_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val hrMeasurementChar = BluetoothGattCharacteristic(
                HEART_RATE_MEASUREMENT_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val hrCccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            hrMeasurementChar.addDescriptor(hrCccd)
            heartRateService.addCharacteristic(hrMeasurementChar)

            // Standard Current Time Service (0x1805)
            val currentTimeService = BluetoothGattService(
                CURRENT_TIME_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val currentTimeChar = BluetoothGattCharacteristic(
                CURRENT_TIME_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            currentTimeService.addCharacteristic(currentTimeChar)

            bluetoothGattServer?.addService(heartRateService)
            serviceScope.launch {
                delay(200L)
                bluetoothGattServer?.addService(currentTimeService)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open local BluetoothGattServer", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.i(TAG, "Peripheral advertising started as $ADVERTISED_DEVICE_NAME")
            if (activeBluetoothGatt == null) {
                updateAndBroadcastStatus("Advertising as $ADVERTISED_DEVICE_NAME")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Peripheral advertising failed with code: $errorCode")
            if (activeBluetoothGatt == null) {
                updateAndBroadcastStatus("Advertise Error ($errorCode)")
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "iPhone connected to watch GATT Server: ${device.address}")
                connectedIphoneDevice = device
                connectToIphoneGattServer(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val responseBytes = byteArrayOf(0x00, 72)
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                responseBytes
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }
    }

    private fun stopModeAPeripheralAdvertising() {
        if (isAdvertising) {
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertiser", e)
            }
            isAdvertising = false
        }
    }

    private fun closeLocalGattServer() {
        try {
            bluetoothGattServer?.clearServices()
            bluetoothGattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT server", e)
        } finally {
            bluetoothGattServer = null
        }
    }

    // =========================================================================================
    // 3. MODE B: WATCH AS CENTRAL / PROXIMITY SCANNER FALLBACK (RSSI > -65 dBm)
    // =========================================================================================

    private fun startModeBProximityScanning() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateAndBroadcastStatus("Bluetooth Disabled")
            return
        }

        bluetoothLeScanner = adapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            updateAndBroadcastStatus("BLE Scanner Unsupported")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            isScanning = true
            updateAndBroadcastStatus("Scanning (Touch Watch to iPhone)")

            scanTimeoutJob?.cancel()
            scanTimeoutJob = serviceScope.launch {
                delay(45_000L)
                if (isScanning) {
                    stopModeBScanning()
                    if (activeBluetoothGatt == null) {
                        updateAndBroadcastStatus("Scan Timed Out - Disconnected")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
            updateAndBroadcastStatus("Scan Error: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            val device = result.device ?: return

            if (rssi > PROXIMITY_RSSI_THRESHOLD_DBM) {
                Log.i(TAG, "Proximity iPhone beacon detected (${device.address}) at $rssi dBm")
                stopModeBScanning()
                connectedIphoneDevice = device
                updateAndBroadcastStatus("Detected (${rssi} dBm) - Bonding")

                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    device.createBond()
                }
                connectToIphoneGattServer(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
            updateAndBroadcastStatus("Scan Failed ($errorCode)")
        }
    }

    private fun stopModeBScanning() {
        scanTimeoutJob?.cancel()
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE scanner", e)
            }
            isScanning = false
        }
    }

    // =========================================================================================
    // 4. REVERSE GATT CLIENT CONNECTION, AUTO-BOND RECOVERY & POST-BOND RESYNC WAVES
    // =========================================================================================

    private fun tryAutoConnectBondedIphone() {
        if (activeBluetoothGatt != null) return
        try {
            // Check already-connected GATT devices first
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            if (connectedDevices.isNotEmpty()) {
                val dev = connectedDevices.first()
                connectedIphoneDevice = dev
                connectToIphoneGattServer(dev)
                return
            }
            // Otherwise check bonded devices for an iPhone / BLE peer
            val bonded = bluetoothAdapter?.bondedDevices?.firstOrNull { device ->
                val name = device.name.orEmpty()
                name.contains("iPhone", ignoreCase = true) ||
                    device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
            if (bonded != null) {
                Log.i(TAG, "Found bonded iOS candidate: ${bonded.name} (${bonded.address})")
                connectedIphoneDevice = bonded
                connectToIphoneGattServer(bonded)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect bonded devices", e)
        }
    }

    private fun connectToIphoneGattServer(device: BluetoothDevice) {
        reconnectJob?.cancel()
        if (activeBluetoothGatt != null && activeBluetoothGatt?.device?.address == device.address) {
            return
        }
        closeActiveGattClient()
        Log.i(TAG, "Connecting GATT Client to iPhone: ${device.address} over TRANSPORT_LE")
        activeBluetoothGatt = device.connectGatt(
            this,
            false,
            gattClientCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    /**
     * Re-discovers and re-writes the ANCS Data Source and Notification Source CCCD descriptors.
     * Essential on iOS because if the initial CCCD write happened while the user was still tapping
     * "Allow OP2R-Bridge to Receive Your iPhone Notifications?", iOS silently ignores the stream
     * until the watch re-subscribes after authorization is granted.
     */
    private fun resyncAncsSubscriptions(reason: String) {
        val gatt = activeBluetoothGatt
        if (gatt == null) {
            val dev = connectedIphoneDevice
            if (dev != null) {
                connectToIphoneGattServer(dev)
            } else {
                tryAutoConnectBondedIphone()
            }
            return
        }

        Log.i(TAG, "Re-syncing ANCS CCCD subscriptions ($reason)")
        lastEventSummary = "Re-Sync ($reason)"
        updateAndBroadcastStatus("Re-Syncing ANCS...")

        clearGattQueue()
        val ancsService = gatt.getService(ANCS_SERVICE_UUID)
        if (ancsService == null) {
            gatt.discoverServices()
            return
        }

        val dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID)
        if (dataSourceChar != null) {
            subscribeToCharacteristicNotifications(gatt, dataSourceChar)
        } else {
            gatt.discoverServices()
        }
    }

    /**
     * Schedules automatic post-bond re-subscription waves at +3.5s and +8.5s if no ANCS packets
     * have arrived yet, catching the exact moment after the user taps "Allow" on the iOS popup.
     */
    private fun schedulePostBondResyncWaves() {
        postBondResyncJob?.cancel()
        postBondResyncJob = serviceScope.launch {
            delay(3500L)
            if (isActive && activeBluetoothGatt != null && nsPacketCount == 0) {
                Log.i(TAG, "Post-bond wave 1 (+3.5s): Re-affirming ANCS CCCD subscription")
                resyncAncsSubscriptions("Auto +3.5s")
            }
            delay(5000L)
            if (isActive && activeBluetoothGatt != null && nsPacketCount == 0) {
                Log.i(TAG, "Post-bond wave 2 (+8.5s): Re-affirming ANCS CCCD subscription")
                resyncAncsSubscriptions("Auto +8.5s")
            }
        }
    }

    private fun scheduleGattReconnect(device: BluetoothDevice, reason: String) {
        Log.w(TAG, "Scheduling GATT reconnect in 1000ms ($reason) for ${device.address}")
        postBondResyncJob?.cancel()
        clearGattQueue()
        ancsPacketParser.reset()

        try {
            activeBluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT before reconnect", e)
        } finally {
            activeBluetoothGatt = null
        }

        updateAndBroadcastStatus("Reconnecting ($reason)...")
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(1000L)
            // Use autoConnect=true on subsequent reconnects so BLE link recovers seamlessly
            activeBluetoothGatt = device.connectGatt(
                this@AncsBridgeService,
                false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)

            if (device != null) {
                // Note: iOS RPA address may resolve to identity address upon bonding, so update reference
                connectedIphoneDevice = device
                when (bondState) {
                    BluetoothDevice.BOND_BONDING -> {
                        Log.i(TAG, "Bonding in progress with ${device.address}")
                        updateAndBroadcastStatus("Bonding with iPhone...")
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "Bond established with ${device.address}; discovering ANCS services")
                        updateAndBroadcastStatus("Bonded - Discovering ANCS...")
                        val gatt = activeBluetoothGatt
                        if (gatt != null) {
                            clearGattQueue()
                            gatt.discoverServices()
                        } else {
                            connectToIphoneGattServer(device)
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.w(TAG, "Bond lost or rejected with ${device.address}")
                        updateAndBroadcastStatus("Unbonded - Tap Mode A or B")
                    }
                }
            }
        }
    }

    private fun registerBondStateReceiver() {
        if (!isBondReceiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(bondStateReceiver, filter)
            }
            isBondReceiverRegistered = true
        }
    }

    private fun unregisterBondStateReceiver() {
        if (isBondReceiverRegistered) {
            try {
                unregisterReceiver(bondStateReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering bond receiver", e)
            }
            isBondReceiverRegistered = false
        }
    }

    // =========================================================================================
    // 5. THREAD-SAFE GATT OPERATION QUEUE WITH DATA SOURCE COMPLETION LOCK & WATCHDOG
    // =========================================================================================

    private fun enqueueGattOperation(operation: GattOperation) {
        gattQueue.add(operation)
        processNextGattOperation()
    }

    private fun clearGattQueue() {
        gattOperationWatchdogJob?.cancel()
        waitingForDataSourceUid = null
        gattQueue.clear()
        isGattOperationInFlight.set(false)
    }

    private fun onGattOperationCompleted() {
        gattOperationWatchdogJob?.cancel()
        waitingForDataSourceUid = null
        isGattOperationInFlight.set(false)
        processNextGattOperation()
    }

    private fun startGattWatchdog(timeoutMs: Long, label: String) {
        gattOperationWatchdogJob?.cancel()
        gattOperationWatchdogJob = serviceScope.launch {
            delay(timeoutMs)
            if (isGattOperationInFlight.get()) {
                Log.w(TAG, "GATT watchdog expired after ${timeoutMs}ms for $label; advancing queue")
                onGattOperationCompleted()
            }
        }
    }

    private fun processNextGattOperation() {
        val gatt = activeBluetoothGatt ?: run {
            clearGattQueue()
            return
        }

        if (!isGattOperationInFlight.compareAndSet(false, true)) {
            return
        }

        val nextOp = gattQueue.poll()
        if (nextOp == null) {
            isGattOperationInFlight.set(false)
            return
        }

        val dispatched = when (nextOp) {
            is GattOperation.RequestMtu -> {
                startGattWatchdog(2000L, "RequestMtu")
                gatt.requestMtu(nextOp.mtu)
            }
            is GattOperation.WriteDescriptor -> {
                startGattWatchdog(2500L, "WriteDescriptor(${nextOp.characteristic.uuid})")
                // Always enable local characteristic notification right when writing CCCD
                gatt.setCharacteristicNotification(nextOp.characteristic, true)
                writeGattDescriptorCompat(gatt, nextOp.descriptor, nextOp.payload)
            }
            is GattOperation.RequestNotificationAttributes -> {
                waitingForDataSourceUid = nextOp.notificationUid
                ancsPacketParser.prepareForAttributeRequest(nextOp.notificationUid)
                // Hold lock until Data Source finishes reassembling (or 1200ms watchdog expires)
                startGattWatchdog(1200L, "GetNotificationAttributes(UID=${nextOp.notificationUid})")
                writeGattCharacteristicCompat(
                    gatt,
                    nextOp.characteristic,
                    nextOp.payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            }
            is GattOperation.WriteActionCommand -> {
                startGattWatchdog(1500L, "PerformNotificationAction")
                writeGattCharacteristicCompat(
                    gatt,
                    nextOp.characteristic,
                    nextOp.payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            }
        }

        if (!dispatched) {
            Log.w(TAG, "GATT operation failed to dispatch: $nextOp")
            onGattOperationCompleted()
        }
    }

    private fun writeGattDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        payload: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, payload)
            status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = payload
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun writeGattCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, payload, writeType)
            status == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            connectedIphoneDevice = device

            if (status == GATT_ERROR_133) {
                scheduleGattReconnect(device, "GATT_ERROR 133")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT Client connected to ${device.address}")
                    clearGattQueue()
                    ancsPacketParser.reset()
                    enqueueGattOperation(GattOperation.RequestMtu(TARGET_MTU))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT Client disconnected from ${device.address} (status=$status)")
                    scheduleGattReconnect(device, "Disconnected ($status)")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            onGattOperationCompleted()
            gatt.discoverServices()
        }

        /**
         * Critical on Android 13/14 (Wear OS 5.0): When iOS toggles "Share System Notifications"
         * or updates its GATT table after pairing, iOS sends a Service Changed indication.
         */
        override fun onServiceChanged(gatt: BluetoothGatt) {
            Log.i(TAG, "onServiceChanged received from iPhone; re-discovering ANCS services")
            clearGattQueue()
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onServicesDiscovered failed with status: $status")
                return
            }

            val ancsService = gatt.getService(ANCS_SERVICE_UUID)
            if (ancsService == null) {
                Log.w(TAG, "ANCS Service hidden or missing; initiating bond with iPhone")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    updateAndBroadcastStatus("Bonding Required for ANCS...")
                    gatt.device.createBond()
                } else {
                    serviceScope.launch {
                        delay(800L)
                        activeBluetoothGatt?.discoverServices()
                    }
                }
                return
            }

            val dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID)
            val notificationSourceChar = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID)

            if (dataSourceChar == null || notificationSourceChar == null) {
                Log.e(TAG, "ANCS Data Source or Notification Source characteristic missing")
                return
            }

            // CRITICAL ANCS RULE:
            // Step 1: Subscribe to Data Source (22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB) FIRST.
            subscribeToCharacteristicNotifications(gatt, dataSourceChar)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid
            onGattOperationCompleted()

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed on $charUuid with status $status")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                } else {
                    // Retry subscription after 1.5s if encryption was still settling
                    serviceScope.launch {
                        delay(1500L)
                        resyncAncsSubscriptions("Retry Status $status")
                    }
                }
                return
            }

            if (charUuid == DATA_SOURCE_UUID) {
                Log.i(TAG, "ANCS Data Source subscribed! Now subscribing to Notification Source...")
                val ancsService = gatt.getService(ANCS_SERVICE_UUID)
                val notificationSourceChar = ancsService?.getCharacteristic(NOTIFICATION_SOURCE_UUID)
                if (notificationSourceChar != null) {
                    // Step 2: Subscribe to Notification Source ONLY AFTER Data Source CCCD write succeeds
                    subscribeToCharacteristicNotifications(gatt, notificationSourceChar)
                }
            } else if (charUuid == NOTIFICATION_SOURCE_UUID) {
                Log.i(TAG, "ANCS Notification Source subscribed! Bridge is fully active.")
                if (initialSubscriptionTimestampMs == 0L) {
                    initialSubscriptionTimestampMs = SystemClock.elapsedRealtime()
                    schedulePostBondResyncWaves()
                }
                stopModeAPeripheralAdvertising()
                updateAndBroadcastStatus("Bonded - Listening to ANCS")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Control Point write failed with status: $status")
                onGattOperationCompleted()
                return
            }

            // If we are waiting for Data Source attributes for a NotificationUID, keep the queue lock
            // held until Data Source completes (or the 1200ms watchdog fires).
            if (waitingForDataSourceUid == null) {
                onGattOperationCompleted()
            }
        }

        // Android 13+ / Wear OS 5.0 (API 33/34) Non-Deprecated 3-Parameter Callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicNotification(gatt, characteristic.uuid, value.clone())
        }

        // Fallback for API 30-32
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleCharacteristicNotification(gatt, characteristic.uuid, value.clone())
            }
        }
    }

    private fun subscribeToCharacteristicNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
        enqueueGattOperation(
            GattOperation.WriteDescriptor(
                characteristic = characteristic,
                descriptor = cccd,
                payload = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        )
    }

    private fun handleCharacteristicNotification(
        gatt: BluetoothGatt,
        charUuid: UUID,
        value: ByteArray
    ) {
        when (charUuid) {
            NOTIFICATION_SOURCE_UUID -> {
                val event = ancsPacketParser.parseNotificationSourcePayload(value) ?: return
                nsPacketCount += 1
                val catName = AncsPacketParser.resolveCategoryName(event.categoryId)
                lastEventSummary = "NS #${event.notificationUid} ($catName)"
                updateAndBroadcastStatus("Bonded - Listening to ANCS")

                // If EventID == 2 (Removed), cancel corresponding notification on watch
                if (event.eventId == AncsPacketParser.EVENT_ID_NOTIFICATION_REMOVED) {
                    pendingFallbackJobs.remove(event.notificationUid)?.cancel()
                    completedDataSourceUids.remove(event.notificationUid)
                    mainHandler.post {
                        notificationManager.cancel(resolveAndroidNotificationId(event.notificationUid))
                        broadcastDismissNotificationToWatchUi(event.notificationUid)
                    }
                    return
                }

                // Smart Pre-Existing Filter:
                // Only ignore PreExisting (0x04) during the first 2.5 seconds of initial connection dump
                // (never ignore Incoming Calls, and never drop live notifications after connect!)
                val withinInitialDumpWindow = initialSubscriptionTimestampMs > 0L &&
                    (SystemClock.elapsedRealtime() - initialSubscriptionTimestampMs) < 2500L
                if (ignorePreExistingAlerts &&
                    event.isPreExisting &&
                    withinInitialDumpWindow &&
                    event.categoryId != AncsPacketParser.CATEGORY_ID_INCOMING_CALL
                ) {
                    Log.d(TAG, "Ignoring initial dump PreExisting UID=${event.notificationUid}")
                    return
                }

                if (event.eventId == AncsPacketParser.EVENT_ID_NOTIFICATION_ADDED ||
                    event.eventId == AncsPacketParser.EVENT_ID_NOTIFICATION_MODIFIED
                ) {
                    // 1. If Incoming Call (CategoryID == 1), wake watch and ring IMMEDIATELY on the 8-byte packet!
                    if (event.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL &&
                        !completedDataSourceUids.contains(event.notificationUid)
                    ) {
                        val immediateCallAlert = ancsPacketParser.buildFallbackFromSourceEvent(event)
                        dispatchAlertToWatchMainThread(immediateCallAlert)
                    } else {
                        // 2. For standard messages, schedule an 850ms fallback so if Data Source stalls,
                        // the watch still wakes and shows the notification!
                        scheduleNotificationFallback(event)
                    }

                    // 3. Enqueue serialized Control Point GetNotificationAttributes (0x00) request
                    val controlPointChar = gatt.getService(ANCS_SERVICE_UUID)
                        ?.getCharacteristic(CONTROL_POINT_UUID) ?: return

                    val requestCommand = ancsPacketParser.buildGetNotificationAttributesCommand(
                        event.notificationUid
                    )
                    enqueueGattOperation(
                        GattOperation.RequestNotificationAttributes(
                            characteristic = controlPointChar,
                            payload = requestCommand,
                            notificationUid = event.notificationUid
                        )
                    )
                }
            }

            DATA_SOURCE_UUID -> {
                ancsPacketParser.onDataSourcePacketReceived(value)
            }
        }
    }

    private fun scheduleNotificationFallback(event: AncsPacketParser.NotificationSourceEvent) {
        pendingFallbackJobs.remove(event.notificationUid)?.cancel()
        val job = serviceScope.launch {
            delay(850L)
            if (!completedDataSourceUids.contains(event.notificationUid)) {
                Log.w(TAG, "Data Source delayed >850ms for UID=${event.notificationUid}; firing fallback alert")
                val fallbackAlert = ancsPacketParser.buildFallbackFromSourceEvent(event)
                dispatchAlertToWatchMainThread(fallbackAlert)
            }
        }
        pendingFallbackJobs[event.notificationUid] = job
    }

    // =========================================================================================
    // 6. WEAR OS 5.0 SCREEN WAKE, FULL-SCREEN MESSAGE POPUP & CALL ACTIONS
    // =========================================================================================

    private fun onDataSourceNotificationAssembled(alert: AncsPacketParser.AssembledAncsNotification) {
        dsPacketCount += 1
        lastEventSummary = "${alert.appDisplayName}: ${alert.title}"
        completedDataSourceUids.add(alert.notificationUid)
        pendingFallbackJobs.remove(alert.notificationUid)?.cancel()

        // Release the serialized Control Point lock now that Data Source has finished
        if (waitingForDataSourceUid == alert.notificationUid) {
            onGattOperationCompleted()
        }

        updateAndBroadcastStatus("Bonded - Listening to ANCS")
        dispatchAlertToWatchMainThread(alert)
    }

    private fun dispatchAlertToWatchMainThread(alert: AncsPacketParser.AssembledAncsNotification) {
        mainHandler.post {
            if (alert.isIncomingCall) {
                wakeWatchScreen(12_000L)
                triggerIncomingCallVibration()
                postIncomingCallWatchNotification(alert)
                presentFullScreenMessagePopupOnWatch(alert)
            } else {
                wakeWatchScreen(8_000L)
                if (!alert.isSilent) {
                    triggerMessageHapticVibration()
                }
                postStandardWatchNotification(alert)
                presentFullScreenMessagePopupOnWatch(alert)
            }
        }
    }

    /**
     * Triggers a local hardware test popup & vibration on the OnePlus Watch 2R so the user
     * can verify Wear OS 5.0 permissions, screen wake, and full-screen message rendering at any time.
     */
    private fun triggerLocalDiagnosticTestAlert() {
        val testAlert = AncsPacketParser.AssembledAncsNotification(
            notificationUid = 9999,
            categoryId = AncsPacketParser.CATEGORY_ID_SOCIAL,
            eventFlags = 0,
            appBundleId = "net.whatsapp.WhatsApp",
            appDisplayName = "WhatsApp (Test)",
            title = "OP2R Watch Hardware OK",
            message = "Screen wake, vibration, and full-screen popup are working on your OnePlus Watch 2R! Re-syncing iPhone ANCS link now...",
            isFallbackOnly = false
        )
        lastEventSummary = "Test Alert Fired"
        dispatchAlertToWatchMainThread(testAlert)
    }

    private fun wakeWatchScreen(durationMs: Long) {
        try {
            @Suppress("DEPRECATION")
            val screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "OP2RBridge::AlertScreenWakeLock"
            )
            screenWakeLock.acquire(durationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to wake screen for alert", e)
        }
    }

    private fun presentFullScreenMessagePopupOnWatch(alert: AncsPacketParser.AssembledAncsNotification) {
        // 1. Broadcast to MainActivity if already visible
        val broadcastIntent = Intent(ACTION_NEW_NOTIFICATION_BROADCAST).apply {
            setPackage(packageName)
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
            putExtra(EXTRA_APP_NAME, alert.appDisplayName)
            putExtra(EXTRA_TITLE, alert.title)
            putExtra(EXTRA_MESSAGE, alert.message)
            putExtra(EXTRA_IS_CALL, alert.isIncomingCall)
        }
        sendBroadcast(broadcastIntent)

        // 2. Launch MainActivity over lockscreen/ambient display so the message card is immediately visible
        try {
            val popupActivityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
                putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
                putExtra(EXTRA_APP_NAME, alert.appDisplayName)
                putExtra(EXTRA_TITLE, alert.title)
                putExtra(EXTRA_MESSAGE, alert.message)
                putExtra(EXTRA_IS_CALL, alert.isIncomingCall)
            }
            startActivity(popupActivityIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen activity launch handled via notification fullScreenIntent", e)
        }
    }

    private fun broadcastDismissNotificationToWatchUi(notificationUid: Int) {
        val intent = Intent(ACTION_DISMISS_NOTIFICATION_BROADCAST).apply {
            setPackage(packageName)
            putExtra(EXTRA_NOTIFICATION_UID, notificationUid)
        }
        sendBroadcast(intent)
    }

    private fun triggerIncomingCallVibration() {
        val vibrator = getWatchVibrator()
        val pattern = longArrayOf(0L, 450L, 250L, 450L, 600L, 450L, 250L, 450L)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun triggerMessageHapticVibration() {
        val vibrator = getWatchVibrator()
        val pattern = longArrayOf(0L, 140L, 90L, 180L)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun getWatchVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun resolveAndroidNotificationId(ancsUid: Int): Int {
        return ALERT_NOTIFICATION_BASE_ID + (ancsUid and 0x0FFFFFFF)
    }

    private fun buildFullScreenPopupPendingIntent(
        alert: AncsPacketParser.AssembledAncsNotification
    ): PendingIntent {
        val fullScreenLaunchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
            putExtra(EXTRA_APP_NAME, alert.appDisplayName)
            putExtra(EXTRA_TITLE, alert.title)
            putExtra(EXTRA_MESSAGE, alert.message)
            putExtra(EXTRA_IS_CALL, alert.isIncomingCall)
        }
        return PendingIntent.getActivity(
            this,
            resolveAndroidNotificationId(alert.notificationUid),
            fullScreenLaunchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postIncomingCallWatchNotification(alert: AncsPacketParser.AssembledAncsNotification) {
        val notifyId = resolveAndroidNotificationId(alert.notificationUid)
        val acceptIntent = Intent(this, AncsBridgeService::class.java).apply {
            action = ACTION_PERFORM_CALL_ACTION
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_ANCS_ACTION_ID, AncsPacketParser.ACTION_ID_POSITIVE)
        }
        val acceptPendingIntent = PendingIntent.getService(
            this,
            notifyId * 2,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, AncsBridgeService::class.java).apply {
            action = ACTION_PERFORM_CALL_ACTION
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_ANCS_ACTION_ID, AncsPacketParser.ACTION_ID_NEGATIVE)
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            notifyId * 2 + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPendingIntent = buildFullScreenPopupPendingIntent(alert)

        val acceptAction = Notification.Action.Builder(
            android.R.drawable.sym_action_call,
            "Accept",
            acceptPendingIntent
        ).build()

        val declineAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Decline",
            declinePendingIntent
        ).build()

        val callNotification = Notification.Builder(this, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(alert.title)
            .setContentText(alert.message)
            .setSubText("iPhone Call")
            .setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle(alert.title)
                    .bigText(alert.message)
                    .setSummaryText("Incoming iPhone Call")
            )
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(acceptAction)
            .addAction(declineAction)
            .build()

        notificationManager.notify(notifyId, callNotification)
    }

    private fun postStandardWatchNotification(alert: AncsPacketParser.AssembledAncsNotification) {
        val notifyId = resolveAndroidNotificationId(alert.notificationUid)
        val fullScreenPendingIntent = buildFullScreenPopupPendingIntent(alert)

        val bigTextStyle = Notification.BigTextStyle()
            .setBigContentTitle("${alert.appDisplayName}: ${alert.title}")
            .bigText(alert.message)
            .setSummaryText(alert.appDisplayName)

        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("${alert.appDisplayName}: ${alert.title}")
            .setContentText(alert.message)
            .setSubText(alert.appDisplayName)
            .setStyle(bigTextStyle)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .extend(Notification.WearableExtender())
            .build()

        notificationManager.notify(notifyId, notification)
    }

    /**
     * Sends CommandIDPerformNotificationAction (0x02) + NotificationUID (4B LE) + ActionID (0x00/0x01)
     * to the ANCS Control Point characteristic.
     */
    private fun performAncsCallAction(notificationUid: Int, actionId: Byte) {
        val gatt = activeBluetoothGatt
        val controlPointChar = gatt?.getService(ANCS_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_POINT_UUID)

        if (controlPointChar != null) {
            val actionPayload = ancsPacketParser.buildPerformNotificationActionCommand(
                notificationUid = notificationUid,
                actionId = actionId
            )

            enqueueGattOperation(
                GattOperation.WriteActionCommand(
                    characteristic = controlPointChar,
                    payload = actionPayload
                )
            )
        }

        // Dismiss the ongoing call notification & watch popup after answering or declining
        notificationManager.cancel(resolveAndroidNotificationId(notificationUid))
        broadcastDismissNotificationToWatchUi(notificationUid)
    }

    // =========================================================================================
    // 7. BLE RESET / UNBIND & NOTIFICATION CHANNEL HELPERS
    // =========================================================================================

    private fun resetAndUnbindBle() {
        reconnectJob?.cancel()
        postBondResyncJob?.cancel()
        stopModeAPeripheralAdvertising()
        stopModeBScanning()
        clearGattQueue()
        ancsPacketParser.reset()
        pendingFallbackJobs.values.forEach { it.cancel() }
        pendingFallbackJobs.clear()
        completedDataSourceUids.clear()
        initialSubscriptionTimestampMs = 0L
        nsPacketCount = 0
        dsPacketCount = 0
        lastEventSummary = "Reset"
        closeActiveGattClient()
        closeLocalGattServer()

        connectedIphoneDevice?.let { device ->
            try {
                val removeBondMethod = device.javaClass.getMethod("removeBond")
                removeBondMethod.invoke(device)
            } catch (e: Exception) {
                Log.w(TAG, "Programmatic removeBond not permitted; cleared GATT session", e)
            }
        }
        connectedIphoneDevice = null
        updateAndBroadcastStatus("Disconnected")
    }

    private fun closeActiveGattClient() {
        try {
            activeBluetoothGatt?.disconnect()
            activeBluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing GATT client", e)
        } finally {
            activeBluetoothGatt = null
        }
    }

    private fun createNotificationChannels() {
        val fgsChannel = NotificationChannel(
            CHANNEL_FOREGROUND_SERVICE,
            "ANCS BLE Bridge Background Link",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the OnePlus Watch 2R BLE ANCS socket alive when screen is off"
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "iPhone Messages & App Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wakes screen and displays bridged iPhone messages, WhatsApp, and alerts"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "iPhone Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Bridged iPhone incoming call alerts with Accept/Decline controls"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannels(
            listOf(fgsChannel, alertsChannel, callsChannel)
        )
    }

    private fun buildForegroundServiceNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_FOREGROUND_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OP2R-Bridge Active")
            .setContentText("$statusText · NS:$nsPacketCount DS:$dsPacketCount")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateAndBroadcastStatus(newStatus: String) {
        currentStatusText = newStatus
        notificationManager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundServiceNotification(newStatus)
        )

        val broadcast = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONNECTION_STATE, newStatus)
            putExtra(EXTRA_IGNORE_PRE_EXISTING, ignorePreExistingAlerts)
            putExtra(EXTRA_NS_COUNT, nsPacketCount)
            putExtra(EXTRA_DS_COUNT, dsPacketCount)
            putExtra(EXTRA_LAST_EVENT, lastEventSummary)
        }
        sendBroadcast(broadcast)
    }
}
