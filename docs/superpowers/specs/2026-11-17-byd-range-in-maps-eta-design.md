# BYD battery + range in the Android Auto navigation card — design

Date: 2026-11-17
Status: agreed with the user (all decisions below were chosen explicitly)

## Problem

The phone never sees the car's CAN bus. In Android Auto, Google Maps shows time and distance to the
destination, but nothing about the car's state. `byd-open-remote` already knows SoC and electric range
through the BYD cloud API, so the figure can be produced on the phone and shown inside Maps' own ETA card.

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Target host | **Phone Maps projected into Android Auto** | BYD services exist only on the head unit; the phone case is where the cloud data is the only source |
| Base bundle | **`nl-morphe-patches` (GPL-3.0), Maps `26.44.06.1006895251` (vc 1068856906)** | Their hooks are verified on that exact build; the AA adapter, rename and microG-RE support already exist |
| Packaging | Renamed package (`org.ungoogled.android.apps.maps[.microg]`), installed via KingInstaller + Shizuku | Stock name cannot be installed over the preinstalled system app, and Google sign-in dies on re-sign |
| Sign-in | microG-RE through their `Add microG-RE support` patch | Native Google sign-in is impossible on any re-signed build |
| Display | **Inside the ETA/status text of the navigation card** | User's explicit choice; the anchor is a single injection point |
| Data path | **ContentProvider + broadcast nudge** | Robust when Maps starts before the bridge; no polling in the UI thread |

## Architecture

```
BYD cloud ──► byd-open-remote (com.bydopenremote.app)
                BydRepository.getCachedStatus()
                        │  ContentProvider  content://com.bydopenremote.app.vehicle/status
                        │  + broadcast com.bydopenremote.app.action.VEHICLE_STATUS_CHANGED
                        ▼
              patched Maps (org.ungoogled.android.apps.maps)
                patches.maps.gearslip.BydVehicle      ← cache, receiver, async query
                patches.maps.gearslip.BydEtaText      ← pure formatting rules
                        │  decorate(String) at Lbltz;-><init> (status text of the turn card)
                        ▼
              TurnCardStatusContainerLayout → @id/status_text
```

## Anchor (verified in the 26.44.06 decode)

- `res/layout/adaptive_turn_card_current_step_layout.xml`: `TurnCardStatusContainerLayout` → `TextView @id/status_text`.
- `smali_classes7/bmkg.smali` ~line 2274 is the only `setText` on that TextView, fed by `Lbltz;->b:Ljava/lang/String;`.
- `smali_classes7/bltz.smali`: `(Lblty;Ljava/lang/String;Lj$/util/Optional;Lbluk;)V` — parameter 2 is the text.
- `smali_classes4/rrc.smali` (dex 4 = `gmm.car`) references `navcore/turncard`, so the car surface uses this card.

## Data contract

| Item | Value |
|---|---|
| Authority | `com.bydopenremote.app.vehicle` |
| Path | `/status` |
| Columns | `soc` INTEGER 0–100, `-1` unknown · `range_km` INTEGER ≥ 0, `-1` unknown · `ts` INTEGER epoch ms · `distance_unit` TEXT `km`/`mi` · `vin` TEXT nullable |
| Permission | `com.bydopenremote.app.permission.READ_VEHICLE`, `protectionLevel="normal"` (the patched Maps is re-signed, so `signature` would never be granted) |
| Broadcast | `com.bydopenremote.app.action.VEHICLE_STATUS_CHANGED`, sent with `setPackage(<target>)` |
| Target package default | `org.ungoogled.android.apps.maps`, configurable in byd-open-remote |

## Rules

- Range is sent in km; the display converts nothing — it shows what the provider reports with the provider's unit label.
- Unknown values never produce text: `soc < 0` and `range_km < 0` means "no suffix".
- Data older than 15 minutes is treated as unknown.
- The suffix is only appended when the original status text looks like a navigation status (contains a digit and
  one of `min`, `km`, `mi`, `hr`), so messages like "Rerouting…" stay untouched.
- No ContentProvider query on the UI thread: `decorate` reads a cache; the cache is filled by the broadcast and by
  an asynchronous refresh when it goes stale.

## Out of scope

- Head-unit Maps Automotive (`com.google.android.apps.maps.automotive`) — covered by the existing
  `romanchukg-cloud/maps-automotive-for-byd-patches` project.
- Charge state, temperatures, door state; only SoC and range.
- Publishing the fork; GPL-3.0 obligations apply if it is ever distributed.
