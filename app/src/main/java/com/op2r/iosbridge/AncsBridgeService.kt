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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Wear OS 5.0 (API 34) Foreground BLE ANCS Bridge Service for OnePlus Watch 2R (OPWWE234).
 *
 * Implements:
 * - Apple Watch-grade Always-Alive BLE Connection:
 *   1. Persistent Solicited ANCS Peripheral Advertising ("OP2R-Bridge" soliciting 7905F431-...)
 *      that stays active across disconnects so iOS CoreBluetooth automatically re-establishes the link.
 *   2. 20-second Keepalive Heartbeat (readRemoteRssi + CONNECTION_PRIORITY_BALANCED) to prevent
 *      Snapdragon W5 / BES2700 co-processor and iOS supervision timeout disconnects.
 *   3. Continuous 5-second Auto-Reconnect Watchdog loop using autoConnect=true and bonded device scanning.
 * - Reliable ANCS Data Source Attribute 0x03 (Message Body, 256B) & Caller ID (0x01 Title + 0x02 Subtitle)
 *   pipeline with Android 14 GATT BUSY retry protection and 350ms idle-stream flush.
 */
@SuppressLint("MissingPermission")
class AncsBridgeService : Service() {

    companion object {
        private const val TAG = "OP2R_ANCS_Bridge"

        // Exact Apple Notification Center Service (ANCS) 128-bit UUIDs
        val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Service Intent Actions
        const val ACTION_START_MODE_A_PERIPHERAL = "com.op2r.iosbridge.action.START_MODE_A_PERIPHERAL"
        const val ACTION_START_MODE_B_CENTRAL = "com.op2r.iosbridge.action.START_MODE_B_CENTRAL"
        const val ACTION_RESYNC_ANCS = "com.op2r.iosbridge.action.RESYNC_ANCS"
        const val ACTION_SIMULATE_TEST_ALERT = "com.op2r.iosbridge.action.SIMULATE_TEST_ALERT"
        const val ACTION_PERFORM_POSITIVE = "com.op2r.iosbridge.action.PERFORM_POSITIVE"
        const val ACTION_PERFORM_NEGATIVE = "com.op2r.iosbridge.action.PERFORM_NEGATIVE"
        const val ACTION_STOP_BRIDGE = "com.op2r.iosbridge.action.STOP_BRIDGE"

        // Full-Screen Popup Activity Extras for MainActivity
        const val ACTION_SHOW_WATCH_POPUP = "com.op2r.iosbridge.action.SHOW_WATCH_POPUP"
        const val EXTRA_NOTIFICATION_UID = "extra_notification_uid"
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SUBTITLE = "extra_subtitle"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_HAS_POSITIVE = "extra_has_positive"
        const val EXTRA_HAS_NEGATIVE = "extra_has_negative"

        // Notification Channels & IDs
        private const val CHANNEL_FOREGROUND_BRIDGE = "op2r_ancs_foreground_channel"
        private const val CHANNEL_INCOMING_CALLS = "op2r_ancs_incoming_calls_channel"
        private const val CHANNEL_IOS_MESSAGES = "op2r_ancs_ios_messages_channel"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val INCOMING_CALL_NOTIFICATION_BASE_ID = 2000
        private const val MESSAGE_NOTIFICATION_BASE_ID = 3000

        // Preferred BLE MTU for 256-byte ANCS Data Source payloads
        private const val PREFERRED_ANCS_MTU = 247

        // Keepalive & Reconnect intervals
        private const val KEEPALIVE_HEARTBEAT_INTERVAL_MS = 20_000L
        private const val AUTO_RECONNECT_INTERVAL_MS = 5_000L
        private const val DATA_SOURCE_IDLE_FLUSH_MS = 350L
        private const val ZERO_DATA_SOURCE_FALLBACK_MS = 1_800L

        private val _bridgeState = MutableStateFlow(BridgeUiState())
        val bridgeState: StateFlow<BridgeUiState> = _bridgeState.asStateFlow()
    }

    enum class DiscoveryMode {
        MODE_A_PERIPHERAL_SOLICITATION,
        MODE_B_CENTRAL_SCANNER
    }

    data class BridgeUiState(
        val statusText: String = "IDLE",
        val discoveryMode: DiscoveryMode = DiscoveryMode.MODE_A_PERIPHERAL_SOLICITATION,
        val connectedDeviceName: String? = null,
        val connectedDeviceAddress: String? = null,
        val isBonded: Boolean = false,
        val isAncsSubscribed: Boolean = false,
        val negotiatedMtu: Int = 23,
        val rxSourcePackets: Int = 0,
        val rxDataPackets: Int = 0,
        val keepAlivePings: Int = 0,
        val lastRssi: Int = 0,
        val lastNotification: AncsPacketParser.AssembledAncsNotification? = null,
        val diagnosticLog: List<String> = emptyList()
    )

    private sealed class GattOperation {
        abstract val retryCount: Int
        abstract fun withIncrementedRetry(): GattOperation

        data class EnableNotificationWithCccd(
            val characteristic: BluetoothGattCharacteristic,
            val label: String,
            override val retryCount: Int = 0
        ) : GattOperation() {
            override fun withIncrementedRetry() = copy(retryCount = retryCount + 1)
        }

        data class WriteCccdDescriptor(
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
            val label: String,
            override val retryCount: Int = 0
        ) : GattOperation() {
            override fun withIncrementedRetry() = copy(retryCount = retryCount + 1)
        }

        data class RequestNotificationAttributes(
            val controlPoint: BluetoothGattCharacteristic,
            val commandBytes: ByteArray,
            val notificationUid: Int,
            override val retryCount: Int = 0
        ) : GattOperation() {
            override fun withIncrementedRetry() = copy(retryCount = retryCount + 1)
        }

        data class PerformNotificationAction(
            val controlPoint: BluetoothGattCharacteristic,
            val commandBytes: ByteArray,
            val notificationUid: Int,
            val positive: Boolean,
            override val retryCount: Int = 0
        ) : GattOperation() {
            override fun withIncrementedRetry() = copy(retryCount = retryCount + 1)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var activeGatt: BluetoothGatt? = null
    private var targetIosDevice: BluetoothDevice? = null

    private var notificationSourceCharacteristic: BluetoothGattCharacteristic? = null
    private var controlPointCharacteristic: BluetoothGattCharacteristic? = null
    private var dataSourceCharacteristic: BluetoothGattCharacteristic? = null

    // Thread-safe Sequential GATT Operation Queue with single-flight lock & timeout watchdog
    private val gattQueue = ConcurrentLinkedQueue<GattOperation>()
    private val gattQueueLock = Any()
    @Volatile
    private var isGattOperationInFlight = false
    private var activeInFlightOperation: GattOperation? = null
    private var gattWatchdogRunnable: Runnable? = null

    private var isAdvertisingModeA = false
    private var isScanningModeB = false
    private var userStoppedBridge = false

    // Tracks whether any Data Source chunk arrived for the active NotificationUID
    private val dataSourceReceivedForUid = ConcurrentHashMap<Int, Boolean>()
    // Tracks pending fallback runnables per NotificationUID
    private val pendingFallbackRunnables = ConcurrentHashMap<Int, Runnable>()
    // Idle-stream flush runnable that flushes partial Data Source attributes 350ms after the last chunk
    private var dataSourceIdleFlushRunnable: Runnable? = null

    // Ignore pre-existing notification bursts unless it's an active Incoming Call
    private var ignorePreExistingAlerts = true

    // Always-Alive BLE Coroutine Jobs
    private var keepAliveHeartbeatJob: Job? = null
    private var continuousReconnectJob: Job? = null

    private val ancsPacketParser = AncsPacketParser { assembledNotification ->
        mainHandler.post {
            onAncsNotificationReady(assembledNotification)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannels()
        registerBondStateReceiver()
        startBridgeForegroundNotification("OP2R ANCS Bridge Always-On")
        appendLog("Service created on API ${Build.VERSION.SDK_INT}")

        // Start the continuous Apple Watch-style auto-reconnect & solicitation watchdog
        startContinuousAutoReconnectWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MODE_A_PERIPHERAL -> {
                userStoppedBridge = false
                startModeAPeripheralAdvertising()
            }
            ACTION_START_MODE_B_CENTRAL -> {
                userStoppedBridge = false
                startModeBCentralScanning()
            }
            ACTION_RESYNC_ANCS -> {
                resyncAncsSubscriptions()
            }
            ACTION_SIMULATE_TEST_ALERT -> {
                triggerSimulatedTestAlert()
            }
            ACTION_PERFORM_POSITIVE -> {
                val uid = intent.getIntExtra(EXTRA_NOTIFICATION_UID, -1)
                if (uid != -1) {
                    enqueueNotificationAction(uid, positive = true)
                    cancelWatchNotificationForUid(uid)
                }
            }
            ACTION_PERFORM_NEGATIVE -> {
                val uid = intent.getIntExtra(EXTRA_NOTIFICATION_UID, -1)
                if (uid != -1) {
                    enqueueNotificationAction(uid, positive = false)
                    cancelWatchNotificationForUid(uid)
                }
            }
            ACTION_STOP_BRIDGE -> {
                userStoppedBridge = true
                disconnectAndStopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                userStoppedBridge = false
                if (!isAdvertisingModeA && !isScanningModeB && activeGatt == null) {
                    startModeAPeripheralAdvertising()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterBondStateReceiver()
        disconnectAndStopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =========================================================================
    // APPLE WATCH-GRADE ALWAYS-ALIVE BLE ENGINE (HEARTBEAT + AUTO-RECONNECT)
    // =========================================================================

    /**
     * Runs a continuous 20-second keepalive heartbeat while connected to iPhone.
     * - Requests BluetoothGatt.CONNECTION_PRIORITY_BALANCED so neither iOS nor the
     *   OnePlus Watch 2R BES2700 co-processor drops the supervision link during idle periods.
     * - Calls gatt.readRemoteRssi() every 20s to generate lightweight link-layer traffic.
     */
    private fun startKeepAliveHeartbeat(gatt: BluetoothGatt) {
        keepAliveHeartbeatJob?.cancel()
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        } catch (e: Exception) {
            Log.w(TAG, "requestConnectionPriority exception: ${e.message}")
        }

        keepAliveHeartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(KEEPALIVE_HEARTBEAT_INTERVAL_MS)
                val currentGatt = activeGatt ?: break
                try {
                    val ok = currentGatt.readRemoteRssi()
                    if (ok) {
                        _bridgeState.value = _bridgeState.value.copy(
                            keepAlivePings = _bridgeState.value.keepAlivePings + 1
                        )
                    } else {
                        appendLog("Keepalive RSSI ping returned false; verifying link")
                    }
                } catch (e: Exception) {
                    appendLog("Keepalive error: ${e.message}")
                }
            }
        }
    }

    private fun stopKeepAliveHeartbeat() {
        keepAliveHeartbeatJob?.cancel()
        keepAliveHeartbeatJob = null
    }

    /**
     * Runs continuously in the background like an Apple Watch companion daemon:
     * Whenever the watch is not actively subscribed to ANCS (and the user hasn't stopped the bridge):
     * 1. Ensures Mode A Solicited ANCS Peripheral Advertising ("OP2R-Bridge") is running so iOS
     *    CoreBluetooth immediately spots the watch and reconnects automatically.
     * 2. Attempts background autoConnect GATT connection to the last known or bonded iPhone.
     */
    private fun startContinuousAutoReconnectWatchdog() {
        continuousReconnectJob?.cancel()
        continuousReconnectJob = serviceScope.launch {
            while (isActive) {
                delay(AUTO_RECONNECT_INTERVAL_MS)
                if (userStoppedBridge) continue

                val isSubscribed = _bridgeState.value.isAncsSubscribed
                if (isSubscribed && activeGatt != null) {
                    // Already connected and subscribed — keep Solicited Advertising alive in background
                    // in case iOS cycles its link, and let the 20s heartbeat maintain supervision.
                    continue
                }

                // Ensure Mode A Solicited ANCS Advertising is active so iOS auto-connects on sight
                if (!isAdvertisingModeA &&
                    _bridgeState.value.discoveryMode == DiscoveryMode.MODE_A_PERIPHERAL_SOLICITATION
                ) {
                    appendLog("Always-On Watchdog: Restarting ANCS Solicited Advertising")
                    startModeAPeripheralAdvertising()
                }

                // If we aren't currently mid-connection, initiate autoConnect to target or bonded iPhone
                if (activeGatt == null) {
                    val candidateDevice = targetIosDevice ?: findBondedIphoneDevice()
                    if (candidateDevice != null) {
                        targetIosDevice = candidateDevice
                        appendLog("Always-On Watchdog: Auto-connecting to ${candidateDevice.address}")
                        connectOrInitializeAncsGattClient(candidateDevice, useAutoConnect = true)
                    }
                }
            }
        }
    }

    private fun findBondedIphoneDevice(): BluetoothDevice? {
        val bonded = bluetoothAdapter?.bondedDevices.orEmpty()
        return bonded.firstOrNull { device ->
            val name = device.name.orEmpty().lowercase()
            name.contains("iphone") || name.contains("ios") || name.contains("apple")
        } ?: bonded.firstOrNull()
    }

    // =========================================================================
    // MODE A: PERIPHERAL ADVERTISING WITH SERVICE SOLICITATION (7905F431-...)
    // =========================================================================

    private fun startModeAPeripheralAdvertising() {
        stopModeBCentralScanning()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("BLUETOOTH OFF", DiscoveryMode.MODE_A_PERIPHERAL_SOLICITATION)
            appendLog("Bluetooth adapter disabled or unavailable")
            return
        }

        try {
            adapter.name = "OP2R-Bridge"
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to set adapter name: ${e.message}")
        }

        ensureGattServerStarted()

        if (isAdvertisingModeA) {
            // Already advertising Mode A
            return
        }

        bleAdvertiser = adapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            appendLog("BLE Advertiser unsupported; switching to Mode B Central Scanner")
            startModeBCentralScanning()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // Never time out advertising so watch stays discoverable/reconnectable
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Primary advertisement packet: Flags + Solicited ANCS 128-bit UUID (fits strictly <= 31B)
        val advertiseDataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            advertiseDataBuilder.addServiceSolicitationUuid(ParcelUuid(ANCS_SERVICE_UUID))
        } else {
            advertiseDataBuilder.addServiceUuid(ParcelUuid(ANCS_SERVICE_UUID))
        }
        val advertiseData = advertiseDataBuilder.build()

        // Scan response packet: carries the "OP2R-Bridge" device name for iOS Settings -> Bluetooth
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, modeAAdvertiseCallback)
            isAdvertisingModeA = true
            if (!_bridgeState.value.isAncsSubscribed) {
                updateStatus("ADVERTISING ANCS SOLICITATION", DiscoveryMode.MODE_A_PERIPHERAL_SOLICITATION)
                startBridgeForegroundNotification("Advertising OP2R-Bridge to iPhone...")
            }
            appendLog("Mode A started: Soliciting ANCS UUID $ANCS_SERVICE_UUID")
        } catch (e: Exception) {
            appendLog("Mode A startAdvertising exception: ${e.message}")
        }
    }

    private fun stopModeAPeripheralAdvertising() {
        if (!isAdvertisingModeA) return
        try {
            bleAdvertiser?.stopAdvertising(modeAAdvertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising failed: ${e.message}")
        } finally {
            isAdvertisingModeA = false
        }
    }

    private val modeAAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            appendLog("Mode A advertisement active (LOW_LATENCY, Connectable)")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertisingModeA = false
            appendLog("Mode A advertise failed (code=$errorCode)")
        }
    }

    private fun ensureGattServerStarted() {
        if (gattServer != null) return
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        } catch (e: Exception) {
            appendLog("openGattServer error: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("GATT Server: iOS Central connected (${device.address})")
                targetIosDevice = device
                mainHandler.post {
                    connectOrInitializeAncsGattClient(device, useAutoConnect = false)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("GATT Server: Central disconnected (${device.address})")
                mainHandler.post {
                    if (!userStoppedBridge) {
                        startModeAPeripheralAdvertising()
                    }
                }
            }
        }
    }

    // =========================================================================
    // MODE B: CENTRAL SCANNER FALLBACK + BONDED DEVICE CONNECTOR
    // =========================================================================

    private fun startModeBCentralScanning() {
        stopModeAPeripheralAdvertising()
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("BLUETOOTH OFF", DiscoveryMode.MODE_B_CENTRAL_SCANNER)
            return
        }

        val bondedDevices = adapter.bondedDevices.orEmpty()
        val bondedIphone = findBondedIphoneDevice()
        if (bondedIphone != null) {
            appendLog("Found bonded iOS device: ${bondedIphone.name ?: bondedIphone.address}")
            targetIosDevice = bondedIphone
            connectOrInitializeAncsGattClient(bondedIphone, useAutoConnect = false)
        } else if (bondedDevices.isEmpty()) {
            appendLog("No bonded iPhone found yet — starting BLE scanner")
        }

        bleScanner = adapter.bluetoothLeScanner
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceSolicitationUuid(ParcelUuid(ANCS_SERVICE_UUID))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(ANCS_SERVICE_UUID))
                .build()
        )

        try {
            bleScanner?.startScan(filters, scanSettings, modeBScanCallback)
            isScanningModeB = true
            updateStatus("SCANNING FOR IPHONE (MODE B)", DiscoveryMode.MODE_B_CENTRAL_SCANNER)
            startBridgeForegroundNotification("Scanning for iPhone ANCS...")
            appendLog("Mode B BLE scanner started")
        } catch (e: Exception) {
            try {
                bleScanner?.startScan(modeBScanCallback)
                isScanningModeB = true
                appendLog("Mode B unfiltered BLE scan active")
            } catch (ex: Exception) {
                appendLog("Mode B scan failure: ${ex.message}")
            }
        }
    }

    private fun stopModeBCentralScanning() {
        if (!isScanningModeB) return
        try {
            bleScanner?.stopScan(modeBScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error: ${e.message}")
        } finally {
            isScanningModeB = false
        }
    }

    private val modeBScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val isLikelyIos = name.contains("iPhone", ignoreCase = true) ||
                device.bondState == BluetoothDevice.BOND_BONDED ||
                result.scanRecord?.manufacturerSpecificData?.get(0x004C) != null

            if (isLikelyIos && activeGatt == null) {
                appendLog("Mode B discovered iOS candidate: ${name.ifBlank { device.address }}")
                stopModeBCentralScanning()
                targetIosDevice = device
                connectOrInitializeAncsGattClient(device, useAutoConnect = false)
            }
        }
    }

    // =========================================================================
    // GATT CLIENT CONNECTION, BONDING, MTU NEGOTIATION & ANCS DISCOVERY
    // =========================================================================

    private fun connectOrInitializeAncsGattClient(
        device: BluetoothDevice,
        useAutoConnect: Boolean = false
    ) {
        if (activeGatt != null && _bridgeState.value.connectedDeviceAddress == device.address) {
            appendLog("Refreshing services on existing GATT for ${device.address}")
            activeGatt?.discoverServices()
            return
        }

        closeActiveGatt()
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        _bridgeState.value = _bridgeState.value.copy(
            statusText = if (useAutoConnect) "AUTO-RECONNECTING..." else "CONNECTING GATT...",
            connectedDeviceName = device.name ?: "iPhone",
            connectedDeviceAddress = device.address,
            isBonded = bonded,
            isAncsSubscribed = false
        )

        appendLog(
            "Opening GATT client to ${device.address} (bond=${device.bondState}, autoConnect=$useAutoConnect)"
        )
        activeGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, useAutoConnect, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, useAutoConnect, gattClientCallback)
        }
    }

    /**
     * Re-discovers and re-subscribes to ANCS Data Source + Notification Source on the active link.
     */
    private fun resyncAncsSubscriptions() {
        val gatt = activeGatt
        val device = targetIosDevice
        appendLog("Re-syncing ANCS GATT subscriptions...")
        clearGattQueue()
        ancsPacketParser.resetParser()
        ignorePreExistingAlerts = true
        if (gatt != null) {
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            } catch (_: Exception) {}
            gatt.discoverServices()
        } else if (device != null) {
            connectOrInitializeAncsGattClient(device, useAutoConnect = false)
        } else {
            startModeAPeripheralAdvertising()
        }
    }

    /**
     * Injects a realistic multi-packet ANCS Notification Source + 256-byte Data Source burst
     * through the exact binary parser and full-screen popup pipeline to verify watch UI + vibration.
     */
    private fun triggerSimulatedTestAlert() {
        appendLog("Injecting local ANCS test alert (UID #9999)...")
        val testUid = 9999

        val sourcePacket = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(AncsPacketParser.EVENT_ID_NOTIFICATION_ADDED.toByte())
            .put(
                (AncsPacketParser.EVENT_FLAG_IMPORTANT or
                    AncsPacketParser.EVENT_FLAG_POSITIVE_ACTION or
                    AncsPacketParser.EVENT_FLAG_NEGATIVE_ACTION).toByte()
            )
            .put(AncsPacketParser.CATEGORY_ID_SOCIAL.toByte())
            .put(1.toByte())
            .putInt(testUid)
            .array()

        ancsPacketParser.parseNotificationSourcePacket(sourcePacket)

        val appIdBytes = "net.whatsapp.WhatsApp".toByteArray(StandardCharsets.UTF_8)
        val titleBytes = "Sarah Jenkins".toByteArray(StandardCharsets.UTF_8)
        val subtitleBytes = "+1 (415) 890-4321".toByteArray(StandardCharsets.UTF_8)
        val messageBytes = (
            "Hey! This is a full 256-byte ANCS Attribute 0x03 payload reassembled across BLE MTU " +
                "fragments on your OnePlus Watch 2R. Caller ID subtitle and full scrollable body are active!"
            ).toByteArray(StandardCharsets.UTF_8)

        val payload = ByteBuffer.allocate(
            5 + 3 + appIdBytes.size + 3 + titleBytes.size + 3 + subtitleBytes.size + 3 + messageBytes.size
        ).order(ByteOrder.LITTLE_ENDIAN)
            .put(AncsPacketParser.COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES)
            .putInt(testUid)
            .put(AncsPacketParser.ATTRIBUTE_ID_APP_IDENTIFIER)
            .putShort(appIdBytes.size.toShort())
            .put(appIdBytes)
            .put(AncsPacketParser.ATTRIBUTE_ID_TITLE)
            .putShort(titleBytes.size.toShort())
            .put(titleBytes)
            .put(AncsPacketParser.ATTRIBUTE_ID_SUBTITLE)
            .putShort(subtitleBytes.size.toShort())
            .put(subtitleBytes)
            .put(AncsPacketParser.ATTRIBUTE_ID_MESSAGE)
            .putShort(messageBytes.size.toShort())
            .put(messageBytes)
            .array()

        // Feed in 64-byte chunks to exercise multi-packet Data Source fragmentation reassembly
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + 64, payload.size)
            ancsPacketParser.onDataSourceChunkReceived(payload.copyOfRange(offset, end))
            offset = end
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                appendLog("GATT Client CONNECTED to ${device.address} (bonded=$bonded)")
                _bridgeState.value = _bridgeState.value.copy(
                    statusText = if (bonded) "CONNECTED (BONDED)" else "PAIRING / BONDING...",
                    connectedDeviceName = device.name ?: "iPhone",
                    connectedDeviceAddress = device.address,
                    isBonded = bonded
                )
                startBridgeForegroundNotification("Connected to ${device.name ?: "iPhone"}")
                startKeepAliveHeartbeat(gatt)

                // Request MTU 247 first; discoverServices() runs in onMtuChanged (or fallback after 600ms)
                mainHandler.postDelayed({
                    val requested = gatt.requestMtu(PREFERRED_ANCS_MTU)
                    if (!requested) {
                        gatt.discoverServices()
                    }
                }, 250L)

                if (!bonded) {
                    appendLog("Initiating SMP bond (createBond) for ANCS encryption")
                    device.createBond()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("GATT Client DISCONNECTED (status=$status) — Auto-Reconnect engaged")
                stopKeepAliveHeartbeat()
                clearGattQueue()
                ancsPacketParser.resetParser()
                _bridgeState.value = _bridgeState.value.copy(
                    statusText = "RECONNECTING TO IPHONE...",
                    isAncsSubscribed = false
                )
                startBridgeForegroundNotification("Reconnecting to iPhone...")
                closeActiveGatt()

                if (!userStoppedBridge) {
                    // Restart Mode A Solicited ANCS Advertising immediately so iOS reconnects on sight,
                    // AND open an autoConnect=true GATT client so Android's BLE controller also reconnects.
                    mainHandler.post {
                        startModeAPeripheralAdvertising()
                        connectOrInitializeAncsGattClient(device, useAutoConnect = true)
                    }
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _bridgeState.value = _bridgeState.value.copy(lastRssi = rssi)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            appendLog("Negotiated BLE MTU = $mtu (status=$status)")
            _bridgeState.value = _bridgeState.value.copy(negotiatedMtu = mtu)
            gatt.discoverServices()
        }

        override fun onServicesChanged(gatt: BluetoothGatt) {
            appendLog("GATT onServicesChanged triggered by iOS — re-discovering ANCS")
            clearGattQueue()
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("onServicesDiscovered failed: status=$status")
                return
            }

            val ancsService = gatt.getService(ANCS_SERVICE_UUID)
            if (ancsService == null) {
                appendLog("ANCS Service not visible yet — awaiting bond / encrypted link")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                } else {
                    mainHandler.postDelayed({ gatt.discoverServices() }, 1500L)
                }
                return
            }

            notificationSourceCharacteristic = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID)
            controlPointCharacteristic = ancsService.getCharacteristic(CONTROL_POINT_UUID)
            dataSourceCharacteristic = ancsService.getCharacteristic(DATA_SOURCE_UUID)

            if (notificationSourceCharacteristic == null ||
                controlPointCharacteristic == null ||
                dataSourceCharacteristic == null
            ) {
                appendLog("ANCS characteristics incomplete on service")
                return
            }

            appendLog("ANCS discovered! Subscribing Data Source (1st) -> Notification Source (2nd)")
            enqueueAncsSubscriptionSequence()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ||
                status == 137
            ) {
                appendLog("CCCD write requires encryption (status=$status) — bonding with iPhone")
                completeCurrentGattOperation()
                gatt.device.createBond()
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("CCCD 0x2902 enabled for ${shortUuid(charUuid)}")
                if (charUuid == NOTIFICATION_SOURCE_UUID) {
                    ignorePreExistingAlerts = true
                    // Ignore iOS's initial dump of old Notification Center items for 3 seconds,
                    // then allow live notifications and any modified alerts through.
                    mainHandler.postDelayed({
                        ignorePreExistingAlerts = false
                    }, 3000L)

                    _bridgeState.value = _bridgeState.value.copy(
                        statusText = "BONDED • ALWAYS-ON ANCS",
                        isBonded = true,
                        isAncsSubscribed = true
                    )
                    startBridgeForegroundNotification("Connected to iPhone • Always-On ANCS")
                    appendLog("ANCS Live! Ready for Caller ID & 256B Message Body")
                }
            } else {
                appendLog("CCCD write failed for ${shortUuid(charUuid)} (status=$status)")
            }

            completeCurrentGattOperation()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CONTROL_POINT_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLog("Control Point write status=$status")
                }
            }
            completeCurrentGattOperation()
        }

        // Android 13 / 14 (API 33 / 34 - Wear OS 5.0) callback signature
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleAncsCharacteristicPayload(characteristic.uuid, value)
        }

        // Backward-compatible callback for older Android runtimes
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val bytes = characteristic.value ?: return
                handleAncsCharacteristicPayload(characteristic.uuid, bytes)
            }
        }
    }

    // =========================================================================
    // SEQUENTIAL GATT QUEUE ENGINE WITH ANDROID 14 BUSY RETRY PROTECTION
    // =========================================================================

    private fun enqueueAncsSubscriptionSequence() {
        val dataSource = dataSourceCharacteristic ?: return
        val notifSource = notificationSourceCharacteristic ?: return

        clearGattQueue()
        // Strictly subscribe to Data Source FIRST, then Notification Source SECOND
        enqueueGattOperation(
            GattOperation.EnableNotificationWithCccd(
                characteristic = dataSource,
                label = "DataSource (22EA)"
            )
        )
        enqueueGattOperation(
            GattOperation.EnableNotificationWithCccd(
                characteristic = notifSource,
                label = "NotificationSource (9FBF)"
            )
        )
    }

    private fun enqueueNotificationAttributesRequest(notificationUid: Int) {
        val controlPoint = controlPointCharacteristic ?: return
        val cmd = ancsPacketParser.buildGetNotificationAttributesCommand(notificationUid)
        enqueueGattOperation(
            GattOperation.RequestNotificationAttributes(
                controlPoint = controlPoint,
                commandBytes = cmd,
                notificationUid = notificationUid
            )
        )
    }

    fun enqueueNotificationAction(notificationUid: Int, positive: Boolean) {
        val controlPoint = controlPointCharacteristic ?: return
        val cmd = ancsPacketParser.buildPerformNotificationActionCommand(notificationUid, positive)
        appendLog("Queueing Control Point Action (uid=$notificationUid, positive=$positive)")
        enqueueGattOperation(
            GattOperation.PerformNotificationAction(
                controlPoint = controlPoint,
                commandBytes = cmd,
                notificationUid = notificationUid,
                positive = positive
            )
        )
    }

    private fun enqueueGattOperation(operation: GattOperation) {
        synchronized(gattQueueLock) {
            gattQueue.add(operation)
        }
        mainHandler.post { processNextGattOperation() }
    }

    private fun completeCurrentGattOperation() {
        cancelGattWatchdog()
        synchronized(gattQueueLock) {
            isGattOperationInFlight = false
            activeInFlightOperation = null
        }
        // Post to main looper with a tiny 15ms breather so Android's internal mDeviceBusy flag clears
        mainHandler.postDelayed({ processNextGattOperation() }, 15L)
    }

    private fun clearGattQueue() {
        cancelGattWatchdog()
        synchronized(gattQueueLock) {
            gattQueue.clear()
            isGattOperationInFlight = false
            activeInFlightOperation = null
        }
    }

    private fun scheduleGattWatchdog(label: String) {
        cancelGattWatchdog()
        val runnable = Runnable {
            val shouldRelease = synchronized(gattQueueLock) {
                if (isGattOperationInFlight) {
                    isGattOperationInFlight = false
                    activeInFlightOperation = null
                    true
                } else {
                    false
                }
            }
            if (shouldRelease) {
                appendLog("GATT watchdog released lock ($label)")
                processNextGattOperation()
            }
        }
        gattWatchdogRunnable = runnable
        mainHandler.postDelayed(runnable, 2500L)
    }

    private fun cancelGattWatchdog() {
        gattWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        gattWatchdogRunnable = null
    }

    private fun processNextGattOperation() {
        val gatt = activeGatt ?: return
        val nextOp: GattOperation = synchronized(gattQueueLock) {
            if (isGattOperationInFlight) return
            val op = gattQueue.poll() ?: return
            isGattOperationInFlight = true
            activeInFlightOperation = op
            op
        }

        when (nextOp) {
            is GattOperation.EnableNotificationWithCccd -> {
                val localOk = gatt.setCharacteristicNotification(nextOp.characteristic, true)
                val cccd = nextOp.characteristic.getDescriptor(CCCD_UUID)
                if (!localOk || cccd == null) {
                    appendLog("Failed local setCharacteristicNotification for ${nextOp.label}")
                    completeCurrentGattOperation()
                    return
                }
                scheduleGattWatchdog("CCCD ${nextOp.label}")
                val writeStarted = writeGattDescriptorCompat(
                    gatt = gatt,
                    descriptor = cccd,
                    value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
                if (!writeStarted) {
                    retryOrCompleteBusyOperation(nextOp, "CCCD ${nextOp.label}")
                }
            }

            is GattOperation.WriteCccdDescriptor -> {
                scheduleGattWatchdog("Descriptor ${nextOp.label}")
                val writeStarted = writeGattDescriptorCompat(gatt, nextOp.descriptor, nextOp.value)
                if (!writeStarted) {
                    retryOrCompleteBusyOperation(nextOp, "Descriptor ${nextOp.label}")
                }
            }

            is GattOperation.RequestNotificationAttributes -> {
                scheduleGattWatchdog("GetAttrs UID=${nextOp.notificationUid}")
                val writeStarted = writeGattCharacteristicCompat(
                    gatt = gatt,
                    characteristic = nextOp.controlPoint,
                    value = nextOp.commandBytes,
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (!writeStarted) {
                    retryOrCompleteBusyOperation(nextOp, "GetAttrs UID=${nextOp.notificationUid}")
                }
            }

            is GattOperation.PerformNotificationAction -> {
                scheduleGattWatchdog("Action UID=${nextOp.notificationUid}")
                val writeStarted = writeGattCharacteristicCompat(
                    gatt = gatt,
                    characteristic = nextOp.controlPoint,
                    value = nextOp.commandBytes,
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (!writeStarted) {
                    retryOrCompleteBusyOperation(nextOp, "Action UID=${nextOp.notificationUid}")
                }
            }
        }
    }

    /**
     * If Android 14's Bluetooth stack returns false / ERROR_GATT_WRITE_REQUEST_BUSY (201)
     * because an incoming Notification Source notification callback was still finishing on the Binder thread,
     * retry up to 4 times at 80ms intervals instead of dropping the Control Point command!
     */
    private fun retryOrCompleteBusyOperation(op: GattOperation, label: String) {
        cancelGattWatchdog()
        if (op.retryCount < 4) {
            val retried = op.withIncrementedRetry()
            synchronized(gattQueueLock) {
                isGattOperationInFlight = false
                activeInFlightOperation = null
                gattQueue.add(retried)
            }
            mainHandler.postDelayed({ processNextGattOperation() }, 80L)
        } else {
            appendLog("GATT write busy after 4 retries ($label)")
            completeCurrentGattOperation()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeGattDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeDescriptor(descriptor, value)
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        } catch (e: Exception) {
            appendLog("writeDescriptor exception: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun writeGattCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val status = gatt.writeCharacteristic(characteristic, value, writeType)
                status == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            appendLog("writeCharacteristic exception: ${e.message}")
            false
        }
    }

    // =========================================================================
    // ANCS PACKET DISPATCH, DATA SOURCE 0x03 EXTRACTION & WEAR OS NOTIFICATIONS
    // =========================================================================

    private fun handleAncsCharacteristicPayload(charUuid: UUID, bytes: ByteArray) {
        when (charUuid) {
            NOTIFICATION_SOURCE_UUID -> {
                _bridgeState.value = _bridgeState.value.copy(
                    rxSourcePackets = _bridgeState.value.rxSourcePackets + 1
                )
                val event = ancsPacketParser.parseNotificationSourcePacket(bytes) ?: return
                when (event.eventId) {
                    AncsPacketParser.EVENT_ID_NOTIFICATION_ADDED,
                    AncsPacketParser.EVENT_ID_NOTIFICATION_MODIFIED -> {
                        // Filter out pre-existing notification bursts during initial bond or re-sync
                        // (except active Incoming Calls, which should always ring immediately)
                        if (event.isPreExisting &&
                            ignorePreExistingAlerts &&
                            event.categoryId != AncsPacketParser.CATEGORY_ID_INCOMING_CALL
                        ) {
                            return
                        }

                        appendLog(
                            "ANCS Source RX: UID=${event.notificationUid} Cat=${event.categoryId} Flags=0x${
                                event.eventFlags.toString(16)
                            }"
                        )
                        dataSourceReceivedForUid.remove(event.notificationUid)

                        // Request AppIdentifier (0x00), Title (0x01), Subtitle (0x02 Caller ID),
                        // and Message Body (0x03, 256B) from Control Point
                        enqueueNotificationAttributesRequest(event.notificationUid)

                        // For Incoming Calls (CategoryID == 1), immediately wake the watch and ring
                        // while Control Point fetches Caller Name (0x01) + Phone Number (0x02) (~60ms).
                        // For regular messages, wait for the real Attribute 0x03 Data Source payload!
                        if (event.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL) {
                            val immediateCallAlert = buildFallbackFromSourceEvent(event)
                            mainHandler.post {
                                triggerWatchHapticFeedback(isIncomingCall = true)
                                postIncomingCallWatchNotification(immediateCallAlert)
                                presentFullScreenMessagePopupOnWatch(immediateCallAlert)
                            }
                        } else {
                            scheduleZeroDataSourceFallback(event)
                        }
                    }

                    AncsPacketParser.EVENT_ID_NOTIFICATION_REMOVED -> {
                        cancelPendingFallback(event.notificationUid)
                        dataSourceReceivedForUid.remove(event.notificationUid)
                        cancelWatchNotificationForUid(event.notificationUid)
                    }
                }
            }

            DATA_SOURCE_UUID -> {
                _bridgeState.value = _bridgeState.value.copy(
                    rxDataPackets = _bridgeState.value.rxDataPackets + 1
                )
                // If this chunk starts a GetNotificationAttributes response (CommandID == 0x00),
                // mark that Data Source is actively streaming for this UID and cancel the generic fallback!
                if (bytes.size >= 5 && bytes[0] == AncsPacketParser.COMMAND_ID_GET_NOTIFICATION_ATTRIBUTES) {
                    val uid = ByteBuffer.wrap(bytes, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    dataSourceReceivedForUid[uid] = true
                    cancelPendingFallback(uid)
                }

                ancsPacketParser.onDataSourceChunkReceived(bytes)
                scheduleDataSourceIdleFlush()
            }
        }
    }

    /**
     * Schedules a 350ms idle flush after each Data Source chunk.
     * In the standard case, checkIfAllAttributesComplete() fires immediately on the final chunk of
     * AttributeID 0x03 (Message Body). If iOS omits a trailing attribute or truncates a multibyte
     * UTF-8 sequence at the 256B boundary, this flush guarantees the real extracted attributes
     * (Title, Subtitle/Caller ID, and Message 0x03) are emitted to the watch UI within 350ms.
     */
    private fun scheduleDataSourceIdleFlush() {
        dataSourceIdleFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!ancsPacketParser.hasPendingAssembly()) return

        val runnable = Runnable {
            val flushed = ancsPacketParser.flushPendingPartialAttributes()
            if (flushed != null) {
                appendLog("Flushed partial Data Source stream for UID=${flushed.notificationUid}")
            }
        }
        dataSourceIdleFlushRunnable = runnable
        mainHandler.postDelayed(runnable, DATA_SOURCE_IDLE_FLUSH_MS)
    }

    /**
     * Last-resort fallback that ONLY runs if zero Data Source packets arrived within 1800ms
     * (e.g., if iOS locked out Control Point attributes for a restricted notification).
     */
    private fun scheduleZeroDataSourceFallback(event: AncsPacketParser.NotificationSourceEvent) {
        cancelPendingFallback(event.notificationUid)
        val runnable = Runnable {
            pendingFallbackRunnables.remove(event.notificationUid)
            if (dataSourceReceivedForUid[event.notificationUid] == true) {
                return@Runnable
            }
            // Also check if parser has partial bytes waiting to be flushed first
            val flushed = ancsPacketParser.flushPendingPartialAttributes()
            if (flushed != null) {
                return@Runnable
            }

            val fallbackAlert = buildFallbackFromSourceEvent(event)
            appendLog("Zero Data Source response after ${ZERO_DATA_SOURCE_FALLBACK_MS}ms (UID=${event.notificationUid})")
            onAncsNotificationReady(fallbackAlert)
        }
        pendingFallbackRunnables[event.notificationUid] = runnable
        mainHandler.postDelayed(runnable, ZERO_DATA_SOURCE_FALLBACK_MS)
    }

    private fun cancelPendingFallback(notificationUid: Int) {
        pendingFallbackRunnables.remove(notificationUid)?.let {
            mainHandler.removeCallbacks(it)
        }
    }

    private fun buildFallbackFromSourceEvent(
        event: AncsPacketParser.NotificationSourceEvent
    ): AncsPacketParser.AssembledAncsNotification {
        val isCall = event.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL
        val categoryLabel = when (event.categoryId) {
            AncsPacketParser.CATEGORY_ID_INCOMING_CALL -> "Incoming Call"
            AncsPacketParser.CATEGORY_ID_MISSED_CALL -> "Missed Call"
            AncsPacketParser.CATEGORY_ID_VOICEMAIL -> "Voicemail"
            AncsPacketParser.CATEGORY_ID_SOCIAL -> "Social / Message"
            AncsPacketParser.CATEGORY_ID_SCHEDULE -> "Calendar Alert"
            AncsPacketParser.CATEGORY_ID_EMAIL -> "Email"
            else -> "iPhone Alert"
        }
        return AncsPacketParser.AssembledAncsNotification(
            notificationUid = event.notificationUid,
            categoryId = event.categoryId,
            eventFlags = event.eventFlags,
            appIdentifier = if (isCall) "com.apple.mobilephone" else "com.apple.Notifications",
            title = if (isCall) "Incoming iPhone Call" else categoryLabel,
            subtitle = if (isCall) "Fetching Caller ID..." else "",
            message = if (isCall) {
                "Requesting Caller Name & Number from iPhone..."
            } else {
                "Notification #${event.notificationUid} received from iPhone"
            },
            hasPositiveAction = event.hasPositiveAction || isCall,
            hasNegativeAction = event.hasNegativeAction || true
        )
    }

    private fun onAncsNotificationReady(alert: AncsPacketParser.AssembledAncsNotification) {
        cancelPendingFallback(alert.notificationUid)
        dataSourceIdleFlushRunnable?.let { mainHandler.removeCallbacks(it) }

        val callerInfo = if (alert.subtitle.isNotBlank()) " (${alert.subtitle})" else ""
        appendLog(
            "ANCS 0x03 Ready [UID=${alert.notificationUid}]: ${alert.title}$callerInfo — ${
                alert.message.take(48)
            }"
        )
        _bridgeState.value = _bridgeState.value.copy(lastNotification = alert)

        wakeWatchScreenBriefly()
        triggerWatchHapticFeedback(
            isIncomingCall = alert.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL
        )

        if (alert.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL) {
            postIncomingCallWatchNotification(alert)
        } else {
            postStandardMessageWatchNotification(alert)
        }

        // Launch or update the round full-screen popup on the OnePlus Watch 2R with the full
        // 256B Attribute 0x03 Message Body and Caller ID (Title + Subtitle)
        presentFullScreenMessagePopupOnWatch(alert)
    }

    private fun presentFullScreenMessagePopupOnWatch(alert: AncsPacketParser.AssembledAncsNotification) {
        try {
            val popupIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_SHOW_WATCH_POPUP
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
                putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
                putExtra(EXTRA_APP_ID, alert.appIdentifier)
                putExtra(EXTRA_TITLE, alert.title)
                putExtra(EXTRA_SUBTITLE, alert.subtitle)
                putExtra(EXTRA_MESSAGE, alert.message)
                putExtra(EXTRA_HAS_POSITIVE, alert.hasPositiveAction)
                putExtra(EXTRA_HAS_NEGATIVE, alert.hasNegativeAction)
            }
            startActivity(popupIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch full-screen popup activity directly: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeWatchScreenBriefly() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "op2r:ancs_alert_wakeup"
            )
            wakeLock.acquire(5000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun triggerWatchHapticFeedback(isIncomingCall: Boolean) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            val timings = if (isIncomingCall) {
                longArrayOf(0, 350, 150, 350, 200, 500)
            } else {
                longArrayOf(0, 180, 90, 220)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration error: ${e.message}")
        }
    }

    private fun postIncomingCallWatchNotification(alert: AncsPacketParser.AssembledAncsNotification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_WATCH_POPUP
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
            putExtra(EXTRA_APP_ID, alert.appIdentifier)
            putExtra(EXTRA_TITLE, alert.title)
            putExtra(EXTRA_SUBTITLE, alert.subtitle)
            putExtra(EXTRA_MESSAGE, alert.message)
            putExtra(EXTRA_HAS_POSITIVE, true)
            putExtra(EXTRA_HAS_NEGATIVE, true)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            alert.notificationUid,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptPendingIntent = buildActionPendingIntent(
            action = ACTION_PERFORM_POSITIVE,
            notificationUid = alert.notificationUid,
            requestCodeOffset = 10_000
        )
        val declinePendingIntent = buildActionPendingIntent(
            action = ACTION_PERFORM_NEGATIVE,
            notificationUid = alert.notificationUid,
            requestCodeOffset = 20_000
        )

        // Format Caller ID with both Caller Name (title) and Phone Number (subtitle)
        val callerDisplayName = if (alert.subtitle.isNotBlank() && alert.subtitle != alert.title) {
            "${alert.title} • ${alert.subtitle}"
        } else {
            alert.title.ifBlank { "Incoming Call" }
        }

        val caller = Person.Builder()
            .setName(callerDisplayName)
            .setImportant(true)
            .build()

        val callSubText = when {
            alert.subtitle.isNotBlank() -> "Caller ID: ${alert.subtitle}"
            alert.message.isNotBlank() -> alert.message
            else -> "Incoming iPhone Call"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(alert.title)
            .setContentText(callSubText)
            .setSubText(alert.subtitle.ifBlank { "iPhone Call" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declinePendingIntent,
                    acceptPendingIntent
                )
            )
            .build()

        nm.notify(INCOMING_CALL_NOTIFICATION_BASE_ID + (alert.notificationUid and 0x3FF), notification)
    }

    private fun postStandardMessageWatchNotification(alert: AncsPacketParser.AssembledAncsNotification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_SHOW_WATCH_POPUP
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NOTIFICATION_UID, alert.notificationUid)
            putExtra(EXTRA_CATEGORY_ID, alert.categoryId)
            putExtra(EXTRA_APP_ID, alert.appIdentifier)
            putExtra(EXTRA_TITLE, alert.title)
            putExtra(EXTRA_SUBTITLE, alert.subtitle)
            putExtra(EXTRA_MESSAGE, alert.message)
            putExtra(EXTRA_HAS_POSITIVE, alert.hasPositiveAction)
            putExtra(EXTRA_HAS_NEGATIVE, alert.hasNegativeAction)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            alert.notificationUid,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissPendingIntent = buildActionPendingIntent(
            action = ACTION_PERFORM_NEGATIVE,
            notificationUid = alert.notificationUid,
            requestCodeOffset = 30_000
        )

        val headerTitle = if (alert.subtitle.isNotBlank()) {
            "${alert.title} (${alert.subtitle})"
        } else {
            alert.title
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_IOS_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(headerTitle)
            .setContentText(alert.message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(headerTitle)
                    .bigText(alert.message)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(contentPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss on iPhone",
                dismissPendingIntent
            )

        if (alert.hasPositiveAction) {
            val positivePendingIntent = buildActionPendingIntent(
                action = ACTION_PERFORM_POSITIVE,
                notificationUid = alert.notificationUid,
                requestCodeOffset = 40_000
            )
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "Action",
                positivePendingIntent
            )
        }

        nm.notify(MESSAGE_NOTIFICATION_BASE_ID + (alert.notificationUid and 0x3FF), builder.build())
    }

    private fun cancelWatchNotificationForUid(notificationUid: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(INCOMING_CALL_NOTIFICATION_BASE_ID + (notificationUid and 0x3FF))
        nm.cancel(MESSAGE_NOTIFICATION_BASE_ID + (notificationUid and 0x3FF))
    }

    private fun buildActionPendingIntent(
        action: String,
        notificationUid: Int,
        requestCodeOffset: Int
    ): PendingIntent {
        val intent = Intent(this, AncsBridgeService::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_UID, notificationUid)
        }
        return PendingIntent.getService(
            this,
            requestCodeOffset + (notificationUid and 0xFFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // =========================================================================
    // BOND STATE RECEIVER (AUTO-REDISCOVER ANCS WHEN SMP PAIRING COMPLETES)
    // =========================================================================

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

            if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                appendLog("SMP Bond completed with ${device.address} — discovering ANCS")
                targetIosDevice = device
                _bridgeState.value = _bridgeState.value.copy(
                    isBonded = true,
                    statusText = "BONDED • SUBSCRIBING ANCS"
                )
                if (activeGatt != null) {
                    activeGatt?.discoverServices()
                } else {
                    connectOrInitializeAncsGattClient(device, useAutoConnect = false)
                }
            }
        }
    }

    private fun registerBondStateReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bondStateReceiver, filter)
        }
    }

    private fun unregisterBondStateReceiver() {
        try {
            unregisterReceiver(bondStateReceiver)
        } catch (_: Exception) {}
    }

    // =========================================================================
    // FOREGROUND SERVICE & NOTIFICATION CHANNEL INFRASTRUCTURE
    // =========================================================================

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val bridgeChannel = NotificationChannel(
            CHANNEL_FOREGROUND_BRIDGE,
            "OP2R iOS ANCS Bridge Link",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains persistent BLE ANCS connection with iPhone"
        }

        val callChannel = NotificationChannel(
            CHANNEL_INCOMING_CALLS,
            "iPhone Incoming Calls (ANCS)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen Caller ID alerts with Answer and Decline actions"
            enableVibration(true)
        }

        val msgChannel = NotificationChannel(
            CHANNEL_IOS_MESSAGES,
            "iPhone Messages & App Alerts (ANCS)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Mirrored iOS notifications via ANCS Data Source 0x03"
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(bridgeChannel, callChannel, msgChannel))
    }

    private fun startBridgeForegroundNotification(contentText: String) {
        val openUiIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openUiIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_FOREGROUND_BRIDGE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OP2R iOS ANCS Bridge")
            .setContentText(contentText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

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

    private fun closeActiveGatt() {
        try {
            activeGatt?.close()
        } catch (_: Exception) {
        } finally {
            activeGatt = null
            notificationSourceCharacteristic = null
            controlPointCharacteristic = null
            dataSourceCharacteristic = null
        }
    }

    private fun disconnectAndStopAll() {
        stopKeepAliveHeartbeat()
        stopModeAPeripheralAdvertising()
        stopModeBCentralScanning()
        clearGattQueue()
        try {
            activeGatt?.disconnect()
        } catch (_: Exception) {}
        closeActiveGatt()
        try {
            gattServer?.close()
        } catch (_: Exception) {
        } finally {
            gattServer = null
        }
        updateStatus("STOPPED", _bridgeState.value.discoveryMode)
    }

    private fun updateStatus(status: String, mode: DiscoveryMode) {
        _bridgeState.value = _bridgeState.value.copy(
            statusText = status,
            discoveryMode = mode
        )
    }

    private fun appendLog(entry: String) {
        Log.d(TAG, entry)
        val current = _bridgeState.value.diagnosticLog
        val updated = (listOf(entry) + current).take(25)
        _bridgeState.value = _bridgeState.value.copy(diagnosticLog = updated)
    }

    private fun shortUuid(uuid: UUID): String = uuid.toString().substring(0, 8).uppercase()
}
