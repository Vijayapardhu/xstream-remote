package com.xstream.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean userClosed = new AtomicBoolean(false);
    private final Object sessionLock = new Object();

    private volatile AurSession session;
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
        this.clientFingerprint = readClientFingerprint();
    }

    // ------------------------------------------------------------- public

    String savedHost() {
        return prefs.getString("host", "");
    }

    void connect(String target) {
        if (target == null) {
            return;
        }
        target = target.trim();
        if (target.isEmpty()) {
            return;
        }
        final int gen = ++generation;
        userClosed.set(false);
        host = target;
        error = "";
        errorKind = "";
        model = "";
        vendor = "";
        software = "";
        app = "";
        peerCert = "";
        rtt = 0;
        status = "connecting";
        prefs.edit().putString("host", target).apply();
        emitState();

        final String ip = target;
        io.execute(() -> open(ip, gen));
    }

    void disconnect() {
        userClosed.set(true);
        generation++;
        AurSession current;
        synchronized (sessionLock) {
            current = session;
            session = null;
        }
        if (current != null) {
            current.close();
        }
        status = "disconnected";
        error = "";
        errorKind = "";
        emitState();
    }

    void forget() {
        disconnect();
        prefs.edit().remove("host").apply();
        host = "";
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
        AurSession current;
        synchronized (sessionLock) {
            current = session;
            session = null;
        }
        if (current != null) {
            current.close();
        }
        io.shutdownNow();
        scans.shutdownNow();
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
            byte[] cert = readAsset("client.pem");
            byte[] key = decodePemKey(readAsset("client.key"));
            fresh = new AurSession(target, AurSession.PORT, cert, key, bridgeEvents);
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
                byte[] cert = readAsset("client.pem");
                byte[] key = decodePemKey(readAsset("client.key"));
                fresh = new AurSession(host, AurSession.PORT, cert, key, bridgeEvents);
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

    // -------------------------------------------------------------- utils

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
        if ("auth".equals(kind)) {
            return "The box rejected this phone's certificate.";
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

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decodePemKey(byte[] pemBytes) {
        String pem = new String(pemBytes, StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.decode(pem, Base64.DEFAULT);
    }

    private String readClientFingerprint() {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(readAsset("client.pem")));
            return AurSession.sha256(certificate.getEncoded());
        } catch (Exception e) {
            return "";
        }
    }
}
