package com.xstream.remote;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Home-screen widget: playback and volume keys in a wider strip.
 */
public class WidgetRemoteProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context, id));
        }
    }

    private static RemoteViews build(Context context, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_remote);
        Widget2x2Provider.setStatus(context, rv);
        register(context, rv, id, R.id.btn_power, "POWER");
        register(context, rv, id, R.id.btn_back, "BACK");
        register(context, rv, id, R.id.btn_home, "HOME");
        register(context, rv, id, R.id.btn_ok, "CENTER");
        register(context, rv, id, R.id.btn_vol_down, "VOLUME_DOWN");
        register(context, rv, id, R.id.btn_mute, "VOLUME_MUTE");
        register(context, rv, id, R.id.btn_vol_up, "VOLUME_UP");
        register(context, rv, id, R.id.btn_play_pause, "PLAY_PAUSE");
        return rv;
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
