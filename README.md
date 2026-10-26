# Xstream Remote

A tiny native Android remote for **Airtel Xstream** and other **Android TV / Google TV** boxes. It talks directly to the box over your LAN using the Android TV Remote Service v2 protocol — no cloud, no ads, no analytics, no Gradle.

**v1.0.0** · **85.7 KB** · Android 7.0+ (API 24) · target API 34

> **v1 is pinned-cert.** This release is built around one pre-paired client certificate. The repository does **not** contain the author's `client.pem` or `client.key`; those files are gitignored. To build a working APK you pair your own box with the included Python bridge (see [Pairing](#pairing-v1)). In-app pairing, per-device certificates, and a saved-device list are the first **v2** milestone — see the [roadmap](docs/ROADMAP.md).

| Remote | Keys | Connect |
|:---:|:---:|:---:|
| <img src="docs/screenshots/phone-remote.png" width="240" alt="Remote screen"> | <img src="docs/screenshots/phone-keys.png" width="240" alt="Keys screen"> | <img src="docs/screenshots/phone-connect.png" width="240" alt="Connect screen"> |

More UI shots in [`docs/screenshots/`](docs/screenshots/).

## What works

- **Discovery** — mDNS (`_androidtvremote2._tcp.local`) plus a fast port-6466 sweep of the local /24.
- **One-thumb remote** — circular swipe pad, D-pad, OK, Back, Home, Menu, playback controls.
- **Keys** — a 20-key grid for TV functions, numbers, inputs, and shortcuts.
- **Volume + power** — live volume display, volume up/down, mute, and power. Volume is sent as Android TV key events.
- **Keyboard** — send text to the box's focused field; useful for search and logins.
- **Apps + links** — launch installed apps through `https://` and `market://details?id=` links.
- **Live status** — connection state, round-trip time, volume, box model, and the TV certificate fingerprint.
- **Reliable session** — ping replies, `remote_start` handshake, a 12 s setup watchdog, and automatic reconnect.
- **Small + local** — one WebView, hand-written protobuf, Lucide SVG icons, no runtime dependencies, no cloud calls.

## What v1 is not

- It is not universal yet: the APK needs a client certificate that was paired on a laptop.
- It does not pair from the phone, keep multiple TVs, or show a device list.
- It has no widgets, Quick Settings tile, or smart gestures beyond the swipe pad.
- It does not bypass DRM, subscriptions, or credentials. It only sends remote-control commands to a box you own.

Those are the v2 work items, in priority order, in [docs/ROADMAP.md](docs/ROADMAP.md).

## How it works

```text
Phone
┌──────────────────────────────────────────────────────────────┐
│  WebView UI  (HTML/CSS/JS + inline Lucide sprite)            │
│       │  window.Native  /  window.__tv                       │
│  MainActivity  ──  TvBridge  ──  AurSession  ── TLS 6466 ──► │  Android TV box
│                       │                                      │
│                    Discovery (mDNS + /24 scan)               │
└──────────────────────────────────────────────────────────────┘
```

- `AurSession` opens a TLS socket to port **6466**, sends a varint-framed `RemoteMessage` protobuf, advertises the supported feature mask, answers pings, and injects keys, text, volume, and app links.
- `TvBridge` owns the session lifecycle, reconnects, discovery, the setup watchdog, and the UI state.
- `MainActivity` hosts the WebView and exposes a small Java bridge.
- The APK is assembled with plain SDK tools: `aapt2` → `javac` → `d8` → zip assets → `zipalign` → `apksigner`. No Gradle, no AndroidX, no third-party runtime libraries.

Pairing uses port **6467** and is currently handled by `remote/server.py` (the Python `androidtvremote2` library), not by the phone app. v2 ports that flow into Java.

## Build

### Requirements

- JDK 11+ (tested with JDK 24)
- Android SDK **build-tools 34.0.0** and **platform android-34** (`android.jar`)
- Python 3.9+ (used to rebuild the UI and icon sprite)
- Windows for the current `build.ps1`; the Java/UI sources are portable

The Lucide SVGs are committed in `apk/icons/`, so the icon step works offline.

### Build

```powershell
cd apk
$env:XSTREAM_STORE_PASS = "choose-a-keystore-password"
powershell -ExecutionPolicy Bypass -File build.ps1
```

Outputs:

- `apk\XstreamRemote-1.0.apk`
- `XstreamRemote.apk` (copy at the repository root)

The script creates `apk\keystore.jks` on first run if it is missing. Keep that file, and the password, out of Git. If you already have a keystore:

```powershell
$env:XSTREAM_KEYSTORE = "C:\path\to\your.jks"
$env:XSTREAM_KEY_ALIAS = "your-alias"
$env:XSTREAM_STORE_PASS = "your-password"
```

SDK paths can be overridden when the defaults do not match your machine:

```powershell
$env:ANDROID_BUILD_TOOLS = "C:\Android\Sdk\build-tools\34.0.0"
$env:ANDROID_JAR = "C:\Android\Sdk\platforms\android-34\android.jar"
```

If `apk\assets\client.pem` and `client.key` are absent, the build still succeeds but warns that the APK cannot connect. That is expected for a fresh clone; pair first, then rebuild.

## Pairing (v1)

The app needs a client identity that the TV has accepted. The included Python bridge performs the pairing and saves that identity.

### 1. Set up the bridge

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r remote\requirements.txt
```

### 2. Pair the TV

Put the phone and the TV on the same Wi-Fi network, then run:

```powershell
python remote\server.py --pair 192.168.1.10
```

Replace the IP with your box's address (the bridge can also scan from its web UI). The bridge prints a local URL and a random PIN. The TV shows a 6-character pairing code; enter it at the `code>` prompt.

The bridge writes:

```text
remote\certs\client_192.168.1.10.pem
remote\certs\client_192.168.1.10.key
```

It also keeps `remote\certs\pairs.json` so the bridge can reconnect later. All of that is gitignored.

### 3. Put the identity in the APK

```powershell
Copy-Item remote\certs\client_192.168.1.10.pem apk\assets\client.pem
Copy-Item remote\certs\client_192.168.1.10.key apk\assets\client.key
```

Then rebuild (see [Build](#build)).

> **Do not publish an APK built this way.** The client private key is embedded in its assets. v2 removes the embedded key and generates a per-install identity on the phone.

### Re-pairing

If the TV rejects the identity (factory reset, cleared pairings, new network), pair again and replace both files. The bridge can also be started without `--pair` and used as a laptop remote:

```powershell
python remote\server.py
```

The bridge's web UI is for a trusted LAN. Do not expose port 8777 to the internet; use `--bind 127.0.0.1` if you only need it locally. See [`remote/README.md`](remote/README.md).

## Install

```powershell
adb install -r XstreamRemote.apk
```

Or copy the APK to the phone and open it (allow install from unknown sources). The phone and TV must be on the same network; guest Wi-Fi, VLANs, and AP isolation will block discovery and control.

## Project layout

```text
apk/
  AndroidManifest.xml
  build.ps1                  # aapt2/javac/d8/zipalign/apksigner pipeline
  java/com/xstream/remote/   # MainActivity, TvBridge, AurSession, Discovery, Proto
  res/                       # theme, launcher icons, strings
  web/                       # index.template.html + Lucide sprite
  tools/                     # UI builder, icon fetch, screenshot helpers
  test/                      # JVM protocol tests
  assets/ui/index.html       # generated, committed for convenience
remote/
  server.py                  # optional Python bridge + pairing helper
  ui.html
  requirements.txt
docs/
  ARCHITECTURE.md
  INSTALL.md
  ROADMAP.md
  screenshots/
```

The separate `TvLauncher/` Gradle experiment and the original network research notes are intentionally not part of this repository.

## Security and privacy

- **LAN only.** The app has no cloud endpoints, analytics, accounts, or ads.
- **No DRM or subscription bypass.** It only sends Android TV Remote Service commands to a device you control.
- **v1 client key.** The paired client key is packaged into the APK's assets. Keep a self-built v1 APK private. The repository's `.gitignore` excludes both key files.
- **v1 server trust.** The box presents a self-signed certificate. v1 accepts it and shows its SHA-256 fingerprint in Settings; it does **not** pin it yet. v2 will pin the TV certificate after pairing.
- **Bridge.** The Python bridge is a local tool. It prints a random PIN and gates the UI behind it; do not expose it to the internet. Use `--no-pin` only on a trusted network.
- **Signing key.** `apk/keystore.jks` and the keystore password are local secrets. Neither is committed.

## Tested on

- **Phone:** Motorola Edge 50 Fusion, Android 16, connected over Wi-Fi.
- **TV:** Airtel Xstream box, model reported as `XSTREAMIPTV1-VT`, software `7.00.999235495`, Android TV Remote Service on `192.168.1.10`.
- **Real session:** discovery, connect, D-pad/keys, volume readout (17), and model readback; round-trip time around 424 ms.
- **JVM harnesses:** `apk/test/` contains manual desktop harnesses for the handshake, key/IME paths, and app launch paths; they use a real box and the local client identity.

Other Android TV / Google TV boxes should work if they expose the standard Android TV Remote Service. They have not been tested yet. v2 is where that changes.

## Roadmap

v2 is planned in three deliberately small steps:

1. **Universal pairing** — port the 6467 pairing flow to Java, generate a per-install client identity, store devices securely, remove the embedded cert, and add onboarding.
2. **Minimal UX** — one-screen remote, real brand app icons, device switcher, smart gestures, haptics, accessibility, landscape/tablet.
3. **Everywhere** — home-screen widget, Quick Settings tile, media notification, app shortcuts.

The full plan, design principles, technical risks, and acceptance criteria are in [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Credits

- Icons: [Lucide](https://lucide.dev) (ISC).
- Protocol reference and the optional bridge: [androidtvremote2](https://github.com/tronikos/androidtvremote2) (Apache-2.0).
- See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Not affiliated with Airtel, Google, Vantiva, or the Android TV project. All trademarks belong to their owners. Use this only on devices you own or are authorized to control.

## License

[MIT](LICENSE)
