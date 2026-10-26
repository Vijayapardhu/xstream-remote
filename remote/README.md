# Python bridge and pairing helper

`server.py` is a small LAN web app that holds one Android TV Remote Service
connection. It is useful for two things:

1. **Pairing a new client identity** that you can put into the Android APK (v1).
2. Using a laptop or any browser as a remote without building the APK.

The Android app itself is standalone once it has a paired identity. You do not
need this bridge running while you use the app.

## Requirements

- Python 3.9+
- `androidtvremote2` 0.3.2 (installed from `requirements.txt`)

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

## Run

```powershell
python server.py
```

On first run the bridge creates a default self-signed client certificate at
`certs/atv_client.pem` and `certs/atv_client.key` if they are missing. It then
prints a URL such as:

```text
http://192.168.1.20:8777/?k=1234
```

Open that URL on a phone or laptop on the same network. The PIN is stored as a
cookie after the first open. The bridge binds to `0.0.0.0:8777` by default.

## Pair a TV for the APK

```powershell
python server.py --pair 192.168.1.10
```

Replace the IP with the TV's address. The TV displays a 6-character code; type
it at the `code>` prompt. The bridge saves the new identity as:

```text
certs/client_192.168.1.10.pem
certs/client_192.168.1.10.key
```

Copy both files into `apk/assets/` as `client.pem` and `client.key`, then rebuild
the APK. `certs/` and `pairs.json` are gitignored.

The bridge can also scan for boxes from its web UI and pair from there.

## Options

| Flag | Meaning |
| --- | --- |
| `--port 8777` | HTTP port |
| `--bind 0.0.0.0` | Bind address; use `127.0.0.1` for local-only |
| `--cert PATH` | Default client certificate |
| `--key PATH` | Default client key |
| `--pin 1234` | Fixed access PIN (default: random 4 digits) |
| `--no-pin` | Disable the PIN gate (trusted network only) |
| `--host 192.168.1.10` | Connect on startup |
| `--pair 192.168.1.10` | Start the pairing flow on startup |

## API

The web UI uses JSON endpoints:

```text
GET  /api/state                 connection + device state
POST /api/scan                  discover TVs (mDNS + port sweep)
POST /api/connect   {host}
POST /api/key       {key}       e.g. "DPAD_UP", "HOME", "VOLUME_UP"
POST /api/text      {text}      type into the focused field
POST /api/launch    {link}      open a URI (https://, market://details?id=)
POST /api/volume    {level|delta|mute}
POST /api/shortcuts {link}      save a launcher shortcut
```

## Security

This is a LAN tool, not an internet service. It speaks plain HTTP and controls
your TV. Do not port-forward it. The PIN gate is a convenience, not a substitute
for network isolation. Use `--bind 127.0.0.1` if you only need the bridge on the
same machine, and avoid `--no-pin` on shared networks.

The bridge stores client private keys in `certs/`. They are intentionally
gitignored. The default certificate generated on first run is not paired to any
TV; pairing creates the per-host identity that the APK needs.

## Troubleshooting

- **TV not found:** phone/box on the same subnet, no guest-network isolation,
  and port 6466 reachable. Discovery uses mDNS and a /24 sweep.
- **Pairing fails:** the TV must be awake and the code must be entered before it
  expires. Start pairing again if the code times out.
- **Connection rejected:** the TV may have forgotten the identity (factory reset
  or cleared pairings). Re-pair and rebuild the APK with the new files.
- **Bridge will not start:** install `requirements.txt`; the bridge also creates
  `certs/` automatically.
