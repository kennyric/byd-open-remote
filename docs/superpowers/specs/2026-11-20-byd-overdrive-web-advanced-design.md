# Overdrive Web + Advanced Options — Design

Date: 2026-11-20
Status: Ready for execution at 11:01 GMT+7
Scope: Android companion (`standard` flavor; demo build hides it)

## Intent

Turn the existing "Car Web (:8080)" screen into a first-class **Overdrive**
entry and move its configuration into a new **Advanced** section in the
hamburger menu. The WebView remains a browser to the car's Overdrive server,
but the URL bar is hidden by default and the default page is selectable from
Overdrive's own page list.

The old **Lab (trial)** mode is no longer needed. It is removed in the same
execution window, along with its menu entry, screen, strings, and cloud
diagnostic tests. Advanced/Overdrive become normal standard-build features;
the demo flavor still hides them.

## Requirements

1. Add **Advanced options** to the hamburger menu.
2. Advanced has a toggle to show/hide the **Overdrive** menu entry.
   The entry is renamed from "Car Web (:8080)" to "Overdrive".
   **Default: off** — Overdrive is hidden until the user enables it.
3. Move the URL setting to Advanced. Add **Hide URL bar**, default **on**.
4. Advanced can change the default URL:
   - top 5 preset pages,
   - a **Load all pages** expansion for the rest,
   - and a manual URL field.
5. Remove the Lab (trial) menu and screen.

## Non-goals

- No direct AC-current control through the BYD cloud (proven no-op).
- No Overdrive login implementation; the WebView shows Overdrive's login page.
- No LAN scanning or gateway auto-detection.
- No attempt to embed Overdrive itself in the APK.
- No demo-flavor exposure.

## Decisions

### Gate changes

- `LabGate` is deleted.
- Advanced and Overdrive are available when `!BuildConfig.DEMO_BUILD`.
- Demo builds hide both and finish their activities immediately.
- The old `nav_lab` drawer entry and `LabActivity` are removed.

### Entry points

- New drawer item **Advanced** (`nav_advanced`), always visible in the
  standard build. It stays visible even when Overdrive is hidden, so the
  entry can be restored.
- The **Overdrive** drawer item (`nav_car_web`, existing ID) is visible only
  when **Show Overdrive in menu** is on. Default: **off**.
- `MainActivity.onResume()` re-applies Overdrive visibility so returning from
  Advanced updates the drawer immediately.

### Advanced screen

A new `AdvancedActivity` with a scrollable form:

- **Show Overdrive in menu** — switch, default **off**.
- **Hide URL bar** — switch, default **on**.
- **Car address** — optional base URL, e.g. `http://192.168.1.50:8080`.
  **Leave blank to keep Overdrive closed** until an address is configured.
- **Default page**:
  - Top 5 presets as a compact radio list:
    1. Vehicle Control — `/vehicle-control.html`
    2. Events — `/events.html`
    3. Charging — `/charging.html`
    4. Trips — `/trips.html`
    5. Live View — `/live-view.html`
  - **Load all pages** button expands the full list (Dashboard, Parking,
    Recordings, Widgets, Network, BYD Cloud, etc.).
  - **Manual URL** field is always visible. It accepts a path
    (`events.html`) or a full `http(s)://` URL. A non-empty manual URL
    overrides the preset selection; choosing a preset clears the manual field.
- **Preview** — resolved URL, updated as fields change.
- **Save** — persists preferences and finishes.
- **Reset** — restores defaults in the form.

### URL model

Preferences (`SharedPreferences` name `car_web`):

| Key | Default | Meaning |
|---|---|---|
| `show_overdrive_entry` | `false` | Drawer visibility |
| `hide_url_bar` | `true` | Hide the URL row in Overdrive |
| `car_base_url` | `""` | Optional `http(s)://host:port` base |
| `default_page` | `"/"` | Path or absolute URL |

Resolution order for the page the WebView opens:

1. If `default_page` is an absolute `http(s)://` URL, use it.
2. Otherwise, if `car_base_url` is valid, join the base with the page path.
3. Otherwise there is **no URL**: do not auto-load. The Overdrive screen shows
   an empty state with a button to open Advanced.

No Wi-Fi gateway fallback. A blank car address and a relative page resolve to
nothing, by design.

The on-screen URL bar, when visible, is a temporary override: loading a URL
there does not change the Advanced default. It is hidden by default.

### Page list

All paths are served by Overdrive's local HTTP server (`HttpServer.java`).
Labels are ours; the path is the contract.

**Top 5**
- Vehicle Control — `/vehicle-control.html` (contains the AC current limit)
- Events — `/events.html`
- Charging — `/charging.html`
- Trips — `/trips.html`
- Live View — `/live-view.html`

**All pages (Load all)**
- Dashboard — `/`
- Automations — `/automations.html`
- Parking — `/parking.html`
- Recordings — `/recording.html`
- Widgets — `/widgets.html`
- Network — `/network.html`
- BYD Cloud — `/byd-cloud.html`
- Remote Access — `/remote-access.html`
- About — `/about.html`
- Module Faults — `/module-faults.html`
- Seat Positions — `/seat-positions.html`
- Sessions — `/sessions.html`
- Daemons — `/daemons.html`
- Communication — `/communicate.html`
- Telegram — `/telegram.html`
- MQTT — `/mqtt.html`
- WireGuard — `/wireguard.html`
- ABRP — `/abrp.html`
- Road Sense — `/road-sense.html`
- SafeKeep — `/safekeep.html`
- Storage — `/storage.html`
- Factory DVR — `/factory-dvr.html`
- Key Mapping — `/key-mapping.html`
- Notifications — `/notifications.html`
- Performance — `/performance.html`
- Surveillance — `/surveillance.html`

Excluded because they are not direct server routes: `widget-builder.html`,
`vehicle-control-3d-test.html`, `remote-dev-view.html` (dev-only).

### Overdrive screen

`CarWebActivity` keeps its class name, but all user-facing strings become
"Overdrive".

- Reads `hide_url_bar`: hides the URL row (`rowCarWebUrl`).
- Resolves the default page using the URL model above.
- Auto-loads only when a URL resolves.
- If no URL resolves: do not load anything; show an empty state with
  "Set the car address in Advanced" and a button that opens `AdvancedActivity`.
- Back navigates WebView history; reload stays in the toolbar.
- Cleartext HTTP is allowed app-wide by `android-companion/app/src/main/res/xml/network_security_config.xml`.
  The car address is typed by the user (LAN or Tailscale), so it cannot be scoped to a host list;
  trust anchors stay the system store.

## Lab removal

Delete in the same execution:

- `nav_lab` drawer item and `MainActivity` handling.
- `LabActivity`, `activity_lab.xml`.
- `LabConsole`, `LabVerdict`, `LabRetryPolicy`, `LabGate`, `LabContext`.
- Cloud Lab models/helpers: `AcCurrentLimit`, `ChargeLimitValues`,
  `CloudCallTrace`, `LabEndpointPolicy`, and the `BydCloudClient` Lab methods
  (`AcCurrentLimitResult`, `callControlEndpoint`, `readAcCurrentLimit`,
  `writeAcCurrentLimit`, `copyJson`).
- Lab tests and lab strings (`lab_*`, `nav_lab`).
- Keep `CarWebUrl`, `CarWebPrefs`, `OverdrivePages`, `CarWebActivity`,
  `AdvancedActivity`, and the Overdrive tests.

## Success criteria

- Lab is gone from the drawer and codebase; no `nav_lab` reference remains.
- Drawer shows **Advanced**; **Overdrive** is hidden by default.
- Turning "Show Overdrive in menu" on adds Overdrive without an app restart.
- "Hide URL bar" on (default) opens Overdrive with no URL row.
- With a car address and **Vehicle Control**, Overdrive loads
  `/vehicle-control.html`.
- With **Events**, it loads `/events.html`.
- **Load all pages** reveals Dashboard and the system pages.
- A manual absolute URL overrides the car address; a manual relative path
  joins a non-blank car address.
- Blank car address + relative page = Overdrive stays closed, with an Advanced link.
- Demo build hides Advanced and Overdrive.
- All unit tests pass; APK builds and keeps the canonical signature.

## Risks

| Risk | Mitigation |
|---|---|
| Blank car address leaves Overdrive closed | Advanced helper explains that an address is required; the empty state links to Advanced |
| Overdrive hidden by default | Advanced remains visible; one switch restores it |
| Overdrive not installed/not reachable | WebView error toast; user checks IP/Overdrive |
| Cleartext HTTP blocked in release builds | `network_security_config.xml` allows cleartext app-wide (base config, system trust anchors); HTTPS-only endpoints stay HTTPS and the updater still rejects non-HTTPS URLs in code |
| Preset path differs by Overdrive version | Manual URL fallback + documented path list |
| Lab deletion breaks references | Remove in one task, run full suite, grep `nav_lab`/`LabGate` |
