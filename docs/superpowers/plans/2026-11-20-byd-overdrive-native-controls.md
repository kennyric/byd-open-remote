# Native Overdrive controls — implementation plan

Spec: `docs/superpowers/specs/2026-11-20-byd-overdrive-native-controls-design.md`

## 1. API client (TDD)

`lab/OverdriveApi.java` — pure request/response helpers, no Android UI:

- `authStatusUrl`, `tokenUrl`, `currentLimitUrl`, `scheduleUrl`, `startChargingUrl`
- `deviceToken(deviceId, accessCode)`
- `parseDeviceId(json)`, `parseJwt(json)`
- `CurrentLimit` model: `state`, `label`, `supported`
- `labelForState(int)`, `stateForLabel(String)`
- `Schedule` model: `enabled`, `start`, `end`, `chargeWay`, `repeat()` label
- `chargeWayFor(Repeat)`, `repeatFor(chargeWay)`
- `scheduleBody(...)`

Tests: `app/src/test/java/com/bydwatch/companion/lab/OverdriveApiTest.java`.

## 2. Session storage

`lab/OverdriveSession.java` over `CarWebPrefs`:

- `accessCode`, `jwt`, `jwtExpiry`, `deviceId`
- `hasJwt(now)`, `clear()`

Tests: `OverdriveSessionTest`.

## 3. Controls screen

`lab/OverdriveControlActivity.java` + `res/layout/activity_overdrive_controls.xml`:

- Segmented current limit; tapping a segment posts `state`.
- Schedule fields; Save posts the full schedule body.
- Start charging now.
- Status line: `Connected` / `Login needed` / `Not reachable`.
- 401/403 → clear JWT, show `Login needed`.
- Reachability probe on resume refreshes values.

Tests: `OverdriveControlActivityTest` (Robolectric, stubbed transport).

## 4. Advanced

- Access-code `TextInputLayout` + Connect / Disconnect.
- Status: `Connected` / `Login needed` / `Not reachable`.
- `Login needed` shows the login button.

Tests: extend `AdvancedActivityTest`.

## 5. Controls page card

Replace `cardOverdriveShortcuts` contents with:

- `Overdrive` title + status dot + `Connected`.
- Two stat tiles: current limit, schedule summary.
- `Open Overdrive controls ›` row → `OverdriveControlActivity`.

Rename ids to `cardOverdriveControls`, `tvOverdriveControlsStatus`,
`tvOverdriveCurrentSummary`, `tvOverdriveScheduleSummary`,
`btnOpenOverdriveControls`. Update `OverdriveShortcutsTest` → `OverdriveControlsCardTest`.

## 6. Verify & ship

```bash
./gradlew.bat :app:testStandardDebugUnitTest
./gradlew.bat :app:assembleStandardDebug
apksigner verify --print-certs <apk>
```

Publish to the local server on `:8091` as `BYD-Open-Remote-Lab-trial.apk`.
No version bump, no GitHub push.
