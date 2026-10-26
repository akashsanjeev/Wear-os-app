package com.op2r.iosbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Wear OS 5.0 (Android 14 / API 34) Circular-Inscribed UI Activity custom-engineered
 * for the OnePlus Watch 2R (OPWWE234 — 1.43" AMOLED, 466x466 px, 233x233 dp).
 *
 * Uses circular chord tapering (52% -> 68% -> 82% -> 76% -> 64%) so every pill button,
 * live NS/DS packet counter, and the Full-Screen Notification Message Popup Card fit
 * strictly inside the round bezel with zero clipping.
 */
class MainActivity : ComponentActivity() {

    private lateinit var telemetryHeaderView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var preExistingToggleBtn: Button

    // Circular-Inscribed Full-Screen Notification Message Popup Overlay Views
    private lateinit var popupOverlayRoot: FrameLayout
    private lateinit var popupAppBadgeView: TextView
    private lateinit var popupTitleView: TextView
    private lateinit var popupMessageView: TextView
    private lateinit var popupCallActionsRow: LinearLayout
    private lateinit var popupDismissBtn: Button

    private var activePopupNotificationUid: Int = -1
    private var ignorePreExisting = true
    private var isReceiverRegistered = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startAndBindForegroundBridgeService()
        promptBatteryOptimizationsOnFirstLaunch()
    }

    private val bridgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AncsBridgeService.ACTION_STATE_BROADCAST -> {
                    val state = intent.getStringExtra(AncsBridgeService.EXTRA_CONNECTION_STATE)
                        ?: AncsBridgeService.currentStatusText
                    ignorePreExisting = intent.getBooleanExtra(
                        AncsBridgeService.EXTRA_IGNORE_PRE_EXISTING,
                        ignorePreExisting
                    )
                    val nsCount = intent.getIntExtra(
                        AncsBridgeService.EXTRA_NS_COUNT,
                        AncsBridgeService.nsPacketCount
                    )
                    val dsCount = intent.getIntExtra(
                        AncsBridgeService.EXTRA_DS_COUNT,
                        AncsBridgeService.dsPacketCount
                    )
                    updateWatchUiState(state, nsCount, dsCount)
                }

                AncsBridgeService.ACTION_NEW_NOTIFICATION_BROADCAST -> {
                    handleIncomingNotificationIntent(intent)
                }

                AncsBridgeService.ACTION_DISMISS_NOTIFICATION_BROADCAST -> {
                    val dismissedUid = intent.getIntExtra(AncsBridgeService.EXTRA_NOTIFICATION_UID, -1)
                    if (dismissedUid == activePopupNotificationUid) {
                        hideFullScreenMessagePopup()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure Wear OS 5.0 wakes the OnePlus Watch 2R display over ambient/lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(AncsBridgeService.PREFS_NAME, Context.MODE_PRIVATE)
        ignorePreExisting = prefs.getBoolean(AncsBridgeService.PREF_IGNORE_PRE_EXISTING, true)

        setContentView(buildRootWatchLayout())
        requestRequiredBleAndNotificationPermissions()
        handleIncomingNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        registerBridgeBroadcastReceiver()
        updateWatchUiState(
            AncsBridgeService.currentStatusText,
            AncsBridgeService.nsPacketCount,
            AncsBridgeService.dsPacketCount
        )
    }

    override fun onPause() {
        unregisterBridgeBroadcastReceiver()
        super.onPause()
    }

    private fun buildRootWatchLayout(): FrameLayout {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05070B"))
        }

        // Layer 1: Single-Screen Circular-Safe Control Deck for OnePlus Watch 2R (466x466)
        rootFrame.addView(
            buildCircularSafeControlDeck(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        // Layer 2: Circular-Inscribed Full-Screen Notification Message Popup Card
        popupOverlayRoot = buildCircularInscribedNotificationPopup()
        rootFrame.addView(
            popupOverlayRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        return rootFrame
    }

    /**
     * Builds the Single-Screen Circular-Safe Layout proportioned specifically for the
     * OnePlus Watch 2R (466x466 px / 233x233 dp) using tapered chord widths:
     * - Top Header + Live NS/DS Telemetry: 54% width
     * - Interactive Status & Re-Sync Pill: 68% width
     * - Primary Mode A / Mode B Row (Equator): 82% width (2 columns)
     * - Pre-Existing Toggle Pill: 76% width
     * - Utility Test Alert / Reset Row (Lower Arc): 64% width (2 columns)
     */
    private fun buildCircularSafeControlDeck(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#05070B"))
        }

        // 1. Top Arc Title + Live NS/DS Packet Counter (54% of 466px)
        telemetryHeaderView = TextView(this).apply {
            text = "OP2R · NS:0 DS:0"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val headerParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.54f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(4)
        }

        // 2. Upper Arc Connection Status + Tap-to-ReSync Pill (68% of 466px)
        val statusPillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(999).toFloat()
            setColor(Color.parseColor("#0F172A"))
            setStroke(dpToPx(1), Color.parseColor("#1E293B"))
        }
        statusTextView = TextView(this).apply {
            text = AncsBridgeService.currentStatusText
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = statusPillBg
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dispatchServiceAction(AncsBridgeService.ACTION_RESYNC_ANCS)
            }
        }
        val statusParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.68f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(6)
        }

        // 3. Equator 2-Column Primary Mode Row (82% of 466px = 382px, widest part of circle)
        val primaryModesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnPeripheralMode = createCompactWatchPillButton(
            label = "Advertise\niPhone (A)",
            bgColorHex = "#1B253D",
            strokeColorHex = "#38BDF8",
            textSizeSp = 10.5f
        ) {
            dispatchServiceAction(AncsBridgeService.ACTION_START_PERIPHERAL)
        }
        val btnScannerMode = createCompactWatchPillButton(
            label = "Scan\nBeacon (B)",
            bgColorHex = "#1B253D",
            strokeColorHex = "#10B981",
            textSizeSp = 10.5f
        ) {
            dispatchServiceAction(AncsBridgeService.ACTION_START_SCAN)
        }

        val colLeftParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
            rightMargin = dpToPx(4)
        }
        val colRightParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply {
            leftMargin = dpToPx(4)
        }
        primaryModesRow.addView(btnPeripheralMode, colLeftParams)
        primaryModesRow.addView(btnScannerMode, colRightParams)

        val primaryRowParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.82f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(6)
        }

        // 4. Center Pre-Existing Filter Toggle Pill (76% of 466px = 354px)
        preExistingToggleBtn = createCompactWatchPillButton(
            label = formatPreExistingLabel(),
            bgColorHex = "#141C2E",
            strokeColorHex = "#64748B",
            textSizeSp = 9.5f
        ) {
            ignorePreExisting = !ignorePreExisting
            preExistingToggleBtn.text = formatPreExistingLabel()
            val intent = Intent(this, AncsBridgeService::class.java).apply {
                action = AncsBridgeService.ACTION_SET_IGNORE_PRE_EXISTING
                putExtra(AncsBridgeService.EXTRA_IGNORE_PRE_EXISTING, ignorePreExisting)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        val toggleParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.76f),
            dpToPx(32)
        ).apply {
            bottomMargin = dpToPx(6)
        }

        // 5. Tapered Lower Arc 2-Column Utility Row (64% of 466px = 298px, fits inside bottom curve)
        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val btnTestAlert = createCompactWatchPillButton(
            label = "Test + Sync",
            bgColorHex = "#132A24",
            strokeColorHex = "#10B981",
            textSizeSp = 9.5f
        ) {
            dispatchServiceAction(AncsBridgeService.ACTION_TRIGGER_TEST_ALERT)
        }
        val btnResetBle = createCompactWatchPillButton(
            label = "Reset BLE",
            bgColorHex = "#2A151C",
            strokeColorHex = "#EF4444",
            textSizeSp = 9.5f
        ) {
            dispatchServiceAction(AncsBridgeService.ACTION_RESET_BLE)
        }

        val utilLeftParams = LinearLayout.LayoutParams(0, dpToPx(31), 1f).apply {
            rightMargin = dpToPx(4)
        }
        val utilRightParams = LinearLayout.LayoutParams(0, dpToPx(31), 1f).apply {
            leftMargin = dpToPx(4)
        }
        utilityRow.addView(btnTestAlert, utilLeftParams)
        utilityRow.addView(btnResetBle, utilRightParams)

        val utilityRowParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.64f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        container.addView(telemetryHeaderView, headerParams)
        container.addView(statusTextView, statusParams)
        container.addView(primaryModesRow, primaryRowParams)
        container.addView(preExistingToggleBtn, toggleParams)
        container.addView(utilityRow, utilityRowParams)

        return container
    }

    /**
     * Builds the Circular-Inscribed Full-Screen Notification Message Popup Card
     * proportioned specifically for the 466x466 OnePlus Watch 2R screen:
     * - Top App Badge: max 50% width
     * - Sender Title: 72% width
     * - Scrollable Message Box (Equator): 80% width, fixed 76dp height
     * - Tapered Bottom Action Pill: 50% width (Dismiss) or 60% width (Accept / Decline)
     */
    private fun buildCircularInscribedNotificationPopup(): FrameLayout {
        val overlayFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#060911"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val badgeBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(999).toFloat()
            setColor(Color.parseColor("#14263B"))
            setStroke(dpToPx(1), Color.parseColor("#38BDF8"))
        }

        // 1. Tapered Top App Badge (50% width)
        popupAppBadgeView = TextView(this).apply {
            text = "WhatsApp"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = badgeBackground
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(10), dpToPx(3), dpToPx(10), dpToPx(3))
        }
        val badgeParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.50f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(5)
        }

        // 2. Sender / Title (72% width)
        popupTitleView = TextView(this).apply {
            text = "Notification Title"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val titleParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.72f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpToPx(6)
        }

        // 3. Inscribed Equator Scrollable Message Box (80% width, 76dp height)
        val messageBoxDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(14).toFloat()
            setColor(Color.parseColor("#111827"))
            setStroke(dpToPx(1), Color.parseColor("#1E293B"))
        }

        val messageScrollView = ScrollView(this).apply {
            background = messageBoxDrawable
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }

        popupMessageView = TextView(this).apply {
            text = "Full notification message body is displayed here."
            setTextColor(Color.parseColor("#F1F5F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
        }
        messageScrollView.addView(
            popupMessageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val messageBoxParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.80f),
            dpToPx(76)
        ).apply {
            bottomMargin = dpToPx(8)
        }

        // 4A. Tapered Lower Arc Call Action Row (60% width)
        popupCallActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val btnDeclineCall = createCompactWatchPillButton(
            label = "Decline",
            bgColorHex = "#DC2626",
            strokeColorHex = "#F87171",
            textSizeSp = 10f
        ) {
            if (activePopupNotificationUid != -1) {
                dispatchCallAction(activePopupNotificationUid, AncsPacketParser.ACTION_ID_NEGATIVE)
                hideFullScreenMessagePopup()
            }
        }

        val btnAcceptCall = createCompactWatchPillButton(
            label = "Accept",
            bgColorHex = "#059669",
            strokeColorHex = "#34D399",
            textSizeSp = 10f
        ) {
            if (activePopupNotificationUid != -1) {
                dispatchCallAction(activePopupNotificationUid, AncsPacketParser.ACTION_ID_POSITIVE)
                hideFullScreenMessagePopup()
            }
        }

        val callLeftParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply {
            rightMargin = dpToPx(4)
        }
        val callRightParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply {
            leftMargin = dpToPx(4)
        }
        popupCallActionsRow.addView(btnDeclineCall, callLeftParams)
        popupCallActionsRow.addView(btnAcceptCall, callRightParams)

        val callRowParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.60f),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // 4B. Tapered Lower Arc Dismiss Pill Button (50% width, fits inside bottom curve)
        popupDismissBtn = createCompactWatchPillButton(
            label = "Dismiss",
            bgColorHex = "#1E293B",
            strokeColorHex = "#38BDF8",
            textSizeSp = 10.5f
        ) {
            if (activePopupNotificationUid != -1) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notifyId = 2000 + (activePopupNotificationUid and 0x0FFFFFFF)
                nm.cancel(notifyId)
            }
            hideFullScreenMessagePopup()
        }

        val dismissParams = LinearLayout.LayoutParams(
            pctOfWatchWidth(0.50f),
            dpToPx(32)
        )

        cardContainer.addView(popupAppBadgeView, badgeParams)
        cardContainer.addView(popupTitleView, titleParams)
        cardContainer.addView(messageScrollView, messageBoxParams)
        cardContainer.addView(popupCallActionsRow, callRowParams)
        cardContainer.addView(popupDismissBtn, dismissParams)

        overlayFrame.addView(
            cardContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        return overlayFrame
    }

    private fun handleIncomingNotificationIntent(intent: Intent?) {
        if (intent == null) return
        if (!intent.hasExtra(AncsBridgeService.EXTRA_MESSAGE) &&
            !intent.hasExtra(AncsBridgeService.EXTRA_TITLE)
        ) {
            return
        }

        val uid = intent.getIntExtra(AncsBridgeService.EXTRA_NOTIFICATION_UID, -1)
        val appName = intent.getStringExtra(AncsBridgeService.EXTRA_APP_NAME) ?: "iOS Alert"
        val title = intent.getStringExtra(AncsBridgeService.EXTRA_TITLE) ?: appName
        val message = intent.getStringExtra(AncsBridgeService.EXTRA_MESSAGE) ?: ""
        val isCall = intent.getBooleanExtra(AncsBridgeService.EXTRA_IS_CALL, false)

        activePopupNotificationUid = uid
        popupAppBadgeView.text = if (isCall) "IPHONE CALL" else appName
        popupTitleView.text = title
        popupMessageView.text = message

        if (isCall) {
            popupCallActionsRow.visibility = View.VISIBLE
            popupDismissBtn.visibility = View.GONE
        } else {
            popupCallActionsRow.visibility = View.GONE
            popupDismissBtn.visibility = View.VISIBLE
        }

        popupOverlayRoot.visibility = View.VISIBLE
    }

    private fun hideFullScreenMessagePopup() {
        activePopupNotificationUid = -1
        popupOverlayRoot.visibility = View.GONE
    }

    private fun dispatchCallAction(notificationUid: Int, actionId: Byte) {
        val intent = Intent(this, AncsBridgeService::class.java).apply {
            action = AncsBridgeService.ACTION_PERFORM_CALL_ACTION
            putExtra(AncsBridgeService.EXTRA_NOTIFICATION_UID, notificationUid)
            putExtra(AncsBridgeService.EXTRA_ANCS_ACTION_ID, actionId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun createCompactWatchPillButton(
        label: String,
        bgColorHex: String,
        strokeColorHex: String,
        textSizeSp: Float,
        onClick: () -> Unit
    ): Button {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(999).toFloat()
            setColor(Color.parseColor(bgColorHex))
            setStroke(dpToPx(1), Color.parseColor(strokeColorHex))
        }

        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            gravity = Gravity.CENTER
            background = drawable
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            setOnClickListener { onClick() }
        }
    }

    private fun formatPreExistingLabel(): String {
        val stateLabel = if (ignorePreExisting) "Smart (2.5s)" else "Show All"
        return "Pre-Existing: $stateLabel"
    }

    private fun updateWatchUiState(state: String, nsCount: Int, dsCount: Int) {
        telemetryHeaderView.text = "OP2R · NS:$nsCount DS:$dsCount"
        statusTextView.text = if (state.contains("Bonded", ignoreCase = true)) {
            "$state (Tap Re-Sync)"
        } else {
            state
        }
        val colorHex = when {
            state.contains("Bonded", ignoreCase = true) -> "#10B981"
            state.contains("Advertising", ignoreCase = true) ||
                state.contains("Scanning", ignoreCase = true) ||
                state.contains("Re-Sync", ignoreCase = true) -> "#F59E0B"
            state.contains("Error", ignoreCase = true) ||
                state.contains("Failed", ignoreCase = true) -> "#EF4444"
            else -> "#38BDF8"
        }
        statusTextView.setTextColor(Color.parseColor(colorHex))
        preExistingToggleBtn.text = formatPreExistingLabel()
    }

    private fun requestRequiredBleAndNotificationPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startAndBindForegroundBridgeService()
            promptBatteryOptimizationsOnFirstLaunch()
        }
    }

    private fun startAndBindForegroundBridgeService() {
        val serviceIntent = Intent(this, AncsBridgeService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun dispatchServiceAction(action: String) {
        val intent = Intent(this, AncsBridgeService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun promptBatteryOptimizationsOnFirstLaunch() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            requestIgnoreBatteryOptimizations()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(fallbackIntent)
        }
    }

    private fun registerBridgeBroadcastReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AncsBridgeService.ACTION_STATE_BROADCAST)
                addAction(AncsBridgeService.ACTION_NEW_NOTIFICATION_BROADCAST)
                addAction(AncsBridgeService.ACTION_DISMISS_NOTIFICATION_BROADCAST)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bridgeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(bridgeReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    private fun unregisterBridgeBroadcastReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(bridgeReceiver)
            } catch (_: Exception) {
            }
            isReceiverRegistered = false
        }
    }

    /**
     * Computes exact pixel width from a fraction of the OnePlus Watch 2R (466px) round screen diameter
     * so every row stays strictly inside the circular chord at its vertical offset.
     */
    private fun pctOfWatchWidth(fraction: Float): Int {
        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(320)
        return (screenWidth * fraction).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
