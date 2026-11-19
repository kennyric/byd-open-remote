# BYD Charging-Current Lab (Trial APK) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a debug-only Lab screen that reads and writes the BYD AC charging current limit over the cloud, shows raw evidence, and lets the user probe the whole `smartCharge/*` family without rebuilding the APK.

**Architecture:** New cloud models (`AcCurrentLimit`, `CloudCallTrace`, `AcCurrentLimitResult`) sit beside the existing `BydCloudClient` smart-charge block and use the same plain token envelope. A new `LabActivity` uses `BydRepository`'s serialized executor and `CloudBydGateway.getClient()`, while pure helpers (`ChargeLimitValues`, `LabEndpointPolicy`, `LabVerdict`, `LabContext`, `LabConsole`) carry the testable logic.

**Tech Stack:** Java 17, Android SDK 34, `minSdk 26`, JUnit 4, Robolectric 4.13, Material 1.12, existing `org.json`.

**Spec:** `docs/superpowers/specs/2026-11-19-byd-lab-charging-current-design.md`

## Global Constraints

- Java 17 source/target, `minSdk 26`, `compileSdk 34`; do not add dependencies.
- All new cloud calls use the **plain token envelope** (`transport.postSecure`); no `commandPwd`, no `postVehicleSecure` guest/`userType` retry.
- Lab gate: `BuildConfig.DEBUG && !BuildConfig.DEMO_BUILD`.
- Lab activity is `android:exported="false"` and finishes immediately when the gate is false.
- Raw-runner allowlist: `/control/smartCharge/*` plus exact `/control/getBookingList`, `/control/getStatusNow`, `/control/getGpsInfo`. Everything else, especially `/control/remoteControl*`, `/control/otaUpgrade/*`, `/control/bluetooth/*`, `/control/standby-vehicles/*`, is rejected.
- All cloud calls run on `BydRepository.getExecutor()`.
- Do not change `applicationId` (`com.bydopenremote.app`) or signing configuration.
- No watch-app changes; no BYD wallbox/charging-pile code.
- Every Robolectric test that calls `BydWatchApp.setTestDependencies` must call `BydWatchApp.resetForTesting()` in `@After` so static test state does not leak between tests.
- Trial output: `android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`.

## Review Focus

- A malformed or partial `getAcCurrentLimit` payload (nulls, numbers, `"--"`, empty list) must not crash the Lab — Task 1 tests parsing.
- On a multi-car account, the Lab must use the **active** VIN's `energyType`, not the first vehicle's — Task 5 tests resolution.
- A write that the cloud acks but the car does not apply must read `UNCHANGED`/`PENDING`, never a false `APPLIED` — Task 7 tests classification and the re-read flow.
- The raw runner must never reach lock/unlock, OTA, Bluetooth, or standby-vehicle endpoints — Task 2 tests the policy and Task 8 tests the UI path.
- The Lab entry must not be visible or launchable in release or demo builds — Task 5 tests the gate and drawer entry.

---

### Task 1: AC current-limit model and value canonicalization

**Files:**
- Create: `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/AcCurrentLimit.java`
- Create: `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/ChargeLimitValues.java`
- Test: `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/AcCurrentLimitTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `AcCurrentLimit.parse(String vin, JSONObject payload) -> AcCurrentLimit`
  - fields `String vin`, `String active`, `List<String> options`, `JSONObject raw`
  - `ChargeLimitValues.canonical(String value) -> String`
  - `ChargeLimitValues.same(String a, String b) -> boolean`

- [ ] **Step 1: Write the failing test**

```java
package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AcCurrentLimitTest {

    @Test
    public void parsesLiveEuPayload() {
        JSONObject payload = new JSONObject()
                .put("currentLimitList", new JSONArray()
                        .put("6A").put("8A").put("10A").put("16A").put("MAX"))
                .put("currentEvCurrentLimit", "MAX");

        AcCurrentLimit limit = AcCurrentLimit.parse("VIN123", payload);

        assertEquals("VIN123", limit.vin);
        assertEquals("MAX", limit.active);
        assertEquals(Arrays.asList("6A", "8A", "10A", "16A", "MAX"), limit.options);
    }

    @Test
    public void toleratesDefensiveValues() {
        JSONObject payload = new JSONObject()
                .put("currentLimitList", new JSONArray()
                        .put(6).put(" 10A ").put(JSONObject.NULL).put(""))
                .put("currentEvCurrentLimit", 16);

        AcCurrentLimit limit = AcCurrentLimit.parse("VIN123", payload);

        assertEquals("16", limit.active);
        assertEquals(Arrays.asList("6", "10A"), limit.options);

        AcCurrentLimit empty = AcCurrentLimit.parse("VIN123", new JSONObject());
        assertNull(empty.active);
        assertTrue(empty.options.isEmpty());

        AcCurrentLimit nullPayload = AcCurrentLimit.parse("VIN123", null);
        assertNull(nullPayload.active);
        assertTrue(nullPayload.options.isEmpty());
    }

    @Test
    public void canonicalizesAndComparesValues() {
        assertEquals("10A", ChargeLimitValues.canonical(" 10 "));
        assertEquals("10A", ChargeLimitValues.canonical("10a"));
        assertEquals("MAX", ChargeLimitValues.canonical(" max "));
        assertEquals("", ChargeLimitValues.canonical("--"));
        assertEquals("", ChargeLimitValues.canonical(null));
        assertTrue(ChargeLimitValues.same("10", "10A"));
        assertFalse(ChargeLimitValues.same("10A", "16A"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.AcCurrentLimitTest"`
Expected: FAIL — `AcCurrentLimit` / `ChargeLimitValues` do not exist.

- [ ] **Step 3: Implement the two classes**

`AcCurrentLimit` stores an unmodifiable option list and the raw payload. `parse` must:
- treat null payload as an empty object;
- accept `String` or `Number` for the active value, trim it, and map `""`, `"-"`, `"--"`, `"NULL"`, `"UNKNOWN"` to `null`;
- accept `String` or `Number` list items, trim them, and skip null/empty/sentinel entries;
- preserve order and drop duplicates.

`ChargeLimitValues.canonical` must:
- return `""` for null or a sentinel (`"-"`, `"--"`, `"NULL"`, `"UNKNOWN"`);
- trim, uppercase, and remove internal whitespace;
- append `"A"` to a bare integer (`"10"` → `"10A"`);
- leave `"MAX"` and already-suffixed values unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.AcCurrentLimitTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/overdrive/app/byd/cloud/AcCurrentLimit.java \
        android-companion/app/src/main/java/com/overdrive/app/byd/cloud/ChargeLimitValues.java \
        android-companion/app/src/test/java/com/overdrive/app/byd/cloud/AcCurrentLimitTest.java
git commit -m "feat(lab): add AC current limit model and value normalizer"
```

---

### Task 2: Raw-runner endpoint policy

**Files:**
- Create: `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/LabEndpointPolicy.java`
- Test: `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/LabEndpointPolicyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `LabEndpointPolicy.isAllowed(String endpoint) -> boolean`.

- [ ] **Step 1: Write the failing test**

```java
package com.overdrive.app.byd.cloud;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabEndpointPolicyTest {

    @Test
    public void allowsSmartChargeAndReadOnlyProbes() {
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/getAcCurrentLimit"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/limitCurrentControl"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/homePage"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getStatusNow"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getGpsInfo"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getBookingList"));
    }

    @Test
    public void rejectsPhysicalCommandsAndOtherPaths() {
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControl"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlResult"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlAsyncResult"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlBatteryHeat"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/otaUpgrade/upgradeOta"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/bluetooth/activateKey"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/standby-vehicles/save"));
        assertFalse(LabEndpointPolicy.isAllowed("/vehicleInfo/vehicle/vehicleRealTimeRequest"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/smartCharge/../remoteControl"));
        assertFalse(LabEndpointPolicy.isAllowed("http://example.com/control/smartCharge/homePage"));
        assertFalse(LabEndpointPolicy.isAllowed(null));
        assertFalse(LabEndpointPolicy.isAllowed(""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.LabEndpointPolicyTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `LabEndpointPolicy`**

Allow only:
- exact `/control/getBookingList`, `/control/getStatusNow`, `/control/getGpsInfo`;
- `^/control/smartCharge/[A-Za-z0-9]+$`.

Reject null/blank, anything containing `..`, and anything with `://`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.LabEndpointPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/overdrive/app/byd/cloud/LabEndpointPolicy.java \
        android-companion/app/src/test/java/com/overdrive/app/byd/cloud/LabEndpointPolicyTest.java
git commit -m "feat(lab): restrict diagnostic endpoint allowlist"
```

---

### Task 3: Cloud trace and AC current-limit client methods

**Files:**
- Create: `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/CloudCallTrace.java`
- Modify: `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java` (smart-charging block, after `saveChargingSchedule`)
- Test: `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/BydCloudClientAcCurrentLimitTest.java`
- Test: `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/CloudCallTraceTest.java`

**Interfaces:**
- Consumes: `AcCurrentLimit.parse`, `LabEndpointPolicy.isAllowed`, existing private `buildInner`, `buildTokenOuterEnvelope`, `ensureSession`, `transport.postSecure`.
- Produces:
  - `CloudCallTrace(String endpoint, JSONObject request, JSONObject outerResponse, JSONObject decoded, String code, String message, long durationMs, String error)`
  - `CloudCallTrace.isSuccess() -> boolean`
  - `CloudCallTrace.consoleText() -> String` (full VIN)
  - `CloudCallTrace.shareText() -> String` (masked VIN)
  - `CloudCallTrace.maskedVin() -> String`
  - `BydCloudClient.callControlEndpoint(String endpoint, JSONObject extraInner) -> CloudCallTrace`
  - `BydCloudClient.readAcCurrentLimit(String vin, String energyType) -> AcCurrentLimitResult`
  - `BydCloudClient.writeAcCurrentLimit(String vin, String value) -> AcCurrentLimitResult`
  - nested `BydCloudClient.AcCurrentLimitResult` with `CloudCallTrace trace` and `AcCurrentLimit limit`

- [ ] **Step 1: Write the failing tests**

```java
package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class BydCloudClientAcCurrentLimitTest {

    private static class FakeClient extends BydCloudClient {
        String endpoint;
        JSONObject extra;
        CloudCallTrace nextTrace;

        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public CloudCallTrace callControlEndpoint(String endpoint, JSONObject extraInner) {
            this.endpoint = endpoint;
            this.extra = extraInner;
            return nextTrace;
        }
    }

    @Test
    public void readSendsVinAndEnergyTypeAndParsesResult() throws Exception {
        FakeClient client = new FakeClient();
        JSONObject decoded = new JSONObject()
                .put("currentLimitList", new JSONArray().put("6A").put("MAX"))
                .put("currentEvCurrentLimit", "MAX");
        client.nextTrace = new CloudCallTrace(
                "/control/smartCharge/getAcCurrentLimit",
                new JSONObject(), new JSONObject(), decoded, "0", "", 4L, null);

        BydCloudClient.AcCurrentLimitResult result =
                client.readAcCurrentLimit("VIN_TEST", "2");

        assertEquals("/control/smartCharge/getAcCurrentLimit", client.endpoint);
        assertEquals("VIN_TEST", client.extra.getString("vin"));
        assertEquals("2", client.extra.getString("energyType"));
        assertNotNull(result.limit);
        assertEquals("MAX", result.limit.active);
        assertEquals("MAX", result.limit.options.get(1));
    }

    @Test
    public void writeSendsLimitCurrentValue() throws Exception {
        FakeClient client = new FakeClient();
        client.nextTrace = new CloudCallTrace(
                "/control/smartCharge/limitCurrentControl",
                new JSONObject(), new JSONObject(), new JSONObject(), "0", "", 4L, null);

        BydCloudClient.AcCurrentLimitResult result =
                client.writeAcCurrentLimit("VIN_TEST", "10A");

        assertEquals("/control/smartCharge/limitCurrentControl", client.endpoint);
        assertEquals("VIN_TEST", client.extra.getString("vin"));
        assertEquals("10A", client.extra.getString("limitCurrentValue"));
        assertNull(result.limit);
    }

    @Test
    public void nonAllowlistedEndpointReturnsErrorWithoutNetwork() throws Exception {
        BydCloudClient client = new BydCloudClient(new BydCloudConfig());

        CloudCallTrace trace = client.callControlEndpoint(
                "/control/remoteControl", new JSONObject().put("vin", "VIN_TEST"));

        assertFalse(trace.isSuccess());
        assertNotNull(trace.error);
        assertEquals("endpoint not allowed", trace.error);
    }
}
```

```java
package com.overdrive.app.byd.cloud;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CloudCallTraceTest {

    @Test
    public void successRequiresZeroCodeAndNoError() {
        JSONObject empty = new JSONObject();
        assertTrue(new CloudCallTrace("/x", empty, empty, empty, "0", "", 1L, null).isSuccess());
        assertFalse(new CloudCallTrace("/x", empty, empty, empty, "1001", "no", 1L, null).isSuccess());
        assertFalse(new CloudCallTrace("/x", empty, empty, empty, "0", "", 1L, "boom").isSuccess());
    }

    @Test
    public void shareTextMasksVinButConsoleTextDoesNot() throws Exception {
        JSONObject request = new JSONObject().put("vin", "LGXCE6CB0P0000001");
        CloudCallTrace trace = new CloudCallTrace(
                "/control/smartCharge/getAcCurrentLimit",
                request, new JSONObject(), new JSONObject(), "0", "", 1L, null);

        assertTrue(trace.consoleText().contains("LGXCE6CB0P0000001"));
        assertFalse(trace.shareText().contains("LGXCE6CB0P0000001"));
        assertTrue(trace.shareText().contains("0001"));
        assertEquals("0001", trace.maskedVin());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.BydCloudClientAcCurrentLimitTest" --tests "com.overdrive.app.byd.cloud.CloudCallTraceTest"`
Expected: FAIL — new types/methods do not exist.

- [ ] **Step 3: Implement `CloudCallTrace` and the client methods**

`CloudCallTrace`:
- stores the fields defensively (null → empty `JSONObject`/`""`);
- `isSuccess()` = `error == null && "0".equals(code)`;
- `maskedVin()` = last 4 characters of the request's `vin`, prefixed with `***`, or `""` when absent;
- `consoleText()` includes endpoint, code, message, duration, request JSON, raw outer response JSON, decoded JSON, error;
- `shareText()` is `consoleText()` with every `"vin":"..."` value replaced by `"***<last4>"` (regex is acceptable).

`BydCloudClient.callControlEndpoint`:
- if `!LabEndpointPolicy.isAllowed(endpoint)`, return a trace with `error = "endpoint not allowed"` and no network;
- build `nowMs`, `buildInner(nowMs)`, merge `extraInner` keys over it;
- build the plain token envelope with `buildTokenOuterEnvelope`;
- POST with `transport.postSecure`;
- capture duration with `System.currentTimeMillis()`;
- decode `respondData` with `BydCloudTransport.decryptRespondData` when present;
- catch every exception into `trace.error` and return the trace (never throw from this method).

`readAcCurrentLimit`:
- default `energyType` to `"0"` when null/blank;
- call `callControlEndpoint("/control/smartCharge/getAcCurrentLimit", extra)`;
- parse `trace.decoded` only when `trace.isSuccess() && trace.decoded != null`;
- return `AcCurrentLimitResult(trace, limitOrNull)`.

`writeAcCurrentLimit`:
- call `callControlEndpoint("/control/smartCharge/limitCurrentControl", extra)` with `limitCurrentValue`;
- return `AcCurrentLimitResult(trace, null)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.overdrive.app.byd.cloud.BydCloudClientAcCurrentLimitTest" --tests "com.overdrive.app.byd.cloud.CloudCallTraceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/overdrive/app/byd/cloud/CloudCallTrace.java \
        android-companion/app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java \
        android-companion/app/src/test/java/com/overdrive/app/byd/cloud/BydCloudClientAcCurrentLimitTest.java \
        android-companion/app/src/test/java/com/overdrive/app/byd/cloud/CloudCallTraceTest.java
git commit -m "feat(lab): add traced control calls and AC current limit client"
```

---

### Task 4: Before/after verdict

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabVerdict.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabVerdictTest.java`

**Interfaces:**
- Consumes: `ChargeLimitValues.same`.
- Produces: `LabVerdict.classify(String before, String requested, String after, String code, String error, boolean reReadCompleted) -> LabVerdict`.

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LabVerdictTest {

    @Test
    public void appliedWhenReReadMatchesRequested() {
        assertEquals(LabVerdict.APPLIED,
                LabVerdict.classify("MAX", "10A", "10A", "0", null, true));
        assertEquals(LabVerdict.APPLIED,
                LabVerdict.classify("MAX", "10", "10A", "0", null, true));
    }

    @Test
    public void unchangedAndChangedAreDistinct() {
        assertEquals(LabVerdict.UNCHANGED,
                LabVerdict.classify("MAX", "10A", "MAX", "0", null, true));
        assertEquals(LabVerdict.CHANGED,
                LabVerdict.classify("MAX", "10A", "16A", "0", null, true));
    }

    @Test
    public void pendingWhenNoReReadHappened() {
        assertEquals(LabVerdict.PENDING,
                LabVerdict.classify("MAX", "10A", null, "0", null, false));
    }

    @Test
    public void rejectsAndErrorsAreReportedFirst() {
        assertEquals(LabVerdict.REJECTED,
                LabVerdict.classify("MAX", "10A", "MAX", "1001", null, true));
        assertEquals(LabVerdict.UNREACHABLE,
                LabVerdict.classify("MAX", "10A", "MAX", "6002", null, true));
        assertEquals(LabVerdict.ERROR,
                LabVerdict.classify("MAX", "10A", "MAX", "0", "network", true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabVerdictTest"`
Expected: FAIL — `LabVerdict` does not exist.

- [ ] **Step 3: Implement `LabVerdict`**

Order of checks:
1. `error != null` → `ERROR`;
2. `"6002".equals(code)` → `UNREACHABLE`;
3. `code != null && !code.isEmpty() && !"0".equals(code)` → `REJECTED`;
4. `!reReadCompleted` → `PENDING`;
5. `ChargeLimitValues.same(after, requested)` → `APPLIED`;
6. `ChargeLimitValues.same(after, before)` → `UNCHANGED`;
7. otherwise `CHANGED`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabVerdictTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/LabVerdict.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/LabVerdictTest.java
git commit -m "feat(lab): classify AC current write outcomes"
```

---

### Task 5: Lab shell, gate, and drawer entry

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabGate.java`
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabContext.java`
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java`
- Create: `android-companion/app/src/main/res/layout/activity_lab.xml`
- Modify: `android-companion/app/src/main/res/menu/drawer_menu.xml`
- Modify: `android-companion/app/src/main/AndroidManifest.xml`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabGateTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabContextTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/ui/LabEntryTest.java`

**Interfaces:**
- Consumes: `ConfigStore.getVin()`, `ConfigStore.getVehicles()`, `VehicleSummary.energyType`, `BydWatchApp.getRepository()`, `CloudBydGateway.getClient()`.
- Produces:
  - `LabGate.isEnabled(boolean debugBuild, boolean demoBuild) -> boolean`
  - `LabContext.resolveEnergyType(String activeVin, List<VehicleSummary> vehicles, String fallback) -> String`
  - `LabContext.rawEnergyType(String activeVin, List<VehicleSummary> vehicles) -> String`
  - `R.id.nav_lab`, `R.layout.activity_lab`, `LabActivity`

- [ ] **Step 1: Write the failing tests**

```java
package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabGateTest {
    @Test
    public void enabledOnlyForDebugNonDemoBuilds() {
        assertTrue(LabGate.isEnabled(true, false));
        assertFalse(LabGate.isEnabled(false, false));
        assertFalse(LabGate.isEnabled(true, true));
        assertFalse(LabGate.isEnabled(false, true));
    }
}
```

```java
package com.bydwatch.companion.lab;

import com.bydwatch.companion.model.VehicleSummary;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LabContextTest {

    private static VehicleSummary car(String vin, String energyType) {
        return VehicleSummary.fromBydVehicleJson(new JSONObject()
                .put("vin", vin)
                .put("energyType", energyType));
    }

    @Test
    public void prefersActiveVinOverFirstVehicle() {
        assertEquals("2", LabContext.resolveEnergyType(
                "VIN_B", Arrays.asList(car("VIN_A", "0"), car("VIN_B", "2")), "0"));
    }

    @Test
    public void fallsBackWhenActiveVinIsMissingOrUnknown() {
        assertEquals("0", LabContext.resolveEnergyType(
                "VIN_X", Arrays.asList(car("VIN_A", "0")), "2"));
        assertEquals("2", LabContext.resolveEnergyType(
                "VIN_X", Collections.emptyList(), "2"));
        assertEquals("0", LabContext.resolveEnergyType(
                "VIN_X", Arrays.asList(car("VIN_X", "9")), "9"));
    }
}
```

```java
package com.bydwatch.companion.ui;

import android.content.Intent;
import android.view.MenuItem;

import androidx.test.core.app.ApplicationProvider;

import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.lab.LabActivity;
import com.google.android.material.navigation.NavigationView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabEntryTest {

    @Test
    public void drawerShowsLabAndOpensActivity() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setUsername("test@byd.id");
        store.setPassword("ValidPass123");
        store.setPin("123456");
        store.setRegion("id");
        store.setVin("VIN_TEST");
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repository = new BydRepository(gateway, store);
        com.bydwatch.companion.BydWatchApp.setTestDependencies(store, gateway, repository);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        NavigationView nav = activity.findViewById(R.id.navView);
        MenuItem item = nav.getMenu().findItem(R.id.nav_lab);
        assertNotNull(item);
        assertTrue(item.isVisible());

        nav.getMenu().performIdentifierAction(R.id.nav_lab, 0);

        ShadowActivity shadow = Shadows.shadowOf(activity);
        Intent next = shadow.getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LabActivity.class.getName(), next.getComponent().getClassName());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.*" --tests "com.bydwatch.companion.ui.LabEntryTest"`
Expected: FAIL — classes, menu id, and activity do not exist.

- [ ] **Step 3: Implement the shell**

`LabGate` is a two-line static predicate.

`LabContext.resolveEnergyType`:
- find `activeVin` in `vehicles`; if found and `energyType` is `"0"` or `"2"`, return it;
- otherwise return `fallback` when it is `"0"` or `"2"`;
- otherwise `"0"`.
`rawEnergyType` returns the active car's raw string, or `""`.

`LabActivity`:
- `onCreate`: `finish()` unless `LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD)`;
- `setContentView(R.layout.activity_lab)`;
- bind a header with a "TRIAL" badge and a back button;
- bind context views: `tvLabVin`, `tvLabEnergyRaw`, `etLabEnergy`, `tvLabRegion`, `tvLabSession`, `tvLabChargeState`, `tvLabPinState`, `btnLabRefreshContext`;
- resolve `BydWatchApp.getRepository()`; cast its gateway to `CloudBydGateway`; if that fails, or the configured VIN is blank, show `lab_unavailable` and disable actions;
- resolve energy type: `raw = LabContext.rawEnergyType(activeVin, configStore.getVehicles())`; if `raw` is not `"0"`/`"2"`, call `client.fetchFirstVinAndEnergyType()` on the executor and use element `[1]` as the fallback; then `LabContext.resolveEnergyType(activeVin, vehicles, fallback)`; catch and fall back to `"0"`;
- render VIN masked, region, session configured state, cached `VehicleStatus` charging/plug state, and `client.isControlPasswordVerified()`;
- long-press the VIN row toggles between masked and full VIN for local inspection;
- refresh context on a background executor and post to the UI thread.

`activity_lab.xml`:
- `NestedScrollView` → vertical `LinearLayout` with padding;
- header row: title, TRIAL badge, back `ImageButton`;
- four `MaterialCardView` sections with the IDs listed above plus placeholders for Task 6–8 cards (empty containers are acceptable for now);
- use `@style/Theme.BydWatch` inherited theme, existing color tokens, and `@string` labels.

`drawer_menu.xml`: add `nav_lab` after `nav_watch_bridge` with `@string/nav_lab`.

`AndroidManifest.xml`: add the activity inside `<application>` with `android:name=".lab.LabActivity"`, `android:exported="false"`, `android:label="@string/lab_title"`.

`MainActivity`:
- after `navView` binding, set `nav_lab` visibility to `LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD)`;
- in the navigation listener, before `showPage`, start `LabActivity` and return `false` for `nav_lab`.

Strings: add `nav_lab`, `lab_title`, `lab_trial_badge`, `lab_back`, `lab_context_title`, `lab_vin`, `lab_energy_type`, `lab_energy_raw`, `lab_region`, `lab_session`, `lab_charge_state`, `lab_pin_state`, `lab_refresh_context`, `lab_unavailable`. Add Indonesian equivalents in `values-in/strings.xml`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.*" --tests "com.bydwatch.companion.ui.LabEntryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/ \
        android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java \
        android-companion/app/src/main/res/layout/activity_lab.xml \
        android-companion/app/src/main/res/menu/drawer_menu.xml \
        android-companion/app/src/main/AndroidManifest.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/ \
        android-companion/app/src/test/java/com/bydwatch/companion/ui/LabEntryTest.java
git commit -m "feat(lab): add debug-only lab entry and context screen"
```

---

### Task 6: Read current limit and call console

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabConsole.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java`
- Modify: `android-companion/app/src/main/res/layout/activity_lab.xml`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabConsoleTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabReadTest.java`

**Interfaces:**
- Consumes: `BydCloudClient.readAcCurrentLimit`, `CloudCallTrace`, `AcCurrentLimit`.
- Produces:
  - `LabConsole(int capacity)`, `add(CloudCallTrace)`, `text()`, `clear()`
  - Lab views: `btnLabReadLimit`, `tvLabActiveLimit`, `tvLabOptions`, `acLabLimitValue`, `tvLabConsole`, `svLabConsole`, `btnLabCopyAll`, `btnLabShare`, `btnLabClear`

- [ ] **Step 1: Write the failing tests**

```java
package com.bydwatch.companion.lab;

import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabConsoleTest {
    @Test
    public void keepsNewestEntriesAndClears() {
        LabConsole console = new LabConsole(2);
        console.add(new CloudCallTrace("/a", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));
        console.add(new CloudCallTrace("/b", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));
        console.add(new CloudCallTrace("/c", new JSONObject(), new JSONObject(),
                new JSONObject(), "0", "", 1L, null));

        String text = console.text();
        assertFalse(text.contains("/a"));
        assertTrue(text.contains("/b"));
        assertTrue(text.contains("/c"));

        console.clear();
        assertEquals("", console.text());
    }
}
```

```java
package com.bydwatch.companion.lab;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.AcCurrentLimit;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabReadTest {

    static class FakeClient extends BydCloudClient {
        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public AcCurrentLimitResult readAcCurrentLimit(String vin, String energyType) {
            JSONObject payload = new JSONObject()
                    .put("currentLimitList", new JSONArray().put("6A").put("10A").put("MAX"))
                    .put("currentEvCurrentLimit", "MAX");
            CloudCallTrace trace = new CloudCallTrace(
                    "/control/smartCharge/getAcCurrentLimit",
                    new JSONObject().put("vin", vin).put("energyType", energyType),
                    new JSONObject(), payload, "0", "", 3L, null);
            return new AcCurrentLimitResult(trace, AcCurrentLimit.parse(vin, payload));
        }
    }

    @Test
    public void readShowsActiveValueOptionsAndConsoleEntry() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
        store.setVehicles(Arrays.asList(VehicleSummary.fromBydVehicleJson(
                new JSONObject().put("vin", "VIN_TEST_0001").put("energyType", "0"))));

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        activity.findViewById(com.bydwatch.companion.R.id.btnLabReadLimit).performClick();
        drain(repository);

        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabActiveLimit).contains("MAX"));
        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabOptions).contains("10A"));
        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabConsole)
                .contains("getAcCurrentLimit"));
    }

    private static void drain(BydRepository repository) {
        try {
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }

    private static String text(LabActivity activity, int id) {
        return ((android.widget.TextView) activity.findViewById(id)).getText().toString();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabConsoleTest" --tests "com.bydwatch.companion.lab.LabReadTest"`
Expected: FAIL — `LabConsole` and read views do not exist.

- [ ] **Step 3: Implement console and read card**

`LabConsole` keeps an `ArrayDeque<String>` of formatted entries, drops the oldest when capacity is exceeded, exposes `text()` joined by blank lines, and prefixes every entry with `[HH:mm:ss]` at add time. `LabActivity` uses a capacity of 200. Entries are always fully expanded in the trial; single-entry copying is handled by the selectable text view.

Add the current-limit card to `activity_lab.xml`:
- `btnLabReadLimit`
- `tvLabActiveLimit`
- `tvLabOptions`
- `acLabLimitValue` (`AutoCompleteTextView`; populated from the last read, free text acts as the Custom value)
- placeholder Set button/switch for Task 7

Add the console card:
- `tvLabConsole` (monospace, `textIsSelectable="true"`, inside `svLabConsole`)
- `btnLabCopyAll` (clipboard), `btnLabShare` (ACTION_SEND), `btnLabClear`

`LabActivity`:
- add `readLimit()`: on the repository executor call `client.readAcCurrentLimit(activeVin, energyType)`;
- on success, render `active`, join options with `"  "`, fill `acLabLimitValue` with the active value, attach an `ArrayAdapter<String>` of the options, and append `trace.consoleText()` to the console;
- on failure, show `trace.error` or `code/message` in `tvLabActiveLimit` and still append the trace;
- after appending, scroll `svLabConsole` to the bottom; selectable text covers single-entry copying;
- copy/share use `trace.shareText()`; clear empties the console.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabConsoleTest" --tests "com.bydwatch.companion.lab.LabReadTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/LabConsole.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java \
        android-companion/app/src/main/res/layout/activity_lab.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/LabConsoleTest.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/LabReadTest.java
git commit -m "feat(lab): read AC current limit and show raw call console"
```

---

### Task 7: Set current limit and before/after confirmation

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabRetryPolicy.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java`
- Modify: `android-companion/app/src/main/res/layout/activity_lab.xml`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabWriteTest.java`

**Interfaces:**
- Consumes: `BydCloudClient.writeAcCurrentLimit`, `readAcCurrentLimit`, `LabVerdict`, `LabRetryPolicy`.
- Produces:
  - `LabRetryPolicy(int attempts, long delayMs)`, `LabRetryPolicy.defaults()`
  - `LabActivity.setRetryPolicyForTesting(LabRetryPolicy)` (annotated `@VisibleForTesting`)
  - Lab views: `btnLabSetLimit`, `switchLabReRead`, `tvLabVerdict`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.AcCurrentLimit;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabWriteTest {

    static class FakeClient extends BydCloudClient {
        String value = "MAX";
        String requested;

        FakeClient() { super(new BydCloudConfig()); }

        private AcCurrentLimitResult result(String endpoint, JSONObject payload) {
            CloudCallTrace trace = new CloudCallTrace(endpoint,
                    new JSONObject(), new JSONObject(), payload, "0", "", 1L, null);
            return new AcCurrentLimitResult(trace, AcCurrentLimit.parse("VIN_TEST_0001", payload));
        }

        @Override
        public AcCurrentLimitResult readAcCurrentLimit(String vin, String energyType) {
            return result("/control/smartCharge/getAcCurrentLimit",
                    new JSONObject()
                            .put("currentLimitList", new JSONArray().put("6A").put("10A").put("MAX"))
                            .put("currentEvCurrentLimit", value));
        }

        @Override
        public AcCurrentLimitResult writeAcCurrentLimit(String vin, String value) {
            requested = value;
            this.value = value;
            return result("/control/smartCharge/limitCurrentControl", new JSONObject());
        }
    }

    @Test
    public void setWritesThenReReadsAndShowsApplied() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
        store.setVehicles(Arrays.asList(VehicleSummary.fromBydVehicleJson(
                new JSONObject().put("vin", "VIN_TEST_0001").put("energyType", "0"))));

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        activity.setRetryPolicyForTesting(new LabRetryPolicy(1, 0L));

        activity.findViewById(com.bydwatch.companion.R.id.btnLabReadLimit).performClick();
        drain(repository);

        android.widget.EditText input = activity.findViewById(com.bydwatch.companion.R.id.acLabLimitValue);
        input.setText("10A");
        activity.findViewById(com.bydwatch.companion.R.id.btnLabSetLimit).performClick();

        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        drain(repository);

        assertEquals("10A", client.requested);
        String verdict = ((android.widget.TextView) activity.findViewById(
                com.bydwatch.companion.R.id.tvLabVerdict)).getText().toString();
        assertTrue(verdict.contains("APPLIED"));
    }

    private static void drain(BydRepository repository) {
        try {
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabWriteTest"`
Expected: FAIL — write views, retry policy, or confirmation flow missing.

- [ ] **Step 3: Implement the write flow**

`LabRetryPolicy`:
- `defaults()` = 6 attempts, 2000 ms delay;
- constructor rejects attempts < 1 by clamping to 1.

`LabActivity`:
- `setRetryPolicyForTesting` is `@VisibleForTesting` and only meaningful in debug builds.
- Set button opens an `AlertDialog` titled `lab_confirm_write_title` showing endpoint and payload; positive button `lab_send_confirm`.
- On confirm, run on `repository.getExecutor()`:
  1. if `switchLabReRead` is on, read before (unless a fresh read is already displayed);
  2. call `writeAcCurrentLimit(activeVin, value)`;
  3. if trace success and re-read on, sleep `delayMs` and read after up to `attempts`;
  4. classify with `LabVerdict.classify(before, requested, after, trace.code, trace.error, reReadCompleted)`;
  5. post `tvLabVerdict` as `VERDICT · before → after · N ms` (N from the write trace) and append both traces to the console.
- If the write trace is not successful, do not re-read; show `REJECTED`/`UNREACHABLE`/`ERROR`.
- No automatic retry of the write itself.

Add to `activity_lab.xml`: `btnLabSetLimit`, `switchLabReRead` (default checked), `tvLabVerdict`.
Add strings: `lab_set_limit`, `lab_confirm_write_title`, `lab_confirm_write_message`, `lab_send_confirm`, `lab_cancel`, `lab_reread`, `lab_verdict_idle`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabWriteTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/LabRetryPolicy.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java \
        android-companion/app/src/main/res/layout/activity_lab.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/LabWriteTest.java
git commit -m "feat(lab): write AC current limit with before/after verdict"
```

---

### Task 8: Smart-charge triggers and raw runner

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabEndpoints.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java`
- Modify: `android-companion/app/src/main/res/layout/activity_lab.xml`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabRawRunnerTest.java`

**Interfaces:**
- Consumes: `BydCloudClient.callControlEndpoint`, `LabEndpointPolicy`, `LabConsole`.
- Produces:
  - `LabEndpoints` constants: `GET_AC_LIMIT`, `SET_AC_LIMIT`, `HOME_PAGE`, `CHANGE_CHARGE_STATUS`, `CHANGE_RESULT`, `SAVE_SCHEDULE`, `CHARGE_SOC_LIMIT`, `STOP_CHARGE`, `CONTINUE_CHARGE`, `LIMIT_CURRENT_RESULT`
  - `LabEndpoints.rawRunnerPaths() -> String[]`
  - Lab views: `btnLabHomePage`, `switchLabSmartCharge`, `btnLabToggle`, `btnLabStartCharge`, `etLabScheduleStart`, `etLabScheduleEnd`, `etLabScheduleWay`, `switchLabScheduleEnabled`, `btnLabSaveSchedule`, `etLabSocLimit`, `btnLabSetSoc`, `btnLabStopCharge`, `btnLabContinueCharge`, `etLabRequestSerial`, `btnLabPollLimitResult`, `spLabEndpoint`, `etLabCustomEndpoint`, `etLabRawInner`, `btnLabSendRaw`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.data.CloudBydGateway;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabRawRunnerTest {

    static class FakeClient extends BydCloudClient {
        String endpoint;
        JSONObject inner;

        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public CloudCallTrace callControlEndpoint(String endpoint, JSONObject extraInner) {
            this.endpoint = endpoint;
            this.inner = extraInner;
            return new CloudCallTrace(endpoint, extraInner, new JSONObject(),
                    new JSONObject(), "0", "", 1L, null);
        }
    }

    @Test
    public void rawRunnerSendsEditableJsonToSmartChargeEndpoint() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        android.widget.EditText custom = activity.findViewById(
                com.bydwatch.companion.R.id.etLabCustomEndpoint);
        custom.setText("/control/smartCharge/homePage");
        android.widget.EditText inner = activity.findViewById(
                com.bydwatch.companion.R.id.etLabRawInner);
        inner.setText("{\"vin\":\"VIN_TEST_0001\",\"probe\":\"1\"}");

        activity.findViewById(com.bydwatch.companion.R.id.btnLabSendRaw).performClick();
        drain(repository);

        assertEquals("/control/smartCharge/homePage", client.endpoint);
        assertEquals("1", client.inner.optString("probe"));
        assertTrue(((android.widget.TextView) activity.findViewById(
                com.bydwatch.companion.R.id.tvLabConsole)).getText().toString()
                .contains("smartCharge/homePage"));
    }

    private static void drain(BydRepository repository) {
        try {
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabRawRunnerTest"`
Expected: FAIL — raw-runner views and constants do not exist.

- [ ] **Step 3: Implement the triggers**

`LabEndpoints` holds the ten path constants and returns the raw-runner path list in this order: `getAcCurrentLimit`, `limitCurrentControl`, `limitCurrentControlResult`, `homePage`, `changeChargeStatue`, `changeResult`, `saveOrUpdate`, `chargeSocLimit`, `stopChargeControl`, `continueChargeControl`.

Add a smart-charge card with:
- `btnLabHomePage` → `{vin}`
- `switchLabSmartCharge` + `btnLabToggle` → `{vin, smartChargeSwitch: "1"|"0"}`
- `btnLabStartCharge` → `{vin, timeZone: "", status: "1"}`
- schedule fields + `btnLabSaveSchedule` → `{vin, startChargeTime, endChargeTime, chargeWay, status, timeZone: ""}`
- `etLabSocLimit` + `btnLabSetSoc` → `{vin, chargeSocLimit: int}`
- `btnLabStopCharge` / `btnLabContinueCharge` → `{vin}`
- `etLabRequestSerial` + `btnLabPollLimitResult` → `{vin, requestSerial}`

Add a raw-runner card:
- `spLabEndpoint` populated from `LabEndpoints.rawRunnerPaths()`;
- `etLabCustomEndpoint` overrides the spinner when non-empty;
- `etLabRawInner` default `{"vin":"<active vin>"}`;
- `btnLabSendRaw` parses the JSON (show `lab_invalid_json` on error) and calls `callControlEndpoint`.

Every trigger runs on `BydRepository.getExecutor()` and appends its `CloudCallTrace.consoleText()` to the console. A disallowed endpoint never reaches the client; the policy error is displayed and logged.

Add strings for every label and the Indonesian equivalents for the visible ones.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.LabRawRunnerTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole unit suite**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/LabEndpoints.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java \
        android-companion/app/src/main/res/layout/activity_lab.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/LabRawRunnerTest.java
git commit -m "feat(lab): add smart-charge triggers and raw endpoint runner"
```

---

### Task 9: Trial APK and on-car verification

**Files:**
- Create: `docs/testing/lab-charging-current-trial.md`
- Modify: none in app code.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a signed trial APK and a results template.

- [ ] **Step 1: Build the debug APK**

Run: `cd android-companion && ./gradlew.bat :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL; artifact at `app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`.

- [ ] **Step 2: Verify the canonical signature**

Run: `apksigner verify --print-certs android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`
(if `apksigner` is not on PATH, use `$ANDROID_HOME/build-tools/<version>/apksigner`).
Expected: SHA-256 `D1:67:0D:07:3C:1A:85:42:B7:80:80:2A:DF:CC:41:DE:58:87:5B:C5:98:AD:CC:76:44:D8:CC:8F:15:5C:B8:99`.

- [ ] **Step 3: Install over the existing app**

Run: `adb install -r android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`
Expected: `Success`.

- [ ] **Step 4: Write the trial results template**

Create `docs/testing/lab-charging-current-trial.md` with:
- build/version, date, VIN (masked), region;
- car state at each read (plugged, charging, power);
- table: step, car setting, app read, app set, app re-read, car display, verdict, notes;
- the eight trial-protocol steps from the spec as checkboxes;
- a section for raw console paste.

- [ ] **Step 5: Run the on-car trial**

Follow the spec's trial protocol:
1. plug in the AC charger;
2. set a known limit in the car;
3. Lab → Read and compare;
4. Lab → Set a different supported value;
5. check the car/charger;
6. Lab → Read and record the verdict;
7. retry after replug/next session if `PENDING`/`UNCHANGED`;
8. force one failure and confirm the console is copyable.

Record every result in the template.

- [ ] **Step 6: Commit the template**

```bash
git add docs/testing/lab-charging-current-trial.md
git commit -m "docs: add lab charging-current trial checklist"
```

---

## Self-Review Notes

- Spec coverage: read (Tasks 1, 3, 6), write (Tasks 3, 7), other smart-charge triggers (Task 8), raw runner (Tasks 2, 8), console/copy (Tasks 3, 6), debug gate (Task 5), trial build (Task 9).
- The `Review Focus` items map to Task 1, Task 5, Task 7, Tasks 2/8, and Task 5 respectively.
- Interfaces are named consistently across tasks: `AcCurrentLimit`, `CloudCallTrace`, `AcCurrentLimitResult`, `LabGate`, `LabContext`, `LabVerdict`, `LabConsole`, `LabRetryPolicy`, `LabEndpoints`.
- No task adds the wallbox, watch, or release-visible Lab UI.
