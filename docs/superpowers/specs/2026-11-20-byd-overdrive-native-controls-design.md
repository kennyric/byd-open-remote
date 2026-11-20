# Native Overdrive controls — design

Date: 2026-11-20
Branch: `feat/byd-charging-lab`

## Problem

The AC charge current limit and the charging schedule cannot be written through
the BYD Cloud API the app already uses. The car's **Overdrive** server exposes
both through its local HTTP API, but it is a web app: today the app only opens
its pages in a WebView, so the user still taps through Overdrive's UI.

The user wants a **native UI**: current limit and schedule edited in the
companion app, with Overdrive used only as the transport.

## Approach

Add a native Overdrive API client plus a dedicated controls screen.

```
Native screen → Overdrive local API → car SDK / BYD cloud
```

The app never calls BYD Cloud for these two controls.

### Auth

Native `/api/*` calls need a JWT:

1. `GET /auth/status` (public) → `{ "deviceId": "byd-xxxxxxxx" }`.
2. The user enters Overdrive's **access code** once in Advanced.
3. `POST /auth/token` with `{ "token": "<deviceId>-<accessCode>" }` →
   `{ "success": true, "jwt": "...", "expiresIn": 2592000 }`.
4. The JWT is stored on the phone and sent as
   `Authorization: Bearer <jwt>` (30-day expiry).

The access code is Overdrive's device secret. It is only used to mint the local
session; it is never sent to BYD.

### Endpoints

| Method | Path | Body / response |
| --- | --- | --- |
| `GET` | `/api/vehicle/ac-charge-current-limit` | `{ success, supported, state (1..5), label }` |
| `POST` | `/api/vehicle/ac-charge-current-limit` | `{ state: 1..5 }`, readback-verified |
| `GET` | `/api/vehicle/charging-schedule` | `{ success, supported, enabled, startChargeTime, endChargeTime, chargeWay, source }` |
| `POST` | `/api/vehicle/charging-schedule` | `{ startChargeTime, endChargeTime, chargeWay, enabled }` |
| `POST` | `/api/vehicle/start-charging` | `{}` |

Current-limit `state`: `1 = 6 A`, `2 = 8 A`, `3 = 10 A`, `4 = 16 A`, `5 = Max`.

`chargeWay`: `s` = once, `e` = every day, `0`–`6` = weekday list with `Mon = 0`.
`endChargeTime` may be the sentinel `full`.

Only `enabled` is required for a toggle-only schedule write; a full save needs
all three of `startChargeTime`, `endChargeTime`, `chargeWay`.

### Screens

1. **Controls page** — the Overdrive card below TPMS becomes a summary: current
   limit, schedule window, and `Open Overdrive controls ›`. It is visible only
   when Overdrive controls are enabled, an address is set, and the car answers
   `/auth/status`.
2. **Overdrive controls** (new activity) — segmented current limit
   (`6 / 8 / 10 / 16 / Max`), schedule editor (start, end, enabled, repeat), Save,
   and Start charging now. Pull-to-refresh style reload on open and on resume.
3. **Advanced** — access-code field with `Connected` / `Login needed` /
   `Not reachable`, a *Connect* action, and *Disconnect*.

If the JWT is rejected (401/403), the session is cleared and the screen shows
`Login needed` instead of retrying in a loop.

## Non-goals

- No BYD Cloud calls for current limit or schedule.
- No Overdrive web UI interaction after the one-time access code.
- No version bump and no GitHub release until the user tests the APK.

## Testing

- URL/token construction, `state ↔ label` mapping, `chargeWay` payload and
  parsing, schedule model round-trip.
- Session storage: save/clear, expiry, masked access code.
- Activity gating: card hidden unless enabled + address + reachable; controls
  screen shows `Login needed` on a 401 and clears the stored session.
