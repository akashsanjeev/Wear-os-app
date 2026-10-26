# OP2R-Bridge v3.8.0 — Native Wear OS 5.0 Alert UI, Back-Swipe Fix & Battery Optimization Plan

## 1. Edge-to-Edge AMOLED Notification & Call UI (No Blue/Square Box)
- **Remove Nested Square Boxes in `MainActivity.kt`:**
  - Eliminate the rectangular `cardBox` (`#111827` fill with `#38BDF8` sky-blue border) and the inner bordered `messageScroll` box (`#090D16` fill with `#1F2937` border) inside `buildFullScreenPopupOverlay()`.
  - Render incoming calls and notifications directly on the circular pure-black (`#000000`) AMOLED screen with circular bezel-safe padding (`40dp` top/bottom, `28dp` left/right).
- **Modern Wear OS 5.0 Alert Layout:**
  - **Top App/Category Pill Badge:** Compact rounded pill at the top center showing the app name (e.g., `WHATSAPP`, `MESSAGES`, `INCOMING CALL`) with subtle category accent tinting (`#052E16` emerald for calls, `#0C4A6E` cyan for messages, `#1E293B` slate for general notifications).
  - **Title & Sender Header:** Crisp, centered bold header (`19sp`, `#FFFFFF`, max 2 lines) displaying the resolved caller name or notification sender.
  - **Borderless Message Body:** Clean, transparent scrollable text area (`14.5sp`, `#E2E8F0`) centered on the watch face with no square border or background box.
  - **Curved Pill Action Buttons:** Full-pill (`999dp` radius) buttons at the bottom—side-by-side green/red `Answer` & `Decline` pills for incoming calls, and a single sleek `Dismiss` pill (`#1E293B` fill) for messages and alerts.

## 2. Bottom Horizontal Page Indicators & Page 2 Back-Swipe Navigation
- **Bottom Center Page Dots (`MainActivity.kt`):**
  - Move the 2-page indicator dots (`pageDotClock` and `pageDotControls`) to the bottom-center of the 466×466 circular screen (`gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL`, `bottomMargin = dp(12)`) in a horizontal layout matching the horizontal swipe gesture.
- **Prevent System Back Gesture from Closing the App on Page 2 (`MainActivity.kt` & `AndroidManifest.xml` / `styles.xml`):**
  - Disable Wear OS system swipe-to-dismiss (`android:windowSwipeToDismiss = false` via activity theme) so left-to-right swipes on Page 2 are handled in-app rather than killing the activity.
  - Override `dispatchTouchEvent(ev: MotionEvent)` in `MainActivity` to detect horizontal swipes (`abs(dx) > abs(dy)` and `abs(dx) > 38dp`) across both pages—even when touching inside the vertical `ScrollView` on Page 2—so swiping right on Page 2 smoothly returns to Page 1 (Clock), and swiping left on Page 1 opens Page 2 (Controls).
  - Override `onBackPressed()` so:
    1. If a call/notification popup is open, back dismisses the popup.
    2. If on Page 2 (Controls), back switches to Page 1 (Clock).
    3. If already on Page 1 (Clock), back exits the app normally.

## 3. BLE Battery Optimization (Zero Auto-Disconnect Guarantee) & Dual-Engine Support
- **Stop High-Power Advertising Only While Connected (`AncsBridgeService.kt`):**
  - Once the iPhone is connected and ANCS Notification Source + Data Source descriptors are subscribed (`isAncsSubscribed = true`), automatically stop the `ADVERTISE_MODE_LOW_LATENCY` peripheral advertiser (`stopModeAPeripheralAdvertising()`). Stopping peripheral advertising does **not** affect an active GATT connection, and immediately saves significant BLE radio power.
  - In `onConnectionStateChange`, the instant `STATE_DISCONNECTED` occurs, immediately restart peripheral advertising so the iPhone reconnects seamlessly.
- **Balanced Connection Priority & Gentle Keep-Alive (`AncsBridgeService.kt`):**
  - Use `BluetoothGatt.CONNECTION_PRIORITY_HIGH` during initial service discovery and contact/message attribute bursts, then transition to `BluetoothGatt.CONNECTION_PRIORITY_BALANCED` once subscribed so the BLE controller uses standard connection intervals without dropping the link.
  - Relax the RSSI keep-alive heartbeat from `20s` to `45s` and relax the background reconnect watchdog loop from `5s` to `15s` while actively connected and subscribed.
- **Smart Mode Battery Whitelist & RTOS Power Saver Guidance:**
  - Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission and a one-tap battery exemption check so ColorOS/Wear OS Smart Mode never throttles the persistent BLE bridge in the background (while documenting how OnePlus Watch 2R's strict RTOS Power Saver mode shuts down the Snapdragon W5 Android OS).

## 4. Web Companion Simulator & Release Package (`src/App.tsx`)
- Update the interactive OnePlus Watch 2R preview on the web dashboard to reflect the borderless AMOLED notification/call UI, bottom horizontal page indicators, Page 2 → Page 1 back-swipe behavior, and `v3.8.0` release notes.
