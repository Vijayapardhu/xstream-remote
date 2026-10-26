# Roadmap — from a working v1 to the best minimal remote

## Vision

> Pick it up, press one thing, and the TV responds. No setup language, no tabs,
> no account, no lag.

The remote should feel like a physical accessory: one hand, a few large targets,
instant feedback, and nothing on screen that is not useful.

## Design principles

1. **One screen.** The remote is the app. Everything else is a sheet, a long
   press, or a settings row.
2. **One thumb.** Primary controls live in the lower two-thirds. Every target is
   at least 48 dp; the pad is the biggest thing on screen.
3. **Tactile.** A light haptic tick for movement, a firmer one for OK, a double
   tick for Back. No spinners for key presses.
4. **Progressive disclosure.** D-pad, volume, power, Back/Home are always there.
   Numbers, colour keys, input, and diagnostics appear only when needed.
5. **Universal, not proprietary.** Pair any Android TV / Google TV box that
   exposes the Android TV Remote Service. No baked-in certificate, no author's
   device, no lock-in.
6. **Private by default.** LAN only. No cloud, accounts, analytics, ads, or
   telemetry.
7. **Tiny.** Stay under 250 KB if possible. No runtime SDKs unless they buy
   something the app cannot do in a few hundred lines.
8. **Accessible.** TalkBack labels, 48 dp targets, high contrast, reduced motion,
   large text, and no colour-only status.

## v1 baseline (done)

- 85.7 KB APK, Android 7.0+, target 34.
- Direct AUR v2 client: TLS 6466, protobuf, keys, IME, app links, volume readout.
- mDNS + /24 discovery, reconnect, 12 s watchdog.
- WebView UI with Lucide icons and a swipe pad.
- One pre-paired client identity in `apk/assets/`; no in-app pairing.
- Python bridge for pairing and laptop control.

## v2.0 — Universal (P0)

**Goal:** a fresh install on any supported Android TV box can pair and control it
without a laptop, a bundled key, or a source edit.

### 1. In-app pairing

- Port the port-**6467** pairing flow into Java:
  - `PairingMessage` protobuf schema;
  - the code-based key exchange used by `androidtvremote2` 0.3.2;
  - TLS with the client identity generated on the phone.
- Pairing screen:
  - discover/select the TV;
  - show the TV's 6-character code input with a large keypad;
  - progress, timeout, retry, and "code rejected" states;
  - a success screen with the TV model and a name field.
- Test first against the Airtel box, then at least one other Android TV/Google TV
  device with a different firmware/feature mask.
- Keep a debug-only "import a certificate" path for development.

**Risk:** this is the hardest part. The crypto/protobuf port needs a focused
spike before UI work starts. If a pure-Java implementation becomes too large,
evaluate BouncyCastle as a size trade-off; do not quietly ship a 5 MB APK.

### 2. Per-install identity

- Generate an RSA keypair on first pair; build a self-signed X.509 client
  certificate. A compact DER builder is preferred (no dependency); BouncyCastle
  is the fallback.
- Store the private key encrypted with an Android Keystore AES key, or in the
  Android Keystore if the TLS stack can use it directly. Store the certificate
  and metadata in app storage.
- Delete `apk/assets/client.pem` and `client.key` from the build. Add a release
  check that fails if either file is present in the APK.
- Never log or export the private key. Add a "Forget device" action that removes
  the certificate and saved host.

### 3. Device manager

- Save multiple TVs: host, display name, model, vendor, software, client
  identity, TV certificate fingerprint.
- Device switcher in the header; last-used TV reconnects on launch.
- Rename, forget, and "pair another TV".
- Handle IP changes with a re-scan and a clear "TV moved" state.

### 4. TV certificate pinning

- After pairing, store the TV's SHA-256 fingerprint and pin it on every TLS
  connection.
- Show the expected vs. observed fingerprint on mismatch; do not silently trust a
  different box at the same IP.
- Keep an explicit "trust this new certificate" action after a factory reset.

### 5. Onboarding

```text
Welcome → Scan → Select TV → Enter code → Name device → Test keys → Done
```

- Explain the two-device requirement (phone + TV on the same Wi-Fi).
- Offer manual IP entry.
- A "skip" path for people who already know the flow.
- Time-to-control target: under 60 seconds from first launch.

### Acceptance

- [ ] Fresh install, no cert assets, pairs and controls a TV in under 60 s.
- [ ] No `client.pem`/`client.key` in the repository, APK, or release assets.
- [ ] Pairing succeeds on Airtel plus one other Android TV/Google TV device.
- [ ] Two TVs saved; switching takes two taps or fewer.
- [ ] TV certificate is pinned; mismatch is visible.
- [ ] Pairing failures are actionable, never a blank screen.

## v2.1 — Minimal (P0/P1)

**Goal:** make the remote feel designed, not assembled.

### Layout

- One primary screen: device chip + status at the top, power top-right, pad in the
  lower centre, volume rail on the right, Back/Home below the pad.
- Replace the four-tab bar with:
  - **Remote** (default);
  - **More** bottom sheet for keys, apps, keyboard, and settings.
- Advanced keys live in a sheet, not a permanent grid.
- Tablet/landscape: pad on the left, volume/transport on the right; no stretched
  phone layout.
- Respect safe areas, gesture navigation, and one-handed reach.

### Visual system

- AMOLED black (`#07080B`) surfaces, one restrained accent (Airtel red or a
  calmer coral), neutral greys, no gradients-as-decoration.
- 8 pt spacing grid; 3–4 type sizes; 48 dp minimum; clear pressed states.
- Lucide for controls. Real brand marks for apps.
- Motion: 120–180 ms fades/slides; respect "remove animations".
- No persistent shadows, borders, or labels that repeat the obvious.

### Real app icons

AUR does not expose the TV's installed-app icon list. The realistic approach:

- A small curated brand pack: YouTube, Netflix, Prime Video, Disney+ Hotstar,
  Spotify, Play Store, and the Airtel Xstream app, following each brand's usage
  guidelines.
- Unknown packages get a deterministic monogram tile (first letter + colour from
  a hash) instead of a generic dot.
- A shortcut editor lets the user pin their own apps/links.
- Do not scrape or redistribute TV app icons.

### Smart gestures

All optional, discoverable, and disable-able:

| Gesture | Action |
| --- | --- |
| Swipe on pad | D-pad direction (8-way with dead zone) |
| Tap pad | OK |
| Two-finger vertical | Volume up/down with HUD |
| Double-tap pad | Play/pause |
| Long-press Back | Menu |
| Edge swipe from left | Back |
| Swipe up from bottom edge | Home |
| Three-finger tap | Keyboard |
| Long-press power | Power menu / confirm |

- Native haptic feedback via a small bridge, not `navigator.vibrate`.
- Gesture hints on first run; a practice area in Settings.
- Never trigger volume or power from an ambiguous gesture; add a short "are you
  sure?" for power-off.

### Onboarding and states

- First-run pairing wizard (from v2.0).
- Empty, connecting, reconnecting, asleep, wrong-network, and auth-rejected
  states with one clear action each.
- A compact "connection health" row: RTT, model, last seen.
- A real "forget" flow, not a hidden long press.

### Accessibility and performance

- Every icon button has a TalkBack label; focus order follows the layout.
- 48 dp targets, 4.5:1 contrast, reduced-motion support, large-text layout.
- Cold start under 1.5 s; key feedback under 100 ms on a normal LAN.
- No layout shift when the keyboard opens; keep the pad stable.
- Measure WebView memory; if the UI is the bottleneck, evaluate native views in
  v2.2 rather than assuming a rewrite is needed.

### Acceptance

- [ ] A first-time user can identify every primary control without a label.
- [ ] One-handed use is comfortable on a 6.1–6.7" phone.
- [ ] The app has one main screen and one "More" surface.
- [ ] Known apps show real brand icons; unknown apps show a clean monogram.
- [ ] Gestures can be turned off, and no gesture fires accidentally.
- [ ] TalkBack reaches every control; all targets are at least 48 dp.

## v2.2 — Everywhere (P1)

**Goal:** control the TV without opening the app.

### Home-screen widget

- Layouts: 2×2 (pad + OK + Back/Home) and 4×2 (pad, volume, power, transport).
- Native `AppWidgetProvider` + `RemoteViews`; no WebView in the widget.
- Two modes:
  - **Fast:** a foreground service keeps the AUR session warm for a few minutes
    after a tap; fastest response, visible notification, user opt-in.
  - **Battery saver:** each tap wakes the service, connects, sends, and stops.
- Show a clear connecting/error state on the widget; never a dead button.
- Test after reboot, after the TV sleeps, and after the phone kills the service.

### Quick Settings tile

- `TileService` with connect/power or a "Remote" shortcut.
- Long-press opens the app.

### Media notification

- Play/pause, previous/next, volume, and a "Remote" action.
- Use a foreground service only while the user has enabled it; respect Android 13
  notification permission.

### App shortcuts

- Long-press the launcher icon: Remote, Keyboard, Apps, Pair a TV.
- Static shortcuts are enough for v2.2.

### Acceptance

- [ ] Widget D-pad/volume/power works from the home screen.
- [ ] Widget survives reboot and reconnects to the last TV.
- [ ] Battery impact is measured and disclosed; the fast mode is opt-in.
- [ ] Quick Settings tile and media notification do not require the app open.

## v3.0 — Smart (P2)

- **Macros / scenes:** "Movie night" = power on, launch app, set volume, mute
  notifications; "Good night" = power off. Editable, local-only.
- **Voice:** use the AUR voice feature if it works reliably across boxes; keep the
  mic optional and permission-gated.
- **Wake-on-LAN:** test whether the box supports WOL; offer it only where it
  works.
- **Multi-room:** optional grouping for users with several boxes; not core.
- **Wear OS tile:** a tiny pad on the watch; evaluate demand first.

## Sequencing and effort

| Step | Priority | Estimate |
| --- | --- | --- |
| Pairing protocol spike (6467 + crypto) | P0 | 2–3 days |
| Per-install cert + storage | P0 | 1–2 days |
| Device manager + onboarding | P0 | 2 days |
| Multi-device testing + edge cases | P0 | 2 days |
| UI redesign + real icons | P0/P1 | 4–5 days |
| Gestures + haptics + accessibility | P1 | 2 days |
| Widget + Quick Settings + notification | P1 | 3–4 days |
| Release hardening, docs, screenshots | P1 | 2 days |

Part-time, this is roughly three to four weeks. Pairing is the gate; do not start
the visual redesign in a way that assumes pairing is solved.

## Metrics

- Pairing success rate on first attempt (target > 90% on supported boxes).
- Time from first launch to first successful key (target < 60 s).
- Key feedback latency on LAN (target < 100 ms; measured p95).
- Cold start (target < 1.5 s).
- APK size (target < 250 KB without a crypto dependency; if a dependency is
  required, publish the trade-off and keep it under 2 MB).
- Crash-free sessions (target > 99.5%).
- Accessibility: 100% of controls labelled; 0 targets below 48 dp.

## Open decisions

1. **Pairing crypto:** pure-Java/compact DER vs. BouncyCastle. Spike first; size
   and correctness decide.
2. **Key storage:** Android Keystore-backed AES vs. direct Keystore key. Choose
   the simplest option that keeps the private key out of plain files.
3. **Widget mode:** persistent foreground service vs. on-demand connect. Ship
   both, default to battery saver.
4. **Build system:** keep the tiny custom pipeline for the APK, and optionally add
   a Gradle wrapper for contributors. Gradle is developer tooling, not runtime
   size.
5. **UI technology:** keep WebView for v2.1, measure, and only go native if the
   gestures/haptics/performance demand it.
6. **Distribution:** GitHub releases first; F-Droid/Play later. Choose a neutral
   app name if the "Xstream" trademark becomes a problem.
7. **Public repo:** v1 is private for safety; v2 with no embedded key is a good
   candidate for public source.

## Non-goals

- IR blasters, Roku, webOS, Fire TV, or Apple TV protocols in v2.
- DRM, subscription, credential, or account bypass.
- Cloud sync, accounts, analytics, or ads.
- Flashing, unlocking, or modifying the TV.
- Scraping TV app icons or private data.

## What "done" looks like

A friend installs the APK from a GitHub release, opens it on the same Wi-Fi as
their Android TV, enters the code on the TV, and is controlling it a minute
later. They never see a certificate, a terminal, or a settings maze. The app is
small, quiet, and feels like the remote that should have shipped with the TV.
