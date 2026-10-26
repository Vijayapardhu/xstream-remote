# Install and build

## What v1 needs

v1 is a source release with one important limitation: the APK must contain a
client identity that your TV has already paired. The repository does not ship
that identity. You create it with the Python bridge, copy it into the app, and
build.

If you were given a prebuilt v1 APK, it was built with its author's identity.
Keep it private and use it only with the TV it was paired to.

## Requirements

- Windows 10/11 for the current PowerShell build script
- JDK 11 or newer on `PATH` (`javac`, `keytool`)
- Android SDK build-tools **34.0.0** (`aapt2`, `d8`, `zipalign`, `apksigner`)
- Android platform **android-34** (`android.jar`)
- Python 3.9+ on `PATH`
- A phone on Android 7.0+ and a TV exposing Android TV Remote Service
- Both devices on the same LAN, with client isolation/VLAN separation disabled

The build script defaults to:

```text
C:\cmdline-tools\build-tools\34.0.0
C:\cmdline-tools\platforms\android-34\android.jar
```

Override them if needed:

```powershell
$env:ANDROID_BUILD_TOOLS = "C:\Android\Sdk\build-tools\34.0.0"
$env:ANDROID_JAR = "C:\Android\Sdk\platforms\android-34\android.jar"
```

## 1. Pair your TV

Install the bridge dependencies and start pairing:

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r remote\requirements.txt

python remote\server.py --pair 192.168.1.10
```

Replace `192.168.1.10` with the TV's LAN address. The TV shows a 6-character
code. Enter it at the `code>` prompt. On success the bridge writes:

```text
remote\certs\client_192.168.1.10.pem
remote\certs\client_192.168.1.10.key
```

The bridge creates a default client certificate on first run if one is missing;
the per-host files above are the ones the APK needs.

If you do not know the IP, run the bridge without `--pair`, open the URL it
prints, and use its scan/pair UI:

```powershell
python remote\server.py
```

## 2. Copy the identity into the APK

```powershell
Copy-Item remote\certs\client_192.168.1.10.pem apk\assets\client.pem
Copy-Item remote\certs\client_192.168.1.10.key apk\assets\client.key
```

Both files are gitignored. Never commit them, and never publish an APK that has
them inside.

## 3. Build

```powershell
cd apk
$env:XSTREAM_STORE_PASS = "choose-a-keystore-password"
powershell -ExecutionPolicy Bypass -File build.ps1
```

The first run creates `apk\keystore.jks` if it is missing. If you already have a
keystore, point the script at it:

```powershell
$env:XSTREAM_KEYSTORE = "C:\keys\xstream-release.jks"
$env:XSTREAM_KEY_ALIAS = "xstream"
$env:XSTREAM_STORE_PASS = "your-password"
```

The output is `apk\XstreamRemote-1.0.apk`, copied to `XstreamRemote.apk` at the
repository root. The script prints the signer certificate and verifies the v2
and v3 signatures.

Keep `keystore.jks` and its password backed up. Android only accepts an update
signed with the same key; losing it means uninstalling the old app before
installing a new build.

If `client.pem`/`client.key` are missing, the build completes with a warning and
the resulting APK cannot connect. Pair first, then rebuild.

## 4. Install

With USB debugging enabled and the phone authorized:

```powershell
adb install -r XstreamRemote.apk
```

Or copy the APK to the phone and open it, allowing installs from the file
manager/browser.

The package name is `com.xstream.remote`. The app opens on the Connect screen,
scans the LAN, and shows discovered boxes. Pick yours; the saved certificate is
used automatically.

### Signature mismatch

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the installed app was signed with a
different key. Back up anything you care about, uninstall the old package, and
install the new APK:

```powershell
adb uninstall com.xstream.remote
adb install XstreamRemote.apk
```

The app has no important local data, but uninstalling clears the remembered TV.
You can reconnect by tapping the box again.

## Re-pairing

Re-pair when:

- the TV was factory reset or its pairings were cleared;
- the TV is replaced;
- the bridge or app reports a certificate/auth rejection;
- you move to a different TV.

Run `python remote\server.py --pair <new-ip>`, copy the new
`client_<host>.pem`/`.key` over the two files in `apk\assets\`, and rebuild.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Build says `missing build tool` | Set `ANDROID_BUILD_TOOLS` and `ANDROID_JAR`; install build-tools 34 and platform 34. |
| Build says `Set XSTREAM_STORE_PASS` | Set the environment variable before running `build.ps1`. |
| APK builds but reports `client.pem` / `client.key` | Copy the per-host pair files into `apk\assets\` and rebuild. |
| Box not discovered | Same subnet, no guest network, no AP/client isolation, mDNS allowed; the app also sweeps port 6466. |
| Connect times out | Box awake and on the same LAN; port 6466 reachable; try the box's IP manually. |
| Pairing code rejected | The code expires; start `--pair` again. Keep the TV awake. |
| `INSTALL_FAILED_OLDER_SDK` | The phone must be Android 7.0 (API 24) or newer. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The signing key changed; uninstall the old package first. |

## Security notes

- The v1 client private key is embedded in the APK assets. Do not distribute a
  self-built v1 APK. v2 generates a per-install identity on the phone and removes
  the embedded key.
- v1 accepts the TV's self-signed certificate and displays its fingerprint; it
  does not pin it yet. v2 pins the TV after pairing.
- The Python bridge is a LAN tool. Do not expose port 8777 to the internet.
- This project is not affiliated with Airtel, Google, or Vantiva. Use it only on
  devices you own or are authorized to control.
