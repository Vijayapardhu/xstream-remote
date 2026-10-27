# Setup box — how this APK was made using the Airtel Xstream box

This document describes how the `XstreamRemote-2.0.apk` release was built and
validated against the Airtel Xstream Android TV box, and how to reproduce it.

## What's the setup box

The test box is an **Airtel Xstream IPTV box** (`XSTREAMIPTV1-VT`, software
`7.00.999235495`, Android TV Remote Service exposed). It is on a private LAN
with the test phone (`192.168.1.10` for TV control `6466`, pairing `6467`).

## What was used to make the APK

- Source repo: `Vijayapardhu/xstream-remote` (public, source-only).
- Build host: Windows 11, JDK 24, Android SDK build-tools 34.0.0,
  Python 3.9.
- Build pipeline (`apk/build.ps1`): `aapt2 compile` → `javac --release 11`
  → `d8 --min-api 24` → `zip` assets + `zipalign` → `apksigner`.
- UI rebuilt by `apk/tools/make_ui.py` (template + sprite + brands →
  `assets/ui/index.html`, 186 KB).
- Brand art from `apk/brands/` (Simple Icons SVGs + Hotstar/Prime favicons),
  inlined by `make_brands.py` into `web/brands.svg`.
- Icons fetched by `apk/tools/fetch_icons.py` (Lucide SVGs); `more-horizontal`
  and `pencil` added for missing sprite symbols.
- Haptics: `apk/java/com/xstream/remote/Haptics.java` (primitive/AMM patterns).
- Widgets added: `Widget2x2Provider`, `WidgetRemoteProvider`,
  `WidgetActionReceiver` + layouts/xml + manifest entries.
- App icon redesigned: master `apk/web/icon.svg`; adaptive/round/mono
  vectors + legacy PNG mipmaps + 512 store asset.

The APK contains **no bundled client certificate** (`client.pem`/`client.key`
are gitignored and never read by build or APK). The phone generates its own
RSA identity the first time it pairs with the TV.

## How to reproduce the build

```powershell
cd <repo>
$env:XSTREAM_STORE_PASS = "your-keystore-password"  # from your keystore
# For this repo's existing keystore (gitignored):
# $env:XSTREAM_KEYSTORE = "C:\path\to\keystore.jks"
# $env:XSTREAM_KEY_ALIAS = "airtel"
powershell -ExecutionPolicy Bypass -File apk\build.ps1
```

Output: `apk\XstreamRemote-2.0.apk` (~226 KB, v2 + v3 signatures).
If `INSTALL_FAILED_UPDATE_INCOMPATIBLE` appears, uninstall the old build
first (uninstalling clears the paired host and identity).

## Pairing against the setup box

1. Make sure the phone is on the same LAN / Wi-Fi as the TV.
2. Open the app. Tap **Scan** (or enter the TV's IP manually).
3. The TV shows a six-character hex code. Type it into the pairing screen.
4. The pairing code stays visible forever on the phone (not auto-cleared).
5. On success the TV cert fingerprint is pinned; the app connects over TLS 6466.

Full pairing reaches the six-character step; the full handshake is also
verified against the local protocol mock (`test/`). The real TV pairing needs
the six-character code shown on screen (the code changes per session).

## Live verified features

- Connect / disconnect / reconnect (pinned cert).
- 22-key pad center + directions + volume + mute.
- Media layout (rewind / play-pause / forward rail; channel rail hidden).
- App-aware context bar (Live → Guide/Options/Captions).
- Numbers pad (channel / PIN).
- Keyboard text input.
- Macros (tile + test + save).
- Settings → Ease / haptics / motion / labels / pad / theme.
- Widget tap sends a key through MainActivity (reconnects if needed).
- Real app links work (YouTube, Netflix, Hotstar, Prime, Spotify, Play).
- Mute icon dynamic, volume repeats on hold, context has no double-haptic.

## What is out of scope / future

- Multi-TV manager.
- Play-Store-sourced app icons for unlisted apps.
- A second TV model for full pairing validation.
- Release signing for the Google Play Store (this repo is source-only; build
  your own signed APK with your keystore).

Not affiliated with Airtel, Google, or the Android TV project.
