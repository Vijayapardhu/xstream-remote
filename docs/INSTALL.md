# Install, build and pair

## What v2 needs

v2 has **no client certificate assets**. The phone generates its own RSA
identity the first time it pairs, and the TV certificate is pinned after the
six-character code succeeds. A fresh clone builds a universal APK; a fresh
install can pair any supported Android TV box on the same LAN.

The repository does not distribute a prebuilt APK. Build one with your own
signing key (or the Android debug key for personal testing).

## Requirements

- Windows 10/11 for the current PowerShell build script
- JDK 11 or newer on `PATH` (`javac`, `keytool`)
- Android SDK build-tools **34.0.0** (`aapt2`, `d8`, `zipalign`, `apksigner`)
- Android platform **android-34** (`android.jar`)
- Python 3.9+ on `PATH` (UI and icon build)
- A phone on Android 7.0+ and a TV exposing Android TV Remote Service
- Both devices on the same LAN, with client isolation/VLAN separation disabled

The build script defaults to:

```text
C:\cmdline-tools\build-tools\34.0.0
C:\cmdline-tools\platforms\android-34\android.jar
```

Override the SDK paths if needed:

```powershell
$env:ANDROID_BUILD_TOOLS = "C:\Android\Sdk\build-tools\34.0.0"
$env:ANDROID_JAR = "C:\Android\Sdk\platforms\android-34\android.jar"
```

## 1. Build

Release-style build:

```powershell
cd apk
$env:XSTREAM_STORE_PASS = "choose-a-keystore-password"
powershell -ExecutionPolicy Bypass -File build.ps1
```

The first run creates `apk\keystore.jks` if it is missing. Keep the keystore and
its password backed up and out of Git. To use an existing key:

```powershell
$env:XSTREAM_KEYSTORE = "C:\keys\xstream-release.jks"
$env:XSTREAM_KEY_ALIAS = "xstream"
$env:XSTREAM_STORE_PASS = "your-password"
```

Local test build with the standard Android debug key:

```powershell
cd apk
$env:XSTREAM_STORE_PASS = "android"
$env:XSTREAM_KEYSTORE  = "$env:USERPROFILE\.android\debug.keystore"
$env:XSTREAM_KEY_ALIAS = "androiddebugkey"
powershell -ExecutionPolicy Bypass -File build.ps1
```

If that keystore does not exist, create it once with `keytool` (or build once
from Android Studio). The debug build is fine for your own phone, but sign with
your own release key before sharing an APK.

Output: `apk\XstreamRemote-2.0.apk`.

The build bundles only `assets/ui/index.html`. It does not read `client.pem` or
`client.key`, and the APK contains no client private key. The script prints the
signer certificate and verifies the v2/v3 signatures.

If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` appears, the phone already has the app
signed with a different key. Uninstall it first. Uninstalling clears the paired
host and the per-install identity.

## 2. Install

With USB debugging enabled and the phone authorized:

```powershell
adb install -r apk\XstreamRemote-2.0.apk
```

Or copy the APK to the phone and open it, allowing installs from the file
manager/browser. The package name is `com.xstream.remote`.

## 3. Pair

1. Put the phone and TV on the same Wi-Fi/LAN.
2. Open the app. It scans for boxes, or you can type the TV's IP.
3. Choose the TV. The TV displays a six-character code.
4. Enter the code in the app. On success the app pins the TV certificate and
   connects.

Pairing uses TLS on port **6467**. Normal control uses TLS on port **6466**.
The client private key is created on the phone and stored in the app's private
files directory; it is never packaged, exported, or logged.

If discovery does not find the box, enter its IP manually. Guest Wi-Fi, AP
isolation, VLAN separation, and client isolation block discovery and control.

## Re-pairing and forgetting

Re-pair from **Settings → Pair / re-pair this box** when:

- the TV was factory reset or its pairings were cleared;
- the TV was replaced;
- the app reports an authentication or certificate-pinning error;
- the TV's IP changed and the old pin no longer matches the box at that address.

**Disconnect** stops the session and keeps the identity and pin. **Forget**
removes the remembered host, identity and pin; pair again to control the box.

## 4. Run the tests

```powershell
node apk\tools\ui-test.js
```

Set `CHROME_PATH` if Chrome is not in the default Windows location. The test
loads the generated UI in preview mode with a mock bridge, and checks:

- navigation and pairing dialogs;
- customization, import/export and reset;
- the app-aware context bar;
- the live, media and other-app layouts;
- all 22 keys and the numbers pad;
- keyboard text, macros, app links and mute state;
- volume hold-to-repeat and comfort settings.

The JVM protocol harnesses under `apk/test/` are manual tools for a paired box.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Build says `missing build tool` | Set `ANDROID_BUILD_TOOLS` and `ANDROID_JAR`; install build-tools 34 and platform 34. |
| Build says `Set XSTREAM_STORE_PASS` | Set the environment variable before running `build.ps1`. |
| Box not discovered | Same subnet, no guest network or AP/client isolation, mDNS allowed; the app also sweeps port 6466. Try manual IP. |
| Connect times out | Box awake and on the same LAN; port 6466 reachable; try manual IP. |
| TV does not show a code | The box may have an existing pairing prompt or a full pairing list. Clear old pairings in the TV settings, then start pairing again. |
| Pairing code rejected | Codes expire quickly; keep the TV awake and enter all six characters promptly. |
| Certificate mismatch after pairing | The box at that IP changed or was factory reset. Forget it and pair again. |
| `INSTALL_FAILED_OLDER_SDK` | The phone must be Android 7.0 (API 24) or newer. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The signing key changed; uninstall the old package first. |

## Release checklist

- [ ] `git status` shows no `apk/assets/client.*`, `apk/keystore.jks`, APKs,
      idsig files, or `remote/certs/`.
- [ ] Build with the release keystore, not the debug key.
- [ ] `apksigner verify --print-certs apk\XstreamRemote-2.0.apk` is clean.
- [ ] `unzip -l apk\XstreamRemote-2.0.apk` shows only the generated UI asset,
      resources and classes — no client key.
- [ ] Install, pair from the TV code, and exercise connect, D-pad, volume,
      keyboard, macros, app links and reconnect.
- [ ] Do not commit or upload the built APK; publish source and let users sign
      their own.

## Security notes

- The client private key is per install and lives in app-private storage. It is
  not encrypted at rest beyond Android's app sandbox.
- The TV's SHA-256 certificate fingerprint is pinned per host after pairing. A
  different certificate at the same IP is rejected.
- WebView debugging is off unless the activity is started with
  `--ez xstream_debug true`.
- The app is LAN-only. It does not bypass DRM, subscriptions or credentials.
- The Python bridge under `remote/` is an optional development tool; do not
  expose its port to the internet.
- This project is not affiliated with Airtel, Google, or Vantiva. Use it only on
  devices you own or are authorized to control.
