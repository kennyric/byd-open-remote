# Releasing

## 1. The signing key

All distributed APKs are signed with one canonical key. Changing it breaks in-place
updates for every installed user (`App not installed` /
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

- Certificate SHA-256:
  `D1:67:0D:07:3C:1A:85:42:B7:80:80:2A:DF:CC:41:DE:58:87:5B:C5:98:AD:CC:76:44:D8:CC:8F:15:5C:B8:99`
- Current location: `C:\Users\kenny\.android\debug.keystore`
  (alias `androiddebugkey`, store/key password `android`)
- Keep a copy at `~/.android/byd-open-remote.jks` and back it up somewhere durable.

`android-companion/app/build.gradle` signs **both** build types and **both** flavors with
this key, and `assemble*`/`bundle*` fail if the keystore is missing or its certificate does
not match the fingerprint. Do not bypass that check.

Key lookup order:

1. `BYD_KEYSTORE` (env)
2. `byd.keystore` in `android-companion/local.properties`
3. `~/.android/byd-open-remote.jks`
4. `~/.android/debug.keystore`

Password/alias overrides: `BYD_KEYSTORE_PASSWORD`, `BYD_KEY_ALIAS`, `BYD_KEY_PASSWORD`, or
`byd.keystore.password`, `byd.key.alias`, `byd.key.password` in `local.properties`.

## 2. Bump the version

`android-companion/app/build.gradle` → `defaultConfig`:

```groovy
versionCode N+1      // must be greater than the installed build
versionName 'x.y.z'
```

The demo flavor has its own `versionCode`/suffix and is not shipped to users.

## 3. Build

```bash
cd android-companion
./gradlew.bat :app:assembleStandardRelease
```

Artifact: `app/build/outputs/apk/standard/release/BYD-Open-Remote.apk`.

Debug builds are signed with the same key, so a debug APK can stand in when needed.

## 4. Verify before publishing

```bash
apksigner verify --print-certs app/build/outputs/apk/standard/release/BYD-Open-Remote.apk
```

The printed SHA-256 must equal the fingerprint in section 1. For belt and braces, install
it over the previous release on a device/emulator:

```bash
adb install -r BYD-Open-Remote.apk     # must print Success
```

## 5. Publish

Asset name must stay `BYD-Open-Remote.apk` — the in-app updater picks assets by name and
trusts the release URL. Do **not** upload the demo APK to a release: it shares the
`BYD-Open-Remote` prefix and has historically been picked first when GitHub lists it
ahead of the companion (the updater now skips any asset containing `demo`, but keeping it
out of releases is the real guarantee).

```bash
gh release create vX.Y.Z path/to/BYD-Open-Remote.apk \
  --title "BYD Open Remote vX.Y.Z" --notes "…"

# replacing an existing asset (also used to re-sign old releases)
gh release upload vX.Y.Z path/to/BYD-Open-Remote.apk --clobber
```

## 6. Re-signing an existing APK

Only valid if you own the target private key. Never use another keystore.

```bash
zipalign -p -f 4 in.apk aligned.apk
apksigner sign --ks ~/.android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out out.apk aligned.apk
apksigner verify --print-certs out.apk
```

## 7. When users report “App not installed”

1. Compare certificates of the installed package and the new APK
   (`apksigner verify --print-certs`). If they differ, it is a signing break, not a
   versionCode problem.
2. Find the key that signed the installed build; sign the new APK with **that** key if it
   still exists. Only then can users update in place.
3. If the key is lost, users must uninstall once (data is lost — `allowBackup=false`).
4. Never “fix” it by publishing under a new package name or by bumping the versionCode.
