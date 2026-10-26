package com.xstream.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Probe one app-link URI per run. The box closes the session when it cannot
 * resolve an intent, so each probe reconnects cleanly.
 *
 * usage: java com.xstream.remote.TestLaunch 192.168.1.10 "market://details?id=..."
 */
public final class TestLaunch {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        String uri = args[1];

        byte[] certPem = Files.readAllBytes(Paths.get("assets", "client.pem"));
        String keyPem = new String(Files.readAllBytes(Paths.get("assets", "client.key")),
                StandardCharsets.UTF_8);
        byte[] keyDer = Base64.getDecoder().decode(keyPem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", ""));

        AurSession session = new AurSession(host, AurSession.PORT, certPem, keyDer,
                new AurSession.Events() {
                    @Override
                    public void onConfigured(String model, String vendor, String software) {
                        System.out.println("  configured " + model);
                    }

                    @Override
                    public void onReady(boolean started, long rttMillis) {
                        System.out.println("  ready rtt=" + rttMillis);
                    }

                    @Override
                    public void onVolume(int level, int max, boolean muted) {
                        System.out.println("  volume " + level + "/" + max);
                    }

                    @Override
                    public void onApp(String packageName) {
                        System.out.println("  app " + packageName);
                    }

                    @Override
                    public void onError(String message) {
                        System.out.println("  ERROR " + message);
                    }

                    @Override
                    public void onClosed(String reason) {
                        System.out.println("  CLOSED " + reason);
                    }

                    @Override
                    public void onPeerCert(String sha256) {
                    }
                });

        session.connect();
        if (!session.awaitReady(8000)) {
            System.out.println("  not ready, aborting");
            session.close();
            return;
        }
        Thread.sleep(400);
        System.out.println("launching " + uri);
        session.sendAppLink(uri);
        Thread.sleep(4000);
        if (session.isReady()) {
            System.out.println("  returning home");
            session.sendKey("HOME");
            Thread.sleep(1200);
        }
        session.close();
        System.out.println("done");
    }
}
