package com.tchvu3.capacitorvoicerecorder;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class ForegroundService extends Service {

    public static final String CHANNEL_ID = "VoiceRecorderChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String EXTRA_ICON_RES_NAME = "small_icon_res_name";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification(intent.hasExtra(EXTRA_ICON_RES_NAME) ? intent.getStringExtra(EXTRA_ICON_RES_NAME): "icon_default");
        // Start the foreground service with the appropriate type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop your recording logic here
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String notificationIconResName) {
        @SuppressLint("DiscouragedApi") int iconResId = getResources().getIdentifier(notificationIconResName, "drawable" ,getPackageName());

        if (iconResId == 0) {
            iconResId = R.drawable.default_icon; // fallback to default icon
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Recording in Progress")
                .setContentText("Remember to stop recording")
                .setSmallIcon(iconResId) // Ensure you have a small icon set
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel serviceChannel = new NotificationChannel(
                        CHANNEL_ID,
                        "Voice Recorder Service Channel",
                        NotificationManager.IMPORTANCE_LOW
                );
                serviceChannel.setSound(null, null);
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
