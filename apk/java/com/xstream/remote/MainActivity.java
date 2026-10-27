package com.xstream.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Single-activity shell. The UI is HTML/CSS/JS in assets/ui; every network
 * operation is done natively by TvBridge/AurSession over TLS.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TvBridge bridge;
    private String pendingWidgetKey;
    private boolean widgetSessionConnected;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 8, 11));
        getWindow().setNavigationBarColor(Color.rgb(7, 8, 11));

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(7, 8, 11));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // Off by default. Local builds can opt in with:
        // adb shell am start -n com.xstream.remote/.MainActivity --ez xstream_debug true
        if (getIntent().getBooleanExtra("xstream_debug", false)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Api(), "Native");

        bridge = new TvBridge(this, json -> runOnUiThread(() -> {
            handleWidgetState(json);
            if (web != null) {
                web.evaluateJavascript("window.__tv(" + json + ");", null);
            }
        }));

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/ui/index.html");
        handleWidgetIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWidgetIntent(intent);
    }

    /**
     * A home-screen widget tapped a key (or app link). Remember it and send it
     * once the TV session is ready, (re)connecting first if needed.
     */
    private void handleWidgetIntent(Intent intent) {
        if (intent == null || bridge == null) {
            return;
        }
        String key = intent.getStringExtra("widget_key");
        if (key == null || key.isEmpty()) {
            key = intent.getStringExtra(WidgetActionReceiver.EXTRA_KEY);
        }
        if (key == null || key.isEmpty()) {
            return;
        }
        pendingWidgetKey = key;
        String host = bridge.savedHost();
        if (host.isEmpty()) {
            return;
        }
        if (widgetSessionConnected) {
            bridge.key(pendingWidgetKey);
            pendingWidgetKey = null;
        } else {
            bridge.connect(host);
        }
    }

    /**
     * Reads TV state events from the bridge so a pending widget key can be sent
     * as soon as the session becomes ready.
     */
    private void handleWidgetState(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String status = o.optString("status", "");
            if ("connected".equals(status)) {
                widgetSessionConnected = true;
                if (pendingWidgetKey != null) {
                    bridge.key(pendingWidgetKey);
                    pendingWidgetKey = null;
                }
            } else if ("disconnected".equals(status) || "idle".equals(status)
                    || "error".equals(status)) {
                widgetSessionConnected = false;
            }
        } catch (Exception ignored) {
            // malformed state events are ignored
        }
    }

    public final class Api {
        @JavascriptInterface
        public String savedHost() {
            return bridge.savedHost();
        }

        @JavascriptInterface
        public boolean isPaired(String host) {
            return bridge.isPaired(host);
        }

        @JavascriptInterface
        public void pairStart(String host) {
            bridge.pairStart(host);
        }

        @JavascriptInterface
        public void pairFinish(String code) {
            bridge.pairFinish(code);
        }

        @JavascriptInterface
        public void pairCancel() {
            bridge.pairCancel();
        }

        @JavascriptInterface
        public void haptic(String kind) {
            runOnUiThread(() -> performHaptic(kind));
        }

        @JavascriptInterface
        public void scan() {
            bridge.scan();
        }

        @JavascriptInterface
        public void connect(String host) {
            bridge.connect(host);
        }

        @JavascriptInterface
        public void disconnect() {
            bridge.disconnect();
        }

        @JavascriptInterface
        public void forget() {
            bridge.forget();
        }

        @JavascriptInterface
        public void key(String name) {
            bridge.key(name);
        }

        @JavascriptInterface
        public void text(String value) {
            bridge.text(value);
        }

        @JavascriptInterface
        public void launch(String uri) {
            bridge.launch(uri);
        }

        @JavascriptInterface
        public void macro(String json) {
            bridge.macro(json);
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }
    }

    private void performHaptic(String kind) {
        Haptics.play(this, kind);
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("window.__back&&window.__back()", null);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
