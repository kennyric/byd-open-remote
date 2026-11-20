# Overdrive Web + Advanced Options — Implementation Plan (v2)

> **Execution window:** Do not start before **2026-11-20 11:01 GMT+7**.
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Lab trial mode, rename the WebView to **Overdrive**, hide its URL bar by default, and add an **Advanced** drawer screen that controls the Overdrive entry, car address, and default page from a top-5 preset list with "Load all" and manual URL entry.

**Architecture:** A new `AdvancedActivity` edits `SharedPreferences` through `CarWebPrefs`. `OverdrivePages` exposes a top-5 list plus the rest, `CarWebUrl.resolve` joins a base origin with a page path (no gateway fallback), and `CarWebActivity` decides whether to show the URL row or an empty state. `MainActivity` re-applies drawer visibility in `onResume()`.

**Tech Stack:** Java 17, Android SDK 34, `minSdk 26`, JUnit 4, Robolectric 4.13, Material 1.12, existing `SharedPreferences`/`WebView`.

**Spec:** `docs/superpowers/specs/2026-11-20-byd-overdrive-web-advanced-design.md`

## Global Constraints

- Lab mode is deleted completely: no `LabActivity`, `nav_lab`, `LabGate`, lab cloud helpers, lab tests, or `lab_*` strings.
- Advanced and Overdrive are visible when `!BuildConfig.DEMO_BUILD`; demo hides both.
- Default `show_overdrive_entry` is **false**.
- Default `hide_url_bar` is **true**.
- Blank car address means **no auto-load**; no Wi-Fi gateway detection.
- No new dependencies.
- Do not change `applicationId` or signing configuration.
- Work on `feat/byd-charging-lab`; do not merge.

## Shared Preferences

Name: `car_web`

| Key | Type | Default |
|---|---|---|
| `show_overdrive_entry` | boolean | `false` |
| `hide_url_bar` | boolean | `true` |
| `car_base_url` | string | `""` |
| `default_page` | string | `"/"` |

## Review Focus

- Lab is truly gone: no `nav_lab`, `LabGate`, lab screen, lab strings, or lab tests; full suite green — Task 0.
- Fresh install: Overdrive hidden, Advanced visible; enabling it restores Overdrive without restart — Tasks 2/4.
- Blank car address + relative page does nothing (empty state, no gateway auto-load) — Task 5.
- Top 5 presets, "Load all" expansion, and manual URL override all resolve correctly — Tasks 1/3.
- Demo build hides Advanced and Overdrive; standard build shows them — Tasks 3/4.

---

### Task 0: Remove Lab trial mode

**Files:**
- Delete:
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabActivity.java`
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabConsole.java`
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabVerdict.java`
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabRetryPolicy.java`
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabGate.java`
  - `android-companion/app/src/main/java/com/bydwatch/companion/lab/LabContext.java`
  - `android-companion/app/src/main/res/layout/activity_lab.xml`
  - `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/AcCurrentLimit.java`
  - `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/ChargeLimitValues.java`
  - `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/CloudCallTrace.java`
  - `android-companion/app/src/main/java/com/overdrive/app/byd/cloud/LabEndpointPolicy.java`
  - `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/AcCurrentLimitTest.java`
  - `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/BydCloudClientAcCurrentLimitTest.java`
  - `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/CloudCallTraceTest.java`
  - `android-companion/app/src/test/java/com/overdrive/app/byd/cloud/LabEndpointPolicyTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabConsoleTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabVerdictTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabReadTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabWriteTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabRawRunnerTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabGateTest.java`
  - `android-companion/app/src/test/java/com/bydwatch/companion/lab/LabContextTest.java`
- Modify: `BydCloudClient.java`, `MainActivity.java`, `drawer_menu.xml`, `AndroidManifest.xml`, `strings.xml`, `values-in/strings.xml`, `CarWebActivity.java`
- Rename: `android-companion/app/src/test/java/com/bydwatch/companion/ui/LabEntryTest.java` → `AdvancedEntryTest.java` (class name too)

**Interfaces:**
- Consumes: existing `CarWebActivity`.
- Produces: no Lab symbols; `CarWebActivity` gated by `!BuildConfig.DEMO_BUILD`.

- [ ] **Step 1: Delete the Lab files and tests**

Use `git rm` for the files listed above. Remove the Lab block from `BydCloudClient` (`AcCurrentLimitResult`, `callControlEndpoint`, `readAcCurrentLimit`, `writeAcCurrentLimit`, `copyJson`) and any now-unused imports. Remove `nav_lab` from the drawer and `MainActivity`, and delete the `LabActivity` manifest entry.

- [ ] **Step 2: Replace the gate**

In `CarWebActivity.onCreate`, replace `LabGate.isEnabled(...)` with:
```java
if (BuildConfig.DEMO_BUILD) {
    super.onCreate(savedInstanceState);
    finish();
    return;
}
```
In `MainActivity`, set `nav_car_web` visibility to `!BuildConfig.DEMO_BUILD` for now (Task 4 will add the preference).

Remove all `lab_*` and `nav_lab` string resources from both language files.

- [ ] **Step 3: Keep the drawer tests for Car Web**

Rename `LabEntryTest` to `AdvancedEntryTest`; delete the Lab test method; keep
`drawerShowsCarWebAndOpensActivity` and rename it to Overdrive terminology.
Add `@After BydWatchApp.resetForTesting()` if not present.

- [ ] **Step 4: Run the full suite**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest`
Expected: PASS, with no references to `nav_lab` or `LabGate`.

Also run:
`grep -rn "nav_lab\|LabGate\|LabActivity\|lab_" android-companion/app/src/main`
Expected: no matches.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove lab trial mode"
```

---

### Task 1: Overdrive page presets and URL resolution

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdrivePages.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdrivePagesTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java`

**Interfaces:**
- Consumes: existing `CarWebUrl.normalize`.
- Produces:
  - `OverdrivePages.Page` with public final `String label`, `String path`.
  - `OverdrivePages.topPages() -> List<Page>` (exactly 5)
  - `OverdrivePages.morePages() -> List<Page>` (everything else)
  - `OverdrivePages.allPages() -> List<Page>` (top + more)
  - `CarWebUrl.resolve(String base, String page) -> String`

- [ ] **Step 1: Write the failing tests**

```java
package com.bydwatch.companion.lab;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OverdrivePagesTest {

    @Test
    public void topFiveAreTheUsefulDefaults() {
        List<OverdrivePages.Page> top = OverdrivePages.topPages();
        assertEquals(5, top.size());
        assertEquals("/vehicle-control.html", top.get(0).path);
        assertEquals("/events.html", top.get(1).path);
        assertEquals("/charging.html", top.get(2).path);
        assertEquals("/trips.html", top.get(3).path);
        assertEquals("/live-view.html", top.get(4).path);
    }

    @Test
    public void morePagesContainDashboardAndSystemPages() {
        assertTrue(OverdrivePages.morePages().stream()
                .anyMatch(p -> "/".equals(p.path)));
        assertTrue(OverdrivePages.morePages().stream()
                .anyMatch(p -> "/byd-cloud.html".equals(p.path)));
    }

    @Test
    public void allLabelsAndPathsAreUnique() {
        Set<String> labels = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (OverdrivePages.Page page : OverdrivePages.allPages()) {
            assertTrue(labels.add(page.label));
            assertTrue(paths.add(page.path));
            assertTrue(page.path.startsWith("/"));
        }
        assertEquals(5 + OverdrivePages.morePages().size(),
                OverdrivePages.allPages().size());
    }
}
```

```java
    @Test
    public void resolvesRelativePagesAgainstBase() {
        assertEquals("http://192.168.1.50:8080/vehicle-control.html",
                CarWebUrl.resolve("192.168.1.50", "vehicle-control.html"));
        assertEquals("http://192.168.1.50:8080/events.html",
                CarWebUrl.resolve("http://192.168.1.50:8080", "/events.html"));
        assertEquals("http://192.168.1.50:8080/",
                CarWebUrl.resolve("http://192.168.1.50:8080", "/"));
    }

    @Test
    public void absolutePageOverridesBase() {
        assertEquals("http://10.0.0.5:9090/events.html",
                CarWebUrl.resolve("http://192.168.1.50:8080", "http://10.0.0.5:9090/events.html"));
    }

    @Test
    public void relativePageWithoutBaseIsEmpty() {
        assertEquals("", CarWebUrl.resolve("", "events.html"));
        assertEquals("", CarWebUrl.resolve(null, "events.html"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.OverdrivePagesTest" --tests "com.bydwatch.companion.lab.CarWebUrlTest"`
Expected: FAIL — `OverdrivePages` / `resolve` missing.

- [ ] **Step 3: Implement**

`OverdrivePages.topPages()`:
1. Vehicle Control `/vehicle-control.html`
2. Events `/events.html`
3. Charging `/charging.html`
4. Trips `/trips.html`
5. Live View `/live-view.html`

`morePages()`:
Dashboard `/`, Automations `/automations.html`, Parking `/parking.html`,
Recordings `/recording.html`, Widgets `/widgets.html`, Network `/network.html`,
BYD Cloud `/byd-cloud.html`, Remote Access `/remote-access.html`, About
`/about.html`, Module Faults `/module-faults.html`, Seat Positions
`/seat-positions.html`, Sessions `/sessions.html`, Daemons `/daemons.html`,
Communication `/communicate.html`, Telegram `/telegram.html`, MQTT
`/mqtt.html`, WireGuard `/wireguard.html`, ABRP `/abrp.html`, Road Sense
`/road-sense.html`, SafeKeep `/safekeep.html`, Storage `/storage.html`,
Factory DVR `/factory-dvr.html`, Key Mapping `/key-mapping.html`,
Notifications `/notifications.html`, Performance `/performance.html`,
Surveillance `/surveillance.html`.

`CarWebUrl.resolve`:
- If `page` is an absolute `http(s)://` URL, return `normalize(page)`.
- Normalize `base`; return `""` when empty.
- Strip a leading `/` from `page`; build `scheme://host:port/` + page.
- Preserve query strings.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.OverdrivePagesTest" --tests "com.bydwatch.companion.lab.CarWebUrlTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdrivePages.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdrivePagesTest.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java
git commit -m "feat(overdrive): add top-five page presets and URL resolver"
```

---

### Task 2: Preference wrapper

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java`

**Interfaces:**
- Consumes: `Context`, `SharedPreferences`.
- Produces:
  - `CarWebPrefs.showOverdrive(Context) -> boolean` (default `false`)
  - `CarWebPrefs.setShowOverdrive(Context, boolean)`
  - `CarWebPrefs.hideUrlBar(Context) -> boolean` (default `true`)
  - `CarWebPrefs.setHideUrlBar(Context, boolean)`
  - `CarWebPrefs.baseUrl(Context) -> String` (default `""`)
  - `CarWebPrefs.setBaseUrl(Context, String)`
  - `CarWebPrefs.defaultPage(Context) -> String` (default `"/"`)
  - `CarWebPrefs.setDefaultPage(Context, String)`
  - `CarWebPrefs.reset(Context)`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CarWebPrefsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void defaultsAreHiddenOverdriveAndHiddenUrlBar() {
        assertFalse(CarWebPrefs.showOverdrive(context));
        assertTrue(CarWebPrefs.hideUrlBar(context));
        assertEquals("", CarWebPrefs.baseUrl(context));
        assertEquals("/", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void settersPersist() {
        CarWebPrefs.setShowOverdrive(context, true);
        CarWebPrefs.setHideUrlBar(context, false);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/vehicle-control.html");

        assertTrue(CarWebPrefs.showOverdrive(context));
        assertFalse(CarWebPrefs.hideUrlBar(context));
        assertEquals("http://192.168.1.50:8080", CarWebPrefs.baseUrl(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest"`
Expected: FAIL — `CarWebPrefs` missing.

- [ ] **Step 3: Implement**

Use `context.getApplicationContext().getSharedPreferences("car_web", MODE_PRIVATE)`.
Getters use the defaults above; setters persist; `reset` clears only the four
keys.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java
git commit -m "feat(overdrive): add web preferences with hidden defaults"
```

---

### Task 3: Advanced screen with top 5, Load all, and manual URL

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/AdvancedActivity.java`
- Create: `android-companion/app/src/main/res/layout/activity_advanced.xml`
- Modify: `AndroidManifest.xml`, `drawer_menu.xml`, `strings.xml`, `values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/AdvancedActivityTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs`, `OverdrivePages`, `CarWebUrl`.
- Produces:
  - `AdvancedActivity`
  - `R.id.nav_advanced`
  - Views: `switchAdvShowOverdrive`, `switchAdvHideUrlBar`, `etAdvBaseUrl`,
    `rgAdvTopPages`, `btnAdvLoadAll`, `layoutAdvMorePages`, `rgAdvMorePages`,
    `etAdvManualUrl`, `tvAdvPreview`, `btnAdvSave`, `btnAdvReset`, `btnAdvBack`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdvancedActivityTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void savingTopPagePersistsAndFinishes() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        SwitchMaterial show = activity.findViewById(
                com.bydwatch.companion.R.id.switchAdvShowOverdrive);
        show.setChecked(true);

        android.widget.RadioButton vehicle = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvVehicleControl);
        vehicle.setChecked(true);

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertTrue(CarWebPrefs.showOverdrive(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void manualUrlOverridesPreset() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText manual = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvManualUrl);
        manual.setText("http://192.168.1.50:8080/events.html");

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals("http://192.168.1.50:8080/events.html",
                CarWebPrefs.defaultPage(context));
    }

    @Test
    public void loadAllRevealsMorePages() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        android.view.View more = activity.findViewById(
                com.bydwatch.companion.R.id.layoutAdvMorePages);
        assertEquals(android.view.View.GONE, more.getVisibility());

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvLoadAll).performClick();
        assertEquals(android.view.View.VISIBLE, more.getVisibility());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.AdvancedActivityTest"`
Expected: FAIL — `AdvancedActivity` missing.

- [ ] **Step 3: Implement**

`AdvancedActivity`:
- Gate with `if (BuildConfig.DEMO_BUILD) { finish(); return; }`.
- Bind views; initialize switches from `CarWebPrefs`.
- Base field: prefill `CarWebPrefs.baseUrl`.
- Top 5: a `RadioGroup` with five `RadioButton`s, IDs
  `rbAdvVehicleControl`, `rbAdvEvents`, `rbAdvCharging`, `rbAdvTrips`,
  `rbAdvLiveView`.
- `layoutAdvMorePages` starts `GONE`; `btnAdvLoadAll` toggles it visible and
  updates its text to "Show top 5" / "Load all pages".
- More pages: a second `RadioGroup` populated programmatically from
  `OverdrivePages.morePages()`; selecting one clears the top group and manual
  field. Selecting a top preset clears the more group and manual field.
- Manual URL field: typing any non-empty value clears both radio groups.
- Preview updates on every change:
  `CarWebUrl.resolve(base, selectedPathOrManual)`.
- Save:
  - `base = CarWebUrl.normalize(etAdvBaseUrl)` (empty allowed).
  - `page = manual non-empty ? manual : selected preset path`, default `/`.
  - Persist show, hide, base, page; finish.
- Reset: restore form defaults (show off, hide on, base empty, `/`, no manual);
  do not save until Save.

Layout:
- `NestedScrollView` → vertical `LinearLayout`, card styling from the app.
- Header with back, title "Advanced".
- Overdrive card with the two switches.
- Car address `EditText` + helper text "Blank does nothing — no auto-load."
- Default page card: top-5 radio list, "Load all pages" button, hidden more
  pages container, manual URL field.
- Preview monospace text.
- Save / Reset in a sticky footer so they remain visible while the page list scrolls.

Manifest: `.lab.AdvancedActivity`, `exported=false`, label
`@string/advanced_title`.

Drawer: add `nav_advanced` (tune icon).

Strings (EN + ID): `nav_advanced`, `advanced_title`, `advanced_subtitle`,
`advanced_overdrive_section`, `advanced_show_overdrive`,
`advanced_hide_url_bar`, `advanced_car_address`,
`advanced_car_address_hint`, `advanced_car_address_help`,
`advanced_default_page`, `advanced_load_all`, `advanced_show_top5`,
`advanced_manual_url`, `advanced_manual_url_hint`, `advanced_preview`,
`advanced_save`, `advanced_reset`, `advanced_back`, `nav_overdrive`,
`overdrive_title`, `overdrive_error`, `overdrive_set_address`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.AdvancedActivityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/AdvancedActivity.java \
        android-companion/app/src/main/res/layout/activity_advanced.xml \
        android-companion/app/src/main/AndroidManifest.xml \
        android-companion/app/src/main/res/menu/drawer_menu.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/AdvancedActivityTest.java
git commit -m "feat(overdrive): add advanced screen with presets and manual url"
```

---

### Task 4: Drawer visibility, rename, and demo gate

**Files:**
- Modify: `MainActivity.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/ui/AdvancedEntryTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs.showOverdrive`, `BuildConfig.DEMO_BUILD`, `AdvancedActivity`.
- Produces: `MainActivity.applyStandardMenuVisibility()` called from
  `bindViews()` and `onResume()`.

- [ ] **Step 1: Write the failing test**

Add to `AdvancedEntryTest`:

```java
@Test
public void freshInstallHidesOverdriveButShowsAdvanced() {
    BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
    store.setUsername("test@byd.id");
    store.setPassword("ValidPass123");
    store.setPin("123456");
    store.setRegion("id");
    store.setVin("VIN_TEST");
    BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
    BydRepository repository = new BydRepository(gateway, store);
    BydWatchApp.setTestDependencies(store, gateway, repository);
    android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
    CarWebPrefs.reset(context);

    MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    NavigationView nav = activity.findViewById(R.id.navView);

    assertTrue(nav.getMenu().findItem(R.id.nav_advanced).isVisible());
    assertFalse(nav.getMenu().findItem(R.id.nav_car_web).isVisible());
}

@Test
public void enablingOverdriveShowsIt() {
    BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
    store.setUsername("test@byd.id");
    store.setPassword("ValidPass123");
    store.setPin("123456");
    store.setRegion("id");
    store.setVin("VIN_TEST");
    BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
    BydRepository repository = new BydRepository(gateway, store);
    BydWatchApp.setTestDependencies(store, gateway, repository);
    android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
    CarWebPrefs.reset(context);
    CarWebPrefs.setShowOverdrive(context, true);

    MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    NavigationView nav = activity.findViewById(R.id.navView);

    assertTrue(nav.getMenu().findItem(R.id.nav_car_web).isVisible());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.AdvancedEntryTest"`
Expected: FAIL — Overdrive visibility still unconditional.

- [ ] **Step 3: Implement**

- Add `applyStandardMenuVisibility()`:
  - `boolean standard = !BuildConfig.DEMO_BUILD;`
  - `nav_advanced` visible = `standard`.
  - `nav_car_web` visible = `standard && CarWebPrefs.showOverdrive(this)`.
- Call from `bindViews()` and `onResume()`.
- Listener for `nav_advanced` starts `AdvancedActivity`.
- Drawer title for `nav_car_web` uses `@string/nav_overdrive`.
- Ensure `nav_lab` is gone.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.AdvancedEntryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java \
        android-companion/app/src/main/res/menu/drawer_menu.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/ui/AdvancedEntryTest.java
git commit -m "feat(overdrive): gate drawer entries and rename to overdrive"
```

---

### Task 5: Overdrive screen behavior

**Files:**
- Modify: `CarWebActivity.java`, `activity_car_web.xml`, `strings.xml`, `values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebActivityTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs`, `CarWebUrl.resolve`.
- Produces:
  - `R.id.rowCarWebUrl`
  - `R.id.layoutOverdriveEmpty`, `R.id.btnOverdriveAdvanced`
  - behavior below.

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CarWebActivityTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void hideUrlBarDefaultsToGone() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/events.html");
        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        assertEquals(View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl)
                        .getVisibility());
    }

    @Test
    public void blankAddressAndRelativePageShowsEmptyState() {
        CarWebPrefs.setBaseUrl(context, "");
        CarWebPrefs.setDefaultPage(context, "/events.html");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();

        assertEquals(View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.layoutOverdriveEmpty)
                        .getVisibility());
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        assertEquals(null, Shadows.shadowOf(webView).getLastLoadedUrl());
    }

    @Test
    public void baseAndPageLoadResolvedUrl() {
        CarWebPrefs.setHideUrlBar(context, false);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/events.html");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        assertEquals(View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl)
                        .getVisibility());

        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        String loaded = Shadows.shadowOf(webView).getLastLoadedUrl();
        assertTrue("loaded=" + loaded,
                loaded != null && loaded.contains("192.168.1.50:8080/events.html"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebActivityTest"`
Expected: FAIL — empty-state views missing; gateway fallback still present.

- [ ] **Step 3: Implement**

`activity_car_web.xml`:
- Give the URL row `android:id="@+id/rowCarWebUrl"`.
- Add an empty-state container `layoutOverdriveEmpty` (centered text + button
  `btnOverdriveAdvanced`), initially `GONE`.
- Keep `webCar` and the toolbar.

`CarWebActivity`:
- Gate with `if (BuildConfig.DEMO_BUILD) { finish(); return; }`.
- Remove `findGateway()` and all gateway usage.
- `base = CarWebPrefs.baseUrl(this)`, `page = CarWebPrefs.defaultPage(this)`.
- `url = CarWebUrl.resolve(base, page)`.
- If `hideUrlBar`: `rowCarWebUrl` `GONE`; else `VISIBLE`.
- If `url` is empty:
  - `layoutOverdriveEmpty` `VISIBLE`; `webCar` `GONE` or empty; do not load.
  - `btnOverdriveAdvanced` starts `AdvancedActivity`.
  - If the URL bar is visible, keep it available.
- If `url` is non-empty: `layoutOverdriveEmpty` `GONE`, `webCar` `VISIBLE`,
  load `url`.
- The URL bar remains a temporary override; do not persist.
- Rename title/labels to Overdrive.
- Keep back/reload/WebView client.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebActivityTest"`
Expected: PASS.

- [ ] **Step 5: Run the full suite**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebActivity.java \
        android-companion/app/src/main/res/layout/activity_car_web.xml \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebActivityTest.java
git commit -m "feat(overdrive): honor presets, blank address, and url bar setting"
```

---

### Task 6: Build and publish

- [ ] **Step 1: Build**

Run: `cd android-companion && ./gradlew.bat :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify signature**

Run:
`"D:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`
Expected: `D1670D073C1A8542B780802ADFCC41DE58875BC598ADCC7644D8CC8F155CB899`.

- [ ] **Step 3: Publish**

Copy to `C:\Users\kenny\AppData\Local\Temp\opencode\dist\BYD-Open-Remote-Lab-trial.apk`
and `E:\Kenny\Huawei\BYD-Open-Remote-Lab-trial.apk`.

Verify:
`curl -s -I http://192.168.1.109:8091/BYD-Open-Remote-Lab-trial.apk`
Expected: `200 OK`.

---

## Manual Acceptance

1. Fresh install: drawer shows **Advanced**, no **Overdrive**, no **Lab**.
2. Advanced → turn **Show Overdrive in menu** on → back → Overdrive appears.
3. Advanced → **Car address** blank, **Vehicle Control** selected → Save →
   Overdrive shows the empty state and an Advanced link (no auto-load).
4. Set car address `http://<car-ip>:8080` → Save → Overdrive loads Vehicle
   Control without a URL bar.
5. Advanced → **Events** → Save → Overdrive loads `/events.html`.
6. Advanced → **Load all pages** → Dashboard and system pages appear.
7. Advanced → manual `http://<car-ip>:8080/recording.html` → Save → Overdrive
   loads it; manual overrides the preset.
8. Advanced → **Hide URL bar** off → Overdrive shows the URL row.
9. Demo build: Advanced and Overdrive absent.

## Self-Review Notes

- Spec coverage: Lab removal (Task 0), top-5 presets (Task 1), default-off
  preference (Task 2), Advanced top-5/Load all/manual URL (Task 3), drawer
  visibility/rename/demo gate (Task 4), blank-address empty state and hidden
  URL bar (Task 5), build/publish (Task 6).
- Review Focus tests map to Tasks 0, 2/4, 5, 1/3, 3/4.
- No cloud calls, no gateway detection, no new dependencies.
- Execution must wait until 2026-11-20 11:01 GMT+7.
