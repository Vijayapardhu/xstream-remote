package com.xstream.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Desktop harness: proves the exact protocol code that ships in the APK by
 * connecting to the real box from a plain JVM. Not packaged into the app.
 *
 * usage: java com.xstream.remote.TestAur 192.168.1.10
 */
public final class TestAur {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "192.168.1.10";

        byte[] certPem = Files.readAllBytes(Paths.get("assets", "client.pem"));
        String keyPem = new String(Files.readAllBytes(Paths.get("assets", "client.key")),
                StandardCharsets.UTF_8);
        String base64 = keyPem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] keyDer = Base64.getDecoder().decode(base64);

        AurSession.Events events = new AurSession.Events() {
            @Override
            public void onConfigured(String model, String vendor, String software) {
                System.out.println("configured  vendor=" + vendor + "  model=" + model
                        + "  sw=" + software);
            }

            @Override
            public void onReady(boolean started, long rttMillis) {
                System.out.println("ready=" + started + "  rtt=" + rttMillis + "ms");
            }

            @Override
            public void onVolume(int level, int max, boolean muted) {
                System.out.println("volume      " + level + "/" + max + (muted ? " (muted)" : ""));
            }

            @Override
            public void onApp(String packageName) {
                System.out.println("app         " + packageName);
            }

            @Override
            public void onError(String message) {
                System.out.println("error       " + message);
            }

            @Override
            public void onClosed(String reason) {
                System.out.println("closed      " + reason);
            }

            @Override
            public void onPeerCert(String sha256) {
                System.out.println("peer cert   " + sha256);
            }
        };

        AurSession session = new AurSession(host, AurSession.PORT, certPem, keyDer, events);
        long started = System.currentTimeMillis();
        session.connect();
        System.out.println("tls ok      " + (System.currentTimeMillis() - started) + "ms");

        if (!session.awaitReady(8000)) {
            System.out.println("FAILED: never became ready");
            session.close();
            return;
        }

        Thread.sleep(500);
        System.out.println("-> volume up");
        session.sendKey("VOLUME_UP");
        Thread.sleep(900);
        System.out.println("-> volume down");
        session.sendKey("VOLUME_DOWN");
        Thread.sleep(700);

        System.out.println("-> home");
        session.sendKey("HOME");
        Thread.sleep(700);

        System.out.println("-> text 'hello'");
        try {
            session.sendText("hello");
        } catch (Exception e) {
            System.out.println("   text failed: " + e);
        }

        System.out.println("-> app link youtube");
        session.sendAppLink("market://launch?id=com.google.android.youtube.tv");
        Thread.sleep(3500);

        System.out.println("-> home again");
        session.sendKey("HOME");
        Thread.sleep(900);

        session.close();
        Thread.sleep(300);
        System.out.println("done");
    }
}
