# Implementation Plan - OnePlus Watch 2R Circular UI Optimization

Optimize the Wear OS 5.0 application (`MainActivity.kt`, `AncsBridgeService.kt`) and the interactive web simulator (`App.tsx`) specifically for the **OnePlus Watch 2R** (1.43" circular AMOLED, 466×466 px, 326 ppi). This plan directly addresses the top/bottom clipping, implements user-selected design choices from the planning questionnaire, and resolves the GitHub CI compilation error (`'onServicesChanged' overrides nothing`).

---

## User Review Required

> [!IMPORTANT]
> **OnePlus Watch 2R Screen Geometry & Physics**:
> On a 466×466 px circular display, the visible horizontal chord width narrows dramatically near the vertical extremes ($w(y) = 2 \sqrt{R^2 - (y-R)^2}$). At $y = 35\text{px}$ from the top or bottom, the available width is under $230\text{px}$ (less than half the screen width).
>
> 1. **Page 1 (Clock Screen)**: To prevent any clipping, we replace the tall vertical 6-element stack with a **Clean Curved Layout**:
>    - Centered large digital time (`HH:mm`) and date.
>    - A single integrated, compact horizontal status chip cluster (`[📞 0 Missed] [💬 0 Msgs]`).
>    - A sleek, centered "Clear" pill directly beneath the chips in the equator safe zone.
> 2. **Navigation Indicator**: We remove the clipped bottom text footer (`Swipe Left for Controls` at the bottom chord) and replace it with **Subtle Side-Arc Dots** hugging the right circular bezel edge, completely freeing top and bottom display space.
> 3. **Page 2 (Controls & Diagnostics)**: Implement a **Center-Weighted Scroll** with generous top/bottom padding ($76\text{px}$ top, $84\text{px}$ bottom). Every control, button, and raw attribute log scrolls directly through the wide equatorial safe zone ($466\text{px}$ wide) without clipping against the curved bezels.
> 4. **CI Build Fix (`AncsBridgeService.kt`)**: Fix the GitHub Actions compilation failure where `'onServicesChanged' overrides nothing` by ensuring strictly valid Android BLE callbacks compatible across all Gradle/SDK toolchain builds.

---

## Proposed Changes

### 1. Android Wear OS 5.0 Native App (`/app/src/main/java/com/op2r/iosbridge/`)

#### [MainActivity.kt](file:///app/src/main/java/com/op2r/iosbridge/MainActivity.kt)
- **466×466 Circular Safe-Area Inset Calculation**:
  - Update `buildCircularTwoPageWatchLayout()` to compute circular safe area bounds specifically for 466×466 px round screens (`screenMinPx = 466`).
  - Calculate equatorial horizontal padding and dynamic vertical padding based on circular chords.
- **Page 1 Restructure (Clean Curved Layout)**:
  - Reposition top connection indicator as an unobtrusive compact status pill nestled just above the clock within the safe circle.
  - Center the primary digital clock (`HH:mm`, bold sans-serif) and compact date.
  - Group Missed Calls and Messages counters into a compact horizontal chip row with rounded capsules (`#1F1218` and `#0F1B29`).
  - Position the "Clear Counts" action button centered immediately below the counter chips in the wide center zone (width: $110\text{dp}$, height: $28\text{dp}$).
  - Remove all bottom text labels that previously collided with the lower bezel.
- **Side Arc Page Navigation Dots**:
  - Remove the bottom `pagerFooter` (`Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL`).
  - Create a dedicated side-arc navigation indicator placed along the right bezel edge (`Gravity.CENTER_VERTICAL | Gravity.END`, right margin: $8\text{dp}$).
  - Render two vertical pill/dot indicators (active: cyan `#38BDF8`, inactive: zinc `#52525B`) that visually align with the round bezel curvature and indicate swipe state without consuming vertical screen real estate.
- **Page 2 Restructure (Center-Weighted Scroll)**:
  - Configure `page2ControlsScroll` with generous circular top and bottom padding (`paddingTop = dp(76)`, `paddingBottom = dp(84)`).
  - Constrain button and card widths to $84\%$ of screen width so content is never cut off at bezel edges when scrolling.
  - Keep the Raw ANCS Inspector card (AppID 0x00, Title 0x01, Subtitle 0x02, Message 0x03) cleanly visible as a center card.
- **Full-Screen Popup Overlay Adaptation**:
  - Optimize the circular incoming call and message popup cards to respect the 466×466 bezel geometry:
    - Top App badge: $48\%$ chord width.
    - Title / Contact Name & Subtitle / Phone Number: centered $72\%$ width.
    - Message body box: centered $82\%$ width, scrollable, max height $108\text{dp}$.
    - Accept/Decline action buttons: $52\%$ width centered pill buttons.

#### [AncsBridgeService.kt](file:///app/src/main/java/com/op2r/iosbridge/AncsBridgeService.kt)
- Fix the CI build failure seen in GitHub Actions run `#40384377303`:
  - Inspect line 661 of `BluetoothGattCallback`.
  - Remove `override fun onServicesChanged` / `override fun onServiceChanged` or replace with standard `onServicesDiscovered` and standard Android 14 GATT callback signatures to ensure error-free compilation on all Android SDK / Gradle 8.7 environments.

---

### 2. Interactive Web Companion & Watch Simulator (`/src/App.tsx` & `/src/data/projectFiles.ts`)

#### [App.tsx](file:///src/App.tsx)
- **Watch Bezel Simulator Update (466×466 Circular Viewport)**:
  - Update the circular watch face display container to mirror the exact OnePlus Watch 2R dimensions and bezel curvature.
  - **Page 1**: Implement the clean curved layout:
    - Centered digital time (`10:26`), date, and compact connection pill.
    - Compact dual counter pills (`[📞 0 Missed] [💬 0 Msgs]`).
    - Centered compact "Clear Counts" button.
  - **Side Arc Dots**:
    - Replace bottom text footer (`bottom-2`) with right-side arc indicator dots positioned at the right edge center (`right-2.5 top-1/2 -translate-y-1/2 flex flex-col gap-1.5`).
  - **Page 2**:
    - Wrap controls in a center-weighted scroll view with `pt-16 pb-20 px-8`.
    - Mode A Advertise button, Re-Sync, Test Alert, and live Raw ANCS Attribute Inspector.
- **Update Direct Download & CI Guide**:
  - Update instructions and status cards to reflect the v5 circular UI layout and the build fix.

#### [projectFiles.ts](file:///src/data/projectFiles.ts)
- Keep raw project files in sync so that users exporting the ZIP or downloading code get the exact, buildable Kotlin sources.

---

## Verification Plan

### Automated Verification
1. **Applet Compilation**: Run `compile_applet` to ensure the React TypeScript web application builds with zero TypeScript or Vite bundle errors.
2. **Linting Check**: Run `lint_applet` to ensure strict linting compliance across all files.

### Visual & Geometry Verification
1. **Page 1 Clock Screen (Zero Clipping)**:
   - Verify that at no point do text elements (top connection status, digital clock, date, counter pills, or clear button) touch or clip the top/bottom curved borders.
   - Verify right-edge side arc dots cleanly indicate Page 1 is active.
2. **Page 2 Controls Screen (Smooth Center Scroll)**:
   - Verify scrollable container begins and ends with empty safe padding so the first item ("CONTROLS • MODE A") and last item ("RAW ANCS INSPECTOR") can be scrolled fully into the 466px center zone.
   - Verify right-edge side arc dots switch to Page 2 active state.
3. **Popup Alert Overlay**:
   - Trigger a simulated ANCS alert (both Incoming Call with Contact Name and 256-byte WhatsApp message) to verify the popup card fits inside the circular bezel with no button or text truncation.
