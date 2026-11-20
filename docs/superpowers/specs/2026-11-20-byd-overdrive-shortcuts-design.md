# Overdrive Shortcuts (Experimental) — Design

Date: 2026-11-20
Status: Plan for review
Scope: Android companion standard build; demo hides it

## Intent

Add an opt-in experimental feature under **Advanced** that surfaces two
first-class shortcuts on the main Controls page when the car’s Overdrive
server is reachable:

- **Charging schedule**
- **AC current limit**

The shortcuts open the existing Overdrive WebView directly at the relevant
section. This is deliberately a shortcut/navigation feature, not a second
implementation of Overdrive’s controls.

## Why deep links first

Overdrive already owns both controls:

- Charging schedule: `/vehicle-control.html`, section `#panelCharging`
- AC current limit: `/vehicle-control.html`, section `#acChargeCurrentSection`

Overdrive’s own API (`/api/vehicle/charging-schedule`,
`/api/vehicle/ac-charge-current-limit`) requires its JWT session. Implementing
native forms would duplicate Overdrive’s UI, validation, capability gating,
and auth. For the experimental phase, deep links give the same result with
far less risk.

Phase 2 (not in this plan) can add native forms using the WebView cookie if the
shortcuts prove useful.

## Requirements

1. New experimental toggle under **Advanced**.
2. Default **off**.
3. When on **and** Overdrive is reachable, show a new **Overdrive** card on the
   main Controls page.
4. Card has **Charging schedule** and **AC current limit** buttons.
5. Each button opens `CarWebActivity` at the right page/section.
6. If Overdrive is not configured or not reachable, hide the card.
7. No native charging writes in this phase.

## Decisions

### Opt-in

`CarWebPrefs` gains `overdrive_shortcuts_enabled`, default `false`.
Advanced gets an **Experimental** card with:

- **Overdrive shortcuts** switch.
- Description: “Show charging schedule and AC current limit shortcuts when
  Overdrive is reachable.”
- The switch is saved with the existing Save button.

### “Connected” means reachable

A new `OverdriveConnection` helper probes:

```
GET <car-address>/auth/status
```

- 3-second connect/read timeout.
- No auth required (`/auth/status` is public in Overdrive).
- `HTTP 200` = reachable.
- Blank car address, timeout, DNS failure, or non-2xx = not reachable.

The probe runs asynchronously (OkHttp enqueue) and never blocks the UI or the
cloud command queue. It does not prove the user is logged in; the WebView
handles login as it does today.

### Authentication / access code

No access-code field is needed for this phase.

- The shortcuts are deep links into Overdrive’s own web UI. The WebView keeps
  Overdrive’s session cookie (`byd_session`); if the session is missing or
  expired, Overdrive shows its login page and the user signs in there.
- The reachability probe uses `GET /auth/status`, which is public and does not
  require a login.
- If Phase 2 adds native API calls, the app can reuse
  `CookieManager.getCookie(baseUrl)` and send the same session cookie. Only if
  that proves unreliable should Advanced gain an optional access-token field.

### Main Controls card

Add `cardOverdriveShortcuts` to `activity_main.xml`, shown only when:

- `!BuildConfig.DEMO_BUILD`
- `CarWebPrefs.overdriveShortcutsEnabled`
- the car address is non-blank
- `OverdriveConnection` says reachable
- the current page is Controls

Card contents:

- Title: **Overdrive**
- Status: “Connected” / “Not reachable” (for the moment the probe completes)
- Buttons:
  - **Charging schedule**
  - **AC current limit**
- Small text button **Manage** → `AdvancedActivity`

If unreachable, the card stays hidden; Advanced remains the place to fix the
address.

### Deep links

`CarWebActivity` gains two optional intent extras:

- `EXTRA_PAGE` — overrides the saved default page for this launch only.
- `EXTRA_SCROLL_ID` — element ID to scroll to after load.

Resolution:

- Base = `CarWebPrefs.baseUrl`.
- Page = `EXTRA_PAGE` when present, otherwise `CarWebPrefs.defaultPage`.
- URL = `CarWebUrl.resolve(base, page)`.
- If `EXTRA_SCROLL_ID` is present, append `#id` to the URL and, after
  `onPageFinished`, call:
  ```js
  document.getElementById(id).scrollIntoView({block: 'start'})
  ```
- Sanitize the ID to `[A-Za-z0-9_-]`.
- The URL bar setting is unchanged; the deep link honors `hide_url_bar`.

Shortcut targets:

| Button | Page | Scroll ID |
|---|---|---|
| Charging schedule | `/vehicle-control.html` | `panelCharging` |
| AC current limit | `/vehicle-control.html` | `acChargeCurrentSection` |

The schedule section can be hidden by Overdrive on trims/capabilities without
schedule support. In that case the page still opens at Vehicle Control; the
button does not report a false success.

### Default page

The saved default remains **Vehicle Control** (`/vehicle-control.html`), so a
normal Overdrive open and the AC current shortcut land on the same page.

## Non-goals

- No native schedule form or current-limit segmented control.
- No storage of Overdrive credentials/JWT.
- No background polling of Overdrive.
- No BYD-cloud current-limit calls.
- No demo-flavor exposure.

## Success criteria

- Advanced has an experimental “Overdrive shortcuts” switch, default off.
- With it on and a reachable car address, Controls shows the Overdrive card.
- With it off, or address blank/unreachable, the card is absent.
- **Charging schedule** opens Vehicle Control and scrolls to the schedule.
- **AC current limit** opens Vehicle Control and scrolls to the AC current
  segmented control.
- The card refreshes on return from Advanced without restarting the app.
- No GitHub push until the APK is tested.

## Risks

| Risk | Mitigation |
|---|---|
| Probe blocks UI | OkHttp async, 3s timeout |
| Probe says reachable but login expired | WebView shows Overdrive’s login page |
| SPA renders after `onPageFinished` | Append `#id` and retry `scrollIntoView` once after 300 ms |
| Schedule section hidden on unsupported trims | Open Vehicle Control anyway; no false success |
| Card appears on other pages | Gate visibility on `nav_controls` |
| Cleartext HTTP blocked | Existing debug-only cleartext manifest overlay |
