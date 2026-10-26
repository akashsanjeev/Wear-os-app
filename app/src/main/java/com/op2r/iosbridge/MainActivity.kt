package com.op2r.iosbridge

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * Wear OS 5.0 (API 34) Round Watch Activity for OnePlus Watch 2R (OPWWE234 - 466x466 px).
 *
 * Uses circular chord geometry:
 * - Inscribed safe square padding (~14.64% = 68px on a 466x466 display) so zero text or buttons
 *   clip against the round AMOLED bezel.
 * - Includes an interactive full-screen Notification & Incoming Call Popup Overlay that displays:
 *   1. Caller Name (Attribute 0x01 Title) + Caller Phone Number (Attribute 0x02 Subtitle)
 *      with Answer (Positive Action 0x00) and Decline (Negative Action 0x01) buttons.
 *   2. Full 256-byte Message Body (Attribute 0x03) inside an inscribed scrollable card.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusBadgeView: TextView
    private lateinit var deviceInfoView: TextView
    private lateinit var telemetryStatsView: TextView
    private lateinit var lastAlertSummaryView: TextView
    private lateinit var logFeedView: TextView

    // Full-screen circular popup overlay views
    private lateinit var popupOverlayContainer: FrameLayout
    private lateinit var popupCategoryBadge: TextView
    private lateinit var popupAppNameView: TextView
    private lateinit var popupTitleView: TextView
    private lateinit var popupSubtitleView: TextView
    private lateinit var popupMessageView: TextView
    private lateinit var popupPositiveButton: Button
    private lateinit var popupNegativeButton: Button
    private lateinit var popupCloseButton: Button

    private var activePopupUid: Int = -1
    private var lastShownPopupSignature: String = ""

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startBridgeService(AncsBridgeService.ACTION_START_MODE_A_PERIPHERAL)
        } else {
            statusBadgeView.text = "BLE PERMISSIONS REQUIRED"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableWakeAndShowWhenLocked()
        setContentView(buildCircularSafeWatchLayout())
        observeBridgeState()
        ensurePermissionsAndStartBridge()
        handleIncomingPopupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPopupIntent(intent)
    }

    private fun enableWakeAndShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    /**
     * Builds the root FrameLayout containing:
     * 1. The scrollable circular-safe ANCS Bridge dashboard.
     * 2. The full-screen circular-inscribed Message & Caller ID Popup Overlay.
     */
    private fun buildCircularSafeWatchLayout(): View {
        val metrics = resources.displayMetrics
        val screenMinPx = minOf(metrics.widthPixels, metrics.heightPixels)
        // Inscribed square margin for a circle: (1 - sqrt(2)/2) / 2 ≈ 0.14645 of diameter
        val inscribedInsetPx = (screenMinPx * 0.1465f).toInt().coerceAtLeast(dp(26))

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val scrollRoot = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                inscribedInsetPx,
                inscribedInsetPx,
                inscribedInsetPx,
                inscribedInsetPx + dp(18)
            )
        }

        val headerTitle = TextView(this).apply {
            text = "OP2R • iOS ANCS"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        statusBadgeView = TextView(this).apply {
            text = "INITIALIZING..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }

        deviceInfoView = TextView(this).apply {
            text = "No iPhone Connected • MTU 23"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        }

        telemetryStatsView = TextView(this).apply {
            text = "RX Src: 0 • Data(0x03): 0 • HB: 0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        // Primary Mode A / Mode B Controls
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val modeAButton = createWatchActionButton(
            label = "Advertise (A)",
            bgColorHex = "#0284C7"
        ) {
            startBridgeService(AncsBridgeService.ACTION_START_MODE_A_PERIPHERAL)
        }

        val modeBButton = createWatchActionButton(
            label = "Scan iOS (B)",
            bgColorHex = "#27272A"
        ) {
            startBridgeService(AncsBridgeService.ACTION_START_MODE_B_CENTRAL)
        }

        modeRow.addView(modeAButton, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            marginEnd = dp(4)
        })
        modeRow.addView(modeBButton, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            marginStart = dp(4)
        })

        // Secondary Diagnostic Controls: Re-Sync ANCS + Test Alert Popup
        val diagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(6))
        }

        val resyncButton = createWatchActionButton(
            label = "Re-Sync ANCS",
            bgColorHex = "#0F766E"
        ) {
            startBridgeService(AncsBridgeService.ACTION_RESYNC_ANCS)
        }

        val testAlertButton = createWatchActionButton(
            label = "Test Alert",
            bgColorHex = "#4F46E5"
        ) {
            startBridgeService(AncsBridgeService.ACTION_SIMULATE_TEST_ALERT)
        }

        diagRow.addView(resyncButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginEnd = dp(4)
        })
        diagRow.addView(testAlertButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginStart = dp(4)
        })

        lastAlertSummaryView = TextView(this).apply {
            text = "Waiting for iPhone calls & notifications..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
            background = createRoundedDrawable("#18181B", "#27272A", dp(10))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                val last = AncsBridgeService.bridgeState.value.lastNotification
                if (last != null) {
                    showNotificationPopupCard(last)
                }
            }
        }

        logFeedView = TextView(this).apply {
            text = "GATT Telemetry Ready"
            setTextColor(Color.parseColor("#71717A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }

        container.addView(headerTitle)
        container.addView(statusBadgeView)
        container.addView(deviceInfoView)
        container.addView(telemetryStatsView)
        container.addView(
            modeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            diagRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            lastAlertSummaryView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            logFeedView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        scrollRoot.addView(container)
        rootFrame.addView(
            scrollRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Full-Screen Inscribed Notification & Incoming Call Popup Overlay
        popupOverlayContainer = buildCircularInscribedNotificationPopup(inscribedInsetPx)
        rootFrame.addView(
            popupOverlayContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return rootFrame
    }

    /**
     * Builds the full-screen circular-safe Notification & Caller ID Popup Overlay.
     * - Displays Caller Name (Title 0x01) + Caller Phone Number (Subtitle 0x02) +
     *   Full 256-byte Message Body (Message 0x03) inside an inscribed scrollable card.
     * - Strictly bounded within the 466x466 circular chord so zero text or buttons clip.
     */
    private fun buildCircularInscribedNotificationPopup(inscribedInsetPx: Int): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5000000"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val cardWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                inscribedInsetPx,
                inscribedInsetPx - dp(6),
                inscribedInsetPx,
                inscribedInsetPx - dp(6)
            )
        }

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createRoundedDrawable("#111827", "#38BDF8", dp(16))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        popupCategoryBadge = TextView(this).apply {
            text = "NEW MESSAGE"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        popupAppNameView = TextView(this).apply {
            text = "iMessage"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, dp(2))
        }

        // Caller Name or Sender Name (ANCS Attribute 0x01 Title)
        popupTitleView = TextView(this).apply {
            text = "Sender Name"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, dp(1))
        }

        // Caller ID Phone Number or Thread Subtitle (ANCS Attribute 0x02 Subtitle)
        popupSubtitleView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }

        // Inscribed scrollable message box for the full 256-byte Attribute 0x03 Message Body
        val messageScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = createRoundedDrawable("#090D16", "#1F2937", dp(10))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        popupMessageView = TextView(this).apply {
            text = "Full 256-byte ANCS Attribute 0x03 message body..."
            setTextColor(Color.parseColor("#F3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        messageScroll.addView(
            popupMessageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        popupPositiveButton = createWatchActionButton("Accept", "#059669") {
            if (activePopupUid != -1) {
                sendActionToBridge(AncsBridgeService.ACTION_PERFORM_POSITIVE, activePopupUid)
            }
            popupOverlayContainer.visibility = View.GONE
        }

        popupNegativeButton = createWatchActionButton("Dismiss", "#DC2626") {
            if (activePopupUid != -1) {
                sendActionToBridge(AncsBridgeService.ACTION_PERFORM_NEGATIVE, activePopupUid)
            }
            popupOverlayContainer.visibility = View.GONE
        }

        popupCloseButton = createWatchActionButton("Close", "#374151") {
            popupOverlayContainer.visibility = View.GONE
        }

        actionsRow.addView(
            popupPositiveButton,
            LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(3) }
        )
        actionsRow.addView(
            popupNegativeButton,
            LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
        )
        actionsRow.addView(
            popupCloseButton,
            LinearLayout.LayoutParams(0, dp(32), 0.85f).apply { marginStart = dp(3) }
        )

        cardBox.addView(popupCategoryBadge)
        cardBox.addView(popupAppNameView)
        cardBox.addView(popupTitleView)
        cardBox.addView(popupSubtitleView)
        cardBox.addView(
            messageScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88)
            )
        )
        cardBox.addView(
            actionsRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        cardWrapper.addView(
            cardBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        overlay.addView(
            cardWrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        return overlay
    }

    private fun handleIncomingPopupIntent(intent: Intent?) {
        if (intent?.action != AncsBridgeService.ACTION_SHOW_WATCH_POPUP) return
        val uid = intent.getIntExtra(AncsBridgeService.EXTRA_NOTIFICATION_UID, -1)
        if (uid == -1) return

        val alert = AncsPacketParser.AssembledAncsNotification(
            notificationUid = uid,
            categoryId = intent.getIntExtra(AncsBridgeService.EXTRA_CATEGORY_ID, 0),
            eventFlags = 0,
            appIdentifier = intent.getStringExtra(AncsBridgeService.EXTRA_APP_ID).orEmpty(),
            title = intent.getStringExtra(AncsBridgeService.EXTRA_TITLE).orEmpty(),
            subtitle = intent.getStringExtra(AncsBridgeService.EXTRA_SUBTITLE).orEmpty(),
            message = intent.getStringExtra(AncsBridgeService.EXTRA_MESSAGE).orEmpty(),
            hasPositiveAction = intent.getBooleanExtra(AncsBridgeService.EXTRA_HAS_POSITIVE, false),
            hasNegativeAction = intent.getBooleanExtra(AncsBridgeService.EXTRA_HAS_NEGATIVE, true)
        )
        enableWakeAndShowWhenLocked()
        showNotificationPopupCard(alert)
    }

    private fun showNotificationPopupCard(alert: AncsPacketParser.AssembledAncsNotification) {
        activePopupUid = alert.notificationUid
        val isCall = alert.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL

        popupCategoryBadge.text = if (isCall) "INCOMING IPHONE CALL" else "ANCS 0x03 MESSAGE"
        popupCategoryBadge.setTextColor(
            Color.parseColor(if (isCall) "#10B981" else "#38BDF8")
        )
        popupAppNameView.text = formatReadableAppId(alert.appIdentifier, isCall)
        popupTitleView.text = alert.title.ifBlank { if (isCall) "Incoming Call" else "iPhone Alert" }
        popupTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCall) 15f else 14f)

        if (alert.subtitle.isNotBlank() && alert.subtitle != alert.title) {
            popupSubtitleView.text = if (isCall) "ID: ${alert.subtitle}" else alert.subtitle
            popupSubtitleView.visibility = View.VISIBLE
        } else {
            popupSubtitleView.visibility = View.GONE
        }

        popupMessageView.text = alert.message.ifBlank { alert.title }

        if (isCall) {
            popupPositiveButton.visibility = View.VISIBLE
            popupPositiveButton.text = "Accept"
            popupNegativeButton.visibility = View.VISIBLE
            popupNegativeButton.text = "Decline"
            popupCloseButton.visibility = View.GONE
        } else {
            popupPositiveButton.visibility = if (alert.hasPositiveAction) View.VISIBLE else View.GONE
            popupPositiveButton.text = "Action"
            popupNegativeButton.visibility = if (alert.hasNegativeAction) View.VISIBLE else View.GONE
            popupNegativeButton.text = "Dismiss"
            popupCloseButton.visibility = View.VISIBLE
        }

        popupOverlayContainer.visibility = View.VISIBLE
    }

    private fun formatReadableAppId(appId: String, isCall: Boolean): String {
        val lower = appId.lowercase()
        return when {
            isCall || lower.contains("mobilephone") || lower.contains("incallservice") ->
                "iPhone Phone • Caller ID"
            lower.contains("mobilesms") -> "iMessage • SMS"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") -> "Telegram"
            lower.contains("instagram") -> "Instagram"
            lower.contains("gmail") || lower.contains("mobilemail") -> "Mail"
            lower.contains("slack") -> "Slack"
            else -> appId.substringAfterLast('.').ifBlank { "iOS App" }
        }
    }

    private fun observeBridgeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AncsBridgeService.bridgeState.collect { state ->
                    statusBadgeView.text = state.statusText
                    val devName = state.connectedDeviceName ?: "No iPhone"
                    val bondTag = if (state.isBonded) "Bonded" else "Unbonded"
                    val rssiTag = if (state.lastRssi != 0) " • ${state.lastRssi}dBm" else ""
                    deviceInfoView.text = "$devName ($bondTag) • MTU ${state.negotiatedMtu}$rssiTag"
                    telemetryStatsView.text =
                        "Src:${state.rxSourcePackets} • 0x03:${state.rxDataPackets} • HB:${state.keepAlivePings}"

                    val last = state.lastNotification
                    if (last != null) {
                        val subtitleTag = if (last.subtitle.isNotBlank()) " (${last.subtitle})" else ""
                        lastAlertSummaryView.text =
                            "[UID ${last.notificationUid}] ${last.title}$subtitleTag\n${last.message}\n(Tap to open)"

                        // Automatically pop up or update in-place when Title/Subtitle/Message 0x03 updates
                        val signature = "${last.notificationUid}:${last.title}:${last.subtitle}:${last.message}"
                        if (signature != lastShownPopupSignature) {
                            lastShownPopupSignature = signature
                            showNotificationPopupCard(last)
                        }
                    }

                    if (state.diagnosticLog.isNotEmpty()) {
                        logFeedView.text = state.diagnosticLog.take(6).joinToString("\n") { "• $it" }
                    }
                }
            }
        }
    }

    private fun ensurePermissionsAndStartBridge() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBridgeService(AncsBridgeService.ACTION_START_MODE_A_PERIPHERAL)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridgeService(action: String) {
        val intent = Intent(this, AncsBridgeService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendActionToBridge(action: String, notificationUid: Int) {
        val intent = Intent(this, AncsBridgeService::class.java).apply {
            this.action = action
            putExtra(AncsBridgeService.EXTRA_NOTIFICATION_UID, notificationUid)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun createWatchActionButton(
        label: String,
        bgColorHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = createRoundedDrawable(bgColorHex, "#3F3F46", dp(8))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { onClick() }
        }
    }

    private fun createRoundedDrawable(
        fillHex: String,
        strokeHex: String,
        radiusPx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
