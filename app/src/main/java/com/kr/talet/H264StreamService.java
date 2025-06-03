package com.kr.talet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
/*
 * KHÔNG import android.app.ServiceInfo. Không dùng reflection, dùng hằng số chính thức trực tiếp.
 * foregroundServiceType mediaProjection = 0x20 (32)
 */

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * ForegroundService chuẩn để chạy MediaProjection encoder trên Android 10+
 */
public class H264StreamService extends Service {
    public static final String TAG = "H264StreamService";
    public static final String CHANNEL_ID = "screen_stream"; // đổi tên kênh mới
    public static final int NOTIFICATION_ID = 100;

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
        Log.i(TAG, "[LOG] onCreate() service created, notification channel ready");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Notification cho screen stream foreground");
            channel.enableLights(false);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
            Log.i(TAG, "[LOG] Notification channel created.");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "[LOG] onStartCommand flags=" + flags + ", startId=" + startId);
        Log.i(TAG, "[LOG] Intent = " + (intent == null ? "null" : intent.toString()));
        if (intent != null && intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Object val = intent.getExtras().get(key);
                Log.i(TAG, "[LOG] intent-extras: " + key + " = " + val);
            }
        }

        // 1. Build notification (dùng icon sẵn hoặc bạn đổi ic_notification)
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Streaming")
                .setContentText("Streaming screen to PC")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setColor(Color.GREEN)
                .setOngoing(true)
                .build();

        // 2. Start foreground đúng chuẩn: Gọi SỚM với đúng type theo AOSP, dùng ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.i(TAG, "[LOG] CALL: startForeground notification với ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION (" + ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION + ")");
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                Log.i(TAG, "[LOG] CALL: startForeground notification kiểu cũ (không có type)");
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception err) {
            Log.e(TAG, "[ERROR] fail startForeground: " + err, err);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 3. Parse projection permission intent và các tham số
        int resultCode = intent != null ? intent.getIntExtra(EXTRA_RESULT_CODE, 0) : 0;
        String ip = intent != null ? intent.getStringExtra(EXTRA_PC_IP) : null;
        int port = intent != null ? intent.getIntExtra(EXTRA_PC_PORT, 5000) : 5000;
        Log.i(TAG, "[LOG] resultCode=" + resultCode + " ip=" + ip + " port=" + port);

        // 4. Lấy projection intent permission từ cache Holder
        Intent data = com.kr.talet.StreamIntentCache.getAndClear();
        if (data == null) {
            Log.e(TAG, "[ERROR] Projection permission intent is NULL! Dừng service.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.i(TAG, "[LOG] lấy projection permission Intent data: " + data);

        // 5. Khởi tạo MediaProjection
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            Log.i(TAG, "[LOG] MediaProjection created: " + (mediaProjection != null));
        } catch (Exception e) {
            Log.e(TAG, "[ERROR] MediaProjection mở thất bại (likely quyền null): " + e.getMessage(), e);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 6. Khởi chạy streamer nếu đủ điều kiện
        try {
            Log.i(TAG, "[LOG] Đang tạo H264ScreenStreamer: ip=" + ip + ", port=" + port);
            streamer = new H264ScreenStreamer(mediaProjection, this, ip, port);
            streamer.start();
            Log.i(TAG, "[LOG] ĐÃ KHỞI ĐỘNG streamer video thành công!!");
        } catch (Exception ex) {
            Log.e(TAG, "[ERROR] Không thể chạy streamer: " + ex.getMessage(), ex);
            stopForeground(true);
            stopSelf();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[LOG] Service stop (onDestroy called). Stop streamer + projection");
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
}