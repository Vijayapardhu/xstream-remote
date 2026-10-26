# Architecture

This document describes the v1 implementation: a WebView UI, a thin Java
bridge, and a hand-written Android TV Remote Service (AUR) v2 client.

## Goals

- A remote that opens instantly, works with one hand, and stays out of the way.
- No accounts, cloud services, ads, analytics, or runtime SDKs.
- A small APK and a build that is easy to inspect.
- Keep the protocol isolated so it can be tested on a desktop JVM.

## Components

```text
┌─────────────────────────────────────────────────────────────────────┐
│ MainActivity (single activity)                                      │
│  └─ WebView ── file:///android_asset/ui/index.html                  │
│       ▲  window.__tv(json)          window.Native.*  ▼              │
│  └─ TvBridge ── state, discovery, reconnect, assets, SharedPreferences
│       └─ AurSession ── TLS 6466, protobuf framing, reader thread    │
│            └─ Discovery ── mDNS + /24 port-6466 sweep               │
└─────────────────────────────────────────────────────────────────────┘
```

| File | Responsibility |
| --- | --- |
| `MainActivity.java` | WebView setup, dark system bars, `Native` JavaScript interface, forwards bridge events to `window.__tv`. |
| `TvBridge.java` | Session lifecycle, asset certificate loading, persisted host, reconnect/backoff, 12 s setup watchdog, discovery results, JSON state. |
| `AurSession.java` | One TLS session: handshake, frames, keys, IME text, app links, ping replies, peer fingerprint. No Android imports. |
| `Proto.java` | Minimal protobuf wire codec: varints, length-delimited fields, nested messages. |
| `Discovery.java` | `NsdManager` mDNS discovery plus a parallel /24 TCP probe of port 6466. |
| `web/index.template.html` | UI source: layout, styles, input handling, preview mode. |
| `tools/make_ui.py` | Inlines the Lucide sprite into the final `assets/ui/index.html`. |
| `tools/fetch_icons.py` | Caches Lucide SVGs and builds `web/sprite.svg`; cache allows offline builds. |
| `build.ps1` | aapt2 → javac → d8 → zip assets → zipalign → apksigner. |

The app uses no AndroidX or third-party runtime libraries. The UI is local HTML
loaded from `file:///android_asset/`; it contains no remote content. All network
I/O happens in Java.

## Android TV Remote v2

### Transport

- TLS on port **6466**.
- The client authenticates with the paired X.509 identity.
- The box presents a self-signed certificate. v1 accepts it and reports the
  SHA-256 fingerprint; it does not pin it yet.
- Frames are **varint length + protobuf** (`RemoteMessage`). The frame limit is
  1 MiB. The socket has an 8 s connect/handshake timeout, then no read timeout
  while the session is alive.

### Feature mask

v1 advertises:

```text
F_PING (1) | F_KEY (2) | F_IME (4) | F_POWER (32) | F_VOLUME (64) | F_APP_LINK (512)
= 615
```

The box sends its supported mask in `remote_configure`; the app intersects it
with 615 before replying.

### Handshake

1. Open TLS and start the reader thread.
2. The box sends `remote_configure` (field 1) with its feature mask and device
   info. The app stores model/vendor/software and replies with field 1 containing
   its mask and a small client info message.
3. The box sends `remote_set_active` (field 2); the app replies with field 2 and
   the negotiated mask.
4. The box sends `remote_start` (field 40); the app marks the session ready when
   the started flag is set.
5. The box sends pings (field 8); the app echoes each value in field 9.
6. A 12 s watchdog in `TvBridge` fails the connection if the session does not
   become ready. Keys are rejected until ready.

### Messages used

| Direction | Field | Purpose |
| --- | --- | --- |
| Box → app | 1 | `remote_configure`: supported features + device info |
| Box → app | 2 | `remote_set_active` |
| Box → app | 3 | protocol error |
| Box → app | 8 | ping; app answers in field 9 |
| Box → app | 20 | app-link event; app reads package name |
| Box → app | 21 | IME edit/counter batch |
| Box → app | 40 | `remote_start` ready flag |
| Box → app | 50 | volume level, max, muted |
| App → box | 1 | configure reply |
| App → box | 2 | active reply |
| App → box | 9 | ping reply |
| App → box | 10 | key injection (key code + direction) |
| App → box | 21 | IME text batch |
| App → box | 90 | app-link launch request |

Key names map to Android key codes in `AurSession.KEYS` (`DPAD_UP`, `HOME`,
`MEDIA_PLAY_PAUSE`, `VOLUME_UP`, `MUTE`, colour keys, and so on). Unknown names
are rejected in Java, not passed to the box.

Volume is currently sent as `VOLUME_UP`/`VOLUME_DOWN` keys. The box pushes its
level on field 50, which the UI uses for the level readout.

### App links

The box accepts ordinary `https://` links and `market://details?id=<package>`.
The older `market://launch?id=` form is not supported by this firmware. A bad
app-link intent can make the box close the whole TCP session; `TvBridge` treats
that as a disconnect and reconnects.

## Discovery

`Discovery` runs two mechanisms in parallel because home routers vary:

1. **mDNS** for `_androidtvremote2._tcp` via `NsdManager`, with a multicast lock.
   This yields the service name and, where available, the model.
2. **Subnet sweep** of the phone's IPv4 /24s, probing TCP port 6466 with 24
   worker threads. This finds boxes when multicast is filtered.

Results are deduplicated by IP and streamed to the UI (`boxes` events). The UI
also lets the user type an IP.

## Session lifecycle

`TvBridge` is a small state machine:

```text
idle → scanning → connecting → connected
                       ↘ error
connected → reconnecting (up to 10 attempts, 0.5–5 s backoff) → connected
```

- The last host is stored in `SharedPreferences("xstream")`.
- `open()` loads `client.pem` and `client.key` from assets, constructs
  `AurSession`, starts it, and arms the watchdog.
- Every connection attempt gets a generation number. Late callbacks from an old
  session are ignored.
- `disconnect()` and `forget()` close the session and invalidate the generation.
- Errors are classified (`auth`, `timeout`, `unreachable`, `network`, `dropped`)
  so the UI can show an actionable message.

## UI

`web/index.template.html` is the source of truth. It is a single page with:

- a circular D-pad/swipe pad and primary controls;
- a Keys view with the extra key grid;
- a Connect view with discovery results and manual IP entry;
- a Settings view with connection details, fingerprints, and forget/exit.

`tools/make_ui.py` replaces the sprite placeholder with `web/sprite.svg` and
writes `apk/assets/ui/index.html`; the generated file is committed so the APK can
be assembled without a UI build, and regenerated on every `build.ps1` run. A
`?preview=1` mode feeds mock events for headless screenshots.

`MainActivity` exposes:

```text
Native.savedHost()      Native.scan()      Native.connect(host)
Native.disconnect()     Native.forget()    Native.key(name)
Native.text(value)      Native.launch(uri) Native.exit()
```

`TvBridge` pushes JSON to `window.__tv`; the UI keeps connection, volume, app,
and error state.

## Build pipeline

```text
tools/fetch_icons.py        cached Lucide SVGs → web/sprite.svg
tools/make_ui.py            template + sprite → assets/ui/index.html
aapt2 compile --dir res     → build/res.zip
aapt2 link                  → base APK + R.java (min 24, target 34)
javac --release 11          → classes (with -parameters for the JS bridge)
d8 --min-api 24             → classes.dex
zip update                  → classes.dex + assets (explicit entries)
zipalign -p 4               → aligned APK
apksigner                   → signed APK (v2 + v3)
```

Assets are inserted explicitly into the zip because `aapt2 -A` writes Windows
path separators on this host. The client identity is optional at build time:
without it the APK builds and warns, but cannot connect. The signing keystore is
created on first build if absent; the password comes from
`XSTREAM_STORE_PASS`, never from source.

## Security model (v1)

- **Client identity:** the paired private key is packaged in the APK assets. The
  repo excludes it; a v1 APK built with your key must not be distributed. v2
  removes this by generating a per-install key on the phone.
- **Server trust:** v1 uses a trust-all `X509TrustManager` because the box is
  self-signed. The peer SHA-256 is shown in Settings but not enforced. v2 should
  pin it after pairing.
- **LAN only:** no cloud calls, analytics, accounts, or remote configuration.
- **Bridge:** local HTTP with a random PIN cookie, TLS to the TV, certs under
  `remote/certs/`. It is a pairing/utility tool and must not be exposed to the
  internet.
- **No DRM work:** the app only sends remote-control commands.

## Testing

- `apk/test/TestAur.java` and `TestLaunch.java` are manual JVM harnesses that
  exercise the handshake, keys, IME, and app-link path against a real box. They
  read the local client identity from `apk/assets/` and are not wired into CI.
- `apk/tools/shot.js` and `inspect_ui.js` use headless Chrome for UI geometry and
  screenshots.
- Real-device QA: Motorola Edge 50 Fusion (Android 16) against an Airtel Xstream
  box; discovery, connection, keys, volume readout, reconnect, and watchdog.
- Keyboard/IME and app-link paths are covered by the JVM harnesses and manual
  bridge checks.

## Known limitations

- One pinned certificate; no in-app pairing, device list, or multi-TV support.
- Trust-all server certificate (fingerprint only).
- Volume uses key events; no absolute slider.
- No home-screen widget, Quick Settings tile, or richer gestures.
- The app is portrait-only in v1.
- The separate `TvLauncher` Gradle experiment and original network research notes
  are intentionally outside this repository.

The v2 plan addresses the first four items directly; see [ROADMAP.md](ROADMAP.md).
