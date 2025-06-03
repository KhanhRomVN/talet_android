package com.kr.talet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import java.lang.reflect.Field;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * ForegroundService chuẩn để chạy MediaProjection encoder trên Android 10+
 */
public class H264StreamService extends Service {
    public static final String TAG = "H264StreamService";
    public static final String CHANNEL_ID = "screen_share_channel";
    public static final int NOTI_ID = 5562;

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_PC_IP = "server_ip";
    public static final String EXTRA_PC_PORT = "server_port";

    private H264ScreenStreamer streamer;
    private MediaProjection mediaProjection;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        // Revert: lấy permission intent từ static holder
        Intent data = com.kr.talet.StreamIntentCache.getAndClear();
        String ip = intent.getStringExtra(EXTRA_PC_IP);
        int port = intent.getIntExtra(EXTRA_PC_PORT, 5000);

        Log.i(TAG, "Got params: code=" + code + ", ip=" + ip + ", port=" + port);

        if (code == -1 || data == null) {
            Log.e(TAG, "Missing projection permission intent extras!");
            stopSelf();
            return START_NOT_STICKY;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(code, data);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Screen streaming đang chạy")
                .setContentText("Tap để dừng stream màn hình!")
                .setOngoing(true)
                .setColor(Color.GREEN);

        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pi);

        Notification notification = builder.build();

        // Bắt buộc API 29+: phải khai báo media_projection
        if (Build.VERSION.SDK_INT >= 29) {
            // Avoid import error: Use reflection for ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                Class<?> serviceInfoClass = Class.forName("android.app.ServiceInfo");
                Field field = serviceInfoClass.getField("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION");
                int serviceType = field.getInt(null);
                startForeground(NOTI_ID, notification, serviceType);
            } catch (Exception e) {
                Log.e(TAG, "Could not get FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, fallback to regular startForeground", e);
                startForeground(NOTI_ID, notification);
            }
        } else {
            startForeground(NOTI_ID, notification);
        }

        try {
            streamer = new H264ScreenStreamer(mediaProjection, this, ip, port);
            streamer.start();
            Log.i(TAG, "Streamer started.");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to start H264ScreenStreamer: " + ex.getMessage(), ex);
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service stopped.");
        if (streamer != null) streamer.stop();
        if (mediaProjection != null) mediaProjection.stop();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen Streaming", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Noti cho stream screen foreground");
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}