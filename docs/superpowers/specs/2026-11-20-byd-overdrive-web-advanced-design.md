# Overdrive Web + Advanced Options — Design

Date: 2026-11-20
Status: Ready for execution at 11:01 GMT+7
Scope: Android companion trial build (`standardDebug`)

## Intent

Turn the trial "Car Web (:8080)" screen into a first-class **Overdrive** entry
and move its configuration into a new **Advanced** section in the hamburger
menu. The WebView remains a browser to the car's Overdrive server, but the URL
bar is hidden by default and the default page is selectable from Overdrive's
own page list.

This is still a bridge: the BYD cloud cannot set AC current, but Overdrive's
head-unit server can, and the phone can open that server in a WebView.

## Requirements

1. Add **Advanced options** to the hamburger menu.
2. Advanced has a toggle to show/hide the **Overdrive** menu entry.
   The entry is renamed from "Car Web (:8080)" to "Overdrive".
3. Move the URL setting to Advanced. Add **Hide URL bar**, default **on**.
4. Advanced can change the default URL from a preset list (including
   `events.html`, `vehicle-control.html`, and more) or a manually entered URL.

## Non-goals

- No direct AC-current control through the BYD cloud (proven no-op).
- No Overdrive login implementation; the WebView shows Overdrive's login page.
- No release-visible change; the trial gate stays `DEBUG && !DEMO_BUILD`.
- No background discovery/scanning of the LAN.
- No attempt to embed Overdrive itself in the APK.

## Decisions

### Entry points

- New drawer item **Advanced** (`nav_advanced`), visible only when the trial
  gate is enabled. It stays visible even when the Overdrive entry is hidden,
  so the entry can be restored.
- The **Overdrive** drawer item (`nav_car_web`, existing ID) is visible when
  the trial gate is enabled **and** the "Show Overdrive in menu" preference is
  true.
- `MainActivity.onResume()` re-applies the Overdrive visibility so returning
  from Advanced updates the drawer immediately.

### Advanced screen

A new `AdvancedActivity` with a scrollable form:

- **Show Overdrive in menu** — switch, default on.
- **Hide URL bar** — switch, default on.
- **Car address** — optional base URL, e.g. `http://192.168.1.50:8080`.
  Blank means "use the current Wi-Fi gateway".
- **Default page** — dropdown of curated Overdrive pages plus **Custom…**.
  Selecting a preset stores its path. Selecting Custom reveals a text field
  that accepts a path or a full `http(s)://` URL.
- **Preview** — resolved URL, updated as fields change.
- **Save** — persists preferences and finishes.
- **Reset** — restores defaults.

The car address is separate from the default page so presets remain usable on
home Wi-Fi, where the gateway is the router rather than the car.

### URL model

Preferences (`SharedPreferences` name `car_web`):

| Key | Default | Meaning |
|---|---|---|
| `show_overdrive_entry` | `true` | Drawer visibility |
| `hide_url_bar` | `true` | Hide the URL row in Overdrive |
| `car_base_url` | `""` | Optional `http(s)://host:port` base |
| `default_page` | `"/"` | Path or absolute URL |

Resolution order for the page the WebView opens:

1. If `default_page` is an absolute `http(s)://` URL, use it.
2. Otherwise use `car_base_url` when valid.
3. Otherwise use the active Wi-Fi gateway and append `:8080`.
4. Join the base origin with the page path (`/vehicle-control.html`).
5. If no base can be found, show the URL row even when "Hide URL bar" is on,
   with the "Enter the car IP" hint.

The on-screen URL bar is a temporary override: loading a URL there does not
change the Advanced default. It exists for quick experiments when shown.

### Page presets

All paths are served by Overdrive's local HTTP server (`HttpServer.java`).
Labels are ours; the path is the contract.

**Primary**
- Dashboard — `/`
- Vehicle Control — `/vehicle-control.html` (contains the AC current limit)
- Events — `/events.html`
- Charging — `/charging.html`
- Automations — `/automations.html`
- Trips — `/trips.html`
- Parking — `/parking.html`
- Live View — `/live-view.html`
- Recordings — `/recording.html`
- Widgets — `/widgets.html`

**System / integrations**
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

`CarWebActivity` keeps its class name for continuity, but all user-facing
strings become "Overdrive".

- Reads `hide_url_bar`: hides the URL row (`etCarWebUrl` + `btnCarWebLoad`).
- Resolves the default page using the URL model above.
- Auto-loads on create.
- Back navigates WebView history; reload stays in the toolbar.
- If the resolved URL is empty, the URL row is forced visible and focused.
- Cleartext HTTP remains allowed in the debug manifest only.

## Success criteria

- Drawer shows **Advanced** and **Overdrive** in the trial build.
- Turning "Show Overdrive in menu" off removes Overdrive from the drawer and
  leaves Advanced visible; turning it back on restores it without restarting.
- "Hide URL bar" on (default) opens Overdrive with no URL row.
- Selecting `/vehicle-control.html` opens the head unit's vehicle-control page.
- Selecting `events.html` opens the events page.
- A custom absolute URL overrides the car address.
- A custom relative path joins the car address/gateway.
- All unit tests pass; APK builds and keeps the canonical signature.

## Risks

| Risk | Mitigation |
|---|---|
| Wrong auto-detected gateway (home Wi-Fi) | Advanced "Car address" overrides it |
| Overdrive not installed or not reachable | WebView error toast; user checks IP/Overdrive |
| Cleartext HTTP blocked | Debug-only `usesCleartextTraffic` manifest overlay |
| Hidden URL bar leaves no escape hatch | If no URL resolves, force the URL row visible |
| Changing prefs but drawer stale | Re-apply visibility in `onResume()` |
| Preset path differs by Overdrive version | Custom URL fallback + documented path list |
