# BYD Charging-Current Lab — On-Car Trial

Build: `BYD-Open-Remote.apk` (standardDebug, canonical signature)
Trial date: ____
Tester: ____
Region: ____
VIN (masked): ____
App version: ____ (`Settings` → version)

## Preconditions

- [ ] Debug/trial APK installed over the existing app (`adb install -r` → `Success`)
- [ ] BYD account configured in the app (VIN verified, operation PIN saved)
- [ ] Car parked; AC charger connected
- [ ] Charging started, or ready to start
- [ ] Note the car/charger display's current AC limit before opening the Lab

## Trial protocol

1. [ ] Plug in the AC charger (start charging if possible).
2. [ ] Set a known limit in the car; record it.
3. [ ] Lab → **Read limit**; record active value and supported list.
4. [ ] Lab → select/type a different supported value → **Set limit** → confirm.
5. [ ] Watch the car/charger display for a change.
6. [ ] Lab → **Read limit** again; record the verdict and whether it matches the car.
7. [ ] If `PENDING`/`UNCHANGED`, re-read after replug/next charge start; record.
8. [ ] Force one failure (invalid value or car offline); confirm the console shows a copyable error.

## Results

| Step | Car state (plugged/charging/power) | Car setting | App read | App set | App re-read | Car display | Verdict | Notes |
|---|---|---|---|---|---|---|---|---|
| Baseline |  |  |  | — | — |  | — |  |
| Read after car change |  |  |  | — | — |  | — |  |
| Write test |  |  |  |  |  |  |  |  |
| Re-read / next session |  |  |  | — |  |  |  |  |
| Forced failure |  | — |  |  | — | — |  |  |

Verdict values: `APPLIED`, `UNCHANGED`, `CHANGED`, `PENDING`, `REJECTED`, `UNREACHABLE`, `ERROR`.

## Raw console paste

Paste the Lab **Copy** output here (VIN is masked):

```text

```

## Observations

- Did the read match the car exactly? ____
- Did the write reach the car? ____
- Delay before the cloud reflected the new value: ____
- Did the value apply immediately, only while charging, or only on the next session? ____
- Error codes seen (`1001`, `6002`, other): ____

## Follow-up

- [ ] If `APPLIED`: the production read/write path can be designed.
- [ ] If `UNCHANGED`/`PENDING`: repeat with the car charging and after a replug.
- [ ] If `REJECTED` with `1001`: record trim/region; the endpoint is not supported for this account.
- [ ] If `UNREACHABLE` with `6002`: retry when the car has cellular coverage.
- [ ] Attach the copied console to the issue/PR.
