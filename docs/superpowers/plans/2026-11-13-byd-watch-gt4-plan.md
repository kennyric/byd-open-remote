# BYD Watch GT 4 Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-part personal remote control app for a **BYD Atto 1 (Indonesia)** consisting of an Android companion app (`android-companion/`) and a Huawei Watch GT 4 Lite Wearable app (`watch-gt4/`) bridged via HUAWEI Wear Engine P2P.

**Architecture:** The Android companion app ports Overdrive's MIT-licensed `byd/cloud` and `crypto` (`BangcleCodec` + `bangcle_tables.bin`) layers, exposes a standalone phone login/status/test UI, and hosts a HUAWEI Wear Engine `P2pClient` bridge over Bluetooth. The Watch GT 4 `Lite Wearable` app renders a `466×466` dark OLED crown-scrollable `<list>` (outer-edge battery SoC arc, primary Lock/Unlock with 1-tap unlock confirm, Quick Controls, and `17°C`-default Climate controls) and exchanges compact JSON commands with the phone via `wearengine.js`.

**Tech Stack:** Java 17, Android Gradle (`minSdk 26`, `targetSdk 34`), `org.json`, AndroidX Security Crypto, HUAWEI Wear Engine SDK (`com.huawei.hms:wearengine:5.0.3.304`), JUnit 4 + Robolectric; HarmonyOS Legacy Lite Wearable JS/HML/CSS (API 6–7), `wearengine.js`, Node `node:test`.

**Spec:** [2026-11-13-byd-watch-gt4-design.md](file:///e:/Kenny/Huawei/docs/superpowers/specs/2026-11-13-byd-watch-gt4-design.md)

## Global Constraints

- Target vehicle & region: BYD Atto 1, Indonesia (`region = "id"`, `country = "ID"`, overseas `BangcleCodec` + `bangcle_tables.bin`).
- No Google Play Services dependency in the Android companion app.
- Watch UI runs on Huawei Watch GT 4 `Lite Wearable` (`466×466` round OLED, HML/CSS/JS, API 6–7); crown scrolling requires `<list>` with `rotation({ focus: true })`.
- Watch Hero Card 1 uses an outer-edge circular battery arc along the `466×466` perimeter.
- AC defaults to `17°C` (`rawTemp = 3` in Overdrive's `celsius - 14` scale); fan speed is included only if verified in `com.byd.bydautolink` v3.6.0 `OPENAIR` cloud payload.
- Unlock from the watch requires a 1-tap confirmation overlay; no BYD credentials are stored on or sent to the watch.
- All Android tools, SDK, and Gradle caches stay on drive `D:\` (`ANDROID_HOME=D:\Android\Sdk`, `GRADLE_USER_HOME=D:\Android\.gradle`).

## Review Focus

- **Concurrent watch taps / rapid double-presses during slow BYD Cloud T-Box wake-up (5–15s):** Commands must queue serially on the Android side and debounce in-flight taps on the watch so tokens are never invalidated by parallel logins (covered in Task 3 & Task 6).
- **Invalid or unverified 6-digit operation PIN (`PIN_INVALID`):** Must surface a clear `PIN_INVALID` error code and stop without blindly retrying bad PINs and locking the account (covered in Task 3).
- **Malformed, truncated, or unknown Watch ↔ Phone JSON payload:** Must return a structured `BAD_REQUEST` error without crashing the Android bridge or watch JS runtime (covered in Task 3 & Task 6).
- **Missing credentials, offline phone, or offline vehicle:** Must return `NOT_CONFIGURED` / `CAR_OFFLINE` / `TIMEOUT` and clear watch `"Sending..."` state cleanly instead of hanging (covered in Task 3, Task 5 & Task 6).
- **Missing or out-of-range `tempC` on `ac_on`:** Must default to `17°C` and clamp within `17..31°C` (or verified min) instead of sending invalid raw HVAC values (covered in Task 3 & Task 6).

---

## File Structure

- `E:\Kenny\Huawei\.gitignore` — Ignores `.superpowers/`, `*.apkm`, Gradle/build artifacts, local keystores/signing files.
- `E:\Kenny\Huawei\android-companion\settings.gradle` — Root project + Google, Maven Central, and `https://developer.huawei.com/repo/` repositories.
- `E:\Kenny\Huawei\android-companion\build.gradle` & `app\build.gradle` — Android Java 17 Gradle config (`minSdk 26`, `targetSdk 34`, Wear Engine `5.0.3.304`, Security Crypto, JUnit 4 + Robolectric).
- `E:\Kenny\Huawei\android-companion\app\src\main\assets\byd\bangcle_tables.bin` — White-box Bangcle tables from Overdrive (MIT).
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\overdrive\app\byd\cloud\...` — Ported Overdrive `BydCloudClient`, `BydCloudConfig`, `BydCloudRegionCatalog`, `BydCloudSession`, `BydCloudTransport`, `VehicleCloudSnapshot`, `CloudCapabilities`, and `crypto\*` with thin `CloudLog` adapter.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\model\VehicleStatus.java` — Immutable UI/JSON telemetry snapshot (`soc`, `rangeKm`, `locked`, `acOn`, `cabinTempC`, `targetTempC`, `ts`).
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\protocol\WatchProtocol.java` — Strict JSON parser (`WatchCommand`) & serializer (`WatchResponse`) for the Phone ↔ Watch contract.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\SecureConfigStore.java` — Keystore-backed `EncryptedSharedPreferences` store for BYD username/password/PIN, VIN, region (`id`), and AC default `17°C`.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\BydGateway.java` & `BydRepository.java` — Interface over `BydCloudClient` (enabling deterministic unit tests) and single-threaded serialized command queue + auto-refresh.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\wear\WearEngineBridge.java` — `HiWear.getP2pClient()` + `Receiver` + `AuthClient` (`Permission.DEVICE_MANAGER`) wrapper, peer fingerprint config, and command dispatch.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\wear\WearBridgeService.java` — Persistent `ForegroundService` (`foregroundServiceType="connectedDevice"`) keeping `WearEngineBridge` alive when the phone is locked in a pocket.
- `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\ui\MainActivity.java` & `res\layout\activity_main.xml` — Standalone login, PIN, VIN picker, live telemetry, manual test buttons, Huawei Health authorization action, and Wear Engine debug log.
- `E:\Kenny\Huawei\watch-gt4\entry\src\main\config.json` — Lite Wearable ability & package configuration.
- `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\common\carState.js` — Pure JS state machine, JSON protocol builder/parser, `17°C` default, debounce, and unlock confirmation logic (testable via `node --test`).
- `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\wearengine\wearengine.js` — Huawei Lite Wearable Wear Engine P2P JS SDK wrapper.
- `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\pages\index\index.{hml,css,js}` — `466×466` crown-scrollable `<list>` UI with outer-edge battery arc, Lock/Unlock + confirm modal, Quick Controls, and Climate card.

---

## Phase 1: Android Companion App (`android-companion/`)

### Task 1: Protocol Audit (`OPENAIR` Fan Speed) & Android Project Scaffold

**Files:**
- Create: `E:\Kenny\Huawei\.gitignore`
- Create: `E:\Kenny\Huawei\android-companion\settings.gradle`
- Create: `E:\Kenny\Huawei\android-companion\build.gradle`
- Create: `E:\Kenny\Huawei\android-companion\gradle.properties`
- Create: `E:\Kenny\Huawei\android-companion\app\build.gradle`
- Create: `E:\Kenny\Huawei\android-companion\app\proguard-rules.pro`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\AndroidManifest.xml`
- Create: `E:\Kenny\Huawei\docs\superpowers\plans\openair-audit-notes.md`

**Interfaces:**
- Produces: Gradle project `:app` with `applicationId "com.bydwatch.companion"`, Wear Engine `5.0.3.304`, AndroidX Security Crypto, and JUnit 4 + Robolectric test runner; verified finding on whether `com.byd.bydautolink` v3.6.0 `OPENAIR` supports remote fan speed.

- [ ] **Step 1: Bootstrap Android SDK on `D:\Android\Sdk` (no UAC needed)**

Check `Test-Path "$env:ANDROID_HOME\platforms\android-34"`. If missing, download Google's standalone `commandlinetools-win` `.zip` into `D:\Android\Sdk\cmdline-tools\latest`, accept licenses, and run `sdkmanager.bat "platforms;android-34" "build-tools;34.0.0" "platform-tools"`.
Expected: `D:\Android\Sdk\platforms\android-34`, `build-tools\34.0.0`, and `platform-tools\adb.exe` exist.

- [ ] **Step 2: Inspect `com.byd.bydautolink` v3.6.0 `.apkm` for `OPENAIR` fan-speed parameters**

Extract or scan DEX strings in `E:\Kenny\Huawei\com.byd.bydautolink_3.6.0-363_2arch_7dpi_23lang_216e1ffece85e57ec5c52080a448c05d_apkmirror.com.apkm` to a temp scratch folder for `OPENAIR`, `airSet`, `windLevel`, `airVolume`, or `fan` keys; record findings in `docs/superpowers/plans/openair-audit-notes.md`.
Expected: Definitive yes/no on whether BYD Cloud `OPENAIR` accepts a fan-speed field and its exact key/values.

- [ ] **Step 3: Initialize git repository and `.gitignore`**

Create `.gitignore` ignoring `.superpowers/`, `*.apkm`, `android-companion/.gradle/`, `android-companion/**/build/`, `android-companion/local.properties`, `watch-gt4/.hvigor/`, `watch-gt4/**/build/`, `*.jks`, `*.p12`, `*.keystore`.
Run: `git init` and verify `git status` excludes `.apkm` and `.superpowers/`.

- [ ] **Step 4: Create Gradle wrapper and Android `:app` skeleton in `android-companion/`**

Write `settings.gradle` (with `google()`, `mavenCentral()`, `maven { url 'https://developer.huawei.com/repo/' }`), root `build.gradle` (AGP `8.6.x`), `gradle.properties`, `app/build.gradle` (`minSdk 26`, `targetSdk 34`, Java 17, `androidx.appcompat`, `com.google.android.material`, `androidx.security:security-crypto:1.1.0-alpha06`, `com.huawei.hms:wearengine:5.0.3.304`, `junit:4.13.2`, `org.robolectric:robolectric:4.13`, `org.json:json:20240303` for `testImplementation`), `proguard-rules.pro`, and `AndroidManifest.xml` (`INTERNET`, `ACCESS_NETWORK_STATE`, `BLUETOOTH`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`).

- [ ] **Step 5: Verify empty project builds cleanly**

Run: `cd E:\Kenny\Huawei\android-companion; .\gradlew.bat :app:compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add .gitignore docs/superpowers/ android-companion/
git commit -m "chore: scaffold android-companion project and document OPENAIR audit"
```

---

### Task 2: Port Overdrive BYD Cloud & Bangcle Crypto Layer

**Files:**
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\assets\byd\bangcle_tables.bin`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\assets\byd\wbsk_tables.json` (if required by `EnvelopeCodecFactory`)
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\overdrive\app\byd\cloud\...` (`BydCloudClient`, `BydCloudConfig`, `BydCloudRegionCatalog`, `BydCloudSession`, `BydCloudTransport`, `CloudCapabilities`, `VehicleCloudSnapshot`, `SmartChargeCache`)
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\overdrive\app\byd\cloud\crypto\...` (`BangcleBlockCipher`, `BangcleCodec`, `BangcleTables`, `BangcleTablesFile`, `BydCryptoUtils`, `CredentialCipher`, `EnvelopeCodec`, `EnvelopeCodecFactory`, `Wbsk*` only if referenced by factory)
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\overdrive\app\byd\cloud\CloudLog.java` (thin `android.util.Log` wrapper replacing Overdrive's file logger & stubbing `DrivingSafetyGuard` cleanly)
- Test: `E:\Kenny\Huawei\android-companion\app\src\test\java\com\overdrive\app\byd\cloud\BydCloudPortTest.java`

**Interfaces:**
- Produces:
  - `BydCloudConfig` (configured for `region = "id"`, `country = "ID"`, `language = "en"`)
  - `BydCloudClient(BydCloudConfig config)` with `init(InputStream)`, `login()`, `fetchFirstVin()`, `verifyControlPassword(String vin)`, `fetchVehicleRealtime(String vin)`, `lock(String vin)`, `unlock(String vin)`, `startClimate(String vin, double tempCelsius)`, `stopClimate(String vin)`, `flashLights(String vin)`, `findCar(String vin)`, and static `climateStartParams(double tempCelsius)`
  - `BydCloudRegionCatalog.defaultCountryForRegion("id")` returning `"ID"`

- [ ] **Step 1: Write the failing unit test `BydCloudPortTest.java`**

```java
public class BydCloudPortTest {
    @Test
    public void indonesiaRegionResolvesToIdAndUsesBangcle() {
        assertEquals("ID", BydCloudRegionCatalog.defaultCountryForRegion("id"));
        assertFalse(BydCloudRegionCatalog.isChinaRegion("id"));
    }

    @Test
    public void climateStartParamsMaps17CelsiusToRawTemp3() throws Exception {
        JSONObject params = BydCloudClient.climateStartParams(17.0);
        assertEquals(3, params.getInt("mainSettingTemp"));
        assertEquals(3, params.getInt("copilotSettingTemp"));
        assertEquals(1, params.getInt("airConditioningMode"));
    }

    @Test
    public void bangcleCodecInitializesFromAssetStream() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("byd/bangcle_tables.bin")) {
            assertNotNull(in);
            BydCloudConfig cfg = new BydCloudConfig();
            cfg.setRegion("id");
            BydCloudClient client = new BydCloudClient(cfg);
            client.init(in);
            assertTrue(client.isReady());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.overdrive.app.byd.cloud.BydCloudPortTest"`
Expected: FAIL with missing classes `BydCloudRegionCatalog` / `BydCloudClient`.

- [ ] **Step 3: Download MIT-licensed Overdrive `byd/cloud`, `byd/cloud/crypto`, and `assets/byd` files and adapt non-phone dependencies**

Copy the files into `app/src/main/java/com/overdrive/app/byd/cloud/` and `app/src/main/assets/byd/` (and mirror `bangcle_tables.bin` in `app/src/test/resources/byd/` or configure `testOptions` asset loading). Preserve MIT license notices. Replace Overdrive's `DaemonLogger` / `DrivingSafetyGuard` references with `CloudLog` and an always-false phone safety stub (or remove the head-unit driving block). If Task 1 Step 2 discovered a verified BYD Cloud fan-speed key in `OPENAIR`, expose an overload `startClimate(String vin, double tempCelsius, Integer fanLevel)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.overdrive.app.byd.cloud.BydCloudPortTest"`
Expected: PASS (3 tests passing).

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/
git commit -m "feat(android): port Overdrive BYD Cloud client and Bangcle crypto"
```

---

### Task 3: Secure Store, Watch JSON Protocol, & Serialized `BydRepository`

**Files:**
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\model\VehicleStatus.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\protocol\WatchCommand.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\protocol\WatchResponse.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\protocol\WatchProtocol.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\SecureConfigStore.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\BydGateway.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\CloudBydGateway.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\data\BydRepository.java`
- Test: `E:\Kenny\Huawei\android-companion\app\src\test\java\com\bydwatch\companion\protocol\WatchProtocolTest.java`
- Test: `E:\Kenny\Huawei\android-companion\app\src\test\java\com\bydwatch\companion\data\BydRepositoryTest.java`

**Interfaces:**
- Consumes: `BydCloudClient`, `BydCloudConfig` (from Task 2).
- Produces:
  - `VehicleStatus(int soc, int rangeKm, boolean locked, boolean acOn, Integer cabinTempC, int targetTempC, long ts)` + `.toJson()`
  - `WatchProtocol.parseCommand(String rawJson) -> WatchCommand` (validates `id`, `action` in `{status, lock, unlock, ac_on, ac_off, lights, horn}`, defaults `tempC` to `17` and clamps `17..31`, throws `IllegalArgumentException` on malformed/unknown)
  - `WatchProtocol.ok(String id, String action, String msg, VehicleStatus state) -> String`
  - `WatchProtocol.error(String id, String action, String errCode, String msg) -> String`
  - `BydRepository.handleWatchJson(String rawJson, Callback cb)` (serialized single-thread executor, caches last `VehicleStatus`, auto-refreshes after `lock`/`unlock`/`ac_on`/`ac_off`, maps missing creds -> `NOT_CONFIGURED`, bad PIN -> `PIN_INVALID`, auth fail -> `AUTH_FAILED`, malformed JSON -> `BAD_REQUEST`, offline/timeout -> `CAR_OFFLINE`/`TIMEOUT`)
  - `SecureConfigStore` (`get/setUsername`, `Password`, `Pin`, `Vin`, `Region` default `"id"`, `DefaultTempC` default `17`)

- [ ] **Step 1: Write the failing tests in `WatchProtocolTest.java` and `BydRepositoryTest.java`**

Cover every Review Focus failure mode:
- `WatchProtocolTest`:
  - `parseAcOnDefaultsTempTo17AndClampsOutOfRange()` (`{"id":"1","action":"ac_on"}` -> `tempC == 17`; `tempC: 10` -> `17`; `tempC: 40` -> `31`)
  - `parseRejectsMalformedJsonAndUnknownAction()` (invalid JSON, missing `action`, `"action":"explode"` throw `IllegalArgumentException`)
  - `serializesOkAndErrorResponsesMatchingContract()` (verifies exact JSON keys `id`, `ok`, `action`, `msg`, `state`/`err`)
- `BydRepositoryTest` (using an in-memory fake `BydGateway` + Robolectric/in-memory `ConfigStore`):
  - `returnsNotConfiguredWhenCredentialsMissing()` -> `err == "NOT_CONFIGURED"`
  - `mapsInvalidPinToPinInvalidWithoutRetrying()` -> `err == "PIN_INVALID"`, `verifyPinCalls == 1`
  - `serializesConcurrentCommandsAndAutoRefreshesAfterLock()` -> two concurrent `lock` + `ac_on` calls run sequentially (max concurrency observed == `1`) and refresh telemetry once per mutating command
  - `returnsBadRequestOnMalformedWatchPayload()` -> `ok == false`, `err == "BAD_REQUEST"`

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.bydwatch.companion.protocol.*" --tests "com.bydwatch.companion.data.*"`
Expected: FAIL with missing classes.

- [ ] **Step 3: Implement `VehicleStatus`, `WatchCommand`, `WatchResponse`, `WatchProtocol`, `SecureConfigStore`, `BydGateway`, `CloudBydGateway`, and `BydRepository`**

Implement strict JSON validation (`WatchProtocol`), AndroidX `EncryptedSharedPreferences` (`SecureConfigStore` with default region `"id"` and default AC temp `17`), `CloudBydGateway` wrapping `BydCloudClient` (`login`, `verifyControlPassword`, `fetchVehicleRealtime`, `lock`, `unlock`, `startClimate`, `stopClimate`, `flashLights`, `findCar`), and `BydRepository` with a single-threaded `ExecutorService` and exception-to-error-code mapper.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.bydwatch.companion.protocol.*" --tests "com.bydwatch.companion.data.*"`
Expected: PASS (all tests passing).

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/
git commit -m "feat(android): add watch JSON protocol, secure store, and serialized BydRepository"
```

---

### Task 4: Standalone Phone UI (`MainActivity`)

**Files:**
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\BydWatchApp.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\ui\MainActivity.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\res\layout\activity_main.xml`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\res\values\strings.xml`
- Modify: `E:\Kenny\Huawei\android-companion\app\src\main\AndroidManifest.xml`
- Test: `E:\Kenny\Huawei\android-companion\app\src\test\java\com\bydwatch\companion\ui\MainActivityTest.java`

**Interfaces:**
- Consumes: `SecureConfigStore`, `BydRepository`, `VehicleStatus` (from Task 3).
- Produces: `BydWatchApp` singleton accessor (`getRepository()`, `getConfigStore()`) and `MainActivity` supporting login, VIN fetch, live status rendering, manual controls (`Refresh`, `Lock`, `Unlock`, `AC On/Off` with `17°C` default and `−`/`+`), and an append-only status/command log view.

- [ ] **Step 1: Write the failing Robolectric test `MainActivityTest.java`**

```java
@RunWith(RobolectricTestRunner.class)
public class MainActivityTest {
    @Test
    public void displaysAlternateAuthNoticeAndDefault17CTemp() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView tempView = activity.findViewById(R.id.tvTargetTemp);
        TextView authNotice = activity.findViewById(R.id.tvAuthNotice);
        assertEquals("17°C", tempView.getText().toString());
        assertTrue(authNotice.getText().toString().contains("Alternate Vehicle Authorization"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.bydwatch.companion.ui.MainActivityTest"`
Expected: FAIL with missing `MainActivity` / layout IDs.

- [ ] **Step 3: Implement `BydWatchApp`, `activity_main.xml`, `strings.xml`, and `MainActivity`**

Keep UI clean with three Material cards:
1. **BYD Account (`Indonesia - id`)**: Notice recommending Alternate Vehicle Authorization, Email/Phone, Password, 6-digit PIN, VIN field + `"Fetch VIN & Test Login"` button.
2. **Atto 1 Status & Test Controls**: SoC `%`, range `km`, lock state, cabin temp, AC state, last-updated timestamp; buttons for `Refresh`, `Lock`, `Unlock`, `AC On` / `AC Off` with `−` `17°C` `+` controls, `Flash Lights`, `Horn`.
3. **Watch Bridge Status & Log**: Shows Wear Engine connectivity summary and recent command responses.

- [ ] **Step 4: Run unit tests and assemble debug APK**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS and `app-debug.apk` built cleanly.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/
git commit -m "feat(android): add standalone MainActivity for login and BYD Atto 1 control"
```

---

### Task 5: HUAWEI Wear Engine Android Bridge (`WearEngineBridge` + `WearBridgeService`)

**Files:**
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\wear\WearEngineConfig.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\wear\WearEngineBridge.java`
- Create: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\wear\WearBridgeService.java`
- Modify: `E:\Kenny\Huawei\android-companion\app\build.gradle` (add `buildConfigField` placeholders for `WATCH_PACKAGE_NAME` and `WATCH_CERT_FINGERPRINT`)
- Modify: `E:\Kenny\Huawei\android-companion\app\src\main\AndroidManifest.xml` (register `WearBridgeService` with `android:foregroundServiceType="connectedDevice"`)
- Modify: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\BydWatchApp.java`
- Modify: `E:\Kenny\Huawei\android-companion\app\src\main\java\com\bydwatch\companion\ui\MainActivity.java`
- Modify: `E:\Kenny\Huawei\android-companion\app\src\main\res\layout\activity_main.xml`
- Test: `E:\Kenny\Huawei\android-companion\app\src\test\java\com\bydwatch\companion\wear\WearEngineBridgeTest.java`

**Interfaces:**
- Consumes: `BydRepository`, `WatchProtocol` (from Task 3).
- Produces: `WearEngineBridge(BydRepository repo, PeerTransport transport)` with `start()`, `stop()`, `requestHealthPermission(Callback)`, `onIncomingBytes(byte[] payload)`, and `getConnectionState()`; `WearBridgeService` (`ForegroundService` keeping `WearEngineBridge` registered while screen is off); real `HuaweiWearP2pTransport` using `HiWear.getAuthClient(context)`, `HiWear.getDeviceClient(context)`, and `HiWear.getP2pClient(context)` + `Receiver`.

- [ ] **Step 1: Write the failing test `WearEngineBridgeTest.java`**

Test with a fake `PeerTransport` capturing outbound `byte[]` payloads and auth consent state:
- `dispatchesIncomingWatchJsonAndRepliesWithResponseJson()` (simulates receiving `{"id":"9","action":"lock"}` bytes and asserts `PeerTransport.send()` receives `{"id":"9","ok":true,"action":"lock",...}`)
- `repliesWithBadRequestOnNonUtf8OrCorruptPayload()` (verifies bridge replies cleanly instead of throwing)
- `tracksPermissionPendingStateUntilDeviceManagerGranted()` (verifies `getConnectionState()` reports permission-needed before `AuthClient` consent and listening once granted)

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.bydwatch.companion.wear.WearEngineBridgeTest"`
Expected: FAIL with missing `WearEngineBridge`.

- [ ] **Step 3: Implement `WearEngineConfig`, `WearEngineBridge`, `WearBridgeService`, and wire into `BydWatchApp` + `MainActivity`**

Configure `WATCH_PACKAGE_NAME` (`"com.bydwatch.watch"`) and `WATCH_CERT_FINGERPRINT` (`""` until AppGallery Connect keys are generated in Task 8) in `app/build.gradle`. Implement `HuaweiWearP2pTransport` using `HiWear.getAuthClient` (`requestPermission(..., Permission.DEVICE_MANAGER)`), `HiWear.getDeviceClient` (to locate the paired watch), and `HiWear.getP2pClient` (`setPeerPkgName`, `setPeerFingerPrint`, `registerReceiver`, `send`). Host `WearEngineBridge` inside `WearBridgeService` (`ForegroundService` with low-priority persistent notification and `connectedDevice` type) so watch commands work with the phone locked in a pocket. Update `MainActivity`'s bridge card with an **"Authorize Huawei Health"** button, foreground service toggle/status, and battery-optimization prompt.

- [ ] **Step 4: Run full Android test suite and debug APK build**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, all unit tests PASS, `app-debug.apk` ready for phone install & BYD Atto 1 testing.

- [ ] **Step 5: Commit**

```bash
git add android-companion/
git commit -m "feat(android): add HUAWEI Wear Engine P2P bridge"
```

---

## Phase 2: Watch GT 4 Lite Wearable App (`watch-gt4/`) *(Blocked until Huawei Developer verification + Legacy DevEco Studio)*

### Task 6: Watch Project Scaffold & Pure JS State/Protocol Module

**Files:**
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\config.json`
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\app.js`
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\common\carState.js`
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\wearengine\wearengine.js`
- Test: `E:\Kenny\Huawei\watch-gt4\tests\carState.test.mjs`

**Interfaces:**
- Produces (`carState.js`):
  - `createInitialState()` -> `{ soc: null, rangeKm: null, locked: true, acOn: false, cabinTempC: null, targetTempC: 17, ts: null, pending: false, confirmUnlock: false, phoneOnline: false, banner: "" }`
  - `buildCommand(action, state)` -> JSON string matching Phone ↔ Watch contract (`ac_on` uses `state.targetTempC`, defaulting to `17`)
  - `stepTemp(state, delta)` -> clamps `targetTempC` within `17..31`
  - `applyResponse(state, rawJson)` -> updates `soc`, `rangeKm`, `locked`, `acOn`, `cabinTempC`, `targetTempC`, `ts`, clears `pending`, sets friendly `banner` for `PIN_INVALID` / `AUTH_FAILED` / `CAR_OFFLINE` / `NOT_CONFIGURED` / `TIMEOUT`, safely ignores malformed JSON without throwing.

- [ ] **Step 1: Write failing Node unit tests in `watch-gt4/tests/carState.test.mjs`**

Test `createInitialState()` (`targetTempC === 17`), `stepTemp` clamping (`17..31`), `buildCommand("ac_on", state)` payload, `applyResponse` for success + error codes (`CAR_OFFLINE`, `PIN_INVALID`), and malformed JSON resilience.

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test E:\Kenny\Huawei\watch-gt4\tests\carState.test.mjs`
Expected: FAIL with missing `carState.js`.

- [ ] **Step 3: Scaffold DevEco Lite Wearable files (`config.json`, `app.js`, `wearengine.js`) and implement `carState.js`**

Import official Lite Wearable `wearengine.js` module from Huawei's Wear Engine Lite Wearable SDK, configure `config.json` (`deviceType: ["liteWearable"]`, `466×466` watch profile), and implement `carState.js` cleanly with zero DOM/HarmonyOS globals so Node tests run directly.

- [ ] **Step 4: Run Node tests**

Run: `node --test E:\Kenny\Huawei\watch-gt4\tests\carState.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add watch-gt4/
git commit -m "feat(watch): scaffold Lite Wearable project and car state module"
```

---

### Task 7: Crown-Scrollable Option B Watch UI (`index.hml`, `index.css`, `index.js`)

**Files:**
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\pages\index\index.hml`
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\pages\index\index.css`
- Create: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\pages\index\index.js`

**Interfaces:**
- Consumes: `carState.js` (Task 6) and `wearengine.js` (`P2pClient`, `Message`, `Builder`).
- Produces: Full `466×466` crown-scrollable UI matching the approved Watch GT 4 Hero v2 spec.

- [ ] **Step 1: Build `index.hml` & `index.css` for `466×466` round OLED**

Structure root `<div class="root">` (`466px × 466px`, `#000000`) containing `<list id="mainList" class="main-list">`:
- `<list-item>` 1 (`466×466`): Outer-edge `<progress type="arc" percent="{{socPercent}}">` hugging the perimeter, centered `BYD ATTO 1`, lock & cabin temp line, large SoC `%` + range (`km`), primary Lock/Unlock pill button, and `▾ Turn crown` hint.
- `<list-item>` 2: Quick Controls card (`Flash Lights`, `Horn / Find`, `Refresh`, last synced + phone link status).
- `<list-item>` 3: Climate card (`AC On/Off` toggle + `−` `{{targetTempC}}°C` `+` row; add fan speed only if Task 1 proved cloud support).
- Conditional overlay `div` (`if="{{confirmUnlock}}"`) for 1-tap Unlock confirmation (`Unlock BYD Atto 1?` -> `Confirm` / `Cancel`) and status toast banner.

- [ ] **Step 2: Implement `index.js` lifecycle, crown focus, and Wear Engine P2P**

In `onInit()`: initialize `P2pClient`, set `peerPkgName` (`com.bydwatch.companion`) and `peerFingerPrint`, register receiver calling `applyResponse`. In `onShow()`: call `this.$element('mainList').rotation({ focus: true })` and trigger initial `status` command (with a 15s client-side timeout clearing `pending` -> `"Timeout"` if phone doesn't respond). In `onDestroy()`: call `p2pClient.unregisterReceiver()`.

- [ ] **Step 3: Build `.hap` in DevEco Studio (Legacy) & preview in Simulator**

Verify no HML/CSS/JS lint errors, outer-edge arc renders cleanly at `466×466`, and crown rotation scrolls smoothly across all 3 cards.

- [ ] **Step 4: Commit**

```bash
git add watch-gt4/
git commit -m "feat(watch): implement crown-scrollable Option B UI with outer-edge battery arc"
```

---

### Task 8: AppGallery Connect Signing, Sideload & End-to-End Verification

**Files:**
- Modify: `E:\Kenny\Huawei\android-companion\app\build.gradle` (set real `WATCH_CERT_FINGERPRINT`)
- Modify: `E:\Kenny\Huawei\watch-gt4\entry\src\main\js\default\pages\index\index.js` (set real Android release/debug SHA-256 fingerprint without colons)

**Interfaces:**
- Produces: Signed `.apk` + signed `.hap` registered under the same AppGallery Connect project and verified on physical Android phone + Watch GT 4.

- [ ] **Step 1: Register AppGallery Connect project & generate signing keys**

Create Android signing keystore (extract SHA-256 without colons) and DevEco Studio `.p12`/`.csr`/`.cer`/`.p7b` signing profile with Watch GT 4 UDID fetched via DevEco Assistant.

- [ ] **Step 2: Cross-wire SHA-256 fingerprints and rebuild both artifacts**

Update `WATCH_CERT_FINGERPRINT` in `android-companion/app/build.gradle` and Android SHA-256 in `watch-gt4/.../index.js`. Build signed `.apk` (`.\gradlew.bat assembleDebug` or `assembleRelease`) and signed `.hap` in DevEco Studio.

- [ ] **Step 3: Install `.apk` on phone, sideload `.hap` via DevEco Assistant, and run E2E checklist**

Verify on physical Watch GT 4 + BYD Atto 1 (with Alternate Vehicle Authorization profile):
- [ ] Phone login & status fetch displays real Atto 1 SoC %, range, and lock state.
- [ ] Watch launch shows outer-edge battery arc matching phone status.
- [ ] Crown rotation scrolls between Hero, Quick Controls, and Climate cards.
- [ ] Watch `Lock`, `Unlock` (confirm modal), `Lights`, `Horn`, and `AC On (17°C)` / `AC Off` succeed end-to-end.

- [ ] **Step 4: Commit final configuration**

```bash
git add android-companion/ watch-gt4/
git commit -m "chore: wire AppGallery Connect fingerprints for Wear Engine E2E"
```
