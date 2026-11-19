# BYD Charging-Current Lab (Trial APK) — Design

Date: 2026-11-19
Status: Ready for approval
Scope: Android companion (`android-companion/`), standard flavor, debug build

## Intent

Prove or disprove, on a real car, that the BYD cloud can read and write the AC
charging current limit (6 A / 8 A / 10 A / 16 A / MAX) over the internet.

The trial must answer two questions in one test session:

1. **Read accuracy** — if the limit is changed in the car, does
   `getAcCurrentLimit` return the car's actual value and supported list?
2. **Write effectiveness** — if the app sends `limitCurrentControl`, does the
   car actually change its charging amperage, or does the cloud only
   acknowledge the request?

Because the write endpoint is not publicly verified, the trial must be able to
test variants, inspect raw payloads, and retry with edited JSON **without a new
APK build** for every success or failure.

## Goals

- Read the AC current limit and show:
  - active value (`currentEvCurrentLimit`),
  - supported values (`currentLimitList`),
  - raw decrypted payload,
  - the exact request (device fields may be shown read-only),
  - timing and BYD response code.
- Write an AC current limit, then automatically re-read and show
  **before → after** with a cloud-level verdict.
- Expose the rest of the `/control/smartCharge/*` family as individual,
  editable triggers so protocol questions can be answered live.
- Provide a raw request runner for allowlisted `/control/*` endpoints with
  editable JSON.
- Keep a copyable call console so failures can be reported without rebuilding.
- Ship as a debug/trial APK that installs over the existing app with the
  canonical signing key.

## Non-goals

- No production UI for charging-current control in this change.
- No BYD wallbox / charging-pile (`/cpim/*`, `/pileControl/*`) support.
- No background automation, polling loops, or scheduled writes.
- No watch-app changes.
- No new application ID or new signing key.
- No release-visible Lab entry.

## Anti-goals (would make this a failure even if it "works")

- Read shows a cached or guessed value rather than the car's actual value.
- Write reports success while the car did not change.
- A failed call hides the response and forces another build to diagnose.
- The Lab leaks into the release APK or the demo flavor.
- The trial APK cannot be installed over the user's existing app.

## Constraints

- Java 17 source level, `minSdk 26`, single-module app.
- Existing app is one `MainActivity` with a drawer and page cards.
- All cloud calls must go through the existing serialized
  `BydRepository` executor; no parallel session logins.
- Canonical signing key is mandatory (see `AGENTS.md`); do not change
  `applicationId` (`com.bydopenremote.app`).
- Keep all existing release behavior unchanged.
- The Lab is compiled into the debug APK only from the user's perspective:
  it is gated by `BuildConfig.DEBUG && !BuildConfig.DEMO_BUILD`.

## What the user decided

- Trial APK with a **separate test menu**, not scattered debug buttons.
- Test flow:
  1. Change the limit in the car.
  2. Press Read in the app and verify the app reports the car's value.
  3. Pick a limit in the app, press Set.
  4. Check the car/charger display.
  5. Press Read again and verify the cloud agrees.
- Raw request/response and error codes must be visible, with a copy action.
- The same menu should expose the other smart-charge endpoints as individual
  triggers with editable fields.
- The menu is trial/debug only.

## API contract

All endpoints are POST through the **plain token envelope**
(`transport.postSecure(endpoint, env.outer)`), the same shape already used by
the whole `smartCharge/*` family. They do **not** use `commandPwd`.

They also must **not** use `postVehicleSecure`'s guest/`userType=1` fallback.
pyBYD documents that login, the vehicle list, and the charging endpoints keep
working for a guest account, and that sending `userType` to
`/control/smartCharge/homePage` is refused with `8002`. Therefore a `1001`
on these endpoints is a genuine "unsupported/rejected" answer, not a guest
declaration problem.

The Lab must not silently retry with guest mode: that would turn a real
rejection into a clean response and destroy the diagnostic value of the trial.

### Read AC current limit (live-verified elsewhere)

```
POST /control/smartCharge/getAcCurrentLimit
inner:  { ...buildInner, "vin": "...", "energyType": "0" }   // 0 = EV, 2 = hybrid
result: { "currentLimitList": ["6A","8A","10A","16A","MAX"],
          "currentEvCurrentLimit": "MAX" }
```

`energyType` is resolved from the **active VIN**, not from "the first vehicle":

1. Look up the active VIN in the saved vehicle list and use its raw
   `energyType` when it is `"0"` (EV) or `"2"` (hybrid).
2. Otherwise fall back to `fetchFirstVinAndEnergyType()`.
3. Otherwise default to `"0"`.

The raw cloud value and the resolved request value are both shown in the
context card, and the field stays editable in the Lab.

### Write AC current limit (unverified)

```
POST /control/smartCharge/limitCurrentControl
inner:  { ...buildInner, "vin": "...", "limitCurrentValue": "10A" }
result: code 0, optional requestSerial
```

Possibly followed by:

```
POST /control/smartCharge/limitCurrentControlResult
inner:  { ...buildInner, "vin": "...", "requestSerial": "..." }
```

The poll payload is not publicly documented. The Lab treats it as an optional
manual probe, not as the source of truth.

### Confirmation strategy

The verified read endpoint is the confirmation source:

1. Read before.
2. Send write.
3. Wait a short, configurable delay (default 3 s).
4. Read again, up to N attempts (default 6, 2 s apart).
5. Compare the new active value to the requested value.

Verdicts:

- `APPLIED` — re-read matches the requested value.
- `UNCHANGED` — write acked, re-read still equals the before value.
- `CHANGED` — re-read differs from both the requested and before values.
- `PENDING` — write acked, no change yet within the window; not conclusive.
- `REJECTED` — BYD returned a non-zero code.
- `UNREACHABLE` — BYD returned `6002`.
- `ERROR` — transport, decrypt, or parse failure.

`UNCHANGED` is deliberately not called `NOT APPLIED`: BYD may only apply the
limit at the start of the next AC charge session. `PENDING` and `UNCHANGED`
both mean "try a manual re-read later, including after replugging or starting
a charge". The verdict is a cloud-level observation, not a physical proof.

The user still confirms physically at the car; the app only reports cloud truth
and exposes the raw evidence.

## Lab surface

A dedicated **Lab** screen, opened from a drawer item that is visible only in
the trial build.

### 1. Context

- VIN (masked by default; full value available privately inside the app)
- `energyType` (auto-filled, editable)
- region and session state
- control-PIN verified flag
- live charging context from the cached `VehicleStatus`: charging state,
  aggregate charge power, and plug/charge-connection state
- Refresh context button

### 2. AC charging current

- **Read limit** button.
- Active value + supported list as chips or rows.
- Value selector built from the last read, plus a **Custom** text field so
  unknown values can be probed.
- **Set limit** button with a confirmation dialog showing endpoint and payload.
- **Re-read after set** toggle (default on).
- Before → after banner with verdict and timing.

### 3. Smart-charge family

Individual buttons/cards, each with editable inputs and raw output:

- Read `homePage`
- Toggle smart charging (`smartChargeSwitch` 1/0)
- Start charging now (`status=1`)
- Save schedule (`startChargeTime`, `endChargeTime` or `full`, `chargeWay`, `status`)
- Set SoC limit (`chargeSocLimit`)
- Stop charging (`stopChargeControl`)
- Continue charging (`continueChargeControl`)
- Probe `limitCurrentControlResult` with a `requestSerial`

### 4. Raw runner

- Endpoint dropdown of known `/control/smartCharge/*` paths, plus a custom
  field.
- Editable JSON object for the inner payload.
- **Send** button; request is merged with the normal device fields.
- Response shown raw and decoded.

Safety policy (not just a string prefix):

- Allowed: `/control/smartCharge/*` (the family under test) and read-only
  `/control/getBookingList`, `/control/getStatusNow`, `/control/getGpsInfo`.
- Explicitly blocked: `/control/remoteControl`, `/control/remoteControlResult`,
  `/control/remoteControlAsyncResult`, `/control/remoteControlBatteryHeat*`,
  `/control/otaUpgrade/*`, `/control/bluetooth/*`,
  `/control/appBindingVehicle`, and `/control/standby-vehicles/*`.

The raw runner is therefore a protocol probe, not a lock/unlock console. It
must never bypass the existing PIN verification or driving gate on physical
command endpoints.

### 5. Call console

- Ring buffer of the last ~200 calls.
- Per entry: time, endpoint, request JSON, BYD code/message, decrypted payload,
  duration, exception.
- Expand/collapse, copy entry, copy all, share, clear.
- Auto-scroll; no automatic retries.

## Architecture

- `LabActivity` (new, `android:exported="false"`) hosts the screen.
- `LabController` runs calls on `BydRepository.getExecutor()` and posts results
  to the UI thread.
- `CloudBydGateway.getClient()` supplies the `BydCloudClient`; if the gateway is
  not a cloud gateway, the Lab shows "not available".
- New cloud model: `AcCurrentLimit`.
- New diagnostic model: `CloudCallTrace` — endpoint, sanitized inner request,
  raw outer response, decrypted payload, code/message, duration, error.
- New `BydCloudClient` methods:
  - `fetchAcCurrentLimit(vin, energyType)`
  - `setAcCurrentLimit(vin, value)`
  - `postLabEndpoint(endpoint, inner)` for allowlisted diagnostics
  - `postSmartChargeEndpoint` reused internally so production and Lab share
    one envelope path.
- The Lab never logs tokens, passwords, or encrypted session material.
  VIN is masked in share/copy output; a full-VIN view is available on screen.

## Build and packaging

- Trial build: `./gradlew.bat :app:assembleStandardDebug`
- Output remains `BYD-Open-Remote.apk` (debug variant), signed with the
  canonical key.
- No new flavor, no new package, no version-code bump required for the trial.
- Drawer item is hidden when not `BuildConfig.DEBUG` or when
  `BuildConfig.DEMO_BUILD`.
- `LabActivity.onCreate` finishes immediately outside the trial gate.
- A small "TRIAL" label is shown on the Lab screen only.

## Trial protocol

Run the acceptance test with the car parked and the AC charger connected.
Charging-current behaviour can differ between "plugged, idle", "charging", and
"next session", so the trial records the state for every read.

1. Plug in the AC charger (start charging if possible).
2. Set the car's limit to a known value.
3. Lab → Read: active value and supported list must match the car.
4. Lab → Set a different supported value.
5. Watch the car/charger display for a change.
6. Lab → Read: record whether the cloud agrees with the car.
7. If unchanged, re-read after the next charge start/replug and record the
   outcome as `PENDING`/`UNCHANGED` rather than a hard failure.
8. Force one failure (invalid value or offline car) and confirm the console
   shows a copyable error without a rebuild.

## Testing

Unit tests (JVM/Robolectric, no real cloud):

- Parse the live EU payload exactly:
  `currentLimitList = ["6A","8A","10A","16A","MAX"]`,
  `currentEvCurrentLimit = "MAX"`.
- Parse defensive variants: `null`, numbers, padded strings, empty payload.
- Write request body contains `limitCurrentValue` and the supplied VIN.
- Read request body contains `vin` and `energyType`.
- Raw-runner allowlist rejects paths outside the allowed set, including
  `/control/remoteControl` and other physical command endpoints.
- Lab visibility gate: false for release, false for demo, true for standard debug.
- Before/after verdict mapping for APPLIED, UNCHANGED, CHANGED, PENDING,
  REJECTED, UNREACHABLE, ERROR.
- `energyType` resolution prefers the active VIN, falls back to the first
  vehicle, then `"0"`.

Manual trial (the acceptance test):

1. Install the trial APK over the existing app.
2. Set the car's AC limit to a known value.
3. Lab → Read: active value and list must match the car.
4. Lab → Set a different supported value.
5. Check the car/charger display.
6. Lab → Read: cloud value must match the car.
7. Force a failure (for example a custom invalid value or offline car) and
   confirm the console shows a copyable error without a rebuild.

## Success criteria

- The read path is proven accurate against the car.
- The write path is either proven effective or conclusively rejected with
  visible evidence.
- Every variant tried during the session is reachable from the Lab.
- The release APK contains no visible or launchable Lab entry.
- The trial APK installs in place with the canonical signature.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Write acked but car unchanged | Re-read loop + `UNCHANGED`/`PENDING` verdict; record charging state |
| Guest account / `userType` confusion | Plain token envelope only; never auto-retry with guest mode; show `1001` as a real rejection |
| Apply latency differs by state | Record plugged/charging state; allow manual re-read after replug/next session |
| Cloud returns stale cached value | Show timestamp, allow repeated reads, show raw payload |
| Endpoint unsupported (1001) | Show raw code/message; allow custom payload probes |
| Vehicle offline (6002) | Show `UNREACHABLE`; no auto-retry |
| Region/trim differences | `energyType` and raw payload editable; context card shows region |
| Accidental write while driving | Confirmation dialog; no background writes; do not expose on watch |
| Lab leaks to release | BuildConfig gate at drawer, activity entry, and `onCreate` |

## Decisions left to the builder

- Activity vs. in-activity card (activity is assumed).
- Exact widget layout and styling; follow existing dark theme tokens.
- Ring-buffer size and copy/share formatting.
- Delays and attempt counts for the re-read loop.
- Whether the typed production methods ship alongside the Lab or only the Lab
  path ships now.
- Exact string resources and Indonesian translations.
