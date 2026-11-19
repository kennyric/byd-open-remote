# BYD battery + range in the Android Auto navigation card — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the BYD's state of charge and remaining range inside Google Maps' own ETA/status text on the Android Auto screen, using data from `byd-open-remote`.

**Architecture:** `byd-open-remote` publishes SoC/range through an exported `ContentProvider` and nudges listeners with a package-targeted broadcast. A forked `nl-morphe-patches` bundle injects a decorator into the turn-card status object (`Lbltz;->b`), which appends `· 62% · 310 km` to the ETA line. The Maps side never queries the provider on the UI thread: a broadcast fills a cache and a stale cache triggers an asynchronous refresh.

**Tech Stack:** Java 11 source level (Maps extension), Kotlin (Morphe patch definitions), JUnit 4 + Robolectric, Gradle 9.6.1, Morphe Patcher 2.2.0.

**Spec:** `docs/superpowers/specs/2026-11-17-byd-range-in-maps-eta-design.md`

**Two repositories are modified:**
- APP = `E:\Kenny\Huawei\android-companion` (`applicationId com.bydopenremote.app`)
- FORK = `E:\Kenny\maps-analysis\nl-morphe-patches` (GPL-3.0, Maps `26.44.06.1006895251` / vc `1068856906`)

## Global Constraints

- Maps target is exactly `26.44.06.1006895251`, versionCode `1068856906`, minSdk 29. Patches are pinned through `Constants.COMPATIBILITY_MAPS`.
- The Maps extension module compiles at Java 11 (`options.release.set(11)`); no newer language features.
- The patched Maps is signed with a non-Google key: `signature`-level permissions can never be granted to it. Use `protectionLevel="normal"`.
- No network, no ContentProvider query, and no blocking I/O on the UI thread inside the Maps extension.
- Unknown data (missing provider, stale > 15 min, `soc < 0`, `range_km < 0`) must render nothing at all — not `0%`, not `--`.
- Range is displayed with the provider's own unit label; no conversion.
- Fork stays GPL-3.0; do not distribute a modified bundle without publishing its source.
- Build tooling: JAVA_HOME is JDK 17, `ANDROID_HOME=D:\Android\Sdk`, Morphe CLI at
  `C:\Users\kenny\.config\opencode\tools\morphe\morphe-desktop-2.2.0-all.jar` run with
  `...\tools\morphe\jdk-21.0.13+9-jre\bin\java.exe`.

## Review Focus

- **Provider absent or disabled** — the card must look exactly like stock; no suffix, no crash, no startup delay.
- **Stale reading** (car parked overnight, phone offline) — must show nothing rather than yesterday's range.
- **Non-navigation status text** ("Rerouting…", "Searching for GPS…") — must not be decorated.
- **Miles** (`distance_unit = mi`) — must show `192 mi`, never a silently converted km value.
- **Rapid card rebinds** during guidance — must not queue a provider query per bind or leak receivers.

---

### Task 1: Build the fork unmodified and confirm the pinned target

**Files:**
- Create: `FORK/.community-maps-api-key` (git-ignored build input)

**Interfaces:**
- Produces: a working `FORK/patches/build/libs/*.mpp` and the exact commands used later to patch Maps.

- [ ] **Step 1: Create the build input**

The patches module requires a syntactically valid Maps API key for an unrelated weather patch
(`patches/buienalarm/CommunityMapsKey.kt`). It is only embedded in that patch, which we never select.

```bash
printf 'AIza%s\n' "$(head -c 35 /dev/zero | tr '\0' 'x')" > "FORK/.community-maps-api-key"
```

- [ ] **Step 2: Run the bundle build**

```bash
cd FORK && ./gradlew :patches:buildAndroid --no-daemon
```

Expected: `BUILD SUCCESSFUL`; an `.mpp` file appears under `FORK/patches/build/libs/`.

- [ ] **Step 3: Run the extension and patch unit tests**

```bash
cd FORK && ./gradlew :extensions:maps-gearslip:testDebugUnitTest :patches:test --no-daemon
```

Expected: `BUILD SUCCESSFUL` (this is the baseline that later tasks must keep green).

- [ ] **Step 4: Confirm the Maps patches are visible in the bundle**

```bash
MORPHE="C:/Users/kenny/.config/opencode/tools/morphe"
"$MORPHE/jdk-21.0.13+9-jre/bin/java.exe" -jar "$MORPHE/morphe-desktop-2.2.0-all.jar" \
  list-patches --patches=FORK/patches/build/libs/<produced>.mpp --with-packages | grep -i "apps.maps" | head
```

Expected: `com.google.android.apps.maps` listed.

- [ ] **Step 5: Commit**

```bash
cd FORK && git add -A && git commit -m "build: allow local bundle builds without a shared Maps API key"
```

---

### Task 2: Pure ETA text rules (`BydEtaText`)

No Android imports: this class is unit-testable on the JVM and is the only place that decides *whether* and *how* the suffix is written.

**Files:**
- Create: `FORK/extensions/maps-gearslip/src/main/java/patches/maps/gearslip/BydEtaText.java`
- Test: `FORK/extensions/maps-gearslip/src/test/java/patches/maps/gearslip/BydEtaTextTest.java`

**Interfaces:**
- Produces:
  - `public static boolean looksLikeEta(String text)`
  - `public static String suffix(int soc, int rangeKm, String unit)`
  - `public static String decorate(String original, int soc, int rangeKm, String unit)`
  - `public static String decorateMarker(String original)` → `decorate(original, 62, 310, "km")` (used only by Task 3)
  - Separator between parts is `" · "` (space, U+00B7, space).

- [ ] **Step 1: Write the failing test**

```java
package patches.maps.gearslip;

import org.junit.Test;
import static org.junit.Assert.*;

public class BydEtaTextTest {
    @Test public void appendsSocAndRangeToEtaText() {
        assertEquals("12 min · 5.2 km · 62% · 310 km",
                BydEtaText.decorate("12 min · 5.2 km", 62, 310, "km"));
    }
    @Test public void usesProviderUnitWithoutConverting() {
        assertEquals("8 min · 3.1 mi · 62% · 192 mi", BydEtaText.decorate("8 min · 3.1 mi", 62, 192, "mi"));
    }
    @Test public void showsOnlyRangeWhenSocUnknown() {
        assertEquals("12 min · 310 km", BydEtaText.decorate("12 min", -1, 310, "km"));
    }
    @Test public void showsNothingWhenBothUnknown() {
        assertEquals("12 min", BydEtaText.decorate("12 min", -1, -1, "km"));
    }
    @Test public void leavesNonNavigationStatusAlone() {
        assertEquals("Rerouting…", BydEtaText.decorate("Rerouting…", 62, 310, "km"));
        assertEquals("Searching for GPS…", BydEtaText.decorate("Searching for GPS…", 62, 310, "km"));
    }
    @Test public void leavesNullAndEmptyAlone() {
        assertNull(BydEtaText.decorate(null, 62, 310, "km"));
        assertEquals("", BydEtaText.decorate("", 62, 310, "km"));
    }
    @Test public void markerUsesFixedValues() {
        assertEquals("12 min · 62% · 310 km", BydEtaText.decorateMarker("12 min"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd FORK && ./gradlew :extensions:maps-gearslip:testDebugUnitTest --tests 'patches.maps.gearslip.BydEtaTextTest'
```

Expected: FAIL, `BydEtaText` does not exist.

- [ ] **Step 3: Implement `BydEtaText`**

Rules the tests pin: `looksLikeEta` is true when the text contains at least one digit **and** one of the tokens
`min`, `km`, `mi`, `hr`; `suffix` returns `""` when `soc < 0 && rangeKm < 0`, otherwise joins `"<soc>%"` (only when
`soc >= 0`) and `"<rangeKm> <unit>"` (only when `rangeKm >= 0`) with `" · "`; `decorate` returns the original
untouched when it is null, empty, not ETA-like, or the suffix is empty.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd FORK && ./gradlew :extensions:maps-gearslip:testDebugUnitTest --tests 'patches.maps.gearslip.BydEtaTextTest'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
cd FORK && git add extensions/maps-gearslip/src/main/java/patches/maps/gearslip/BydEtaText.java \
  extensions/maps-gearslip/src/test/java/patches/maps/gearslip/BydEtaTextTest.java \
  && git commit -m "feat(maps): add BYD ETA text decoration rules"
```

---

### Task 3: Marker patch on the turn-card status object (proves the anchor)

**Files:**
- Create: `FORK/patches/src/main/kotlin/patches/googlemaps/car/BydEtaRangePatch.kt`

**Interfaces:**
- Consumes: `BydEtaText.decorateMarker(Ljava/lang/String;)Ljava/lang/String;`
- Produces: patch named **`BYD battery and range in the navigation card`**, index-addressable by name in the CLI.

- [ ] **Step 1: Write the patch**

Shape (follow the neighbouring `NativeNavigationPatch.kt` conventions: `bytecodePatch`, `compatibleWith(COMPATIBILITY_MAPS)`, `dependsOn(sharedExtensionPatch)`):

```kotlin
val bydEtaRangePatch = bytecodePatch(
    name = "BYD battery and range in the navigation card",
    description = "Appends the vehicle's state of charge and remaining electric range, published by " +
        "byd-open-remote, to the time-and-distance status line of the navigation card.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)
    execute {
        val ctor = mutableClassDefBy("Lbltz;").methods.single {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Lblty;", "Ljava/lang/String;", "Lj$/util/Optional;", "Lbluk;")
        }
        val afterSuper = ctor.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_DIRECT && it.toString().contains("Ljava/lang/Object;-><init>()V")
        } + 1
        ctor.addInstructions(afterSuper, """
            invoke-static {p2}, Lpatches/maps/gearslip/BydEtaText;->decorateMarker(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p2
        """)
    }
}
```

- [ ] **Step 2: Register the patch** so the bundle builder picks it up (same mechanism as the other `googlemaps` patches — if the module lists patches explicitly, add it there; otherwise the `@Suppress("unused") val` convention is enough).

```bash
cd FORK && grep -rn "NativeNavigationPatch\|nativeNavigationPatch" patches/src/main/kotlin --include="*.kt" | grep -v "car/NativeNavigationPatch.kt"
```

Expected: shows the registration site to add `bydEtaRangePatch` beside.

- [ ] **Step 3: Build the bundle**

```bash
cd FORK && ./gradlew :patches:buildAndroid --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Patch the pinned Maps APK**

```bash
MORPHE="C:/Users/kenny/.config/opencode/tools/morphe"
"$MORPHE/jdk-21.0.13+9-jre/bin/java.exe" -jar "$MORPHE/morphe-desktop-2.2.0-all.jar" patch \
  -p FORK/patches/build/libs/<produced>.mpp \
  -o E:/Kenny/maps-analysis/maps-byd-marker.apk \
  E:/Kenny/maps-analysis/GoogleMaps_26.44.06.apk
```

Expected: patched APK written; every selected patch reports success (a failed fingerprint means the anchor moved — stop and re-inspect before continuing).

- [ ] **Step 5: Install and view on the Desktop Head Unit**

```bash
D:/Android/Sdk/cmdline-tools/latest/bin/avdmanager.bat create avd -n aa_test \
  -k "system-images;android-34;google_apis_playstore;x86_64" -d pixel_6 --force
D:/Android/Sdk/emulator/emulator.exe -avd aa_test &
D:/Android/Sdk/platform-tools/adb.exe wait-for-device
D:/Android/Sdk/platform-tools/adb.exe install -r E:/Kenny/maps-analysis/maps-byd-marker.apk
D:/Android/Sdk/platform-tools/adb.exe forward tcp:5277 tcp:5277
D:/Android/Sdk/extras/google/auto/desktop-head-unit.exe -c D:/Android/Sdk/extras/google/auto/config/default_1080p.ini
```

In the emulator: start a navigation route in the patched Maps, then read the DHU window.

Expected: the navigation card's status line reads e.g. `12 min · 5.2 km · 62% · 310 km`.
**Record the observed text.** If the suffix does not appear, or appears on non-ETA messages, stop: the anchor or the
`looksLikeEta` rule needs revisiting before any further work.

- [ ] **Step 6: Commit**

```bash
cd FORK && git add patches/src/main/kotlin/patches/googlemaps/car/BydEtaRangePatch.kt \
  && git commit -m "feat(maps): append BYD vehicle data to the navigation card status line"
```

---

### Task 4: ContentProvider in `byd-open-remote`

**Files:**
- Create: `APP/app/src/main/java/com/bydwatch/companion/data/VehicleStatusContract.java`
- Create: `APP/app/src/main/java/com/bydwatch/companion/data/VehicleStatusProvider.java`
- Modify: `APP/app/src/main/AndroidManifest.xml`
- Test: `APP/app/src/test/java/com/bydwatch/companion/data/VehicleStatusContractTest.java`

**Interfaces:**
- Produces:
  - `VehicleStatusContract.AUTHORITY = "com.bydopenremote.app.vehicle"`
  - `VehicleStatusContract.CONTENT_URI` = `content://com.bydopenremote.app.vehicle/status`
  - `VehicleStatusContract.PERMISSION = "com.bydopenremote.app.permission.READ_VEHICLE"`
  - columns `soc`, `range_km`, `ts`, `distance_unit`, `vin`
  - `VehicleStatusContract.row(VehicleStatus status) -> Object[]` returning the five column values in that order
  - `VehicleStatusProvider` (`query`, `getType` = `vnd.android.cursor.item/vnd.bydopenremote.vehicle_status`)

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import org.junit.Test;
import static org.junit.Assert.*;

public class VehicleStatusContractTest {
    @Test public void mapsStatusToRow() {
        VehicleStatus s = new VehicleStatus(62, 310, true, false, null, 22, 1_700_000_000_000L,
                "VIN123", "Atto 3", VehicleStatus.CHARGING_NONE, 0, false, "km");
        Object[] row = VehicleStatusContract.row(s);
        assertEquals(62, row[0]);
        assertEquals(310, row[1]);
        assertEquals(1_700_000_000_000L, row[2]);
        assertEquals("km", row[3]);
        assertEquals("VIN123", row[4]);
    }
    @Test public void missingStatusYieldsUnknownColumns() {
        Object[] row = VehicleStatusContract.row(null);
        assertEquals(-1, row[0]);
        assertEquals(-1, row[1]);
        assertEquals(0L, row[2]);
        assertEquals("km", row[3]);
        assertNull(row[4]);
    }
}
```

The `VehicleStatus` constructor argument order must be read from the class itself before writing this test; if the
existing constructor does not accept exactly these arguments, build the instance through the project's existing
factory/`JSONObject` path instead of adding a constructor.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd APP && ./gradlew :app:testDebugUnitTest --tests 'com.bydwatch.companion.data.VehicleStatusContractTest'
```

Expected: FAIL, `VehicleStatusContract` does not exist.

- [ ] **Step 3: Implement `VehicleStatusContract.row` and `VehicleStatusProvider`**

`row(null)` returns `{-1, -1, 0L, "km", null}`. The provider's `query` reads `BydWatchApp.getInstance()`'s repository
cache (`BydRepository#getCachedStatus`), emits exactly one row, and calls `setNotificationUri` is **not** required.
`insert`/`update`/`delete` throw `UnsupportedOperationException`.

- [ ] **Step 4: Manifest**

```xml
<permission
    android:name="com.bydopenremote.app.permission.READ_VEHICLE"
    android:protectionLevel="normal" />
...
<provider
    android:name="com.bydwatch.companion.data.VehicleStatusProvider"
    android:authorities="com.bydopenremote.app.vehicle"
    android:exported="true"
    android:readPermission="com.bydopenremote.app.permission.READ_VEHICLE" />
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd APP && ./gradlew :app:testDebugUnitTest --tests 'com.bydwatch.companion.data.VehicleStatusContractTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd APP && git add app/src/main/java/com/bydwatch/companion/data/VehicleStatusContract.java \
  app/src/main/java/com/bydwatch/companion/data/VehicleStatusProvider.java \
  app/src/test/java/com/bydwatch/companion/data/VehicleStatusContractTest.java \
  app/src/main/AndroidManifest.xml \
  && git commit -m "feat(data): expose vehicle SoC and range through a read-only ContentProvider"
```

---

### Task 5: Broadcast nudge and target-package preference

**Files:**
- Create: `APP/app/src/main/java/com/bydwatch/companion/data/VehicleStatusBroadcaster.java`
- Modify: `APP/app/src/main/java/com/bydwatch/companion/data/BydRepository.java` (wherever `cachedStatus` is updated)
- Modify: `APP/app/src/main/java/com/bydwatch/companion/data/ConfigStore.java` (target package key, default `org.ungoogled.android.apps.maps`)
- Test: `APP/app/src/test/java/com/bydwatch/companion/data/VehicleStatusBroadcasterTest.java`

**Interfaces:**
- Consumes: `VehicleStatusContract` (Task 4).
- Produces:
  - `VehicleStatusBroadcaster.ACTION = "com.bydopenremote.app.action.VEHICLE_STATUS_CHANGED"`
  - `static void notifyChanged(Context context, String targetPackage)` — no-op when `targetPackage` is blank; includes no extras (receivers re-query).
  - `ConfigStore#getVehicleStatusTargetPackage()` / `setVehicleStatusTargetPackage(String)`

- [ ] **Step 1: Write the failing test**

Robolectric: register a `BroadcastReceiver` for `VehicleStatusBroadcaster.ACTION` with `Context.RECEIVER_EXPORTED`,
call `notifyChanged(context, "org.ungoogled.android.apps.maps")`, assert the intent was received exactly once and that
`notifyChanged(context, "")` produces no broadcast.

- [ ] **Step 2: Run to verify it fails** — `cd APP && ./gradlew :app:testDebugUnitTest --tests '*VehicleStatusBroadcasterTest'`

- [ ] **Step 3: Implement the broadcaster and call it** from the place where `cachedStatus` is updated, passing
  `configStore.getVehicleStatusTargetPackage()`.

- [ ] **Step 4: Run to verify it passes** — same command, expected PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(data): nudge the patched Maps app when vehicle status changes"`

---

### Task 6: `BydVehicle` client inside the Maps extension

**Files:**
- Create: `FORK/extensions/maps-gearslip/src/main/java/patches/maps/gearslip/BydVehicle.java`
- Test: `FORK/extensions/maps-gearslip/src/test/java/patches/maps/gearslip/BydVehicleTest.java`

**Interfaces:**
- Consumes: authority/columns/broadcast action from the spec.
- Produces:
  - `public static String decorate(String original)` — reads the cache only, never queries.
  - `static void attach(Context context)` — idempotent; registers the receiver once.
  - `static boolean fresh(long ts, long now)` — true when `now - ts <= 15 * 60 * 1000L` and `ts > 0`.
  - `static void onStatus(Context context, int soc, int rangeKm, long ts, String unit)` — validation + cache write; ignores `soc < -1`, `rangeKm < -1`, `soc > 100`, blank unit (unit falls back to `"km"`).

- [ ] **Step 1: Write the failing test** (pure JVM, no Robolectric):

```java
@Test public void ignoresOutOfRangeValues() {
    BydVehicle.onStatus(null, 140, 310, System.currentTimeMillis(), "km");
    assertEquals("12 min", BydVehicle.decorate("12 min"));   // 140% rejected -> unchanged
}
@Test public void staleDataIsNotShown() {
    BydVehicle.onStatus(null, 62, 310, System.currentTimeMillis() - 16 * 60 * 1000L, "km");
    assertEquals("12 min", BydVehicle.decorate("12 min"));
}
@Test public void freshDataIsShown() {
    BydVehicle.onStatus(null, 62, 310, System.currentTimeMillis(), "km");
    assertEquals("12 min · 62% · 310 km", BydVehicle.decorate("12 min"));
}
@Test public void noProviderDataLeavesTextUnchanged() {
    assertEquals("12 min · 5.2 km", BydVehicle.decorate("12 min · 5.2 km"));
}
```

- [ ] **Step 2: Run to verify it fails** —
  `cd FORK && ./gradlew :extensions:maps-gearslip:testDebugUnitTest --tests '*BydVehicleTest'`

- [ ] **Step 3: Implement** `BydVehicle` using `ActivityThread.currentApplication()` reflection for `attach` when no
  context is passed, and `delegate` through `BydEtaText.decorate`. A stale cache triggers `attach` + an asynchronous
  `ContentResolver.query` on a single-thread executor; the UI path never waits for it.

- [ ] **Step 4: Run to verify it passes** — same command, expected PASS.

- [ ] **Step 5: Commit** — `git commit -m "feat(maps): read BYD vehicle status from byd-open-remote"`

---

### Task 7: Google sign-in through microG-RE

Sign-in cannot work with Google's own accounts on a re-signed build; this task puts the working path in place and
proves it before the vehicle data is wired in.

**Files:** none (patch selection and app installs only).

**Interfaces:**
- Consumes: the patched APK produced in Task 3.
- Produces: a Maps install that can sign in to a Google account, with the package name recorded for Task 8
  (`org.ungoogled.android.apps.maps.microg` when microG-RE support is selected).

- [ ] **Step 1: Install MicroG-RE 7.3.0** on the emulator (`microg-7.3.0-icon-arm64-v8a.apk` from the MicroG-RE
  release page). Keep Google Play services installed alongside it.
- [ ] **Step 2: Repatch with microG support enabled**, keeping the defaults plus
  `Add microG-RE support` and `Change package name`:

```bash
MORPHE="C:/Users/kenny/.config/opencode/tools/morphe"
"$MORPHE/jdk-21.0.13+9-jre/bin/java.exe" -jar "$MORPHE/morphe-desktop-2.2.0-all.jar" patch \
  -p FORK/patches/build/libs/<produced>.mpp \
  -e "Add microG-RE support" -e "Change package name" -e "BYD battery and range in the navigation card" \
  -o E:/Kenny/maps-analysis/maps-byd-microg.apk \
  E:/Kenny/maps-analysis/GoogleMaps_26.44.06.apk
```

- [ ] **Step 3: Install it** (`adb install -r`, or KingInstaller + Shizuku on a real phone) and record the resulting
  package name: `adb shell pm list packages | grep ungoogled`.

Expected: exactly one `org.ungoogled.android.apps.maps*` package; that string becomes the default
`vehicle status target package` in Task 8.

- [ ] **Step 4: Sign in** inside the patched Maps and confirm saved places appear on the account sheet.

Expected: sign-in completes and the account sheet shows the account. If Maps shows the uncertified-device screen,
record it — that is what `fangkampanat/gmaps-patches`' `Device certification fix` addresses, and a follow-up patch
would be needed.

- [ ] **Step 5: Record the outcome** in `E:\Kenny\maps-analysis\FINDINGS.md` (`## Sign-in` section): package name,
  MicroG-RE version, and the account-sheet result.

### Task 8: Wire live data into the patch and add the settings toggle

**Files:**
- Modify: `FORK/patches/src/main/kotlin/patches/googlemaps/car/BydEtaRangePatch.kt`
- Modify: `FORK/extensions/maps-gearslip/src/main/java/patches/maps/gearslip/BydVehicle.java`

**Interfaces:**
- Swaps `BydEtaText.decorateMarker` for `BydVehicle.decorate(Ljava/lang/String;)Ljava/lang/String;` in the injected code.

- [ ] **Step 1: Change the injected call** to `BydVehicle;->decorate(...)` and drop `decorateMarker` usage (keep the
  method only if a test still references it; otherwise delete it and its test).
- [ ] **Step 2: Add a manifest permission to the patched APK** so the provider's read permission can be granted:
  inject `<uses-permission android:name="com.bydopenremote.app.permission.READ_VEHICLE" />` into the manifest inside the
  same patch (follow the manifest-editing pattern already used in `NativeNavigationPatch.kt`).
- [ ] **Step 3: Rebuild and repatch**, then repeat Task 3 Step 5 install/DHU check with `byd-open-remote` running and
  signed in on the emulator. Expected: the card shows the live SoC/range, and shows nothing when the app is force-stopped.
- [ ] **Step 4: Commit** — `git commit -m "feat(maps): show live BYD battery and range in the navigation card"`

---

### Task 9: End-to-end verification and notes

- [ ] **Step 1: Verify each Review Focus line on the DHU**, one at a time, recording the observed text:
  provider absent → unchanged card; stale data → unchanged; "Rerouting…" → unchanged; `mi` unit → `192 mi`; ten
  consecutive card rebinds → no ANR and no duplicate receivers (`adb shell dumpsys activity broadcasts | grep byd`).
- [ ] **Step 2: Write the results** into `E:\Kenny\maps-analysis\FINDINGS.md` under a new `## Verification` section,
  with the Maps version, bundle commit, and the exact card text observed.
- [ ] **Step 3: Commit** — `git commit -m "docs: record BYD ETA card verification results"`

---

## Execution notes

- Every Maps-side change happens in FORK; every provider-side change happens in APP. Do not copy GPL code into APP.
- `Lbltz;` is an R8 name valid only for `26.44.06.1006895251`. Any Maps update invalidates Task 3's anchor; re-run
  Task 3 Step 4 before anything else after a version bump.
- The patched APK must be installed through KingInstaller + Shizuku with Google Play Store recorded as the installer,
  and the app ticked in Android Auto → Customize launcher, otherwise AA never lists it.
