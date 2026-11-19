# Repository guide (agents & humans)

Project: BYD Open Remote — Android companion (`android-companion/`) plus the Huawei watch module.

## APK signing is mandatory and fixed

Every distributed APK must be signed with the project's canonical key. A different
signature makes Android refuse updates with **“App not installed”**
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) even when the versionCode is higher.

- Canonical certificate SHA-256:
  `D1:67:0D:07:3C:1A:85:42:B7:80:80:2A:DF:CC:41:DE:58:87:5B:C5:98:AD:CC:76:44:D8:CC:8F:15:5C:B8:99`
- Key file (this machine): `C:\Users\kenny\.android\debug.keystore`
- Alias `androiddebugkey`; store/key password `android`
- Prefer keeping a durable copy at `~/.android/byd-open-remote.jks` and backing it up.

`android-companion/app/build.gradle` already wires this key into **both** build types
(`debug` and `release`) for **both** flavors, and fails `assemble*`/`bundle*` when:

1. no keystore is found, or
2. the keystore's certificate does not match the fingerprint above.

Key resolution order (first existing wins):

1. `BYD_KEYSTORE` environment variable
2. `byd.keystore` in `android-companion/local.properties`
3. `~/.android/byd-open-remote.jks`
4. `~/.android/debug.keystore`

Overrides: `BYD_KEYSTORE_PASSWORD`, `BYD_KEY_ALIAS`, `BYD_KEY_PASSWORD`, or the matching
`byd.*` entries in `local.properties`.

### Rules

- **Never** change `applicationId` for an existing package.
- **Never** generate a new signing key, “fix” an install error by re-signing, or build a
  distributable APK with a machine's default debug keystore.
- If the key is missing, stop and ask the maintainer; do not substitute another keystore.
- Verify before publishing:
  ```bash
  apksigner verify --print-certs BYD-Open-Remote.apk
  ```
  The SHA-256 must equal the fingerprint above.
- Signing keys must never be committed to the repository.

## Build & test

```bash
cd android-companion
./gradlew.bat :app:testStandardDebugUnitTest
./gradlew.bat :app:assembleStandardDebug   # com.bydopenremote.app
./gradlew.bat :app:assembleDemoDebug       # com.bydopenremote.demo (screenshots)
```

## Release

Follow `docs/RELEASING.md`: bump `versionCode`, build, verify the certificate, publish the
asset as `BYD-Open-Remote.apk` on a GitHub release.

## Other notes

- Demo flavor must never share a package, authority or permission with the standard app.
- Do not commit `local.properties`, keystores, or built APKs.
