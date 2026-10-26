# OnePlus Watch 2R (`OPWWE234`) — Apple Watch-Grade Always-On BLE Connection, Caller ID & 256B Message Body Extraction

This plan delivers a rock-solid, production-grade upgrade to the standalone **Wear OS 5.0 (Android 14 / API 34)** ANCS bridge on the **OnePlus Watch 2R (`OPWWE234`)**, ensuring the Bluetooth connection never dies, Caller ID (Name + Phone Number) is clearly displayed for all incoming calls, and full 256-byte message bodies are extracted and displayed without generic fallbacks.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Summary of Confirmed Choices**:
> 1. **Caller ID Display**: Incoming calls will show the **Caller Name in a bold primary headline**, the **Phone Number as a distinct subtitle**, and large circular-safe **Accept / Decline buttons**.
> 2. **Apple Watch-Grade Persistent BLE Connection**: The watch will never terminate peripheral advertising, will send a periodic 25-second GATT keepalive ping to reset iOS supervision timeouts, will request balanced BLE connection priority, and will instantly auto-reconnect if the link drops.
> 3. **Full 256-Byte Message Card**: The circular-inscribed scrollable message box will cleanly render the complete 256-byte payload from Attribute `0x03` without clipping or generic placeholder replacement.

---

### 1. Overview & Core Concept

- **What It Does**:
  1. **Permanent Always-On BLE Link**: Operates dual-role BLE (keeps peripheral advertising active even while connected so iOS can re-acquire the watch instantly if out-of-range, backed by a 25-second GATT keepalive heartbeat and aggressive `autoConnect` recovery).
  2. **Caller ID Extraction (Attribute 0x01 & 0x02)**: Requests Subtitle (`0x02`, max 64B) alongside Title (`0x01`, max 64B) and App Identifier (`0x00`). Maps Title to Caller Name and Subtitle to Phone Number / Caller ID.
  3. **256-Byte Message Body Extraction (Attribute 0x03)**: Prioritizes and dynamically reassembles Attribute `0x03` (Message Body) up to 256 bytes across MTU fragments without stalling on missing optional fields.
  4. **Progressive Watch UI Updates**: Renders caller information and message content as soon as attributes arrive, seamlessly upgrading any preliminary alert in-place so generic fallback text is never shown.

---

### 2. User Experience & Visual Design

- **OnePlus Watch 2R (`466×466 px`) Visual Layout**:
  - **Incoming Call Screen**:
    - Top Badge: `IPHONE CALL` (red-tinted pill)
    - Primary Title: Caller Name (e.g. "John Doe" or unknown caller) in large bold font (`15sp`)
    - Subtitle / Caller ID: Phone Number (e.g. "+1 (555) 019-2831" or carrier tag) in monospace cyan/slate text (`11sp`)
    - Bottom Actions: Tapered circular-inscribed Accept (`0x00`) and Decline (`0x01`) buttons
  - **Notification Message Screen**:
    - Top Badge: App Name (e.g. WhatsApp, Messages, Mail)
    - Sender Name: Bold contact title (`13.5sp`)
    - Message Box: 80% width, 76dp high inscribed scrollable viewport displaying the full 256-byte message body
    - Bottom Action: Tapered Dismiss pill (`50%` width)
  - **Control Deck & Telemetry**:
    - Header shows live packet counts (`NS:x DS:x`) and link heartbeat indicator (`Active / Alive`)

---

### 3. Key Technical Decisions & Architecture

#### A. Apple Watch-Grade Always-On BLE Link Architecture
- **Never Stop Peripheral Advertising**: Previously, `AncsBridgeService` called `stopModeAPeripheralAdvertising()` upon subscription. On Wear OS 5.0, if iOS drops the connection when idle, iOS cannot re-find the watch. By keeping low-power peripheral advertising active indefinitely with ANCS service solicitation, iOS automatically re-connects whenever in proximity.
- **25-Second GATT Keepalive Ping**: A background coroutine sends periodic lightweight GATT requests (reading Current Time or Heart Rate notify check) every 25 seconds, keeping the BES2700 Bluetooth co-processor and iOS supervision timers refreshed.
- **Immediate Auto-Reconnect Loop**: On `STATE_DISCONNECTED`, the service immediately requests `connectGatt(..., autoConnect = true, ...)` and launches low-latency peripheral advertising, reconnecting within 1–2 seconds of returning to range.
- **Connection Priority**: Sets `BluetoothGatt.CONNECTION_PRIORITY_BALANCED` on connection to prevent aggressive Android 14 radio sleep.

#### B. ANCS Control Point & Data Source Parser Redesign
- **Expanded Control Point Request (14 Bytes)**:
  - Command ID: `0x00` (`GetNotificationAttributes`)
  - Notification UID: 4 bytes (Little-Endian)
  - Attribute `0x00`: App Identifier
  - Attribute `0x01`: Title (64 bytes max)
  - Attribute `0x02`: Subtitle / Caller ID (64 bytes max) — **NEW**
  - Attribute `0x03`: Message (256 bytes max)
- **Attribute Reassembly Engine**:
  - Eliminates strict `EXPECTED_ATTRIBUTES` all-or-nothing requirement.
  - For `CategoryID == 1` (Incoming Call): completes as soon as Title (`0x01`) and/or Subtitle (`0x02`) are extracted, without waiting for Message (`0x03`).
  - For standard notifications: extracts `0x03` Message Body directly from the multi-packet byte stream, concatenating UTF-8 chunks until the full 256-byte payload is assembled.
- **Progressive Delivery**:
  - Broadcasts the caller's identity immediately to `MainActivity`.
  - Replaces generic fallback strings with actual contact information and real message bodies.

---

### 4. Implementation Steps

1. **`AncsPacketParser.kt`**:
   - Add `ATTRIBUTE_ID_SUBTITLE` (`0x02`) to the Control Point request buffer (`buildGetNotificationAttributesCommand`).
   - Add `subtitle` property to `AssembledAncsNotification` for Caller ID phone number.
   - Refactor attribute completion logic: emit immediately when relevant fields for the category arrive (calls prioritize Title + Subtitle; messages prioritize Title + Message).
2. **`AncsBridgeService.kt`**:
   - Keep peripheral advertising running continuously (do not stop advertising on connect).
   - Implement 25-second periodic GATT keepalive heartbeat.
   - Request `CONNECTION_PRIORITY_BALANCED` on connect.
   - Update `presentFullScreenMessagePopupOnWatch` and notification dispatchers to pass `subtitle` (Caller ID phone number) to `MainActivity` and system notifications.
   - Enhance disconnect handler to immediately re-arm `autoConnect` and low-latency advertising.
3. **`MainActivity.kt`**:
   - Update incoming call overlay to display both Caller Name (`title`) and Phone Number (`subtitle`).
   - Update scrollable message viewport to bind the 256-byte message body.
4. **Web Workbench & Simulator (`App.tsx`, `ancsSimulatorEngine.ts`)**:
   - Sync simulated ANCS engine to generate 14-byte Control Point requests with Subtitle `0x02` and 256-byte Message `0x03`.
   - Update simulator watch face to render Caller ID phone number and long message bodies.
