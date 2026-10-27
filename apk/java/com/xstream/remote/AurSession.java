package com.xstream.remote;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One Android TV Remote v2 control session.
 *
 * Wire format: varint length prefix + RemoteMessage protobuf over TLS 1.2/1.3
 * on port 6466. The handshake is reactive: the box sends remote_configure first,
 * we echo it with the negotiated feature mask, then answer set_active and pings.
 *
 * Pure Java on purpose - no android.* imports - so the exact same class can be
 * exercised from a desktop JVM test before it ships inside the APK.
 */
final class AurSession {

    interface Events {
        void onConfigured(String model, String vendor, String software);

        void onReady(boolean started, long rttMillis);

        void onVolume(int level, int max, boolean muted);

        void onApp(String packageName);

        void onError(String message);

        void onClosed(String reason);

        void onPeerCert(String sha256);
    }

    static final int PORT = 6466;

    private static final int F_PING = 1;
    private static final int F_KEY = 2;
    private static final int F_IME = 4;
    private static final int F_POWER = 32;
    private static final int F_VOLUME = 64;
    private static final int F_APP_LINK = 512;
    private static final int FEATURES = F_PING | F_KEY | F_IME | F_POWER | F_VOLUME | F_APP_LINK;

    private static final int DIR_SHORT = 3;

    private static final Map<String, Integer> KEYS = new HashMap<>();

    static {
        KEYS.put("POWER", 26);
        KEYS.put("TV_POWER", 177);
        KEYS.put("HOME", 3);
        KEYS.put("BACK", 4);
        KEYS.put("MENU", 82);
        KEYS.put("UP", 19);
        KEYS.put("DOWN", 20);
        KEYS.put("LEFT", 21);
        KEYS.put("RIGHT", 22);
        KEYS.put("CENTER", 23);
        KEYS.put("OK", 23);
        KEYS.put("VOLUME_UP", 24);
        KEYS.put("VOLUME_DOWN", 25);
        KEYS.put("MUTE", 164);
        KEYS.put("PLAY_PAUSE", 85);
        KEYS.put("PLAY", 126);
        KEYS.put("PAUSE", 127);
        KEYS.put("STOP", 86);
        KEYS.put("NEXT", 87);
        KEYS.put("PREVIOUS", 88);
        KEYS.put("REWIND", 89);
        KEYS.put("FAST_FORWARD", 90);
        KEYS.put("TV", 170);
        KEYS.put("GUIDE", 172);
        KEYS.put("INFO", 165);
        KEYS.put("SETTINGS", 176);
        KEYS.put("ENTER", 66);
        KEYS.put("DEL", 67);
        KEYS.put("SEARCH", 84);
        KEYS.put("ASSIST", 219);
        KEYS.put("CHANNEL_UP", 166);
        KEYS.put("CHANNEL_DOWN", 167);
        KEYS.put("TV_INPUT", 178);
        KEYS.put("CAPTIONS", 175);
        KEYS.put("SLEEP", 223);
        KEYS.put("WAKEUP", 224);
        KEYS.put("VOICE_ASSIST", 231);
        KEYS.put("APP_SWITCH", 187);
        KEYS.put("RED", 183);
        KEYS.put("GREEN", 184);
        KEYS.put("YELLOW", 185);
        KEYS.put("BLUE", 186);
        // Android KEYCODE_0..KEYCODE_9. Useful for channel numbers and macro
        // sequences even though the stock Airtel remote omits a number pad.
        for (int i = 0; i <= 9; i++) {
            KEYS.put("NUM_" + i, 7 + i);
        }
    }

    private final String host;
    private final int port;
    private final byte[] certDer;
    private final byte[] keyDer;
    private final String pinnedFingerprint;
    private final Events events;

    private final Object writeLock = new Object();
    private final Object readyLock = new Object();

    private volatile SSLSocket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile boolean running;
    private volatile boolean ready;
    private volatile Thread reader;

    private volatile int features = FEATURES;
    private volatile long imeCounter;
    private volatile long fieldCounter;
    private volatile long connectStart;

    AurSession(String host, int port, byte[] certDer, byte[] keyDer,
               String pinnedFingerprint, Events events) {
        this.host = host;
        this.port = port;
        this.certDer = certDer;
        this.keyDer = keyDer;
        this.pinnedFingerprint = pinnedFingerprint == null ? "" : pinnedFingerprint;
        this.events = events;
    }

    void connect() throws Exception {
        close();
        if (pinnedFingerprint.isEmpty()) {
            throw new IOException("This phone is not paired with the box");
        }
        connectStart = System.currentTimeMillis();

        SSLContext context = buildContext();
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket();
        ssl.setSoTimeout(8000);
        ssl.connect(new InetSocketAddress(host, port), 8000);
        ssl.setSoTimeout(0);
        ssl.startHandshake();

        socket = ssl;
        input = ssl.getInputStream();
        output = ssl.getOutputStream();
        running = true;

        try {
            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain.length > 0) {
                events.onPeerCert(sha256(chain[0].getEncoded()));
            }
        } catch (Exception ignored) {
            // the trust manager already checked the pinned fingerprint
        }

        Thread thread = new Thread(this::readLoop, "aur-reader");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    boolean awaitReady(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (readyLock) {
            while (!ready && running) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                readyLock.wait(Math.min(left, 250));
            }
        }
        return ready;
    }

    boolean isReady() {
        return ready && running;
    }

    String host() {
        return host;
    }

    void sendKey(String name) {
        Integer code = KEYS.get(name.toUpperCase(Locale.US));
        if (code == null) {
            throw new IllegalArgumentException("Unknown key " + name);
        }
        sendKey(code);
    }

    void sendKey(int code) {
        requireReady();
        write(Proto.msg(10, Proto.cat(Proto.uint(1, code), Proto.uint(2, DIR_SHORT))));
    }

    void sendText(String text) {
        requireReady();
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = Math.max(0, text.codePointCount(0, text.length()) - 1);
        byte[] field = Proto.cat(
                Proto.uint(1, index),
                Proto.uint(2, index),
                Proto.str(3, text));
        byte[] edit = Proto.cat(Proto.uint(1, 1), Proto.msg(2, field));
        byte[] batch = Proto.cat(
                Proto.uint(1, imeCounter),
                Proto.uint(2, fieldCounter),
                Proto.msg(3, edit));
        write(Proto.msg(21, batch));
    }

    void sendAppLink(String uri) {
        requireReady();
        write(Proto.msg(90, Proto.str(1, uri)));
    }

    void close() {
        running = false;
        closeQuietly();
        Thread thread = reader;
        reader = null;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (readyLock) {
            readyLock.notifyAll();
        }
    }

    private void requireReady() {
        if (!isReady()) {
            throw new IllegalStateException("Not connected");
        }
    }

    private void readLoop() {
        String reason = "Connection closed";
        try {
            while (running) {
                int length = readVarint(input);
                if (length < 0 || length > 1 << 20) {
                    throw new IOException("Bad frame length " + length);
                }
                byte[] frame = new byte[length];
                readFully(input, frame);
                handle(frame);
            }
        } catch (EOFException e) {
            reason = "The box closed the connection";
        } catch (IOException e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } catch (RuntimeException e) {
            reason = String.valueOf(e.getMessage());
        } finally {
            boolean wasRunning = running;
            running = false;
            closeQuietly();
            synchronized (readyLock) {
                readyLock.notifyAll();
            }
            if (wasRunning) {
                events.onClosed(reason);
            }
        }
    }

    private void handle(byte[] frame) {
        List<Proto.Field> message = Proto.parse(frame);
        for (Proto.Field field : message) {
            switch (field.number) {
                case 1:
                    onConfigure(field.bytes);
                    break;
                case 2:
                    write(Proto.msg(2, Proto.uint(1, features)));
                    break;
                case 3:
                    events.onError("The box rejected that request");
                    break;
                case 8: {
                    List<Proto.Field> ping = Proto.parse(field.bytes);
                    long value = Proto.number(ping, 1, 0);
                    write(Proto.msg(9, Proto.uint(1, value)));
                    break;
                }
                case 20: {
                    List<Proto.Field> inject = Proto.parse(field.bytes);
                    List<Proto.Field> app = Proto.nested(inject, 1);
                    String pkg = Proto.text(app, 12, "");
                    if (!pkg.isEmpty()) {
                        events.onApp(pkg);
                    }
                    break;
                }
                case 21: {
                    List<Proto.Field> batch = Proto.parse(field.bytes);
                    imeCounter = Proto.number(batch, 1, imeCounter);
                    fieldCounter = Proto.number(batch, 2, fieldCounter);
                    break;
                }
                case 40: {
                    List<Proto.Field> start = Proto.parse(field.bytes);
                    boolean started = Proto.number(start, 1, 0) != 0;
                    ready = started;
                    events.onReady(started, System.currentTimeMillis() - connectStart);
                    break;
                }
                case 50: {
                    List<Proto.Field> volume = Proto.parse(field.bytes);
                    events.onVolume(
                            (int) Proto.number(volume, 7, 0),
                            (int) Proto.number(volume, 6, 0),
                            Proto.number(volume, 8, 0) != 0);
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void onConfigure(byte[] payload) {
        List<Proto.Field> configure = Proto.parse(payload);
        long supported = Proto.number(configure, 1, 0);
        features = (int) (FEATURES & supported);

        List<Proto.Field> device = Proto.nested(configure, 2);
        String model = Proto.text(device, 1, "Android TV");
        String vendor = Proto.text(device, 2, "");
        String software = Proto.text(device, 6, "");
        events.onConfigured(model, vendor, software);

        byte[] info = Proto.cat(
                Proto.uint(3, 1),
                Proto.str(4, "1"),
                Proto.str(5, "atvremote"),
                Proto.str(6, "1.0.0"));
        write(Proto.msg(1, Proto.cat(Proto.uint(1, features), Proto.msg(2, info))));
    }

    private void write(byte[] message) {
        OutputStream out = output;
        if (out == null) {
            return;
        }
        try {
            synchronized (writeLock) {
                out.write(Proto.varint(message.length));
                out.write(message);
                out.flush();
            }
        } catch (IOException e) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        SSLSocket ssl = socket;
        socket = null;
        if (ssl != null) {
            try {
                ssl.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private SSLContext buildContext() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(certDer));

        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyDer));

        char[] password = "xstream".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("client", privateKey, password, new Certificate[]{certificate});

        KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, password);

        TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // local network, certificate is pinned by the pairing
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("The box did not present a certificate");
                }
                String actual = sha256(chain[0].getEncoded());
                if (!actual.equalsIgnoreCase(pinnedFingerprint)) {
                    throw new CertificateException(
                            "The box certificate changed. Pair this phone again.");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), trust, new SecureRandom());
        return context;
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int readVarint(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("stream ended");
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("varint too long");
            }
        }
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int at = 0;
        while (at < buffer.length) {
            int read = in.read(buffer, at, buffer.length - at);
            if (read < 0) {
                throw new EOFException("stream ended");
            }
            at += read;
        }
    }
}
