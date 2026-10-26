# Implementation Plan: Wear OS 5.0 ANCS Bridge v4 (Swipeable Clock UI, Contact Name Caller ID & Robust 0x03 Message Delivery)

Resolve the missing contact name on incoming calls and generic message fallback text by restructuring the ANCS Control Point command and attribute mapper, eliminating the unused Scan Beacon B mode, and converting the watch face into a 2-page horizontal swipe layout (Page 1: Digital Clock + Missed Calls & Messages counters + Clear button; Page 2: Mode A Advertising + Re-Sync + Test Alert + Raw Attribute Inspector & Telemetry).

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The user confirmed the following decisions during Phase 1:
> - **Page 1 Layout**: Minimal digital time with call and message badge pills and a safe "Clear All" button.
> - **Page Navigation**: Horizontal swipe between Page 1 (Clock & Counters) and Page 2 (Controls & Diagnostics) with page indicator dots (`● ○`).
> - **Raw Attribute Diagnostics**: Page 2 will display the raw `Title`, `Subtitle`, and `Message` strings received from iOS to easily verify contact name delivery in real-time.
> - **Option B Removal**: Mode B (Scan Beacon B) is completely removed from the UI so accidental presses are impossible; Mode A (`Advertise iPhone (A)`) is the dedicated connection mode.

---

## 1. Overview & Core Concept

- **What It Does**:
  1. **Page 1 (Glanceable Safe Watch Face)**: Displays a clean digital clock, real-time counters for **Missed Calls** and **Messages**, a safe **Clear** button, and connection status. Zero accidental button presses while wearing the watch.
  2. **Page 2 (Swipe Right - Management & Diagnostics)**: Houses the Mode A (`Advertise iPhone (A)`) trigger, `Re-Sync ANCS` button, `Test Alert` button, raw ANCS attribute inspector (`Title`, `Subtitle`, `Message`, `AppID`), live BLE telemetry (RX Src/DS packets, keepalive pings, RSSI), and scrollable logs.
  3. **Contact Name vs Phone Number Extraction**: Fixes the `GetNotificationAttributes` Control Point binary request format so iOS never drops the attribute query. Applies intelligent heuristic mapping: if one attribute contains letters and the other digits, the letters field is prioritized as the **Caller Contact Name** and the digits field as the **Phone Number**.
  4. **Full 256-Byte Message Body Delivery**: Ensures `Data Source` packets are reliably requested and parsed without timing out into generic `"Social / Message - Notification #UID"` fallbacks.

- **Target Hardware**: OnePlus Watch 2R (`OPWWE234`, 1.43" AMOLED, `466×466 px`, round display) running Wear OS 5.0 (Android 14 / API 34) paired with iPhone 14.

---

## 2. User Experience & Visual Design

### Page 1: Glanceable Clock & Safe Counters (Main Screen)
- **Geometry**: Strict circular safe area (14.65% chord inset = ~68px on 466×466 display) to prevent bezel clipping.
- **Header**: Quiet connection indicator (`● Connected` in emerald or `● Advertising (A)` in sky blue).
- **Center**:
  - Large digital clock: `10:24` (42sp bold tabular font).
  - Date subtitle: `Monday, Oct 26` (12sp muted slate).
- **Notification Counter Badges**:
  - 📞 **Missed Calls**: `0 Missed Calls` (or `2 Missed Calls` in vibrant rose `#F43F5E` when > 0).
  - 💬 **Messages**: `0 Messages` (or `3 Messages` in sky blue `#38BDF8` when > 0).
- **Lower Arc**:
  - Safe **Clear** button (`Clear All` with subtle rounded border, requiring intentional tap).
  - Page indicator dots: `●  ○` (Swipe left/right indicator).

### Page 2: Management & Diagnostics (Swipe Right)
- **Navigation**: Smooth horizontal gesture or tap on page dots to switch.
- **Top Row**: Mode A connection trigger: `Advertise iPhone (A)` (Option B is completely removed).
- **Action Row**: `Re-Sync ANCS` and `Test Alert`.
- **Raw Attribute Inspector Card**: Shows live values received from iOS:
  - `Raw Title`: (e.g., `"John Appleseed"`)
  - `Raw Subtitle`: (e.g., `"+1 (415) 555-0199"`)
  - `Raw Message`: (e.g., `"Hey, let's meet at 6!"`)
  - `Raw AppID`: (e.g., `"com.apple.mobilephone"`)
- **Telemetry & Event Log**: Live packet counters (`Src: X`, `DS: Y`, `Heartbeat: Z`, `RSSI: -XX dBm`) and diagnostic log lines.

### Full-Screen Alert Overlays (Incoming Calls & Messages)
- **Incoming Call Overlay**:
  - Category badge: `INCOMING IPHONE CALL` (emerald).
  - **Caller Contact Name**: Large bold white title (e.g., `John Appleseed`).
  - **Phone Number**: Monospace sky-blue subtitle (e.g., `+1 (415) 555-0199`).
  - Call actions: Green **Accept** (`0x00`) and Red **Decline** (`0x01`).
- **Message Popup**:
  - App badge + Contact Name + Full 256-byte message body in inscribed scrollable card + Dismiss button.

---

## 3. Technical Architecture & Component Structure

```
┌────────────────────────────────────────────────────────────────────────┐
│                        OnePlus Watch 2R (466x466)                      │
│                                                                        │
│  ┌──────────────────────────────┐    ┌──────────────────────────────┐  │
│  │     PAGE 1: MAIN CLOCK       │    │     PAGE 2: CONTROLS & DIAG  │  │
│  │                              │    │                              │  │
│  │       10:24 AM               │◄──►│  [ Advertise iPhone (A) ]    │  │
│  │       Mon, Oct 26            │    │  [ Re-Sync ] [ Test Alert ]  │  │
│  │                              │    │  Raw Attributes Inspector:   │  │
│  │   📞 0 Missed Calls          │    │   Title: "John Appleseed"    │  │
│  │   💬 3 Messages              │    │   Sub:   "+1 415 555-0199"   │  │
│  │   [ Clear All ]              │    │   Msg:   "Hey, are you free?"│  │
│  │                              │    │  Telemetry: Src/DS/HB/RSSI   │  │
│  │           ●  ○               │    │           ○  ●               │  │
│  └──────────────────────────────┘    └──────────────────────────────┘  │
│                                 ▲                                      │
│                                 │ (Overlay on incoming alert)          │
│  ┌──────────────────────────────┴───────────────────────────────────┐  │
│  │         FULL-SCREEN POPUP OVERLAY (Incoming Call / Message)      │  │
│  │  Caller Name + Phone Number Subtitle + Full 256B Scrollable Body │  │
│  │  [ Accept 0x00 ]                      [ Decline 0x01 ]           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │ Intent extras / StateFlow
┌─────────────────────────────────┴──────────────────────────────────────┐
│            AncsBridgeService.kt (Wear OS Foreground Service)           │
│  - Mode A Solicited ANCS Advertiser ("OP2R-Bridge", 7905F431-...)      │
│  - 20-Second Keepalive RSSI Heartbeat                                  │
│  - Continuous 5-Second Auto-Reconnect Loop                             │
│  - Robust GATT Command Queue (4x Busy Retries)                         │
└─────────────────────────────────▲──────────────────────────────────────┘
                                  │
┌─────────────────────────────────┴──────────────────────────────────────┐
│            AncsPacketParser.kt (Binary Protocol Engine)                │
│  - 12-Byte Control Point Command: [0x00, UID(4B), 0x01(64B),          │
│                                    0x02(64B), 0x03(256B)]              │
│  - Heuristic Contact vs Phone Number Classifier                        │
│  - 350ms Data Source Idle Flush + Raw Attribute Telemetry              │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Implementation Steps

### Step 1: `AncsPacketParser.kt`
- **Reorganize Control Point Command**: Structure `CommandIDGetNotificationAttributes` (0x00) with `NotificationUID (4B LE)` followed by `AttributeIDTitle (0x01, 64B)`, `AttributeIDSubtitle (0x02, 64B)`, and `AttributeIDMessage (0x03, 256B)` without leading unparameterized attributes that cause iOS 16/17/18 ANCS parsers to abort.
- **Contact Name Heuristic Classifier**:
  - If `rawTitle` contains letters and `rawSubtitle` contains phone digits/symbols (`+`, `-`, `()`, numbers), assign `rawTitle` as Caller Contact Name and `rawSubtitle` as Phone Number.
  - If `rawTitle` is a phone number and `rawSubtitle` contains letters (or vice versa), swap them so the human name is always the prominent `title` and the phone number is `subtitle`.
  - Store raw attribute values in `AssembledAncsNotification` for live diagnostic inspection.

### Step 2: `AncsBridgeService.kt`
- **Telemetry & Counters State**: Track unread `messageCount` and `missedCallCount` in `BridgeUiState`, with an action `ACTION_CLEAR_COUNTERS` to reset them.
- **Raw Attribute Feed**: Pass raw `title`, `subtitle`, `message`, and `appId` into `BridgeUiState` so Page 2 can display them directly.
- **Incoming Call Handling**: Increment `missedCallCount` when `CategoryID == 2` (Missed Call) or when an incoming call goes unanswered.

### Step 3: `MainActivity.kt`
- **Two-Page Horizontal ViewPager / Fling-Swipe Layout**:
  - Implement a clean 2-page swipe container using `androidx.viewpager2.widget.ViewPager2` (or a custom 2-page horizontal swipe frame with smooth touch interpolation).
  - **Page 1**: Minimal digital time (`10:24`), date, connection state dot, Missed Call counter pill, Message counter pill, safe `Clear All` button, and bottom page indicator dot `● ○`.
  - **Page 2**: Mode A `Advertise iPhone (A)` button, `Re-Sync ANCS` button, `Test Alert` button, Raw Attribute Inspector, live BLE telemetry, and scrollable log feed with bottom page indicator dot `○ ●`.
- **Accidental Click Prevention**: No connection or scanning buttons exist on Page 1. Mode B is completely removed.

### Step 4: Web Simulator & Documentation (`/src/App.tsx`, `/src/utils/ancsSimulatorEngine.ts`)
- Update the web watch face simulator to reflect the 2-page swipe interface (Page 1 Clock & Counters + Page 2 Controls), remove Mode B button, test the heuristic Contact Name vs Phone Number resolver, and verify compilation with `compile_applet`.
