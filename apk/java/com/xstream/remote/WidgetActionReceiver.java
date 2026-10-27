package com.xstream.remote;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives taps from the home-screen widget keys and forwards them to
 * {@link MainActivity}, which (re)connects to the paired TV and sends the key.
 */
public class WidgetActionReceiver extends BroadcastReceiver {

    public static final String ACTION_KEY = "com.xstream.remote.action.WIDGET_KEY";
    public static final String EXTRA_KEY = "widget_key";
    public static final String EXTRA_APP = "widget_app";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!ACTION_KEY.equals(action)) {
            return;
        }
        String key = intent.getStringExtra(EXTRA_KEY);
        if (key == null || key.isEmpty()) {
            return;
        }
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra(EXTRA_KEY, key);
        launch.putExtra("widget_source", true);
        context.startActivity(launch);
    }
}
