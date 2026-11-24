package com.raphael.app.core;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.*;
import com.raphael.app.MainActivity;
import com.raphael.app.R;

public class RaphaelService extends Service {
    public static final String CH_ID = "raphael_bg";
    public static final String ACT_STOP = "com.raphael.app.ACTION_STOP";
    public static final int NOTIF_ID = 101;
    private static RaphaelService inst;
    private static String curTitle = "Raphael", curText = "Arka planda çalışıyor";

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "Raphael Arka Plan", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false); nm.createNotificationChannel(ch);
        }
        goForeground(buildNotif(curTitle, curText));
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACT_STOP.equals(i.getAction())) {
            AppCore.stopFromService(this);
            stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }
        goForeground(buildNotif(curTitle, curText));
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { if (inst == this) inst = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }

    private void goForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_ID, n);
    }

    private Notification buildNotif(String title, String text) {
        Intent oi = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent opi = PendingIntent.getActivity(this, 1, oi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent si = new Intent(this, RaphaelService.class).setAction(ACT_STOP);
        PendingIntent spi = PendingIntent.getService(this, 2, si, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAct = new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), "Durdur", spi).build();
        return new Notification.Builder(this, CH_ID).setSmallIcon(R.drawable.ic_star).setContentTitle(title).setContentText(text).setContentIntent(opi).setOngoing(true).setOnlyAlertOnce(true).addAction(stopAct).build();
    }

    public static void start(Context c) {
        Context ac = c.getApplicationContext();
        ac.startForegroundService(new Intent(ac, RaphaelService.class));
    }

    public static void update(Context c, String title, String text) {
        curTitle = title; curText = text;
        if (inst == null) return;
        NotificationManager nm = (NotificationManager) inst.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, inst.buildNotif(title, text));
    }

    public static void stop(Context c) {
        if (inst != null) { inst.stopForeground(true); inst.stopSelf(); }
        else { Context ac = c.getApplicationContext(); ac.stopService(new Intent(ac, RaphaelService.class)); }
    }
}

