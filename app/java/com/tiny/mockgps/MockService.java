package com.tiny.mockgps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 前台服务：用 LocationManager 的测试 provider 把坐标写入系统定位。
 * 与原「模拟定位助手」同一套机制（addTestProvider + setTestProviderLocation 循环），
 * 但不依赖任何第三方 SDK。
 */
public class MockService extends Service {

    public static final String ACTION_START = "com.tiny.mockgps.START";
    public static final String ACTION_STOP = "com.tiny.mockgps.STOP";
    public static final String ACTION_STATE = "com.tiny.mockgps.STATE";

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_INTERVAL = "interval";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "mock_gps";
    private static final int NOTIF_ID = 1001;

    private LocationManager lm;
    private Handler handler;
    private Location location;
    private long interval = 1000L;
    private boolean running = false;
    private long lastTouch = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMocking();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            double lat = intent.getDoubleExtra(EXTRA_LAT, 0);
            double lng = intent.getDoubleExtra(EXTRA_LNG, 0);
            interval = intent.getLongExtra(EXTRA_INTERVAL, 1000L);
            try {
                startForegroundCompat(lat, lng);
            } catch (Throwable t) {
                broadcast(false, describe(t));
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                beginMocking(lat, lng);
                broadcast(true, String.format("%.6f, %.6f", lng, lat));
            } catch (Throwable t) {
                broadcast(false, describe(t));
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private String describe(Throwable t) {
        String msg = t.getMessage() == null ? "" : t.getMessage();
        if (msg.contains("Starting FGS with type location") || msg.contains("foregroundServiceType")) {
            return "缺少定位权限：Android 14+ 需先允许本应用获取位置信息";
        }
        if (t instanceof SecurityException) {
            return "未获得模拟位置权限：请在 开发者选项 → 选择模拟位置信息应用 中选择「虚拟定位」";
        }
        if (msg.contains("provider")) {
            return "该设备没有可用的定位 provider：" + msg;
        }
        return "启动失败：" + (msg.isEmpty() ? t.getClass().getSimpleName() : msg);
    }

    /**
     * 心跳：把「服务确实活着」写进本地配置。
     * 进程被强杀时 onDestroy 不会执行，界面下次启动就靠这个时间戳判断服务是否还在跑。
     */
    private void touch() {
        long now = System.currentTimeMillis();
        if (now - lastTouch < 2000L) {
            return;
        }
        lastTouch = now;
        getSharedPreferences("mock", MODE_PRIVATE).edit()
                .putBoolean("running", true)
                .putLong("last_tick", now)
                .apply();
    }

    private void beginMocking(double lat, double lng) {
        stopMockingInternal();

        location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(lat);
        location.setLongitude(lng);
        location.setAccuracy(1.0f);
        location.setAltitude(30.0);
        location.setBearing(1.0f);
        location.setSpeed(0.0f);
        location.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }

        setupProvider(LocationManager.GPS_PROVIDER);
        try {
            setupProvider(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable ignored) {
            // 部分设备没有 network provider，忽略
        }

        running = true;
        lastTouch = 0L;
        touch();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                try {
                    location.setTime(System.currentTimeMillis());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                    }
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
                    try {
                        lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, location);
                    } catch (Throwable ignored) {
                    }
                    touch();
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, interval);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void setupProvider(String name) {
        boolean exists = false;
        try {
            exists = lm.getAllProviders() != null && lm.getAllProviders().contains(name);
        } catch (Throwable ignored) {
        }
        if (exists) {
            try {
                lm.removeTestProvider(name);
            } catch (Throwable ignored) {
            }
        }
        lm.addTestProvider(name, false, false, false, false, true, true, true, 3, 2);
        lm.setTestProviderEnabled(name, true);
    }

    private void stopMocking() {
        stopMockingInternal();
        broadcast(false, "");
    }

    private void stopMockingInternal() {
        running = false;
        lastTouch = 0L;
        getSharedPreferences("mock", MODE_PRIVATE).edit()
                .putBoolean("running", false)
                .putLong("last_tick", 0L)
                .apply();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (lm == null) {
            return;
        }
        for (String name : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                lm.setTestProviderEnabled(name, false);
            } catch (Throwable ignored) {
            }
            try {
                lm.removeTestProvider(name);
            } catch (Throwable ignored) {
            }
        }
    }

    private void broadcast(boolean ok, String message) {
        Intent i = new Intent(ACTION_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_RUNNING, ok);
        i.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(i);
    }

    private void startForegroundCompat(double lat, double lng) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pendingFlags());

        Intent stop = new Intent(this, MockService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, pendingFlags());

        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(String.format("%.6f, %.6f", lng, lat))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.notif_stop), stopPi).build());

        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopMockingInternal();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
