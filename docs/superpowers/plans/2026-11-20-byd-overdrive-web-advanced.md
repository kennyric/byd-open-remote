# Overdrive Web + Advanced Options — Implementation Plan

> **Execution window:** Do not start before **2026-11-20 11:01 GMT+7**. This plan is written for execution outside peak hours.
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the trial Car Web screen to **Overdrive**, hide its URL bar by default, and add an **Advanced** drawer screen that controls the Overdrive entry, URL bar visibility, car address, and default page from a curated Overdrive page list.

**Architecture:** A new `AdvancedActivity` edits `SharedPreferences` through a small `CarWebPrefs` wrapper. `OverdrivePages` supplies the curated path/label list, `CarWebUrl.resolve` joins a base origin with a page path, and `CarWebActivity` reads the preferences to decide whether to show the URL row and which page to load. `MainActivity` re-applies drawer visibility in `onResume()` so changes take effect immediately.

**Tech Stack:** Java 17, Android SDK 34, `minSdk 26`, JUnit 4, Robolectric 4.13, Material 1.12, existing `SharedPreferences`/`WebView`.

**Spec:** `docs/superpowers/specs/2026-11-20-byd-overdrive-web-advanced-design.md`

## Global Constraints

- Trial gate stays `BuildConfig.DEBUG && !BuildConfig.DEMO_BUILD`; release and demo must not show Advanced or Overdrive.
- Cleartext HTTP remains allowed only through `app/src/debug/AndroidManifest.xml`.
- No new dependencies.
- Do not change `applicationId` or signing configuration.
- No BYD-cloud calls in this change; this is only the Overdrive WebView bridge.
- Work on `feat/byd-charging-lab` (the current branch). Do not merge.
- Existing `CarWebActivity` class name may stay; all user-facing strings become "Overdrive".

## Shared Preferences

Name: `car_web`

| Key | Type | Default |
|---|---|---|
| `show_overdrive_entry` | boolean | `true` |
| `hide_url_bar` | boolean | `true` |
| `car_base_url` | string | `""` |
| `default_page` | string | `"/"` |

The old `car_web_url` key is not migrated; the new default page is `/`.

## Review Focus

- Turning "Show Overdrive in menu" off must hide **Overdrive** but keep **Advanced** reachable; turning it back on restores it without an app restart — Task 4 tests this.
- "Hide URL bar" defaults to true and the URL row must actually be `GONE`; when false it is visible — Task 5 tests this.
- A preset path must resolve against the car address or Wi-Fi gateway; an absolute custom URL must override both — Task 1 tests resolution.
- Fresh installs must get show=true, hide=true, base empty, page `/` — Task 2 tests defaults.
- Release/demo must not expose Advanced or Overdrive — Task 3/4 tests the gate.

---

### Task 1: Overdrive page presets and URL resolution

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdrivePages.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdrivePagesTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java`

**Interfaces:**
- Consumes: existing `CarWebUrl.normalize`, `CarWebUrl.fromGateway`.
- Produces:
  - `OverdrivePages.Page` with public final `String label`, `String path`.
  - `OverdrivePages.pages() -> List<Page>`
  - `OverdrivePages.labels() -> String[]`
  - `OverdrivePages.pathForLabel(String label) -> String`
  - `CarWebUrl.resolve(String base, String page) -> String`

- [ ] **Step 1: Write the failing tests**

```java
package com.bydwatch.companion.lab;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OverdrivePagesTest {

    @Test
    public void includesTheImportantPages() {
        List<OverdrivePages.Page> pages = OverdrivePages.pages();
        assertTrue(pages.stream().anyMatch(p -> "/vehicle-control.html".equals(p.path)));
        assertTrue(pages.stream().anyMatch(p -> "/events.html".equals(p.path)));
        assertEquals("/", OverdrivePages.pathForLabel("Dashboard"));
        assertEquals("/vehicle-control.html", OverdrivePages.pathForLabel("Vehicle Control"));
    }

    @Test
    public void labelsAndPathsAreUniqueAndWellFormed() {
        Set<String> labels = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (OverdrivePages.Page page : OverdrivePages.pages()) {
            assertFalse(page.label.isEmpty());
            assertTrue(page.path.startsWith("/"));
            assertTrue("duplicate label " + page.label, labels.add(page.label));
            assertTrue("duplicate path " + page.path, paths.add(page.path));
        }
        assertEquals(OverdrivePages.pages().size(), OverdrivePages.labels().length);
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
Expected: FAIL — `OverdrivePages` missing and `resolve` missing.

- [ ] **Step 3: Implement**

`OverdrivePages` holds an immutable ordered list of `Page` objects:

Primary: Dashboard `/`, Vehicle Control `/vehicle-control.html`, Events `/events.html`,
Charging `/charging.html`, Automations `/automations.html`, Trips `/trips.html`,
Parking `/parking.html`, Live View `/live-view.html`, Recordings `/recording.html`,
Widgets `/widgets.html`.

System/integrations: Network `/network.html`, BYD Cloud `/byd-cloud.html`,
Remote Access `/remote-access.html`, About `/about.html`, Module Faults
`/module-faults.html`, Seat Positions `/seat-positions.html`, Sessions
`/sessions.html`, Daemons `/daemons.html`, Communication `/communicate.html`,
Telegram `/telegram.html`, MQTT `/mqtt.html`, WireGuard `/wireguard.html`,
ABRP `/abrp.html`, Road Sense `/road-sense.html`, SafeKeep `/safekeep.html`,
Storage `/storage.html`, Factory DVR `/factory-dvr.html`, Key Mapping
`/key-mapping.html`, Notifications `/notifications.html`, Performance
`/performance.html`, Surveillance `/surveillance.html`.

`pathForLabel` returns the matching path, or `/` when the label is unknown.

`CarWebUrl.resolve(base, page)`:
1. Normalize `page` as an absolute URL first; if it starts with `http://` or `https://`, return `normalize(page)`.
2. Normalize `base`; return `""` when it is empty.
3. Strip a leading `/` from `page` and build `scheme://host:port/` + page.
4. Preserve query strings on the page.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.OverdrivePagesTest" --tests "com.bydwatch.companion.lab.CarWebUrlTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdrivePages.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdrivePagesTest.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java
git commit -m "feat(overdrive): add page presets and URL resolver"
```

---

### Task 2: Preference wrapper

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java`

**Interfaces:**
- Consumes: `android.content.Context`, `SharedPreferences`.
- Produces:
  - `CarWebPrefs.showOverdrive(Context) -> boolean`
  - `CarWebPrefs.setShowOverdrive(Context, boolean)`
  - `CarWebPrefs.hideUrlBar(Context) -> boolean`
  - `CarWebPrefs.setHideUrlBar(Context, boolean)`
  - `CarWebPrefs.baseUrl(Context) -> String`
  - `CarWebPrefs.setBaseUrl(Context, String)`
  - `CarWebPrefs.defaultPage(Context) -> String`
  - `CarWebPrefs.setDefaultPage(Context, String)`
  - `CarWebPrefs.reset(Context)`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
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
    public void defaultsAreShowHideAndDashboard() {
        assertTrue(CarWebPrefs.showOverdrive(context));
        assertTrue(CarWebPrefs.hideUrlBar(context));
        assertEquals("", CarWebPrefs.baseUrl(context));
        assertEquals("/", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void settersPersist() {
        CarWebPrefs.setShowOverdrive(context, false);
        CarWebPrefs.setHideUrlBar(context, false);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/vehicle-control.html");

        assertFalse(CarWebPrefs.showOverdrive(context));
        assertFalse(CarWebPrefs.hideUrlBar(context));
        assertEquals("http://192.168.1.50:8080", CarWebPrefs.baseUrl(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void resetRestoresDefaults() {
        CarWebPrefs.setShowOverdrive(context, false);
        CarWebPrefs.reset(context);
        assertTrue(CarWebPrefs.showOverdrive(context));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest"`
Expected: FAIL — `CarWebPrefs` missing.

- [ ] **Step 3: Implement `CarWebPrefs`**

Use `context.getApplicationContext().getSharedPreferences("car_web", Context.MODE_PRIVATE)`.
Getters use the defaults from the table; setters apply; `reset` clears the four
keys (do not clear unrelated keys).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java
git commit -m "feat(overdrive): add web preferences"
```

---

### Task 3: Advanced screen

**Files:**
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/AdvancedActivity.java`
- Create: `android-companion/app/src/main/res/layout/activity_advanced.xml`
- Modify: `android-companion/app/src/main/AndroidManifest.xml`
- Modify: `android-companion/app/src/main/res/menu/drawer_menu.xml`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/AdvancedActivityTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs`, `OverdrivePages`, `CarWebUrl`.
- Produces:
  - `R.layout.activity_advanced`
  - `R.id.nav_advanced`
  - `AdvancedActivity`
  - Views: `switchAdvShowOverdrive`, `switchAdvHideUrlBar`, `etAdvBaseUrl`,
    `spAdvPage`, `etAdvCustomPage`, `tvAdvPreview`, `btnAdvSave`,
    `btnAdvReset`, `btnAdvBack`

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
    public void savingTogglesPersistsAndFinishes() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        SwitchMaterial show = activity.findViewById(
                com.bydwatch.companion.R.id.switchAdvShowOverdrive);
        SwitchMaterial hide = activity.findViewById(
                com.bydwatch.companion.R.id.switchAdvHideUrlBar);
        show.setChecked(false);
        hide.setChecked(false);

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertFalse(CarWebPrefs.showOverdrive(context));
        assertFalse(CarWebPrefs.hideUrlBar(context));
        assertTrue(activity.isFinishing());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.AdvancedActivityTest"`
Expected: FAIL — `AdvancedActivity` missing.

- [ ] **Step 3: Implement the screen**

`AdvancedActivity`:
- Gate with `LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD)`; finish otherwise.
- Bind the views listed above.
- Initialize switches from `CarWebPrefs`.
- Populate `spAdvPage` from `OverdrivePages.labels()` plus `"Custom…"`.
- If the saved `default_page` is one of the preset paths, select that label;
  otherwise select Custom and fill `etAdvCustomPage` with the saved value.
- When the spinner changes to a preset, hide the custom field and update the
  preview with `CarWebUrl.resolve(base, presetPath)`. When Custom is selected,
  show the field and resolve its text.
- `btnAdvReset` restores defaults in the form (does not save until Save).
- `btnAdvSave`:
  - `base = CarWebUrl.normalize(etAdvBaseUrl)` (empty allowed).
  - `page = selected preset path`, or the custom field when Custom.
  - If page is empty, use `/`.
  - Persist all four keys and call `finish()`.
- `btnAdvBack` finishes without saving.

`activity_advanced.xml`:
- `NestedScrollView` → vertical `LinearLayout`, same padding/card style as
  `activity_lab.xml`.
- Header with back button, title "Advanced", and a short subtitle.
- A `MaterialCardView` "Overdrive" containing the two `SwitchMaterial`s.
- "Car address" `EditText`.
- "Default page" `Spinner` + custom `EditText` (hidden by default).
- Preview `TextView` (monospace, selectable).
- Save and Reset buttons.

Manifest: add `.lab.AdvancedActivity` with `exported="false"` and label
`@string/advanced_title`.

Drawer: add `nav_advanced` after `nav_car_web` with `@string/nav_advanced` and
the existing tune icon.

Strings (English + Indonesian):
`nav_advanced`, `advanced_title`, `advanced_subtitle`, `advanced_overdrive_section`,
`advanced_show_overdrive`, `advanced_hide_url_bar`, `advanced_car_address`,
`advanced_car_address_hint`, `advanced_default_page`, `advanced_custom`,
`advanced_custom_hint`, `advanced_preview`, `advanced_save`, `advanced_reset`,
`advanced_back`, `nav_overdrive` (rename of `nav_car_web`), `overdrive_title`,
`overdrive_error`.

Rename existing user-facing "Car Web" strings to "Overdrive"; keep the
`nav_car_web` ID for compatibility.

- [ ] **Step 4: Run test to verify it passes**

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
git commit -m "feat(overdrive): add advanced settings screen"
```

---

### Task 4: Drawer visibility and rename

**Files:**
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/ui/LabEntryTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs.showOverdrive`, `LabGate.isEnabled`, `AdvancedActivity`.
- Produces: `MainActivity.applyTrialMenuVisibility()` called from `bindViews()`
  and `onResume()`.

- [ ] **Step 1: Write the failing test**

Add to `LabEntryTest`:

```java
@Test
public void advancedIsAlwaysVisibleAndOverdriveFollowsPreference() {
    BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
    store.setUsername("test@byd.id");
    store.setPassword("ValidPass123");
    store.setPin("123456");
    store.setRegion("id");
    store.setVin("VIN_TEST");
    BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
    BydRepository repository = new BydRepository(gateway, store);
    BydWatchApp.setTestDependencies(store, gateway, repository);
    android.content.Context context =
            org.robolectric.RuntimeEnvironment.getApplication();
    CarWebPrefs.reset(context);
    CarWebPrefs.setShowOverdrive(context, false);

    MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
    NavigationView nav = activity.findViewById(R.id.navView);

    assertNotNull(nav.getMenu().findItem(R.id.nav_advanced));
    assertTrue(nav.getMenu().findItem(R.id.nav_advanced).isVisible());
    assertFalse(nav.getMenu().findItem(R.id.nav_car_web).isVisible());
}
```

Use `RuntimeEnvironment.getApplication()` for the test context; add
the imports. Reset preferences in `@After`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.LabEntryTest"`
Expected: FAIL — `nav_advanced` missing and visibility not applied.

- [ ] **Step 3: Implement**

- Add `nav_advanced` visibility in the existing drawer setup.
- Extract `applyTrialMenuVisibility()`:
  - `boolean enabled = LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD);`
  - `nav_advanced` visible = enabled.
  - `nav_car_web` visible = enabled && `CarWebPrefs.showOverdrive(this)`.
- Call it from `bindViews()` after `navView` binding and from `onResume()`.
- In the navigation listener, handle `nav_advanced` by starting
  `AdvancedActivity` (same pattern as `nav_lab`).
- Rename the drawer title through `nav_overdrive` / "Overdrive".

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.LabEntryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java \
        android-companion/app/src/test/java/com/bydwatch/companion/ui/LabEntryTest.java
git commit -m "feat(overdrive): wire advanced menu and visibility"
```

---

### Task 5: Overdrive screen reads advanced settings

**Files:**
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebActivity.java`
- Modify: `android-companion/app/src/main/res/layout/activity_car_web.xml`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebActivityTest.java`

**Interfaces:**
- Consumes: `CarWebPrefs`, `CarWebUrl.resolve`, `OverdrivePages`.
- Produces: `R.id.rowCarWebUrl`; behavior described below.

- [ ] **Step 1: Write the failing test**

```java
package com.bydwatch.companion.lab;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

import org.robolectric.RuntimeEnvironment;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
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
        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class)
                .setup().get();
        assertEquals(View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.rowCarWebUrl)
                        .getVisibility());
    }

    @Test
    public void shownUrlBarIsVisibleAndLoadsResolvedPage() {
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

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebActivityTest"`
Expected: FAIL — `rowCarWebUrl` missing and preferences unused.

- [ ] **Step 3: Implement**

`activity_car_web.xml`: give the URL-row `LinearLayout` the id `rowCarWebUrl`.

`CarWebActivity`:
- Rename title/labels to Overdrive via strings.
- On create, `boolean hideBar = CarWebPrefs.hideUrlBar(this)`.
- Resolve the page:
  - `String page = CarWebPrefs.defaultPage(this);`
  - `String base = CarWebPrefs.baseUrl(this);`
  - If `base` is empty, use `findGateway()`.
  - `String url = CarWebUrl.resolve(base, page);`
- If `hideBar` and `url` is non-empty: set `rowCarWebUrl` `GONE`; load `url`.
- If `hideBar` and `url` is empty: force `rowCarWebUrl` `VISIBLE`, focus the field,
  show `overdrive_error`/`car_web_no_url`.
- If `!hideBar`: show the row, pre-fill it with `url`, load it.
- The URL bar is a temporary override; do not write it back to preferences.
- Keep back/reload behavior and the WebView client.

Strings: replace `car_web_title` usage with `overdrive_title`; add
`overdrive_error` if needed. Keep the activity label in the manifest as
`@string/overdrive_title`.

- [ ] **Step 4: Run test to verify it passes**

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
git commit -m "feat(overdrive): honor advanced web settings"
```

---

### Task 6: Build, verify, and publish the trial APK

**Files:**
- No source changes.

- [ ] **Step 1: Build the debug APK**

Run: `cd android-companion && ./gradlew.bat :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL; artifact at
`app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`.

- [ ] **Step 2: Verify the canonical signature**

Run:
`"D:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`
Expected: SHA-256 `D1670D073C1A8542B780802ADFCC41DE58875BC598ADCC7644D8CC8F155CB899`.

- [ ] **Step 3: Publish to the preview server**

Copy the APK to `C:\Users\kenny\AppData\Local\Temp\opencode\dist\BYD-Open-Remote-Lab-trial.apk`
(overwrite) and to `E:\Kenny\Huawei\BYD-Open-Remote-Lab-trial.apk`.

Verify:
`curl -s -I http://192.168.1.109:8091/BYD-Open-Remote-Lab-trial.apk`
Expected: `200 OK` and the new file size.

- [ ] **Step 4: Commit the plan/docs if not already committed**

```bash
git add docs/superpowers/specs/2026-11-20-byd-overdrive-web-advanced-design.md \
        docs/superpowers/plans/2026-11-20-byd-overdrive-web-advanced.md
git commit -m "docs: plan overdrive web advanced settings"
```

---

## Manual Acceptance

1. Open the drawer: **Advanced** and **Overdrive** are present.
2. Advanced → turn **Show Overdrive in menu** off → back → Overdrive is gone,
   Advanced is still there.
3. Turn it on → Overdrive returns without restarting the app.
4. Advanced → set **Car address** to the head unit (or leave blank on the car
   hotspot), select **Vehicle Control**, Save.
5. Open Overdrive: no URL bar, it loads `/vehicle-control.html`.
6. Advanced → **Hide URL bar** off → Overdrive shows the URL field.
7. Advanced → **Custom** → enter `events.html` or a full
   `http://<car-ip>:8080/events.html` → Save → Overdrive loads it.
8. Set a wrong IP, confirm the error toast, then correct it in Advanced.
9. Release/demo build: Advanced and Overdrive are absent.

## Self-Review Notes

- Spec coverage: Advanced entry (Tasks 3/4), Overdrive rename (Tasks 3/4/5),
  visibility toggle (Task 4), URL setting moved (Tasks 3/5), hide URL bar
  default true (Task 2/5), presets + manual URL (Tasks 1/3), build/publish
  (Task 6).
- Review Focus tests are owned by Tasks 1, 2, 4, and 5.
- No cloud calls, no watch changes, no new dependencies.
- Execution must wait until 2026-11-20 11:01 GMT+7.
