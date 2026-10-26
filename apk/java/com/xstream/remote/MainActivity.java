package com.xstream.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
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
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Api(), "Native");

        bridge = new TvBridge(this, json -> runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("window.__tv(" + json + ");", null);
            }
        }));

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl("file:///android_asset/ui/index.html");
    }

    public final class Api {
        @JavascriptInterface
        public String savedHost() {
            return bridge.savedHost();
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
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }
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
