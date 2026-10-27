package com.xstream.remote;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * Home-screen widget: two big one-tap keys per cell so the remote is usable
 * without opening the app. Tapping a key brings Xstream to the front and sends
 * the key to the paired TV (reconnecting first if needed).
 */
public class Widget2x2Provider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context, id));
        }
    }

    private static RemoteViews build(Context context, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_2x2);
        setStatus(context, rv);
        register(context, rv, id, R.id.btn_power, "POWER");
        register(context, rv, id, R.id.btn_home, "HOME");
        register(context, rv, id, R.id.btn_back, "BACK");
        register(context, rv, id, R.id.btn_ok, "CENTER");
        return rv;
    }

    public static void setStatus(Context context, RemoteViews rv) {
        SharedPreferences prefs = context.getSharedPreferences("xstream", Context.MODE_PRIVATE);
        String host = prefs.getString("host", "");
        if (host.isEmpty()) {
            rv.setTextViewText(R.id.widget_status, "Xstream Remote");
            rv.setTextViewText(R.id.widget_sub, "Not paired — open the app to pair");
        } else {
            rv.setTextViewText(R.id.widget_status, "Xstream Remote");
            rv.setTextViewText(R.id.widget_sub, "Paired to " + host);
        }
    }

    private static void register(Context context, RemoteViews rv, int appWidgetId, int viewId,
                                 String key) {
        Intent intent = new Intent(context, WidgetActionReceiver.class);
        intent.setAction(WidgetActionReceiver.ACTION_KEY);
        intent.putExtra(WidgetActionReceiver.EXTRA_KEY, key);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        int requestCode = (appWidgetId * 1000) + viewId;
        android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                context, requestCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(viewId, pi);
    }
}
