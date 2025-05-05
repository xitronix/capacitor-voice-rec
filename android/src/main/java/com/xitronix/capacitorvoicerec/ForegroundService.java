package com.xitronix.capacitorvoicerec;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class ForegroundService extends Service {

    public static final String CHANNEL_ID = "VoiceRecorderChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String EXTRA_ICON_RES_NAME = "small_icon_res_name";
    public static final String ACTION_STOP_FOREGROUND_SERVICE = "com.xitronix.capacitorvoicerec.STOP_FOREGROUND_SERVICE";
    private static final String TAG = "VoiceRecorderService";
    
    // Static instance tracking to ensure only one service runs at a time
    private static ForegroundService activeInstance = null;
    private static final Object instanceLock = new Object();
    
    // Static method to check if service is running
    public static boolean isServiceRunning() {
        synchronized (instanceLock) {
            return activeInstance != null;
        }
    }
    
    // Static method to stop any running service
    public static void stopService() {
        synchronized (instanceLock) {
            if (activeInstance != null) {
                try {
                    Intent intent = new Intent(activeInstance, ForegroundService.class);
                    intent.setAction(ACTION_STOP_FOREGROUND_SERVICE);
                    activeInstance.startService(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping foreground service", e);
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (instanceLock) {
            activeInstance = this;
        }
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as foreground service with notification
        if (intent != null) {
            String iconName = intent.getStringExtra(EXTRA_ICON_RES_NAME);
            startForeground(NOTIFICATION_ID, buildNotification(iconName));
            
            String action = intent.getAction();
            if (action != null && action.equals(ACTION_STOP_FOREGROUND_SERVICE)) {
                // If we have an active recorder in the service, stop it
                if (VoiceRecorder.getActiveRecorder() != null) {
                    try {
                        VoiceRecorder.getActiveRecorder().stopRecording();
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping recording in foreground service", e);
                    }
                }
                
                // Stop the service
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            // If intent is null, still start foreground with default notification
            startForeground(NOTIFICATION_ID, buildNotification(null));
        }
        
        // Default handling for continuation
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (instanceLock) {
            if (activeInstance == this) {
                activeInstance = null;
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String notificationIconResName) {
        @SuppressLint("DiscouragedApi")
        int iconResId = 0;
        
        if (notificationIconResName != null) {
            iconResId = getResources().getIdentifier(notificationIconResName, "drawable", getPackageName());
        }
        
        if (iconResId == 0) {
            iconResId = android.R.drawable.ic_btn_speak_now; // Standard microphone icon as fallback
        }
        
        // Create intent for stopping the recording when notification is clicked
        Intent stopIntent = new Intent(this, ForegroundService.class);
        stopIntent.setAction(ACTION_STOP_FOREGROUND_SERVICE);
        PendingIntent pendingStopIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : 
                PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Find the app's main activity
        Intent mainIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        
        if (mainIntent != null) {
            contentIntent = PendingIntent.getActivity(
                this, 0, mainIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? 
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : 
                    PendingIntent.FLAG_UPDATE_CURRENT
            );
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in Progress")
            .setContentText("Tap to return to app")
            .setSmallIcon(iconResId)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true);
            
        // Set content intent if available
        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        
        // Add stop action
        builder.addAction(android.R.drawable.ic_media_pause, "Stop Recording", pendingStopIntent);
        
        return builder.build();
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
