# LocalSend build-and-send

`build-and-send.ps1` builds the standard debug APK and opens the LocalSend
device list with that APK attached.

## Run

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-and-send.ps1
```

- The phone must have **LocalSend open** and be on the same network.
- Select the phone in the device list with `1-9`.
- To send an existing APK without rebuilding:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-and-send.ps1 -NoBuild
```

## Limitations

The current LocalSend CLI release (v1.18.2) has no `send --to` command and
does not support receiver PINs. If the phone asks for a PIN, disable it in
LocalSend or send from the LocalSend app manually.

Fully headless sending would require either:

- the newer `localsend-cli send --to <alias-or-ip>` command from LocalSend
  `main` (not in the v1.18.2 release), or
- a direct implementation of the LocalSend v2 prepare-upload/upload protocol
  (HTTPS with the sender identity certificate).

The CLI executable is downloaded on first use and is intentionally ignored by
git.
