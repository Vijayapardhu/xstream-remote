package com.xstream.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Android-side wrapper around AurSession: certificates, persisted host,
 * reconnect loop, discovery, and JSON events for the WebView.
 */
final class TvBridge {

    interface Sink {
        void emit(String json);
    }

    private final Context context;
    private final Sink sink;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService scans = Executors.newSingleThreadExecutor();
    private final ExecutorService macros = Executors.newSingleThreadExecutor();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean userClosed = new AtomicBoolean(false);
    private final Object sessionLock = new Object();
    private final Object identityLock = new Object();

    private volatile AurSession session;
    private volatile PairingSession pairing;
    private volatile ClientIdentity identity;
    private volatile String pairingHost = "";
    private volatile String host = "";
    private volatile String model = "";
    private volatile String vendor = "";
    private volatile String software = "";
    private volatile String app = "";
    private volatile int level = -1;
    private volatile int max = -1;
    private volatile boolean muted;
    private volatile long rtt;
    private volatile String peerCert = "";
    private volatile String status = "idle";
    private volatile String error = "";
    private volatile String errorKind = "";
    private volatile int generation;
    private volatile String clientFingerprint = "";

    TvBridge(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        this.prefs = this.context.getSharedPreferences("xstream", Context.MODE_PRIVATE);
        this.host = prefs.getString("host", "");
    }

    // ------------------------------------------------------------- public

    String savedHost() {
        return prefs.getString("host", "");
    }

    /**
     * Whether this phone holds a TV certificate for the host. The caller still
     * has to be on the same LAN; this is only the pairing check.
     */
    boolean isPaired(String target) {
        String h = normalizeHost(target);
        if (h.isEmpty()) {
            return false;
        }
        return !prefs.getString(pairCertKey(h), "").isEmpty()
                && !prefs.getString(pairFpKey(h), "").isEmpty();
    }

    /**
     * Start the TV-code pairing flow. The TV shows the code after the native
     * handshake reaches configuration_ack; the UI then calls pairFinish().
     */
    void pairStart(String target) {
        String h = normalizeHost(target);
        if (h.isEmpty()) {
            return;
        }
        final int gen = ++generation;
        userClosed.set(false);
        closeSession();
        cancelPairing(false);
        host = h;
        error = "";
        errorKind = "";
        model = "";
        vendor = "";
        software = "";
        app = "";
        peerCert = "";
        rtt = 0;
        level = -1;
        max = -1;
        status = "connecting";
        prefs.edit().putString("host", h).apply();
        emitState();

        io.execute(() -> {
            if (gen != generation) {
                return;
            }
            PairingSession fresh = null;
            try {
                ClientIdentity id = identity();
                clientFingerprint = id.fingerprint;
                if (gen != generation) {
                    return;
                }
                emitState();
                BoundPairing bound = new BoundPairing(h, gen);
                fresh = new PairingSession(h, id.certDer, id.keyDer, bound);
                bound.owner = fresh;
                pairing = fresh;
                pairingHost = h;
                fresh.start();
            } catch (Exception e) {
                if (fresh != null) {
                    fresh.close();
                }
                if (gen != generation) {
                    return;
                }
                pairing = null;
                pairingHost = "";
                status = "error";
                errorKind = "pair";
                error = e.getMessage() == null
                        ? "Could not start pairing. Try again."
                        : e.getMessage();
                emitPair("error", h, error, "");
                emitState();
            }
        });
    }

    void pairFinish(String code) {
        final PairingSession current = pairing;
        if (current == null) {
            String retryHost = pairingHost.isEmpty() ? host : pairingHost;
            if (!retryHost.isEmpty()) {
                pairStart(retryHost);
            } else {
                emitPair("error", "", "Pairing is not active. Start again.", "");
            }
            return;
        }
        final String hostAtStart = pairingHost;
        final int gen = generation;
        io.execute(() -> {
            if (gen != generation) {
                return;
            }
            try {
                current.finish(code);
            } catch (Exception e) {
                if (gen != generation) {
                    return;
                }
                String message = e.getMessage() == null
                        ? "That code did not work. Try again."
                        : e.getMessage();
                emitPair("error", hostAtStart, message, "");
            }
        });
    }

    void pairCancel() {
        generation++;
        PairingSession current = pairing;
        pairing = null;
        pairingHost = "";
        if (current != null) {
            current.close();
        }
        status = "idle";
        error = "";
        errorKind = "";
        emitPair("cancelled", host, "", "");
        emitState();
    }

    void connect(String target) {
        String h = normalizeHost(target);
        if (h.isEmpty()) {
            return;
        }
        if (!isPaired(h)) {
            closeSession();
            cancelPairing(false);
            host = h;
            status = "error";
            errorKind = "pair";
            error = "Pair this phone with the box first.";
            prefs.edit().putString("host", h).apply();
            emitState();
            return;
        }
        final int gen = ++generation;
        userClosed.set(false);
        cancelPairing(false);
        closeSession();
        host = h;
        error = "";
        errorKind = "";
        model = "";
        vendor = "";
        software = "";
        app = "";
        peerCert = "";
        rtt = 0;
        level = -1;
        max = -1;
        status = "connecting";
        prefs.edit().putString("host", h).apply();
        emitState();

        io.execute(() -> open(h, gen));
    }

    void disconnect() {
        userClosed.set(true);
        generation++;
        closeSession();
        cancelPairing(false);
        status = "disconnected";
        error = "";
        errorKind = "";
        emitState();
    }

    void forget() {
        String old = normalizeHost(host);
        disconnect();
        SharedPreferences.Editor edit = prefs.edit().remove("host");
        if (!old.isEmpty()) {
            edit.remove(pairCertKey(old)).remove(pairFpKey(old));
        }
        edit.apply();
        host = "";
        peerCert = "";
        model = "";
        status = "idle";
        emitState();
    }

    void key(String name) {
        AurSession current = session;
        if (current == null || !current.isReady()) {
            toast("Not connected");
            return;
        }
        try {
            current.sendKey(name);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "Key failed" : e.getMessage());
        }
    }

    void text(String value) {
        AurSession current = session;
        if (current == null || !current.isReady()) {
            toast("Not connected");
            return;
        }
        try {
            current.sendText(value);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "Text failed" : e.getMessage());
        }
    }

    void launch(String uri) {
        AurSession current = session;
        if (current == null || !current.isReady()) {
            toast("Not connected");
            return;
        }
        try {
            current.sendAppLink(uri);
        } catch (Exception e) {
            toast(e.getMessage() == null ? "Launch failed" : e.getMessage());
        }
    }

    /**
     * Runs a user-defined macro on the bridge's I/O thread. Keeping the
     * sequence here (rather than in the WebView) means timers keep running
     * when the screen is off and steps cannot overtake each other.
     *
     * Input shape: {"name":"Movie night","steps":[{"type":"key","value":"HOME"},
     * {"type":"wait","ms":400},{"type":"launch","value":"https://..."}]}
     */
    void macro(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        macros.execute(() -> runMacro(json));
    }
    private void runMacro(String json) {
        String name = "Macro";
        try {
            JSONObject spec = new JSONObject(json);
            name = spec.optString("name", "Macro");
            JSONArray steps = spec.optJSONArray("steps");
            if (steps == null || steps.length() == 0) {
                toast("That macro has no steps");
                return;
            }

            AurSession current = session;
            if (current == null || !current.isReady()) {
                toast("Not connected");
                return;
            }

            emitMacro("start", name, "");
            int count = Math.min(steps.length(), 100);
            for (int i = 0; i < count; i++) {
                if (!current.isReady()) {
                    throw new IllegalStateException("Connection lost during macro");
                }
                JSONObject step = steps.optJSONObject(i);
                if (step == null) {
                    continue;
                }
                String type = step.optString("type", "");
                if ("key".equals(type)) {
                    String key = step.optString("value", "");
                    if (!key.isEmpty()) {
                        current.sendKey(key);
                    }
                } else if ("text".equals(type)) {
                    String value = step.optString("value", "");
                    if (!value.isEmpty()) {
                        current.sendText(value);
                    }
                } else if ("launch".equals(type)) {
                    String uri = step.optString("value", "");
                    if (!uri.isEmpty()) {
                        current.sendAppLink(uri);
                    }
                } else if ("wait".equals(type)) {
                    int ms = step.optInt("ms", 300);
                    if (ms > 0) {
                        Thread.sleep(Math.min(ms, 10000));
                    }
                }
            }
            emitMacro("done", name, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitMacro("error", name, "Macro interrupted");
        } catch (Exception e) {
            emitMacro("error", name,
                    e.getMessage() == null ? "Macro failed" : e.getMessage());
        }
    }

    void scan() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        scans.execute(() -> {
            try {
                Discovery discovery = new Discovery(context, (boxes, done) -> {
                    try {
                        JSONObject event = new JSONObject();
                        event.put("type", "scan");
                        event.put("done", done);
                        JSONArray array = new JSONArray();
                        for (Discovery.Box box : boxes) {
                            JSONObject item = new JSONObject();
                            item.put("ip", box.ip);
                            item.put("name", box.name);
                            item.put("model", box.model);
                            array.put(item);
                        }
                        event.put("tvs", array);
                        sink.emit(event.toString());
                    } catch (Exception ignored) {
                        // nothing to report
                    }
                });
                discovery.start();
            } finally {
                scanning.set(false);
            }
        });
    }

    void shutdown() {
        userClosed.set(true);
        generation++;
        closeSession();
        cancelPairing(false);
        io.shutdownNow();
        scans.shutdownNow();
        macros.shutdownNow();
    }

    // ------------------------------------------------------------ connect

    private void open(String target, int gen) {
        if (gen != generation || userClosed.get()) {
            return;
        }

        AurSession previous;
        synchronized (sessionLock) {
            previous = session;
            session = null;
        }
        if (previous != null) {
            previous.close();
        }

        BoundEvents bridgeEvents = new BoundEvents();
        AurSession fresh = null;
        try {
            ClientIdentity id = identity();
            clientFingerprint = id.fingerprint;
            if (gen != generation || userClosed.get()) {
                return;
            }
            String pin = pinnedFingerprint(target);
            if (pin.isEmpty()) {
                host = target;
                status = "error";
                errorKind = "pair";
                error = "Pair this phone with the box first.";
                emitState();
                return;
            }
            fresh = new AurSession(target, AurSession.PORT,
                    id.certDer, id.keyDer, pin, bridgeEvents);
            bridgeEvents.owner = fresh;
            synchronized (sessionLock) {
                session = fresh;
            }
            fresh.connect();
            armReadyWatchdog(fresh, gen);

            if (gen != generation) {
                synchronized (sessionLock) {
                    if (session == fresh) {
                        session = null;
                    }
                }
                fresh.close();
            }
        } catch (Exception e) {
            if (fresh != null) {
                synchronized (sessionLock) {
                    if (session == fresh) {
                        session = null;
                    }
                }
                fresh.close();
            }
            if (gen != generation) {
                return;
            }
            status = "error";
            errorKind = classify(e);
            error = friendly(e, target, errorKind);
            emitState();
        }
    }

    private void reconnecting(int gen) {
        int attempt = 0;
        while (!userClosed.get() && gen == generation && attempt < 10) {
            attempt++;
            try {
                Thread.sleep(Math.min(500L * attempt, 5000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (userClosed.get() || gen != generation) {
                return;
            }

            BoundEvents bridgeEvents = new BoundEvents();
            AurSession fresh = null;
            try {
                ClientIdentity id = identity();
                clientFingerprint = id.fingerprint;
                String pin = pinnedFingerprint(host);
                if (pin.isEmpty()) {
                    status = "error";
                    errorKind = "pair";
                    error = "Pair this phone with the box again.";
                    emitState();
                    return;
                }
                fresh = new AurSession(host, AurSession.PORT,
                        id.certDer, id.keyDer, pin, bridgeEvents);
                bridgeEvents.owner = fresh;
                synchronized (sessionLock) {
                    session = fresh;
                }
                fresh.connect();
                armReadyWatchdog(fresh, gen);
                if (gen != generation) {
                    synchronized (sessionLock) {
                        if (session == fresh) {
                            session = null;
                        }
                    }
                    fresh.close();
                }
                return;
            } catch (Exception ignored) {
                if (fresh != null) {
                    synchronized (sessionLock) {
                        if (session == fresh) {
                            session = null;
                        }
                    }
                    fresh.close();
                }
            }
        }
        if (gen == generation && !userClosed.get()) {
            status = "error";
            errorKind = "unreachable";
            error = "Lost the connection to " + host + ".";
            emitState();
        }
    }

    /**
     * A box that accepts TLS but never finishes remote_configure/remote_start
     * would otherwise leave the UI on "Connecting" forever. Give it a generous
     * window, then surface a normal error and let the user retry.
     */
    private void armReadyWatchdog(AurSession fresh, int gen) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(12000);
            } catch (InterruptedException e) {
                return;
            }
            if (gen != generation || userClosed.get() || fresh.isReady()) {
                return;
            }
            if (!status.equals("connecting") && !status.equals("reconnecting")) {
                return;
            }
            synchronized (sessionLock) {
                if (session != fresh || fresh.isReady()) {
                    return;
                }
                session = null;
            }
            fresh.close();
            status = "error";
            errorKind = "timeout";
            error = "The box accepted the connection but didn't finish setup. "
                    + "Wake it, then try again.";
            emitState();
        }, "aur-ready-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ------------------------------------------------------------- events

    private final class BoundEvents implements AurSession.Events {
        volatile AurSession owner;

        private boolean stale() {
            synchronized (sessionLock) {
                return owner == null || session != owner;
            }
        }

        @Override
        public void onConfigured(String deviceModel, String deviceVendor, String deviceSoftware) {
            if (stale()) {
                return;
            }
            model = deviceModel == null ? "" : deviceModel;
            vendor = deviceVendor == null ? "" : deviceVendor;
            software = deviceSoftware == null ? "" : deviceSoftware;
            emitState();
        }

        @Override
        public void onReady(boolean started, long rttMillis) {
            if (stale()) {
                return;
            }
            if (started) {
                status = "connected";
                rtt = rttMillis;
                error = "";
                errorKind = "";
                emitState();
            }
        }

        @Override
        public void onVolume(int volumeLevel, int volumeMax, boolean volumeMuted) {
            if (stale()) {
                return;
            }
            level = volumeLevel;
            max = volumeMax;
            muted = volumeMuted;
            try {
                JSONObject event = new JSONObject();
                event.put("type", "volume");
                event.put("level", level);
                event.put("max", max);
                event.put("muted", muted);
                sink.emit(event.toString());
            } catch (Exception ignored) {
                // nothing to report
            }
        }

        @Override
        public void onApp(String packageName) {
            if (stale()) {
                return;
            }
            app = packageName == null ? "" : packageName;
            try {
                JSONObject event = new JSONObject();
                event.put("type", "app");
                event.put("app", app);
                sink.emit(event.toString());
            } catch (Exception ignored) {
                // nothing to report
            }
        }

        @Override
        public void onError(String message) {
            if (stale()) {
                return;
            }
            toast(message);
        }

        @Override
        public void onClosed(String reason) {
            synchronized (sessionLock) {
                if (owner == null || session != owner) {
                    return;
                }
                session = null;
            }
            if (userClosed.get()) {
                return;
            }
            final int gen = generation;

            if (status.equals("connected")) {
                status = "reconnecting";
                emitState();
                io.execute(() -> reconnecting(gen));
            } else if (status.equals("connecting")) {
                status = "error";
                errorKind = "dropped";
                error = "The box closed the link during setup. Wake it, then try again.";
                emitState();
            }
        }

        @Override
        public void onPeerCert(String sha256) {
            if (stale()) {
                return;
            }
            peerCert = sha256 == null ? "" : sha256;
        }
    }

    private final class BoundPairing implements PairingSession.Events {
        final String target;
        final int gen;
        volatile PairingSession owner;

        BoundPairing(String target, int gen) {
            this.target = target;
            this.gen = gen;
        }

        private boolean current() {
            return gen == generation && pairing == owner;
        }

        @Override
        public void onWaiting() {
            if (!current()) {
                return;
            }
            emitPair("waiting", target, "Look at the TV for a 6-character code.", "");
        }

        @Override
        public void onPaired(byte[] serverCert) {
            if (!current()) {
                return;
            }
            String fingerprint = storePair(target, serverCert);
            pairing = null;
            pairingHost = "";
            peerCert = fingerprint;
            emitPair("success", target, "Paired", fingerprint);
            connect(target);
        }

        @Override
        public void onError(String message) {
            if (!current()) {
                return;
            }
            pairing = null;
            pairingHost = "";
            status = "error";
            errorKind = "pair";
            error = message == null ? "Pairing failed." : message;
            emitPair("error", target, error, "");
            emitState();
        }
    }

    // -------------------------------------------------------------- utils

    private ClientIdentity identity() throws Exception {
        ClientIdentity local = identity;
        if (local != null) {
            return local;
        }
        synchronized (identityLock) {
            local = identity;
            if (local == null) {
                File dir = new File(context.getFilesDir(), "identity");
                local = ClientIdentity.loadOrCreate(dir);
                identity = local;
                clientFingerprint = local.fingerprint;
            }
        }
        return local;
    }

    private static String normalizeHost(String target) {
        return target == null ? "" : target.trim().toLowerCase(Locale.US);
    }

    private static String pairCertKey(String host) {
        return "pair_cert_" + host;
    }

    private static String pairFpKey(String host) {
        return "pair_fp_" + host;
    }

    private String pinnedFingerprint(String target) {
        return prefs.getString(pairFpKey(normalizeHost(target)), "");
    }

    private String storePair(String target, byte[] serverCert) {
        String h = normalizeHost(target);
        String der = Base64.encodeToString(serverCert, Base64.NO_WRAP);
        String fingerprint = ClientIdentity.sha256(serverCert);
        prefs.edit()
                .putString(pairCertKey(h), der)
                .putString(pairFpKey(h), fingerprint)
                .putString("host", h)
                .apply();
        return fingerprint;
    }

    private void closeSession() {
        AurSession current;
        synchronized (sessionLock) {
            current = session;
            session = null;
        }
        if (current != null) {
            current.close();
        }
    }

    private void cancelPairing(boolean notify) {
        PairingSession current = pairing;
        pairing = null;
        pairingHost = "";
        if (current != null) {
            current.close();
        }
        if (notify) {
            emitPair("cancelled", host, "", "");
        }
    }

    private void emitPair(String statusValue, String target, String message, String fingerprint) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "pair");
            event.put("status", statusValue);
            event.put("host", target == null ? "" : target);
            event.put("message", message == null ? "" : message);
            event.put("fingerprint", fingerprint == null ? "" : fingerprint);
            sink.emit(event.toString());
        } catch (Exception ignored) {
            // nothing to report
        }
    }

    private void emitState() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "state");
            o.put("status", status);
            o.put("host", host == null ? "" : host);
            o.put("model", model);
            o.put("vendor", vendor);
            o.put("sw", software);
            o.put("app", app);
            o.put("rtt", rtt);
            o.put("cert", clientFingerprint);
            o.put("peer", peerCert);
            o.put("paired", isPaired(host));
            o.put("error", error);
            o.put("error_kind", errorKind);
            if (level >= 0) {
                JSONObject volume = new JSONObject();
                volume.put("level", level);
                volume.put("max", max);
                volume.put("muted", muted);
                o.put("volume", volume);
            }
            sink.emit(o.toString());
        } catch (Exception ignored) {
            // never let a JSON failure kill the bridge
        }
    }

    private void toast(String message) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "toast");
            event.put("msg", message);
            sink.emit(event.toString());
        } catch (Exception ignored) {
            // nothing to report
        }
    }

    private void emitMacro(String status, String name, String message) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "macro");
            event.put("status", status);
            event.put("name", name);
            event.put("message", message);
            sink.emit(event.toString());
        } catch (Exception ignored) {
            // nothing to report
        }
    }

    private static String classify(Throwable throwable) {
        Throwable cause = throwable;
        int guard = 0;
        while (cause != null && guard++ < 8) {
            if (cause instanceof SSLHandshakeException) {
                return "auth";
            }
            if (cause instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return "unreachable";
            }
            if (cause instanceof SSLException) {
                return "auth";
            }
            cause = cause.getCause();
        }
        return "network";
    }

    private static String friendly(Exception e, String target, String kind) {
        if ("pair".equals(kind)) {
            return "Pair this phone with the box first.";
        }
        if ("auth".equals(kind)) {
            return "The box rejected this phone's certificate. Pair this phone again.";
        }
        if ("timeout".equals(kind)) {
            return "No answer from " + target + ":6466. The box may be asleep.";
        }
        if ("unreachable".equals(kind)) {
            return "Can't reach " + target + ". Check the phone is on the box's Wi-Fi.";
        }
        String message = e.getMessage();
        return message == null || message.isEmpty()
                ? "Could not connect to " + target + "."
                : message;
    }
}
