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
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Wear OS 5.0 (API 34) Round Watch Activity for OnePlus Watch 2R (OPWWE234 - 466x466 px).
 *
 * Implements a 2-Page Horizontal Swipe UI inside the inscribed circular safe area:
 * - Page 1 (Main Watch Face - Zero Accidental Presses):
 *   Digital Clock, Date, Connection Status, Missed Calls Counter Pill, Messages Counter Pill,
 *   and a single "Clear Counts" button.
 * - Page 2 (Swipe Side - Controls & Raw Diagnostics):
 *   Dedicated "Advertise iPhone (Mode A)" button (Mode B removed), "Re-Sync", "Test Alert",
 *   Live Raw ANCS Attribute Inspector (Title 0x01, Subtitle 0x02, Message 0x03, AppID 0x00),
 *   and BLE Telemetry Logs.
 * - Full-Screen Popup Overlay:
 *   Wakes the watch to display Incoming Calls (Contact Name + Phone Number + Accept/Decline)
 *   and Full 256-Byte Message Body cards.
 */
class MainActivity : ComponentActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectorJob: Job? = null

    // Page navigation state (0 = Page 1 Clock & Counters, 1 = Page 2 Controls & Diagnostics)
    private var currentPageIndex: Int = 0
    private lateinit var page1ClockContainer: LinearLayout
    private lateinit var page2ControlsScroll: ScrollView
    private lateinit var pageDot1View: View
    private lateinit var pageDot2View: View

    // Page 1 Views (Clean Curved Layout: Centered Clock + Compact Status Chips + Clear Pill)
    private lateinit var page1ConnectionPill: TextView
    private lateinit var page1MissedCallsBadge: TextView
    private lateinit var page1MessagesBadge: TextView
    private lateinit var page1LastPreviewText: TextView
    private lateinit var page1ClearButton: Button

    // Page 2 Views (Center-Weighted Scroll Controls + Raw ANCS Attribute Inspector + Logs)
    private lateinit var statusBadgeView: TextView
    private lateinit var deviceInfoView: TextView
    private lateinit var telemetryStatsView: TextView
    private lateinit var rawAttributesBoxView: TextView
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
    private lateinit var swipeGestureDetector: GestureDetector

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
        setupHorizontalSwipeDetector()
        setContentView(buildCircularTwoPageWatchLayout())
        ensurePermissionsAndStartBridge()
        handleIncomingPopupIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        startObservingBridgeState()
    }

    override fun onStop() {
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingPopupIntent(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::popupOverlayContainer.isInitialized &&
            popupOverlayContainer.visibility != View.VISIBLE &&
            ::swipeGestureDetector.isInitialized
        ) {
            swipeGestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupHorizontalSwipeDetector() {
        swipeGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val deltaX = e2.x - e1.x
                    val deltaY = e2.y - e1.y
                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > dp(34) && abs(velocityX) > 160f) {
                        if (deltaX < 0 && currentPageIndex == 0) {
                            switchToPage(1)
                            return true
                        } else if (deltaX > 0 && currentPageIndex == 1) {
                            switchToPage(0)
                            return true
                        }
                    }
                    return false
                }
            }
        )
    }

    private fun switchToPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, 1)
        if (currentPageIndex == 0) {
            page1ClockContainer.visibility = View.VISIBLE
            page2ControlsScroll.visibility = View.GONE
            pageDot1View.background = createCircleDotDrawable("#38BDF8")
            pageDot2View.background = createCircleDotDrawable("#3F3F46")
        } else {
            page1ClockContainer.visibility = View.GONE
            page2ControlsScroll.visibility = View.VISIBLE
            pageDot1View.background = createCircleDotDrawable("#3F3F46")
            pageDot2View.background = createCircleDotDrawable("#38BDF8")
        }
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

    private fun buildCircularTwoPageWatchLayout(): View {
        val metrics = resources.displayMetrics
        val screenMinPx = minOf(metrics.widthPixels, metrics.heightPixels)
        // Safe chord width (80% of 466px diameter = ~372px / 186dp) for equatorial elements
        val safeCenterWidthPx = (screenMinPx * 0.78f).toInt().coerceAtLeast(dp(176))
        val safeHorizontalPaddingPx = ((screenMinPx - safeCenterWidthPx) / 2).coerceAtLeast(dp(26))

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // =====================================================================
        // PAGE 1: ONEPLUS WATCH 2R CLEAN CURVED CLOCK & COMPACT STATUS CHIPS
        // Centered vertically in the wide 466px equatorial zone so neither the
        // top status pill nor the bottom Clear Counts button clips on the bezel.
        // =====================================================================
        page1ClockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                safeHorizontalPaddingPx,
                dp(28),
                safeHorizontalPaddingPx,
                dp(28)
            )
        }

        page1ConnectionPill = TextView(this).apply {
            text = "● STARTING ANCS"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = createRoundedDrawable("#0B1520", "#1E293B", dp(12))
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }

        val digitalClock = TextClock(this).apply {
            format12Hour = "hh:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, dp(2), 0, 0)
        }

        val dateClock = TextClock(this).apply {
            format12Hour = "EEE, MMM d"
            format24Hour = "EEE, MMM d"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        val countersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }

        page1MissedCallsBadge = TextView(this).apply {
            text = "0 Missed"
            setTextColor(Color.parseColor("#FDA4AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            background = createRoundedDrawable("#1F1218", "#881337", dp(14))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                val last = AncsBridgeService.bridgeState.value.lastNotification
                if (last != null) showNotificationPopupCard(last)
            }
        }

        page1MessagesBadge = TextView(this).apply {
            text = "0 Msgs"
            setTextColor(Color.parseColor("#7DD3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            background = createRoundedDrawable("#0F1B29", "#0369A1", dp(14))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                val last = AncsBridgeService.bridgeState.value.lastNotification
                if (last != null) showNotificationPopupCard(last)
            }
        }

        countersRow.addView(
            page1MissedCallsBadge,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
        )
        countersRow.addView(
            page1MessagesBadge,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        )

        page1LastPreviewText = TextView(this).apply {
            text = "Tap chip to view last alert"
            setTextColor(Color.parseColor("#71717A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), dp(4))
            setOnClickListener {
                val last = AncsBridgeService.bridgeState.value.lastNotification
                if (last != null) showNotificationPopupCard(last)
            }
        }

        page1ClearButton = createWatchActionButton(
            label = "Clear Counts",
            bgColorHex = "#1E293B",
            cornerRadiusDp = 14
        ) {
            startBridgeService(AncsBridgeService.ACTION_CLEAR_COUNTERS)
        }

        page1ClockContainer.addView(
            page1ConnectionPill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page1ClockContainer.addView(digitalClock)
        page1ClockContainer.addView(dateClock)
        page1ClockContainer.addView(
            countersRow,
            LinearLayout.LayoutParams(
                safeCenterWidthPx.coerceAtMost(dp(168)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page1ClockContainer.addView(
            page1LastPreviewText,
            LinearLayout.LayoutParams(
                safeCenterWidthPx.coerceAtMost(dp(164)),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page1ClockContainer.addView(
            page1ClearButton,
            LinearLayout.LayoutParams(dp(112), dp(28))
        )

        // =====================================================================
        // PAGE 2: CENTER-WEIGHTED SCROLL FOR 466x466 ROUND DISPLAY
        // Generous top (72dp) & bottom (80dp) circular padding with clipToPadding=false
        // so the top header and bottom Raw ANCS Inspector scroll smoothly into the
        // wide center equator without ever clipping against the curved bezel.
        // =====================================================================
        page2ControlsScroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(
                safeHorizontalPaddingPx,
                dp(72),
                safeHorizontalPaddingPx,
                dp(80)
            )
            visibility = View.GONE
        }

        val page2Container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val headerTitle = TextView(this).apply {
            text = "CONTROLS • MODE A"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        statusBadgeView = TextView(this).apply {
            text = "INITIALIZING..."
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        deviceInfoView = TextView(this).apply {
            text = "No iPhone • MTU 23"
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
        }

        telemetryStatsView = TextView(this).apply {
            text = "Src:0 • 0x03:0 • HB:0"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(1), 0, dp(6))
        }

        val advertiseModeAButton = createWatchActionButton(
            label = "Advertise iPhone (Mode A)",
            bgColorHex = "#0284C7",
            cornerRadiusDp = 14
        ) {
            startBridgeService(AncsBridgeService.ACTION_START_MODE_A_PERIPHERAL)
        }

        val diagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(6))
        }

        val resyncButton = createWatchActionButton(
            label = "Re-Sync",
            bgColorHex = "#0F766E",
            cornerRadiusDp = 12
        ) {
            startBridgeService(AncsBridgeService.ACTION_RESYNC_ANCS)
        }

        val testAlertButton = createWatchActionButton(
            label = "Test Alert",
            bgColorHex = "#4F46E5",
            cornerRadiusDp = 12
        ) {
            startBridgeService(AncsBridgeService.ACTION_SIMULATE_TEST_ALERT)
        }

        diagRow.addView(resyncButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginEnd = dp(4)
        })
        diagRow.addView(testAlertButton, LinearLayout.LayoutParams(0, dp(30), 1f).apply {
            marginStart = dp(4)
        })

        rawAttributesBoxView = TextView(this).apply {
            text = "RAW ANCS INSPECTOR\nApp(0x00): —\nTitle(0x01): —\nSub(0x02): —\nMsg(0x03): —"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            background = createRoundedDrawable("#111827", "#1E293B", dp(12))
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        logFeedView = TextView(this).apply {
            text = "GATT Telemetry Ready"
            setTextColor(Color.parseColor("#71717A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }

        page2Container.addView(headerTitle)
        page2Container.addView(statusBadgeView)
        page2Container.addView(deviceInfoView)
        page2Container.addView(telemetryStatsView)
        page2Container.addView(
            advertiseModeAButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            )
        )
        page2Container.addView(
            diagRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page2Container.addView(
            rawAttributesBoxView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page2Container.addView(
            logFeedView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        page2ControlsScroll.addView(page2Container)

        // =====================================================================
        // SUBTLE SIDE ARC DOTS (RIGHT BEZEL EDGE INDICATOR)
        // Replaces the bottom footer so the bottom circular arc is never clipped.
        // =====================================================================
        val sideArcPagerDots = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createRoundedDrawable("#0D131F", "#1E293B", dp(10))
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setOnClickListener {
                switchToPage(if (currentPageIndex == 0) 1 else 0)
            }
        }

        pageDot1View = View(this).apply {
            background = createCircleDotDrawable("#38BDF8")
        }

        pageDot2View = View(this).apply {
            background = createCircleDotDrawable("#3F3F46")
        }

        sideArcPagerDots.addView(
            pageDot1View,
            LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                bottomMargin = dp(5)
            }
        )
        sideArcPagerDots.addView(
            pageDot2View,
            LinearLayout.LayoutParams(dp(6), dp(6))
        )

        rootFrame.addView(
            page1ClockContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootFrame.addView(
            page2ControlsScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootFrame.addView(
            sideArcPagerDots,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.END
            ).apply {
                marginEnd = dp(6)
            }
        )

        popupOverlayContainer = buildCircularInscribedNotificationPopup(safeHorizontalPaddingPx)
        rootFrame.addView(
            popupOverlayContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return rootFrame
    }

    private fun buildCircularInscribedNotificationPopup(safeHorizontalPaddingPx: Int): FrameLayout {
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
                safeHorizontalPaddingPx,
                dp(34),
                safeHorizontalPaddingPx,
                dp(34)
            )
        }

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createRoundedDrawable("#111827", "#38BDF8", dp(18))
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
            text = "Messages"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, dp(2))
        }

        popupTitleView = TextView(this).apply {
            text = "Contact Name"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, dp(1))
        }

        popupSubtitleView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4))
        }

        val messageScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = createRoundedDrawable("#090D16", "#1F2937", dp(10))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        popupMessageView = TextView(this).apply {
            text = "Full 256-byte ANCS Attribute 0x03 message body..."
            setTextColor(Color.parseColor("#F3F4F6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
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
            setPadding(0, dp(6), 0, 0)
        }

        popupPositiveButton = createWatchActionButton("Accept", "#059669", 12) {
            if (activePopupUid != -1) {
                sendActionToBridge(AncsBridgeService.ACTION_PERFORM_POSITIVE, activePopupUid)
            }
            popupOverlayContainer.visibility = View.GONE
        }

        popupNegativeButton = createWatchActionButton("Dismiss", "#DC2626", 12) {
            if (activePopupUid != -1) {
                sendActionToBridge(AncsBridgeService.ACTION_PERFORM_NEGATIVE, activePopupUid)
            }
            popupOverlayContainer.visibility = View.GONE
        }

        popupCloseButton = createWatchActionButton("Close", "#374151", 12) {
            popupOverlayContainer.visibility = View.GONE
        }

        actionsRow.addView(
            popupPositiveButton,
            LinearLayout.LayoutParams(0, dp(30), 1f).apply { marginEnd = dp(3) }
        )
        actionsRow.addView(
            popupNegativeButton,
            LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
        )
        actionsRow.addView(
            popupCloseButton,
            LinearLayout.LayoutParams(0, dp(30), 0.85f).apply { marginStart = dp(3) }
        )

        cardBox.addView(popupCategoryBadge)
        cardBox.addView(popupAppNameView)
        cardBox.addView(popupTitleView)
        cardBox.addView(popupSubtitleView)
        cardBox.addView(
            messageScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(74)
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

        val appId = intent.getStringExtra(AncsBridgeService.EXTRA_APP_ID).orEmpty()
        val appName = intent.getStringExtra(AncsBridgeService.EXTRA_APP_NAME)
            .orEmpty()
            .ifBlank { formatReadableAppId(appId, false) }

        val alert = AncsPacketParser.AssembledAncsNotification(
            notificationUid = uid,
            categoryId = intent.getIntExtra(AncsBridgeService.EXTRA_CATEGORY_ID, 0),
            eventFlags = 0,
            appIdentifier = appId,
            appDisplayName = appName,
            title = intent.getStringExtra(AncsBridgeService.EXTRA_TITLE).orEmpty(),
            subtitle = intent.getStringExtra(AncsBridgeService.EXTRA_SUBTITLE).orEmpty(),
            message = intent.getStringExtra(AncsBridgeService.EXTRA_MESSAGE).orEmpty(),
            rawAppId = appId,
            rawTitle = intent.getStringExtra(AncsBridgeService.EXTRA_TITLE).orEmpty(),
            rawSubtitle = intent.getStringExtra(AncsBridgeService.EXTRA_SUBTITLE).orEmpty(),
            rawMessage = intent.getStringExtra(AncsBridgeService.EXTRA_MESSAGE).orEmpty(),
            hasPositiveAction = intent.getBooleanExtra(AncsBridgeService.EXTRA_HAS_POSITIVE, false),
            hasNegativeAction = intent.getBooleanExtra(AncsBridgeService.EXTRA_HAS_NEGATIVE, true)
        )
        enableWakeAndShowWhenLocked()
        showNotificationPopupCard(alert)
    }

    private fun showNotificationPopupCard(alert: AncsPacketParser.AssembledAncsNotification) {
        activePopupUid = alert.notificationUid
        val isCall = alert.categoryId == AncsPacketParser.CATEGORY_ID_INCOMING_CALL

        popupCategoryBadge.text = if (isCall) "INCOMING IPHONE CALL" else alert.appDisplayName.uppercase()
        popupCategoryBadge.setTextColor(
            Color.parseColor(if (isCall) "#10B981" else "#38BDF8")
        )
        popupAppNameView.text = if (isCall) "iPhone Call • Caller ID" else alert.appIdentifier
        popupTitleView.text = alert.title.ifBlank { if (isCall) "Incoming Call" else alert.appDisplayName }
        popupTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCall) 15f else 14f)

        if (alert.subtitle.isNotBlank() &&
            alert.subtitle != alert.title &&
            alert.subtitle != alert.appDisplayName
        ) {
            popupSubtitleView.text = if (isCall) "Number: ${alert.subtitle}" else alert.subtitle
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
            isCall || lower.contains("mobilephone") || lower.contains("incallservice") -> "Phone"
            lower.contains("mobilesms") -> "Messages"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") -> "Telegram"
            lower.contains("instagram") -> "Instagram"
            lower.contains("gmail") || lower.contains("mobilemail") -> "Mail"
            lower.contains("slack") -> "Slack"
            else -> appId.substringAfterLast('.').ifBlank { "iPhone Alert" }
        }
    }

    private fun startObservingBridgeState() {
        stateCollectorJob?.cancel()
        stateCollectorJob = uiScope.launch {
            AncsBridgeService.bridgeState.collect { state ->
                val isConnected = state.isAncsSubscribed
                page1ConnectionPill.text = if (isConnected) {
                    "● CONNECTED • ${state.connectedDeviceName ?: "iPhone"}"
                } else {
                    "● ${state.statusText}"
                }
                page1ConnectionPill.setTextColor(
                    Color.parseColor(if (isConnected) "#10B981" else "#38BDF8")
                )

                page1MissedCallsBadge.text = "${state.missedCallCount} Missed"
                page1MessagesBadge.text = "${state.unreadMessageCount} Msgs"

                val last = state.lastNotification
                if (last != null) {
                    page1LastPreviewText.text = "${last.appDisplayName}: ${last.title} — ${last.message}"

                    val signature =
                        "${last.notificationUid}:${last.title}:${last.subtitle}:${last.message}"
                    if (signature != lastShownPopupSignature) {
                        lastShownPopupSignature = signature
                        showNotificationPopupCard(last)
                    }
                } else {
                    page1LastPreviewText.text = "No unread alerts"
                }

                statusBadgeView.text = state.statusText
                val devName = state.connectedDeviceName ?: "No iPhone"
                val bondTag = if (state.isBonded) "Bonded" else "Unbonded"
                val rssiTag = if (state.lastRssi != 0) " • ${state.lastRssi}dBm" else ""
                deviceInfoView.text = "$devName ($bondTag) • MTU ${state.negotiatedMtu}$rssiTag"
                telemetryStatsView.text =
                    "Src:${state.rxSourcePackets} • 0x03:${state.rxDataPackets} • HB:${state.keepAlivePings}"

                rawAttributesBoxView.text = buildString {
                    append("RAW ANCS INSPECTOR\n")
                    append("App(0x00): ${state.lastRawAppId}\n")
                    append("Title(0x01): ${state.lastRawTitle}\n")
                    append("Sub(0x02): ${state.lastRawSubtitle}\n")
                    append("Msg(0x03): ${state.lastRawMessage.take(80)}")
                }

                if (state.diagnosticLog.isNotEmpty()) {
                    logFeedView.text = state.diagnosticLog.take(6).joinToString("\n") { "• $it" }
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
        cornerRadiusDp: Int = 10,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = createRoundedDrawable(bgColorHex, "#3F3F46", dp(cornerRadiusDp))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { onClick() }
        }
    }

    private fun createCircleDotDrawable(fillHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(fillHex))
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
