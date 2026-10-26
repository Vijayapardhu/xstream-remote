"""
Airtel TV Remote - lightweight LAN remote for the paired Android TV box.

The laptop holds the paired RSA client certificate and speaks Google's Android TV
Remote Service (v2) to the set-top box. A phone just opens this server in a browser:
no app to install, no cert to copy.

    python server.py                  # binds 0.0.0.0:8777, prints a URL with a PIN

Endpoints (all JSON):
    GET  /api/state                 connection + device state
    POST /api/scan                  discover TVs on the LAN (mDNS + port sweep)
    POST /api/connect   {host}      connect to a host
    POST /api/key       {key}       send a key ("DPAD_UP", "HOME", ...)
    POST /api/text      {text}      type into the focused text field
    POST /api/launch    {link}      open a URI (https:// or market://details?id=...)
    POST /api/volume    {level|delta|mute}
    POST /api/shortcuts {link}      save a launcher shortcut

Stdlib only, except androidtvremote2.
"""
import argparse
import asyncio
import json
import os
import random
import re
import socket
import struct
import sys
import threading
import time
import urllib.request
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from androidtvremote2 import AndroidTVRemote
except ImportError:
    print("androidtvremote2 is required:  pip install androidtvremote2", file=sys.stderr)
    raise

HERE = os.path.dirname(os.path.abspath(__file__))
CERTS_DIR = os.path.join(HERE, "certs")
DEFAULT_CERT = os.path.join(CERTS_DIR, "atv_client.pem")
DEFAULT_KEY = os.path.join(CERTS_DIR, "atv_client.key")
os.makedirs(CERTS_DIR, exist_ok=True)

# --------------------------------------------------------------------------- mDNS


def _enc_name(name: str) -> bytes:
    out = b""
    for part in name.split("."):
        if part:
            out += bytes([len(part)]) + part.encode()
    return out + b"\x00"


def _parse_name(buf: bytes, off: int, depth: int = 0):
    labels = []
    while depth < 20:
        depth += 1
        if off >= len(buf):
            return ".".join(labels), off
        ln = buf[off]
        if ln == 0:
            off += 1
            break
        if ln & 0xC0 == 0xC0:
            ptr = struct.unpack(">H", buf[off:off + 2])[0] & 0x3FFF
            sub, _ = _parse_name(buf, ptr, depth)
            labels.append(sub)
            off += 2
            break
        labels.append(buf[off + 1:off + 1 + ln].decode("utf8", "replace"))
        off += 1 + ln
    return ".".join(labels), off


def _parse_rr(buf: bytes, off: int):
    name, off = _parse_name(buf, off)
    if off + 10 > len(buf):
        raise ValueError("short rr")
    typ, _cls, _ttl, rdlen = struct.unpack(">HHIH", buf[off:off + 10])
    off += 10
    rdata = buf[off:off + rdlen]
    off += rdlen
    val = ""
    if typ == 12:  # PTR
        val, _ = _parse_name(buf, off - rdlen)
    elif typ == 33:  # SRV
        _prio, _weight, port = struct.unpack(">HHH", rdata[:6])
        tgt, _ = _parse_name(rdata, 6)
        val = f"{port} {tgt}"
    elif typ in (16, 47):  # TXT
        parts, i = [], 0
        while i < len(rdata):
            ln = rdata[i]
            parts.append(rdata[i + 1:i + 1 + ln].decode("utf8", "replace"))
            i += 1 + ln
        val = "|".join(parts)
    elif typ == 1:
        val = socket.inet_ntoa(rdata)
    elif typ == 28:
        val = socket.inet_ntop(socket.AF_INET6, rdata)
    return name, typ, val, off


def mdns_find() -> dict:
    """Return {ip: {name, port, txt}} for _androidtvremote2._tcp.local via multicast."""
    found = {}
    group, port = "224.0.0.251", 5353
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", port))
    except OSError:
        return found

    try:
        local_ip = local_address()
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                     socket.inet_aton(group) + socket.inet_aton(local_ip))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local_ip))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)

        tid = random.randint(0, 0xFFFF)
        pkt = struct.pack(">HHHHHH", tid, 0, 1, 0, 0, 0) + \
            _enc_name("_androidtvremote2._tcp.local") + struct.pack(">HH", 12, 1)
        s.sendto(pkt, (group, port))
        s.settimeout(1.0)

        names, addrs = {}, {}
        end = time.time() + 2.5
        while time.time() < end:
            try:
                data, addr = s.recvfrom(65535)
            except socket.timeout:
                continue
            if len(data) < 12:
                continue
            _id, flags, qd, an, ns, ar = struct.unpack(">HHHHHH", data[:12])
            if not flags & 0x8000:
                continue
            off = 12
            try:
                for _ in range(qd):
                    _, off = _parse_name(data, off)
                    off += 4
                for _ in range(an + ns + ar):
                    n, t, v, off = _parse_rr(data, off)
                    if t == 33 and v:
                        prt, tgt = v.split(" ", 1)
                        names[tgt] = {"port": int(prt), "name": tgt.split(".")[0]}
                    elif t == 16 and v:
                        for kv in v.split("|"):
                            if kv.startswith("bt="):
                                names.setdefault(n, {})["bt"] = kv[3:]
                    elif t == 1:
                        addrs[n] = v
            except Exception:
                continue
        for host, ip in addrs.items():
            if host in names:
                found[ip] = names[host]
    finally:
        s.close()
    return found


def local_address() -> str:
    """Primary LAN IP (no packets are actually sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.1.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def tcp_open(ip: str, port: int, timeout: float = 0.35) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        return s.connect_ex((ip, port)) == 0
    except OSError:
        return False
    finally:
        s.close()


def eureka(ip: str, timeout: float = 1.2) -> dict:
    try:
        with urllib.request.urlopen(f"http://{ip}:8008/setup/eureka_info?options=detail",
                                    timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        return {}


def scan_lan() -> list:
    """Discovery: mDNS first, then a 6466 sweep of the local /24 for stragglers."""
    mdns = mdns_find()
    local_ip = local_address()
    prefix = local_ip.rsplit(".", 1)[0]

    candidates = set(mdns)
    lock = threading.Lock()
    hits = []

    def probe(i):
        ip = f"{prefix}.{i}"
        if ip == local_ip:
            return
        if tcp_open(ip, 6466):
            with lock:
                hits.append(ip)

    threads = [threading.Thread(target=probe, args=(i,), daemon=True) for i in range(1, 255)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    candidates |= set(hits)

    out = []
    for ip in sorted(candidates, key=lambda a: tuple(int(x) for x in a.split("."))):
        info = eureka(ip)
        md = mdns.get(ip, {})
        out.append({
            "ip": ip,
            "name": md.get("name") or info.get("name") or "Android TV",
            "model": (info.get("cast_build_revision") and "Cast build " +
                      str(info.get("cast_build_revision"))) or "",
            "port": md.get("port", 6466),
            "bt": md.get("bt", ""),
            "paired_ready": tcp_open(ip, 6466),
        })
    return out


# --------------------------------------------------------------------------- bridge


class Bridge:
    """Owns one AUR connection on a dedicated asyncio loop thread."""

    def __init__(self, cert: str, key: str):
        self.cert = cert
        self.key = key
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()
        self.tv = None
        self.host = None
        self.last_error = None
        self.pairing = None
        self.cert_used = (cert, key)
        self._available = False
        self.cert_map = self._load_pairs()
        self._keepalive_task = None
        self.lock = threading.Lock()

    # -- per-host certificates ---------------------------------------------
    def _pairs_file(self):
        return os.path.join(CERTS_DIR, "pairs.json")

    def _load_pairs(self) -> dict:
        try:
            with open(self._pairs_file(), encoding="utf-8") as f:
                return {k: tuple(v) for k, v in json.load(f).items()}
        except Exception:
            return {}

    def _save_pairs(self):
        try:
            with open(self._pairs_file(), "w", encoding="utf-8") as f:
                json.dump({k: list(v) for k, v in self.cert_map.items()}, f, indent=2)
        except Exception:
            pass

    def _certs_for(self, host: str):
        m = self.cert_map.get(host)
        if m and os.path.exists(m[0]):
            return m
        return self.cert, self.key

    def _run_loop(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    # -- lifecycle ---------------------------------------------------------
    def connect(self, host: str, timeout: float = 20.0) -> dict:
        fut = asyncio.run_coroutine_threadsafe(self._connect(host), self.loop)
        return fut.result(timeout=timeout)

    async def _connect(self, host: str, cert: str = None, key: str = None):
        await self._disconnect()
        if cert is None or key is None:
            cert, key = self._certs_for(host)
        tv = AndroidTVRemote("airtel-remote-web", cert, key, host)
        await tv.async_connect()
        self.tv, self.host, self.last_error = tv, host, None
        self.cert_used = (cert, key)
        self._available = True
        try:
            tv.add_is_available_updated_callback(self._on_available)
        except Exception:
            pass
        self._keepalive_task = self.loop.create_task(tv.keep_reconnecting(self._on_auth_error))
        return {"ok": True, "host": host}

    def _on_available(self, available):
        self._available = bool(available)

    # -- pairing -----------------------------------------------------------
    def pair_start(self, host: str, timeout: float = 30.0) -> dict:
        return asyncio.run_coroutine_threadsafe(self._pair_start(host), self.loop).result(timeout)

    async def _pair_start(self, host: str):
        await self._disconnect()
        cert = os.path.join(CERTS_DIR, f"client_{host}.pem")
        key = os.path.join(CERTS_DIR, f"client_{host}.key")
        tv = AndroidTVRemote("airtel-remote-web", cert, key, host)
        await tv.async_generate_cert_if_missing()
        await tv.async_start_pairing()
        self.pairing = {"tv": tv, "host": host, "cert": cert, "key": key, "at": time.time()}
        return {"ok": True, "host": host, "code_length": 6, "encoding": "hex"}

    def pair_finish(self, code: str, timeout: float = 40.0) -> dict:
        return asyncio.run_coroutine_threadsafe(self._pair_finish(code), self.loop).result(timeout)

    async def _pair_finish(self, code: str):
        p = self.pairing
        if not p:
            raise RuntimeError("no pairing in progress")
        try:
            await p["tv"].async_finish_pairing(code)
        finally:
            self.pairing = None
        self.cert_map[p["host"]] = (p["cert"], p["key"])
        self._save_pairs()
        return await self._connect(p["host"], p["cert"], p["key"])

    def pair_cancel(self):
        self.pairing = None
        return asyncio.run_coroutine_threadsafe(self._disconnect(), self.loop).result(10)

    def _on_auth_error(self):
        self.last_error = "pairing rejected - re-pair on the TV"

    def disconnect(self):
        return asyncio.run_coroutine_threadsafe(self._disconnect(), self.loop).result(10)

    async def _disconnect(self):
        if self._keepalive_task:
            self._keepalive_task.cancel()
            self._keepalive_task = None
        if self.tv:
            try:
                self.tv.disconnect()
            except Exception:
                pass
        self.tv = None
        self._available = False

    # -- sends -------------------------------------------------------------
    def send(self, fn, *args):
        if not self.tv:
            raise RuntimeError("not connected")
        self.loop.call_soon_threadsafe(fn, *args)

    def send_key(self, name: str):
        self.send(self.tv.send_key_command, name)

    def text(self, value: str):
        self.send(self.tv.send_text, value)

    def launch(self, link: str):
        self.send(self.tv.send_launch_app_command, link)

    def volume(self, level=None, delta=None, mute=None):
        tv = self.tv
        if not tv:
            raise RuntimeError("not connected")
        info = tv.volume_info or {}
        cur, mx = info.get("level", 0), info.get("max", 100)
        if mute is not None:
            self.send_key("VOLUME_MUTE")
            return
        if level is not None:
            target = max(0, min(mx, int(level)))
            delta = target - cur
        if not delta:
            return

        def ramp(steps):
            k = "VOLUME_UP" if steps > 0 else "VOLUME_DOWN"
            for _ in range(abs(steps)):
                self.loop.call_soon_threadsafe(tv.send_key_command, k)
                time.sleep(0.12)

        threading.Thread(target=ramp, args=(delta,), daemon=True).start()

    # -- state -------------------------------------------------------------
    def state(self) -> dict:
        tv = self.tv
        extra = {"pairing": bool(self.pairing)}
        if self.pairing:
            extra["pair_host"] = self.pairing["host"]
        if not tv:
            return {"connected": False, "host": self.host, "error": self.last_error, **extra}
        return {
            "connected": self._available,
            "host": self.host,
            "device": tv.device_info,
            "is_on": tv.is_on,
            "current_app": tv.current_app,
            "volume": tv.volume_info,
            "error": self.last_error,
            **extra,
        }


# --------------------------------------------------------------------------- http

SHORTCUTS_DEFAULT = [
    {"label": "Home", "key": "HOME"},
    {"label": "Live TV", "key": "TV"},
    {"label": "Guide", "key": "GUIDE"},
    {"label": "Play Store", "link": "market://details?id=com.android.vending"},
    {"label": "Search Store", "link": "market://search?q="},
]


class Handler(BaseHTTPRequestHandler):
    server_version = "AirtelRemote/1.0"
    bridge: Bridge = None
    pin: str = ""
    shortcuts: list = None

    def log_message(self, fmt, *args):
        pass  # quiet

    # -- helpers -----------------------------------------------------------
    def _authorised(self) -> bool:
        if not self.pin:
            return True
        if self.headers.get("Cookie"):
            c = SimpleCookie(self.headers["Cookie"])
            if "k" in c and c["k"].value == self.pin:
                return True
        q = self.path.split("?", 1)[1] if "?" in self.path else ""
        for part in q.split("&"):
            if part.startswith("k=") and part[2:] == self.pin:
                return True
        return False

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        except Exception:
            return {}

    # -- routes ------------------------------------------------------------
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/":
            if not self._authorised():
                return self._login_page()
            with open(os.path.join(HERE, "ui.html"), "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            if self.pin:
                self.send_header("Set-Cookie", f"k={self.pin}; Path=/; SameSite=Lax; Max-Age=31536000")
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/") and not self._authorised():
            return self._json({"error": "unauthorised"}, 401)
        if path == "/api/state":
            return self._json(self.bridge.state())
        if path == "/api/shortcuts":
            return self._json({"items": self.shortcuts})
        self._json({"error": "not found"}, 404)

    def _login_page(self):
        html = ("<html><body style='background:#0b0d10;color:#e8edf4;font-family:sans-serif;"
                "padding:40px'><h3>Airtel Remote</h3><p>Open the exact link printed in the "
                "laptop terminal (it contains <code>?k=&lt;pin&gt;</code>).</p></body></html>")
        b = html.encode()
        self.send_response(401)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self._authorised():
            return self._json({"error": "unauthorised"}, 401)
        data = self._body()
        try:
            if path == "/api/scan":
                return self._json({"tvs": scan_lan()})
            if path == "/api/connect":
                host = (data.get("host") or "").strip()
                if not host:
                    return self._json({"error": "host required"}, 400)
                return self._json(self.bridge.connect(host))
            if path == "/api/pair/start":
                host = (data.get("host") or "").strip()
                if not host:
                    return self._json({"error": "host required"}, 400)
                return self._json(self.bridge.pair_start(host))
            if path == "/api/pair/code":
                code = (data.get("code") or "").strip().replace(" ", "").upper()
                if not code:
                    return self._json({"error": "code required"}, 400)
                return self._json(self.bridge.pair_finish(code))
            if path == "/api/pair/cancel":
                self.bridge.pair_cancel()
                return self._json({"ok": True})
            if path == "/api/key":
                self.bridge.send_key(data["key"])
                return self._json({"ok": True})
            if path == "/api/text":
                self.bridge.text(data.get("text", ""))
                return self._json({"ok": True})
            if path == "/api/launch":
                self.bridge.launch(data["link"])
                return self._json({"ok": True})
            if path == "/api/volume":
                self.bridge.volume(level=data.get("level"), delta=data.get("delta"),
                                   mute=data.get("mute"))
                return self._json({"ok": True})
            if path == "/api/shortcuts":
                link = data.get("link", "").strip()
                label = data.get("label", "").strip() or "Shortcut"
                if link:
                    self.shortcuts.append({"label": label, "link": link})
                    with open(os.path.join(HERE, "shortcuts.json"), "w", encoding="utf-8") as f:
                        json.dump(self.shortcuts, f, indent=2)
                return self._json({"items": self.shortcuts})
        except KeyError as e:
            return self._json({"error": f"missing field {e}"}, 400)
        except Exception as e:
            return self._json({"error": f"{type(e).__name__}: {e}"}, 500)
        self._json({"error": "not found"}, 404)


def load_shortcuts():
    path = os.path.join(HERE, "shortcuts.json")
    if os.path.exists(path):
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return list(SHORTCUTS_DEFAULT)


def ensure_cert_pair(cert: str, key: str) -> bool:
    """Create a self-signed client identity if one is missing.

    The default identity lets the bridge start; pairing a TV writes a
    per-host identity under remote/certs/client_<host>.*.
    """
    if os.path.exists(cert) and os.path.exists(key):
        return False

    async def _generate():
        tv = AndroidTVRemote("airtel-remote-web", cert, key, "127.0.0.1")
        await tv.async_generate_cert_if_missing()

    asyncio.run(_generate())
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8777)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--cert", default=DEFAULT_CERT)
    ap.add_argument("--key", default=DEFAULT_KEY)
    ap.add_argument("--pin", default=None, help="access PIN (default: random 4 digits)")
    ap.add_argument("--no-pin", action="store_true")
    ap.add_argument("--host", default=None, help="connect to this TV at startup")
    ap.add_argument("--pair", default=None, metavar="HOST",
                    help="start the pairing flow at startup (TV shows a 6-char code)")
    a = ap.parse_args()

    try:
        if ensure_cert_pair(a.cert, a.key):
            print(f"generated client certificate: {a.cert}")
    except Exception as e:
        print(f"could not create client certificate: {e}", file=sys.stderr)
        return 2
    for p in (a.cert, a.key):
        if not os.path.exists(p):
            print(f"missing certificate file: {p}", file=sys.stderr)
            return 2

    bridge = Bridge(a.cert, a.key)
    Handler.bridge = bridge
    Handler.pin = "" if a.no_pin else (a.pin or f"{random.randint(0, 9999):04d}")
    Handler.shortcuts = load_shortcuts()

    ip = local_address()
    url = f"http://{ip}:{a.port}/" + (f"?k={Handler.pin}" if Handler.pin else "")
    print("=" * 58)
    print(" Airtel TV Remote - open this on your phone")
    print(f"   {url}")
    if Handler.pin:
        print(f"   PIN: {Handler.pin}   (stored as a cookie after first open)")
    print("=" * 58)

    if a.host:
        try:
            bridge.connect(a.host)
            print(f" connected to {a.host}")
        except Exception as e:
            print(f" could not connect to {a.host}: {e}")

    if a.pair:
        def prompt_pair():
            try:
                bridge.pair_start(a.pair)
                print(f"\n PAIRING: the TV ({a.pair}) is showing a 6-character code.")
                print(" Type it here, or enter it in the app's onboarding screen.\n")
                code = input(" code> ").strip()
                if code:
                    bridge.pair_finish(code)
                    print(f" paired and connected to {a.pair}")
            except Exception as e:
                print(f" pairing error: {type(e).__name__}: {e}")
        threading.Thread(target=prompt_pair, daemon=True).start()

    srv = ThreadingHTTPServer((a.bind, a.port), Handler)
    srv.daemon_threads = True
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
