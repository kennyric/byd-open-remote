# Overdrive remote access: Tailscale + parked surveillance

> Bahasa Indonesia: [Panduan akses jarak jauh Overdrive](overdrive-remote-access.id.md)

This guide gets the car's Overdrive server reachable from your phone **from anywhere** — including
while the car is parked and its screen is off — and then connects BYD Open Remote to it.

It covers three things:

1. Overdrive on the head unit, with the background/ADB access it needs.
2. Parked surveillance + the Sentry Daemon, so the head unit keeps mobile data while the car is off.
3. A Tailscale tunnel, so the phone can reach the car without opening router ports.

> Menu names inside Overdrive vary between alpha versions and between DiLink 3/4/5. If a label does
> not match exactly, look for the closest equivalent; the flow is the same.

---

## TL;DR

1. Install Overdrive on the head unit and grant its ADB request.
2. Overdrive → **Sentry Daemon** + parked **surveillance** → keep the car online while parked.
3. Overdrive → **Tailscale** → generate login URL → sign in on the phone → note the car's address.
4. BYD Open Remote → **Advanced** → address `http://<car-tailscale-ip>:8080` → save.
5. Status shows **Login needed** → enter the Overdrive access code → **Connect**.
6. Drawer → **Overdrive** (or the main-page controls card, if enabled).

---

## Requirements

| Item | Notes |
|---|---|
| BYD with DiLink 3, 4 or 5 | Overdrive supports arm64 head units, Android 10+ |
| Panoramic camera system | Needed for Overdrive's surveillance/camera features |
| Wireless ADB on the head unit | Overdrive asks for it on first launch |
| Tailscale account | Free for personal use |
| BYD Open Remote ≥ 1.3.2 | Earlier builds cannot open `http://` pages in release builds |
| Active data plan on the car | The BYD SIM (or a hotspot) must stay online while parked |

---

## 1. Overdrive on the car

1. Download the current Overdrive APK from its Releases page and install it on the head unit
   (the project's setup guide shows the ADB/Termux install step for your DiLink version).
2. Enable **Wireless ADB** on the head unit.
3. Open Overdrive and accept the ADB authorization prompt.
4. Leave **Disable Autostart** off, so the app can come back after the car sleeps.
5. Open the Overdrive web UI once on the car's screen and finish its first-run setup.

Overdrive runs the web server on port **8080**:

```
http://<car-ip>:8080
```

**Connect the phone to the same Wi-Fi network as the car for this first step.** Open the URL above
on the phone to confirm you can reach the head unit before configuring anything else.

### Where to find the IP, port and access code

On the head unit:

- **IP address** — Overdrive shows its server address on the dashboard or in its settings/status
  card, for example `http://192.168.1.50:8080`. If it only shows the hostname, read the head unit's
  current Wi-Fi IP from Android's Wi-Fi settings.
- **Port** — **8080** by default. It is part of the address Overdrive displays; only change it if
  you deliberately changed the server port.
- **Access code** — shown on Overdrive's **main page / dashboard** on the head unit, usually as
  “Access code: …”. You can also view or rotate it under **Settings → Access / Security** (the exact
  label varies by build). Copy it — this is what you enter in BYD Open Remote later. Treat it like
  the car key.

### Before you start: turn off the head unit's Traffic Monitor

DiLink's **Traffic Monitor / data limit** can pause or restrict Overdrive's background data,
especially while the car is parked. Disable it (or raise its limit well above Overdrive's usage)
during setup, and ideally leave it off for parked use.

On the same Wi-Fi this already works; the rest of this guide makes it work from anywhere.

---

## 2. Keep the car online while parked (surveillance / Sentry Daemon)

This is the step people miss. Without it the head unit goes to sleep, the Tailscale tunnel drops, and
the car looks "offline" from the phone even though everything else is configured.

In Overdrive:

1. Open **Sentry Daemon** (the "stay-awake helper") and enable it.
2. Enable parked **surveillance** / Sentry mode.
3. Use **Vehicle ON + OFF** mode if your build offers it — that is the mode meant for parked use.
4. Accept any power/network permission prompts.
5. Exclude Overdrive from battery optimization on the head unit, and allow autostart.

What this does: the daemon requests the power and network access Overdrive needs to keep running
while the car is off. **Your firmware decides which requests it accepts**, so always test it (step 5).

Practical notes:

- Expect some 12 V battery drain; parked surveillance is not free. A short daily drive recharges it.
- The BYD SIM data plan must be active. If the car loses mobile data, no tunnel can reach it.
- Keep the head unit's **Traffic Monitor / data limit** off (see Part 1), or it can throttle
  Overdrive while parked.
- If the car sleeps anyway, re-check the daemon, the autostart setting, and the firmware's own
  "keep network alive" option (naming differs per model).

---

## 3. Tailscale tunnel

Overdrive has Tailscale built in. **Do not install a second Tailscale app on the head unit** — you
only install Tailscale on the phone.

### On the car

1. Overdrive → **Settings → Tailscale**.
2. Generate the login URL.
3. Open that URL on your phone, sign in to your Tailscale account, and approve the car.
4. Optionally rename the car's device to something short and neutral (a label only; keep using the
   numeric IP in BYD Open Remote).
5. Note the car's **numeric Tailscale IP** (starts with `100.`) in the Tailscale app or admin
   console. Use that IP in BYD Open Remote; do not rely on a hostname.
6. In the Tailscale admin console, **disable key expiry** for the car so it does not drop every
   six months.

### On the phone

1. Install Tailscale and sign in to the **same** tailnet.
2. Keep Tailscale connected (its VPN notification should be visible).
3. If you use custom ACLs, allow your phone to reach the car on port **8080**.

### Test it

With the phone on mobile data (Wi-Fi off), open:

```
http://<car-tailscale-ip>:8080
```

You should get the Overdrive login page. If it works, the hard part is done.

---

## 4. Connect BYD Open Remote

Open **BYD Open Remote → Advanced**.

| Field | What to enter |
|---|---|
| Address | `http://<car-tailscale-ip>:8080` — use the numeric `100.x` address from Tailscale |
| Page | A preset like `Vehicle control`, or **Manual URL** for a specific page |
| Overdrive access code | The code shown on Overdrive's main page (see Part 1) |

Then:

1. Tap **Save**.
2. Return to Advanced; the status should move off **Not reachable**.
3. If it says **Login needed**, enter the access code and tap **Connect**.
4. Once it says **Connected**, open **Overdrive** from the drawer.

Good to know:

- The address field accepts a full pasted URL (`http://…/login.html?redirect=/index.html`); only the
  host and port are used for login and controls. A pasted page URL is kept for the WebView.
- Changing the address clears the Overdrive session by design; re-enter the access code after a
  change.
- The access code is a long-lived device secret. It is stored encrypted on the phone. If you rotate
  it in Overdrive, reconnect here.
- **Advanced → BYD Cloud session** is a different setting: it controls how often the app fetches new
  BYD **Cloud** tokens. It has nothing to do with Overdrive.

Optional: **Advanced → Overdrive controls on the main page** chooses whether the AC-current /
charging-schedule card is always shown, shown only when connected, or hidden.

---

## 5. Test checklist

Do this once, parked, with the car locked:

- [ ] Phone on **mobile data only**, Tailscale connected.
- [ ] `http://<car-tailscale-ip>:8080` opens the Overdrive page.
- [ ] BYD Open Remote → Advanced shows **Connected**.
- [ ] Drawer → Overdrive opens the car UI and login sticks.
- [ ] Lock the car, wait 10–15 minutes, then reload the page from the phone.
- [ ] No `ERR_CLEARTEXT_NOT_PERMITTED` error (requires BYD Open Remote ≥ 1.3.2).
- [ ] Optional: the main-page AC-current card shows live values while parked.

If the last two checks fail after parking, the Sentry Daemon / surveillance step is not holding the
car online yet.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| **Not reachable** | Phone's Tailscale is off, or the car is asleep/offline | Check the Tailscale app; wake the car; test the URL in the phone browser |
| Works on Wi-Fi, not on mobile data | Car lost mobile data while parked | Enable the Sentry Daemon + surveillance; check the BYD SIM plan |
| **Login needed** | Access code missing, expired JWT, or address changed | Enter the code again; reconnect |
| Worked, stopped after months | Tailscale key expired | Disable key expiry for the car in the Tailscale console |
| Address no longer answers | Car's Tailscale IP changed | Update the address in Advanced (this clears the session) |
| Page loads, controls fail | Wrong page preset, or the car does not expose that endpoint | Try `/vehicle-control.html`; check the Overdrive version |
| `ERR_CLEARTEXT_NOT_PERMITTED` | Old app build | Update BYD Open Remote (1.3.2+) |
| Slow / stuttering | Tailscale is relaying traffic (DERP) instead of a direct path | Check `tailscale status` on the phone; both devices behind hard NAT is normal |

---

## Security notes

- Tailscale is WireGuard: the car's plain-HTTP Overdrive page travels **inside an encrypted tunnel**.
  Do not port-forward port 8080 on your router.
- Keep the Tailscale ACL narrow: only your own devices, only the car's port 8080.
- Treat the access code like the car key. Rotate it in Overdrive if it may have leaked.
- Parked surveillance records camera footage on the car; check local privacy rules before enabling it
  in public places.
