package com.xstream.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds Android TV Remote v2 boxes: mDNS first, then a short /24 sweep of the
 * API port. Both are needed in practice - some routers filter multicast, and
 * the box only answers mDNS over multicast.
 */
final class Discovery {

    interface Listener {
        void onResult(List<Box> boxes, boolean done);
    }

    static final class Box {
        final String ip;
        final String name;
        final String model;

        Box(String ip, String name, String model) {
            this.ip = ip;
            this.name = name;
            this.model = model;
        }
    }

    private static final String SERVICE = "_androidtvremote2._tcp";

    private final Context context;
    private final Listener listener;
    private final Map<String, Box> found = Collections.synchronizedMap(new LinkedHashMap<String, Box>());
    private final ExecutorService sweepPool = Executors.newFixedThreadPool(24);
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();

    private volatile boolean running;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;

    Discovery(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        found.clear();
        emit(false);

        acquireMulticast();
        startMdns();

        try {
            sweep();
            // give late mDNS resolutions a moment to land
            Thread.sleep(1400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        finish();
    }

    void stop() {
        running = false;
        stopMdns();
        releaseMulticast();
    }

    private void emit(boolean done) {
        List<Box> boxes;
        synchronized (found) {
            boxes = new ArrayList<>(found.values());
        }
        listener.onResult(boxes, done);
    }

    private void add(String ip, String name, String model) {
        if (ip == null || ip.isEmpty() || found.containsKey(ip)) {
            return;
        }
        found.put(ip, new Box(ip, name == null || name.isEmpty() ? "Android TV" : name, model));
        emit(false);
    }

    private void finish() {
        stopMdns();
        releaseMulticast();
        emit(true);
        running = false;
    }

    // ---------------------------------------------------------------- mDNS

    private void startMdns() {
        try {
            nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String type) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    if (info.getServiceType() != null
                            && info.getServiceType().contains("_androidtvremote2")) {
                        resolve(info);
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }

                @Override
                public void onDiscoveryStopped(String type) {
                }

                @Override
                public void onStartDiscoveryFailed(String type, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String type, int errorCode) {
                }
            };
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception ignored) {
            // sweep is the fallback
        }
    }

    private void resolve(final NsdServiceInfo info) {
        resolver.execute(() -> {
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        latch.countDown();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        try {
                            String ip = ipv4Of(serviceInfo);
                            if (ip != null) {
                                add(ip, serviceInfo.getServiceName(), "");
                            }
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            } catch (Exception e) {
                latch.countDown();
            }
            try {
                latch.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static String ipv4Of(NsdServiceInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                java.util.List<InetAddress> addresses = info.getHostAddresses();
                if (addresses != null) {
                    for (InetAddress address : addresses) {
                        if (address instanceof Inet4Address) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
            InetAddress host = info.getHost();
            return host == null ? null : host.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private void stopMdns() {
        if (nsd != null && discoveryListener != null) {
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
                // already stopped
            }
        }
        nsd = null;
        discoveryListener = null;
        resolver.shutdownNow();
    }

    // --------------------------------------------------------------- sweep

    private void sweep() {
        String local = localIpv4();
        if (local == null || !local.contains(".")) {
            return;
        }
        final String prefix = local.substring(0, local.lastIndexOf('.') + 1);
        final CountDownLatch latch = new CountDownLatch(254);

        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(local)) {
                latch.countDown();
                continue;
            }
            sweepPool.execute(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new java.net.InetSocketAddress(ip, AurSession.PORT), 300);
                    add(ip, "Android TV", "");
                } catch (Exception ignored) {
                    // closed port is the normal case
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(7, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sweepPool.shutdownNow();
    }

    private static String localIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface net = interfaces.nextElement();
                if (!net.isUp() || net.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : net.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress()) {
                        continue;
                    }
                    String name = net.getName();
                    if (name != null && (name.startsWith("wlan") || name.startsWith("ap"))) {
                        return ip.getHostAddress();
                    }
                    if (fallback == null) {
                        fallback = ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // no interfaces available
        }
        return fallback;
    }

    private void acquireMulticast() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("xstream-mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {
            // multicast may still work without the lock
        }
    }

    private void releaseMulticast() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception ignored) {
            // nothing to release
        }
        multicastLock = null;
    }
}
