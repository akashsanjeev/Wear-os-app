# OnePlus Watch 2R (`OPWWE234`) — iOS ANCS Live Notification & Call Delivery Fix (Wear OS 5.0)

Fixes the issue where the **OnePlus Watch 2R (`OPWWE234`)** connects and displays **`"Bonded - Listening to ANCS"`** with **Share System Notifications** enabled on the iPhone, yet incoming iPhone calls and messages do not trigger watch notifications or popups.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Why `"Bonded - Listening to ANCS"` Showed Up Without Receiving Calls or Messages (Root Cause Analysis)**:
> 1. **Premature CCCD Subscription Before iOS ANCS Authorization Finalizes**: During Mode A pairing (`Advertise iPhone (A)`), the watch writes the `Data Source` and `Notification Source` CCCD descriptors within ~150ms of bonding—*before* iOS finishes activating the **Share System Notifications** pipeline (or while the iOS "Allow Notifications" prompt is still being accepted). iOS returns `GATT_SUCCESS` (so the watch shows `"Bonded - Listening to ANCS"`), but silently drops all ANCS events until the watch re-subscribes *after* authorization is active. Furthermore, `BluetoothGattCallback.onServiceChanged()` was not implemented to catch iOS GATT table updates.
> 2. **Back-to-Back Control Point Requests Corrupting `AncsPacketParser`**: When iOS sends two 8-byte `Notification Source` packets in rapid succession (e.g., an initial notification burst, or `Added` + `Modified` for a WhatsApp message or Incoming Call), `AncsBridgeService` sent the second `Control Point` write as soon as `onCharacteristicWrite` fired—*before* `Data Source` finished streaming the first notification's attributes. This caused `AncsPacketParser` to misinterpret the next `0x00` command header as `AttributeID = 0` and read the 4-byte `NotificationUID` as a huge `AttributeLength`, permanently deadlocking the parser state machine.
> 3. **Initial Burst Filtering (`EventFlagPreExisting`) & Zero Fallback When `Data Source` Stalls**: Previously, nothing happened on the watch until all three `Data Source` attributes (`AppIdentifier`, `Title`, `Message`) were fully reassembled. If `Data Source` timed out, the call or message was silently lost.

- **Confirmed Decision 1 (Connection Mode)**: Optimize **Mode A (`Advertise iPhone (A)`)** reverse GATT connection with automatic Post-Bond ANCS Re-Subscription waves (`+3s` and `+8s` after bonding) plus `onServiceChanged` handling.
- **Confirmed Decision 2 (ANCS Pipeline Hardening)**: Implement **Auto Re-Sync ANCS + Queue Lock + Live RX Telemetry + Test Alert** on the OnePlus Watch 2R.

---

### 1. Overview & Core Concept

- **What It Does**:
  1. **Multi-Wave Post-Bond ANCS Re-Subscription & `onServiceChanged`**: Automatically re-verifies and re-writes the `Data Source` and `Notification Source` CCCDs at `+3s` and `+8s` after bonding (and whenever `onServiceChanged` fires or the user taps **Re-Sync ANCS**) so iOS's **Share System Notifications** stream is guaranteed to be active.
  2. **End-to-End GATT Control Point Lock & Deadlock-Proof Parser**: Locks each `Control Point` (`0x00`) attribute request until its corresponding `Data Source` response completes (with a `1200ms` watchdog timeout) and adds strict attribute header validation (`attributeId in 0..7`, `length <= 1024`) so `AncsPacketParser` can never deadlock on fragmented or back-to-back iOS alerts.
  3. **Instant 8-Byte `Notification Source` Call Ringing & Message Fallback**: Wakes the watch and starts ringing *immediately* when an 8-byte Incoming Call (`CategoryID == 1`) packet arrives on `Notification Source`, and triggers a `900ms` fallback popup for standard messages if `Data Source` is delayed—updating the card in-place with the full sender title and message body as soon as `Data Source` arrives.
  4. **Smart Pre-Existing Window & On-Watch Live RX Telemetry (`NS / DS`)**: Displays live `NS:` (Notification Source 8B packets) and `DS:` (Data Source payloads) counters right on the `466×466` watch screen, plus a 1-tap **Re-Sync & Test Alert** button to verify watch vibration/screen-wake hardware and force an immediate ANCS descriptor refresh.

---

### 2. User Experience & Visual Design

- **OnePlus Watch 2R (`466×466 px`) Circular-Safe Control Deck Updates**:
  - Preserves the exact circular chord tapering (`52% -> 68% -> 82% -> 76% -> 64%`) so nothing clips the round bezel.
  - **Row 1 (52% width)**: `OP2R-Bridge · WOS5` header + live `NS:0 · DS:0` packet counter.
  - **Row 2 (68% width)**: Interactive Status & Re-Sync Pill (`Bonded · Listening (Tap to Re-Sync)`). Tapping this pill while connected immediately re-subscribes `Data Source` and `Notification Source` CCCDs on the iPhone.
  - **Row 3 (82% width, Equator 2-Column)**: `Advertise iPhone (A)` and `Scan Beacon (B)`.
  - **Row 4 (76% width)**: `Pre-Existing` filter toggle (defaults to smart mode: ignores only the first 2.5 seconds of historical dump on initial connect, never dropping live notifications).
  - **Row 5 (64% width, Lower Arc 2-Column)**: `Test Popup` (triggers a real watch vibration + screen-wake full-screen message popup to verify watch permissions) and `Reset BLE`.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Hold GATT Queue Until `Data Source` Finishes (Instead of Releasing on `onCharacteristicWrite`)**
  - *Chosen Approach*: Keep `isGattOperationInFlight = true` after writing `CommandIDGetNotificationAttributes (0x00)` to `Control Point` until either `AncsPacketParser` finishes assembling the 3 attributes on `Data Source` OR a `1200ms` watchdog timer fires.
  - *Why*: iOS strictly forbids concurrent `GetNotificationAttributes` requests on the ANCS Control Point; sending a second request before the first `Data Source` stream finishes aborts the transfer and corrupts the byte stream.
- **Decision 2: Two-Stage Alert Delivery (Immediate 8-Byte Trigger + Rich `Data Source` Upgrade)**
  - *Chosen Approach*: Trigger screen wake and alert tracking as soon as the 8-byte `Notification Source` packet arrives, then populate/upgrade the Full-Screen Message Popup Card when `Data Source` completes (or display the fallback category alert at `900ms`).
  - *Why*: Guarantees 100% notification and call reliability even if iOS withholds or delays `Data Source` attributes on locked screens.

---

### 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Hardened ANCS Subscription & Notification Delivery Pipeline**:
```
┌─────────────────────────────────────────────────────────────────────────┐
│ Mode A: iPhone Connects to OP2R-Bridge -> Reverse GATT (TRANSPORT_LE)   │
├─────────────────────────────────────────────────────────────────────────┤
│ 1. Initial CCCD Subscribe: Data Source (22EA..) -> Notif Source (9FBF..)│
│ 2. Post-Bond Re-Sync Waves (+3s, +8s) & onServiceChanged(gatt)          │
│    • Re-applies setCharacteristicNotification(true) & writes 0x01 0x00  │
│    • Ensures iOS "Share System Notifications" stream is active          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ iOS Fires 8-Byte Notification Source Packet (9FBF120D-...)              │
│  • Increments on-watch NS counter immediately (UI thread broadcast)     │
│  • If CategoryID == 1 (Call): Immediate Screen Wake + Call Popup        │
│  • Starts 900ms Fallback Timer (ensures alert shows even if DS stalls)  │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Serialized Control Point Request (69D1D8F3-...) + 1200ms Watchdog Lock  │
│  • Sends 11-byte GetNotificationAttributes (AppID, Title 64B, Msg 256B) │
│  • Holds queue lock until Data Source (22EAC6E9-...) completes          │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Data Source Reassembled -> Increments DS Counter -> Cancels Fallback    │
│  • Wakes OnePlus Watch 2R Screen + Vibrates + Displays Full Message Card│
└─────────────────────────────────────────────────────────────────────────┘
```
