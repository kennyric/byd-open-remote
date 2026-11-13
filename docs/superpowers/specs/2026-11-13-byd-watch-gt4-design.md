# BYD Control App for Huawei Watch GT 4 — Design Spec

**Date:** 2026-11-13  
**Target Vehicle & Region:** BYD Atto 1 — Indonesia (`region = "id"`, `country = "ID"`)  
**Target Devices:** Huawei Watch GT 4 (`Lite Wearable`, 466×466 round OLED) + Paired Android Smartphone (Huawei Health + Wear Engine)

---

## 1. Intent & Why

Provide a personal, reliable way to check the BYD Atto 1's status and trigger core remote commands (lock/unlock, climate/AC, lights, horn) directly from a Huawei Watch GT 4, without pulling out a phone.

Because the Watch GT 4 runs HarmonyOS **Lite Wearable** (LiteOS JS/HML/CSS) rather than Wear OS or full HarmonyOS ArkTS—and cannot run BYD's Java white-box cryptography (`BangcleCodec`) or make standalone BYD Cloud HTTPS calls—the system splits responsibilities cleanly between a **Watch GT 4 remote UI** and an **Android companion app** communicating over Bluetooth via **HUAWEI Wear Engine**.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **Watch GT 4 (`Lite Wearable`) remote UI** modeled after BYD's official Apple Watch / Wear OS app using a **crown-scrollable Option B layout**:
  - Top hero card: outer-edge circular battery SoC `%` arc hugging the `466×466` round perimeter, with big SoC `%`, estimated range (`km`), door lock state, cabin temperature, and primary **Lock / Unlock** button centered inside.
  - Rotating the physical watch crown scrolls vertically down to **Quick Controls** (**Flash Lights**, **Horn / Find Car**, **Refresh Status**) and **Climate Control** (**AC On/Off**, target temperature defaulting to **17°C** with `−` / `+` step controls).
- **Android Companion App** (standard Java, no Google Play Services):
  - Ports Overdrive's MIT-licensed `byd/cloud` and `crypto` classes (`BangcleCodec` + `bangcle_tables.bin`) for overseas BYD Cloud authentication, PIN verification, telemetry polling, and remote commands.
  - Includes a **standalone phone UI** (BYD login, operation PIN, vehicle picker, Wear Engine link status, and direct test buttons for every command) so the BYD Atto 1 integration can be verified on the phone immediately while Huawei Developer account verification and watch `.hap` signing are pending.
  - Hosts the **HUAWEI Wear Engine (`P2pClient` + `Receiver`)** bridge over Bluetooth to execute commands requested by the Watch GT 4 and push updated vehicle telemetry/command results back to the watch.
- **Multi-phase delivery:** Build and test the Android companion app first; build, sign, and sideload the Watch GT 4 `.hap` once Huawei Developer verification completes.

### Non-Goals
- Direct watch-to-internet BYD Cloud requests without the phone.
- Mainland China (`cn` / `WbskCodec`) UI configuration (Overdrive's `EnvelopeCodecFactory` / `WbskCodec` may remain unused or excluded from UI; Indonesia uses overseas `BangcleCodec`).
- NFC / Bluetooth digital car key (passive unlock/start via watch tap).
- Public AppGallery / Google Play distribution (personal sideloaded `.apk` and DevEco Assistant `.hap`).

### Anti-Goals
- **Concurrent primary account lockouts:** Using the owner's primary BYD account logs out the official BYD app. The app must clearly prompt for and support a secondary **Alternate Vehicle Authorization** profile.
- **Accidental wrist unlocks:** Unlocking the car must never fire from an inadvertent single sleeve/wrist tap; the watch must show a quick 1-tap confirmation step before sending `unlock`.
- **Fake/no-op UI controls:** Do not ship watch controls (such as fan speed) that BYD's Cloud API silently ignores without first verifying protocol support.

---

## 3. Constraints & Technical Findings

### Hardware & OS Constraints
- **Huawei Watch GT 4 (`Lite Wearable`):**
  - 466×466 round OLED screen; JS/HML/CSS FA framework (API 6–7 legacy Lite Wearable SDK).
  - **Crown scrolling constraint:** In Lite Wearable HML, physical crown rotation is only exposed via `rotation({ focus: true })` on `<list>` or `<slider>` (not generic `<div>` containers). Therefore, the watch page must be structured as a full-screen `<list>` whose `<list-item>` cards represent the Option B hero, Quick Controls, and Climate sections.
  - **Sideloading & Wear Engine requirements:** Requires a verified Huawei Developer account, an AppGallery Connect project linking both the Android package name and the Watch `.hap` package name + SHA-256 certificate fingerprints (colons stripped in `wearengine.js`), **Huawei Health** paired on the Android phone, and **DevEco Assistant (HDEA)** to sideload the signed `.hap`.
- **Development Environment:**
  - Windows PC with JDK 17, Node.js 24, Git; `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME`, and `ANDROID_USER_HOME` configured on drive `D:\` (`D:\Android\...`).
  - Android Studio installation is pending local UAC confirmation; Huawei Developer account is pending verification; legacy DevEco Studio (with Lite Wearable API 6–7 SDK) will be installed onto `D:\` once Huawei Dev verification completes.

### BYD Cloud Protocol & Climate / Fan-Speed Constraint
- Reference source: `https://github.com/yash-srivastava/Overdrive-release` (MIT License), `com.overdrive.app.byd.cloud.*` and `assets/byd/bangcle_tables.bin`.
- `BydCloudRegionCatalog` maps Indonesia directly (`region = "id"`, `country = "ID"`), using the overseas Bangcle-encrypted transport.
- **Climate (`OPENAIR`) & Fan Speed Finding:**
  - In Overdrive (`BydCloudClient.climateStartParams`) and `pyBYD`, `OPENAIR` sends:
    - `mainSettingTemp` & `copilotSettingTemp` (`rawTemp = celsius - 14`, valid `15..31°C` scale; **user requested default: `17°C`**, i.e., `rawTemp = 3`)
    - `cycleMode` (`2` = auto/fresh or `1` = internal recirculation)
    - `timeSpan` (mapped from 10/15/20/25/30 min; default 20 min)
    - `remoteMode = 4`, `airConditioningMode = 1`, `airAccuracy = 1`, `airSet = null`
  - **Protocol gap:** Neither Overdrive's `BydCloudClient` nor `pyBYD` documents a remote cloud parameter for **fan speed** (`windLevel` / `airVolume`); Overdrive's `climate/fan` MQTT topic is local head-unit SDK only.
  - **Builder/Verification rule:** Before implementing fan speed in the UI, check whether `com.byd.bydautolink` v3.6.0 (present in `E:\Kenny\Huawei`) or live Atto 1 API responses support a fan-speed field in `OPENAIR` / `airSet`. If BYD Cloud `OPENAIR` has no fan-speed parameter, omit fan speed from the watch UI (or note it clearly on the phone) rather than exposing a non-functional control.

---

## 4. Architecture & Component Design

```
┌───────────────────────────┐      Bluetooth (Wear Engine P2P)      ┌──────────────────────────────┐
│  Huawei Watch GT 4 (.hap) │ ◄───────────────────────────────────► │  Android Companion App (.apk)│
│  Lite Wearable HML/CSS/JS │   JSON commands & status snapshots    │  Java + Wear Engine SDK      │
│  - <list> + crown focus   │                                       │  - WearEngineBridgeService   │
│  - wearengine.js P2pClient│                                       │  - BydCloudClient (Bangcle)  │
└───────────────────────────┘                                       │  - Phone Login & Test UI     │
                                                                    └──────────────┬───────────────┘
                                                                                   │ HTTPS (Bangcle)
                                                                                   ▼
                                                                    ┌──────────────────────────────┐
                                                                    │  BYD Cloud (Indonesia `id`)  │
                                                                    └──────────────────────────────┘
```

### 4.1 Workspace & Repository Layout (`E:\Kenny\Huawei`)
- `android-companion/` — Android Studio / Gradle project (Java, `minSdk 26`, `targetSdk 34` — my call; adjust if Wear Engine SDK requires specific min/target).
- `watch-gt4/` — DevEco Studio Lite Wearable project (`config.json`, `pages/index/index.{hml,css,js}`, `wearengine/wearengine.js`).
- `docs/superpowers/specs/` — Design documents.
- Note: Keep `com.byd.bydautolink_*.apkm` untouched as reference; add `.superpowers/` and build outputs to `.gitignore` when initializing git.

### 4.2 Android Companion App (`android-companion/`)
1. **BYD Cloud Layer (`com.bydwatch.companion.byd` + ported `com.overdrive.app.byd.cloud`)**
   - Port Overdrive's `byd/cloud/` and `byd/cloud/crypto/` (`BangcleCodec`, `EnvelopeCodecFactory`, `BydCryptoUtils`, `BydCloudClient`, `BydCloudConfig`, `BydCloudRegionCatalog`, `BydCloudSession`, `BydCloudTransport`) and `assets/byd/bangcle_tables.bin`.
   - Remove/stub Overdrive-specific dependencies not needed on a phone (e.g., head-unit `DrivingSafetyGuard`, file-based Overdrive logger — replace with Android `Log` / thin wrapper).
   - Default region configuration: `"id"` / `"ID"` / `"en"` (editable in advanced settings if ever needed).
   - Secure storage (`EncryptedSharedPreferences` via AndroidX Security Crypto, or Keystore-backed AES): stores BYD email/username, password, 6-digit operation PIN, selected VIN, and AC default temperature (`17°C`).
   - Background execution: single-thread executor / serialized command queue in a dedicated `BydRepository` so concurrent watch taps or refresh requests don't race or corrupt the BYD Cloud session token.
2. **Phone UI (`MainActivity`)**
   - **Account & PIN card:** Email/phone, password, operation PIN, region (`Indonesia - id`), "Alternate Vehicle Authorization recommended" notice, Login & Test Connection button, VIN selector.
   - **Live Status & Manual Test Card:** Displays cached/latest SoC %, range km, door lock state, cabin temp, AC state, last update timestamp; buttons for Refresh, Lock, Unlock, AC On (`17°C` + temp `−`/`+`), AC Off, Flash Lights, Horn.
   - **Wear Engine Bridge Card:** Shows Wear Engine initialization state, paired Watch GT 4 connectivity status, and recent command log (for easy debugging without a watch debugger).
3. **Wear Engine Bridge (`WearEngineBridge`)**
   - Uses HUAWEI Wear Engine Android SDK (`HiWear.getP2pClient(context)` + `Receiver`).
   - Configured with the Watch GT 4 app's package name and signing SHA-256 fingerprint (stored in `BuildConfig` / resources so it's easy to update once AppGallery Connect signing keys are generated).
   - Runs in the app process (with a lightweight foreground/bound notification or on-demand wake when Huawei Health delivers P2P messages; exact Wear Engine background wake behavior to be verified on the user's phone brand).

### 4.3 Watch GT 4 App (`watch-gt4/`)
1. **Crown-Scrollable `<list>` Architecture (`index.hml`, `index.css`, `index.js`)**
   - Root `466×466` dark OLED container (`#000000` background for battery & bezel blending).
   - Full-screen `<list id="mainList">` bound with `rotation({ focus: true })` in `onShow()` / `onReady()` so the physical rotating crown scrolls smoothly between cards:
     - **Card 1 — Hero Status & Primary Lock (`list-item` `466×466`):**
       - Perimeter battery ring (`<progress type="arc">` or `<canvas>` arc) running right along the outermost edge of the round `466×466` screen.
       - Inside the ring: `BYD ATTO 1` header, lock/cabin summary (`Locked • 27°C`), large SoC `%` (`78%`) + range (`290 km`), and primary pill button (**Unlock Car** when locked / **Lock Car** when unlocked).
       - Subtle scroll hint (`▾ Turn crown`) just inside the bottom arc.
     - **Card 2 — Quick Controls (`list-item`):**
       - **Flash Lights** button (`lights`)
       - **Horn / Locate** button (`horn`)
       - **Refresh Status** button (`status`) + last synced timestamp (`2m ago`) + phone link indicator (`Phone Ready` / `No Phone`).
     - **Card 3 — Climate Control (`list-item`):**
       - **AC On / Off** toggle button (`ac_on` / `ac_off`), showing active/off state.
       - Target temperature row: `−` **`17°C`** `+` (clamped `17–31°C` by default, or `15–31°C` if confirmed; **17°C default**).
       - *(Conditional)* Fan speed row only if BYD Cloud protocol verification confirms `OPENAIR` supports a fan-speed field.
   - **Unlock Confirmation Overlay:** When tapping **Unlock Car**, display a clean modal/overlay card (`Unlock BYD Atto 1?` -> `[Confirm]` / `[Cancel]`) so sleeve taps never unlock the car unintentionally.
   - **Pending / Feedback State:** Disable/debounce duplicate command taps while a command is in flight (`Sending...`), show short toast/status banner (`Locked ✓`, `AC 17°C On ✓`, `Phone offline`, `PIN/Auth error`), and auto-update UI from phone response.
   - **State Persistence:** Cache last known vehicle status and target AC temperature locally (or fetch fresh on launch via `status` command) so the screen is immediately readable before Bluetooth round-trip completes.

### 4.4 Phone ↔ Watch JSON Protocol
Compact UTF-8 JSON strings over Wear Engine `P2pClient` (well under Lite Wearable message size limits):

**Watch → Phone (`cmd`)**
```json
{"id":"1700000001","action":"status"}
{"id":"1700000002","action":"lock"}
{"id":"1700000003","action":"unlock"}
{"id":"1700000004","action":"ac_on","tempC":17}
{"id":"1700000005","action":"ac_off"}
{"id":"1700000006","action":"lights"}
{"id":"1700000007","action":"horn"}
```

**Phone → Watch (`resp`)**
```json
{
  "id": "1700000001",
  "ok": true,
  "action": "status",
  "msg": "Updated",
  "state": {
    "soc": 78,
    "rangeKm": 290,
    "locked": true,
    "acOn": false,
    "cabinTempC": 27,
    "targetTempC": 17,
    "ts": 1700000002
  }
}
```
On failure: `{"id":"...","ok":false,"action":"...","err":"PIN_INVALID | AUTH_FAILED | CAR_OFFLINE | TIMEOUT | NOT_CONFIGURED","msg":"Short human error"}`.

---

## 5. Decisions Made (Builder Calls to Check)

The following defaults were chosen as sensible calls unless you prefer otherwise:
1. **Android `minSdk 26` / `targetSdk 34`, pure Java:** Keeps porting Overdrive's Java `byd/cloud` classes frictionless with zero Kotlin interop boilerplate and broad Android compatibility.
2. **Unlock confirmation on watch; instant execution for Lock / Lights / Horn / AC:** Prevents accidental pocket unlocks while keeping other actions one-tap fast.
3. **Serialized command queue + auto-refresh after Lock/Unlock/AC:** Prevents concurrent BYD Cloud calls from racing tokens and updates the watch UI state automatically after a command succeeds.
4. **Separate `ac_on` / `ac_off` + temp `−`/`+` on watch:** Adjusting temperature while AC is on updates/re-sends `ac_on` with the new `tempC`; while AC is off it updates the preset temperature (default `17°C`) without unexpectedly starting AC.
5. **Fan speed gated on protocol check:** Will inspect `com.byd.bydautolink` v3.6.0 / `OPENAIR` schema during planning/implementation; if BYD Cloud `OPENAIR` doesn't support fan speed, it will be omitted rather than mocked.
6. **Two-phase build & test:** Complete and test the Android companion app (including phone UI) on a real Android phone first, while waiting for UAC acceptance of Android Studio and Huawei Developer verification for DevEco Studio & Watch `.hap` signing.

---

## 6. What's Left to the Builder (Planning & Implementation Scope)

- Inspect `com.overdrive.app.byd.cloud` (and `com.byd.bydautolink` v3.6.0 specifically for `OPENAIR` fan speed / `airSet` and minimum temperature scale) to confirm exact field contracts.
- Set up `android-companion/` Gradle wrapper & dependencies (Wear Engine AAR/Maven dependency, AndroidX Security Crypto, `org.json` / standard `HttpURLConnection` or OkHttp).
- Verify Wear Engine Android SDK integration & foreground/background message delivery on the user's phone.
- Scaffold `watch-gt4/` Lite Wearable project once Legacy DevEco Studio + Huawei Developer signing keys are ready, wire `wearengine.js`, and test crown scrolling + P2P round-trips on the physical Watch GT 4.
