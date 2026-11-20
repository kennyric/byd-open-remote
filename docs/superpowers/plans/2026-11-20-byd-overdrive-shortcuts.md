# Overdrive Shortcuts (Experimental) — Implementation Plan

**Goal:** Add an opt-in Advanced setting that shows **Charging schedule** and **AC current limit** shortcuts on the Controls page when the car’s Overdrive server is reachable, opening the correct Overdrive page section in the existing WebView.

**Architecture:** `CarWebPrefs` stores the opt-in flag. `OverdriveConnection` probes the public `GET /auth/status` endpoint asynchronously. `CarWebActivity` accepts an optional page and scroll-ID extra, resolves the page, and scrolls the WebView after load. `MainActivity` shows a new Controls card only when the flag is on and the probe succeeds.

**Tech Stack:** Java 17, Android SDK 34, `minSdk 26`, OkHttp 4.12 (already present), JUnit 4, Robolectric 4.13, Material 1.12.

**Spec:** `docs/superpowers/specs/2026-11-20-byd-overdrive-shortcuts-design.md`

## Global Constraints

- Experimental feature defaults **off**.
- No Overdrive access-code/token field in this phase. Deep links use the WebView’s existing session cookie; `GET /auth/status` is public. If Phase 2 native API calls prove the cookie insufficient, add a token field then, not now.
- No native charging-schedule or current-limit writes in this phase.
- Standard build only; demo hides the card and the Advanced switch.
- No background polling beyond the on-resume reachability probe.
- No new dependencies.
- Do not change `applicationId`, versionCode, or signing.
- Do not push to GitHub until the user tests the APK.

## Review Focus

- No access-code field appears; a fresh WebView with no login still opens the Overdrive login page — Task 2.
- The card is hidden unless the switch is on, the address is non-blank, and `/auth/status` is reachable — Task 4.
- The card does not appear on non-Control pages — Task 4.
- **Charging schedule** opens `/vehicle-control.html#panelCharging`; **AC current limit** opens `/vehicle-control.html#acChargeCurrentSection` — Task 2.
- The card refreshes after returning from Advanced without an app restart — Task 4.

---

### Task 1: Preferences and reachability probe

**Files:**
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java`
- Create: `android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdriveConnection.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdriveConnectionTest.java`

**Interfaces:**
- `CarWebPrefs.overdriveShortcutsEnabled(Context) -> boolean` (default `false`)
- `CarWebPrefs.setOverdriveShortcutsEnabled(Context, boolean)`
- `OverdriveConnection.check(Context, Callback)`
- `OverdriveConnection.Callback.onResult(boolean reachable)`
- `OverdriveConnection.statusUrl(String base) -> String`
- `OverdriveConnection.setReachableForTesting(Boolean)` (`@VisibleForTesting`)

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void shortcutsDefaultOffAndPersist() {
        assertFalse(CarWebPrefs.overdriveShortcutsEnabled(context));
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        assertTrue(CarWebPrefs.overdriveShortcutsEnabled(context));
    }
```

```java
package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OverdriveConnectionTest {

    @Test
    public void buildsPublicStatusUrl() {
        assertEquals("http://192.168.1.50:8080/auth/status",
                OverdriveConnection.statusUrl("192.168.1.50"));
        assertEquals("http://192.168.1.50:8080/auth/status",
                OverdriveConnection.statusUrl("http://192.168.1.50:8080"));
    }

    @Test
    public void blankAddressHasNoStatusUrl() {
        assertEquals("", OverdriveConnection.statusUrl(""));
        assertEquals("", OverdriveConnection.statusUrl(null));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest" --tests "com.bydwatch.companion.lab.OverdriveConnectionTest"`
Expected: FAIL — new key/helper missing.

- [ ] **Step 3: Implement**

`CarWebPrefs`:
- key `overdrive_shortcuts_enabled`, default `false`.
- setter; include in `reset` removal.

`OverdriveConnection`:
- `statusUrl(base)` = `CarWebUrl.normalize(base)` plus `auth/status`.
- `check(context, callback)`:
  - If `testReachable != null`, invoke callback with it (for tests).
  - If `statusUrl` empty, callback `false`.
  - OkHttp async `GET`, 3s connect/read timeout, callback `true` on HTTP 2xx, else `false`.
  - Never throw; all failures call `false`.
- `setReachableForTesting` only effective in debug builds, like other test hooks.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebPrefsTest" --tests "com.bydwatch.companion.lab.OverdriveConnectionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebPrefs.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/OverdriveConnection.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebPrefsTest.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/OverdriveConnectionTest.java
git commit -m "feat(overdrive): add shortcuts preference and reachability probe"
```

---

### Task 2: Deep-link page and scroll extras

**Files:**
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebActivity.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebActivityTest.java`

**Interfaces:**
- `CarWebUrl.withFragment(String url, String id) -> String`
- `CarWebActivity.EXTRA_PAGE`
- `CarWebActivity.EXTRA_SCROLL_ID`
- `CarWebActivity.intent(Context, String page, String scrollId) -> Intent`

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    public void appendsSafeFragmentOnlyOnce() {
        assertEquals("http://x:8080/vehicle-control.html#panelCharging",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", "panelCharging"));
        assertEquals("http://x:8080/vehicle-control.html#acChargeCurrentSection",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", "acChargeCurrentSection"));
        assertEquals("http://x:8080/vehicle-control.html",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", ""));
    }
```

```java
    @Test
    public void deepLinkLoadsVehicleControlSection() {
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        android.content.Intent intent = CarWebActivity.intent(
                context, "/vehicle-control.html", "panelCharging");

        CarWebActivity activity = Robolectric.buildActivity(CarWebActivity.class, intent)
                .setup().get();
        WebView webView = activity.findViewById(com.bydwatch.companion.R.id.webCar);
        String loaded = Shadows.shadowOf(webView).getLastLoadedUrl();
        assertTrue("loaded=" + loaded,
                loaded != null && loaded.contains("/vehicle-control.html#panelCharging"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebUrlTest" --tests "com.bydwatch.companion.lab.CarWebActivityTest"`
Expected: FAIL — `withFragment`, extras, `intent` missing.

- [ ] **Step 3: Implement**

`CarWebUrl.withFragment`:
- Return `url` unchanged when `url` or `id` is empty.
- Sanitize `id` to `[A-Za-z0-9_-]`; return unchanged when nothing remains.
- Append `#id`, replacing an existing fragment.

`CarWebActivity`:
- Add `public static final String EXTRA_PAGE = "overdrive_page"` and
  `EXTRA_SCROLL_ID = "overdrive_scroll_id"`.
- `intent(Context, page, scrollId)` builds an Intent with both extras.
- `openDefaultPage`:
  - `page = getIntent().getStringExtra(EXTRA_PAGE)` when non-empty, else
    `CarWebPrefs.defaultPage(this)`.
  - `scrollId = getIntent().getStringExtra(EXTRA_SCROLL_ID)`.
  - `url = CarWebUrl.withFragment(CarWebUrl.resolve(base, page), scrollId)`.
- In `onPageFinished`, when a scroll ID is present:
  - run `document.getElementById(id).scrollIntoView({block:'start'})`.
  - schedule one retry after 300 ms for SPA rendering.
  - Do not change the saved default page.
- Keep `hide_url_bar` behavior and the empty state.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.CarWebUrlTest" --tests "com.bydwatch.companion.lab.CarWebActivityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebUrl.java \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/CarWebActivity.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebUrlTest.java \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/CarWebActivityTest.java
git commit -m "feat(overdrive): support deep-link page and scroll extras"
```

---

### Task 3: Advanced experimental toggle

**Files:**
- Modify: `android-companion/app/src/main/res/layout/activity_advanced.xml`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/lab/AdvancedActivity.java`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/lab/AdvancedActivityTest.java`

**Interfaces:**
- `R.id.switchAdvOverdriveShortcuts`
- `CarWebPrefs.overdriveShortcutsEnabled`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void experimentalShortcutTogglePersists() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        com.google.android.material.switchmaterial.SwitchMaterial shortcuts =
                activity.findViewById(com.bydwatch.companion.R.id.switchAdvOverdriveShortcuts);
        shortcuts.setChecked(true);
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertTrue(CarWebPrefs.overdriveShortcutsEnabled(context));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.AdvancedActivityTest"`
Expected: FAIL — switch missing.

- [ ] **Step 3: Implement**

`activity_advanced.xml`:
- Add an **Experimental** card after the Overdrive card:
  - `SwitchMaterial switchAdvOverdriveShortcuts`
  - title `@string/advanced_experimental`
  - text `@string/advanced_overdrive_shortcuts`
  - description `@string/advanced_overdrive_shortcuts_desc`

`AdvancedActivity`:
- Bind the switch; initialize from `CarWebPrefs.overdriveShortcutsEnabled`.
- Save persists it.
- Reset turns it off.
- No access-code/token field.

Strings:
- `advanced_experimental` — “Experimental”
- `advanced_overdrive_shortcuts` — “Overdrive shortcuts”
- `advanced_overdrive_shortcuts_desc` — “Show charging schedule and AC current limit shortcuts when Overdrive is reachable.”
- Indonesian equivalents.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.lab.AdvancedActivityTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/res/layout/activity_advanced.xml \
        android-companion/app/src/main/java/com/bydwatch/companion/lab/AdvancedActivity.java \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/lab/AdvancedActivityTest.java
git commit -m "feat(overdrive): add experimental shortcuts toggle"
```

---

### Task 4: Controls card and visibility

**Files:**
- Modify: `android-companion/app/src/main/res/layout/activity_main.xml`
- Modify: `android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java`
- Modify: `android-companion/app/src/main/res/values/strings.xml`
- Modify: `android-companion/app/src/main/res/values-in/strings.xml`
- Test: `android-companion/app/src/test/java/com/bydwatch/companion/ui/OverdriveShortcutsTest.java`

**Interfaces:**
- `R.id.cardOverdriveShortcuts`
- `R.id.btnOverdriveSchedule`, `R.id.btnOverdriveCurrent`, `R.id.btnOverdriveManage`
- `R.id.tvOverdriveShortcutStatus`
- `MainActivity.refreshOverdriveShortcuts()`

- [ ] **Step 1: Write the failing tests**

```java
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverdriveShortcutsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
        OverdriveConnection.setReachableForTesting(null);
    }

    @After
    public void tearDown() {
        OverdriveConnection.setReachableForTesting(null);
        BydWatchApp.resetForTesting();
    }

    private void seed() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setUsername("test@byd.id");
        store.setPassword("ValidPass123");
        store.setPin("123456");
        store.setRegion("id");
        store.setVin("VIN_TEST");
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
    }

    @Test
    public void cardHiddenWhenToggleOff() {
        seed();
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setReachableForTesting(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveShortcuts).getVisibility());
    }

    @Test
    public void cardVisibleWhenEnabledAndReachable() {
        seed();
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setReachableForTesting(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(R.id.cardOverdriveShortcuts).getVisibility());
    }

    @Test
    public void scheduleButtonOpensDeepLink() {
        seed();
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setReachableForTesting(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        activity.findViewById(R.id.btnOverdriveSchedule).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(CarWebActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("/vehicle-control.html", next.getStringExtra(CarWebActivity.EXTRA_PAGE));
        assertEquals("panelCharging", next.getStringExtra(CarWebActivity.EXTRA_SCROLL_ID));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.OverdriveShortcutsTest"`
Expected: FAIL — card/IDs missing.

- [ ] **Step 3: Implement**

`activity_main.xml`:
- Add `cardOverdriveShortcuts` **after** `cardControls` (a separate sibling,
  below the TPMS box), style `@style/Widget.BydWatch.Card`,
  `layout_marginBottom="16dp"`, hidden by default. Do not nest it inside
  `cardControls`.
- Title **Overdrive**, status `tvOverdriveShortcutStatus`.
- Buttons `btnOverdriveSchedule`, `btnOverdriveCurrent`, `btnOverdriveManage`.
- Use existing card styling/tokens.

`MainActivity`:
- Bind card/views.
- `refreshOverdriveShortcuts()`:
  - Hide if `BuildConfig.DEMO_BUILD`, flag off, base blank, or current page is
    not `nav_controls`.
  - Otherwise call `OverdriveConnection.check`; on result, if reachable, show
    card; else hide.
  - Update status text to Connected/Not reachable.
- Call from `onResume()` and from `showPage()`.
- Buttons:
  - Schedule → `CarWebActivity.intent(this, "/vehicle-control.html", "panelCharging")`.
  - Current → `CarWebActivity.intent(this, "/vehicle-control.html", "acChargeCurrentSection")`.
  - Manage → `AdvancedActivity`.
- `showPage()` must keep the card hidden on pages other than `nav_controls`.

Strings:
- `overdrive_shortcuts_title` — “Overdrive”
- `overdrive_shortcuts_connected` — “Connected”
- `overdrive_shortcuts_unreachable` — “Not reachable”
- `overdrive_shortcut_schedule` — “Charging schedule”
- `overdrive_shortcut_current` — “AC current limit”
- `overdrive_shortcut_manage` — “Manage”
- Indonesian equivalents.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest --tests "com.bydwatch.companion.ui.OverdriveShortcutsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android-companion/app/src/main/res/layout/activity_main.xml \
        android-companion/app/src/main/java/com/bydwatch/companion/ui/MainActivity.java \
        android-companion/app/src/main/res/values/strings.xml \
        android-companion/app/src/main/res/values-in/strings.xml \
        android-companion/app/src/test/java/com/bydwatch/companion/ui/OverdriveShortcutsTest.java
git commit -m "feat(overdrive): show experimental shortcuts on controls"
```

---

### Task 5: Build, verify, and hand over the APK

- [ ] **Step 1: Run the full suite**

Run: `cd android-companion && ./gradlew.bat :app:testStandardDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Build**

Run: `cd android-companion && ./gradlew.bat :app:assembleStandardDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify signature and version**

Run:
`"D:/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs android-companion/app/build/outputs/apk/standard/debug/BYD-Open-Remote.apk`
Expected: canonical SHA-256. VersionCode stays `9`.

- [ ] **Step 4: Publish to the local test server only**

Copy to `C:\Users\kenny\AppData\Local\Temp\opencode\dist\BYD-Open-Remote-Lab-trial.apk`
and `E:\Kenny\Huawei\BYD-Open-Remote-Lab-trial.apk`.

Verify `http://192.168.1.109:8091/BYD-Open-Remote-Lab-trial.apk` returns 200.

Do **not** push to GitHub until the user tests it.

---

## Manual Acceptance

1. Advanced → **Experimental** → **Overdrive shortcuts** on → Save.
2. Controls shows the **Overdrive** card when the car address is reachable.
3. Tap **Charging schedule** → Overdrive opens Vehicle Control at the schedule.
4. Back → tap **AC current limit** → Overdrive opens Vehicle Control at the AC current section.
5. Turn the toggle off → the card disappears.
6. Clear the car address or make it unreachable → the card disappears.
7. No access-code/token field is present; if Overdrive is logged out, its login page appears in the WebView.

## Self-Review Notes

- Spec coverage: opt-in (Task 3), reachability (Task 1/4), deep links (Task 2), Controls card (Task 4), build/handover (Task 5).
- Auth: no access-code field; `/auth/status` is public; WebView cookies handle login.
- No native Overdrive API writes, no new dependencies, no version bump.
