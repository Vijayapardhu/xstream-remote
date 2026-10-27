package com.xstream.remote;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The Google TV pairing handshake on port 6467.
 *
 * The TV displays a six-hex-character code after the configuration exchange.
 * The secret sent back is a SHA-256 over both RSA public keys and the last four
 * code characters. The first two characters are a checksum of the digest.
 *
 * Pure Java so it can be tested on a desktop JVM against a mock server.
 */
final class PairingSession {

    interface Events {
        /** The TV is now showing the pairing code. */
        void onWaiting();

        /** Pairing completed; serverCert is the TV certificate to pin. */
        void onPaired(byte[] serverCert);

        /** Pairing failed or the connection closed before completion. */
        void onError(String message);
    }

    static final int PORT = 6467;

    private static final int TIMEOUT_MS = 180000;

    private final String host;
    private final byte[] certDer;
    private final byte[] keyDer;
    private final Events events;
    private final Object writeLock = new Object();

    private volatile SSLSocket socket;
    private volatile InputStream input;
    private volatile OutputStream output;
    private volatile Thread reader;
    private volatile boolean running;
    private volatile boolean waiting;
    private volatile boolean finished;
    private volatile boolean secretSent;
    private volatile byte[] peerCertDer;
    private volatile boolean failed;

    PairingSession(String host, byte[] certDer, byte[] keyDer, Events events) {
        this.host = host;
        this.certDer = certDer;
        this.keyDer = keyDer;
        this.events = events;
    }

    /**
     * Connect to the TV and start the request. The reader thread continues the
     * exchange and calls {@link Events#onWaiting()} when the code appears.
     */
    void start() throws Exception {
        close();
        failed = false;
        waiting = false;
        finished = false;
        secretSent = false;

        SSLContext context = buildContext();
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket();
        try {
            ssl.setSoTimeout(12000);
            ssl.connect(new InetSocketAddress(host, PORT), 12000);
            ssl.setSoTimeout(12000);
            ssl.startHandshake();

            Certificate[] chain = ssl.getSession().getPeerCertificates();
            if (chain == null || chain.length == 0) {
                throw new IOException("The box did not present a certificate");
            }
            peerCertDer = chain[0].getEncoded();

            socket = ssl;
            input = ssl.getInputStream();
            output = ssl.getOutputStream();
            running = true;

            Thread thread = new Thread(this::readLoop, "xstream-pairing");
            thread.setDaemon(true);
            reader = thread;
            thread.start();

            // The pairing timeout is intentionally generous: the user may need
            // to find the TV, read the code, and type it with a remote in one hand.
            ssl.setSoTimeout(TIMEOUT_MS);
            sendRequest();
        } catch (Exception e) {
            running = false;
            try {
                ssl.close();
            } catch (IOException ignored) {
                // already failing
            }
            socket = null;
            input = null;
            output = null;
            throw e;
        }
    }

    /**
     * Send the secret for the code shown on the TV. Safe to call from the UI
     * thread: it only writes one small frame.
     */
    void finish(String pairingCode) throws Exception {
        if (!running) {
            throw new IOException("Pairing connection is not open");
        }
        if (finished || secretSent) {
            return;
        }
        String code = pairingCode == null ? "" : pairingCode.trim().toUpperCase(Locale.US);
        if (!code.matches("[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Enter the 6-character code shown on the TV");
        }

        RSAPublicKey clientKey = (RSAPublicKey) certificate(certDer).getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) certificate(peerCertDer).getPublicKey();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(unsignedBytes(clientKey.getModulus()));
        digest.update(unsignedBytes(clientKey.getPublicExponent()));
        digest.update(unsignedBytes(serverKey.getModulus()));
        digest.update(unsignedBytes(serverKey.getPublicExponent()));
        digest.update(hexBytes(code.substring(2)));
        byte[] secret = digest.digest();

        int check = Integer.parseInt(code.substring(0, 2), 16);
        if ((secret[0] & 0xFF) != check) {
            throw new IllegalArgumentException("That code doesn't match the TV. Try again.");
        }

        secretSent = true;
        byte[] inner = Proto.msg(1, secret);
        write(outer(Proto.msg(40, inner)));
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
    }

    boolean isWaiting() {
        return waiting && running;
    }

    // ------------------------------------------------------------- protocol

    private void sendRequest() throws IOException {
        byte[] request = Proto.cat(
                Proto.str(1, "atvremote"),
                Proto.str(2, ClientIdentity.CLIENT_NAME));
        write(outer(Proto.msg(10, request)));
    }

    private void sendOptions() throws IOException {
        byte[] encoding = Proto.cat(Proto.uint(1, 3), Proto.uint(2, 6));
        byte[] options = Proto.cat(Proto.msg(1, encoding), Proto.uint(3, 1));
        write(outer(Proto.msg(20, options)));
    }

    private void sendConfiguration() throws IOException {
        byte[] encoding = Proto.cat(Proto.uint(1, 3), Proto.uint(2, 6));
        byte[] configuration = Proto.cat(Proto.msg(1, encoding), Proto.uint(2, 1));
        write(outer(Proto.msg(30, configuration)));
    }

    private void readLoop() {
        try {
            while (running) {
                int length = readVarint(input);
                if (length < 0 || length > 1 << 20) {
                    throw new IOException("Bad pairing frame");
                }
                byte[] frame = new byte[length];
                readFully(input, frame);
                handle(frame);
            }
        } catch (SocketTimeoutException e) {
            fail("Pairing timed out. Try again.");
        } catch (EOFException e) {
            fail("The box closed the pairing connection.");
        } catch (IOException e) {
            fail(e.getMessage() == null ? "Pairing connection lost." : e.getMessage());
        } catch (RuntimeException e) {
            fail(e.getMessage() == null ? "Pairing failed." : e.getMessage());
        }
    }

    private void handle(byte[] frame) throws IOException {
        List<Proto.Field> message = Proto.parse(frame);
        long status = Proto.number(message, 2, 200);
        if (status != 200) {
            fail("The TV rejected pairing (status " + status + "). Check the code and try again.");
            return;
        }

        if (Proto.find(message, 11) != null) {
            sendOptions();
            return;
        }
        if (Proto.find(message, 20) != null) {
            sendConfiguration();
            return;
        }
        if (Proto.find(message, 31) != null) {
            waiting = true;
            events.onWaiting();
            return;
        }
        if (Proto.find(message, 41) != null) {
            finished = true;
            running = false;
            closeQuietly();
            events.onPaired(peerCertDer);
            return;
        }

        // An empty status-only message can arrive while the TV transitions
        // screens. Ignore it rather than killing a valid pairing session.
        if (message.isEmpty()) {
            return;
        }
        fail("Unexpected response from the TV during pairing.");
    }

    private void write(byte[] message) throws IOException {
        OutputStream out = output;
        if (out == null) {
            throw new IOException("Pairing connection is not open");
        }
        try {
            synchronized (writeLock) {
                out.write(Proto.varint(message.length));
                out.write(message);
                out.flush();
            }
        } catch (IOException e) {
            running = false;
            closeQuietly();
            throw e;
        }
    }

    private byte[] outer(byte[] body) {
        return Proto.cat(Proto.uint(1, 2), Proto.uint(2, 200), body);
    }

    private void fail(String message) {
        if (failed || finished) {
            return;
        }
        failed = true;
        running = false;
        closeQuietly();
        events.onError(message);
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

    // --------------------------------------------------------------- crypto

    private SSLContext buildContext() throws Exception {
        X509Certificate certificate = certificate(certDer);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyDer));

        char[] password = "xstream-pair".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("client", privateKey, password,
                new Certificate[]{certificate});

        KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, password);

        TrustManager[] trust = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // pairing is authenticated by the six-character secret
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // The TV certificate is pinned after the secret is accepted.
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

    private static X509Certificate certificate(byte[] der) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }

    /**
     * Big-endian unsigned bytes, without the sign-padding byte that
     * BigInteger.toByteArray() adds for a high bit. This matches the Python
     * client's "{n:X}" formatting used by the TV protocol.
     */
    private static byte[] unsignedBytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] out = new byte[raw.length - 1];
            System.arraycopy(raw, 1, out, 0, out.length);
            return out;
        }
        return raw;
    }

    private static byte[] hexBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // --------------------------------------------------------------- framing

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
