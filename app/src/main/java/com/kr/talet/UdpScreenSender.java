package com.kr.talet;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * UdpScreenSender: chụp rootView, encode JPEG, gửi qua UDP nhiều lần mỗi giây.
 * Đơn giản hóa: chỉ cần chạy khi đã gọi start(), tự động stop khi hủy/ngắt.
 */
public class UdpScreenSender {
    private final Activity activity;
    private final String destIp;
    private final int destPort;
    private volatile boolean running = false;
    private Thread workerThread;

    public UdpScreenSender(Activity activity, String destIp, int destPort) {
        this.activity = activity;
        this.destIp = destIp;
        this.destPort = destPort;
    }

    public void start() {
        if (running) return;
        running = true;
        workerThread = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                while (running) {
                    // Chụp màn hình root view của activity (không cần permission)
                    Bitmap bitmap = captureScreenBitmap();
                    if (bitmap != null) {
                        // Resize nhỏ để tiết kiệm băng thông
                        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos);
                        byte[] jpegData = bos.toByteArray();

                        DatagramPacket packet = new DatagramPacket(
                                jpegData, jpegData.length, InetAddress.getByName(destIp), destPort);
                        socket.send(packet);
                        scaled.recycle();
                        bitmap.recycle();
                    }
                    Thread.sleep(40); // ~25 fps
                }
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        workerThread.start();
    }

    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    // Cách lấy ảnh screenshot là từ rootView (không cần MediaProjection, không đầy đủ nhưng demo nhanh)
    private Bitmap captureScreenBitmap() {
        try {
            final View rootView = activity.getWindow().getDecorView().getRootView();
            // Vì getDrawingCache bị deprecated, dùng cách sau:
            Bitmap bitmap = Bitmap.createBitmap(rootView.getWidth(), rootView.getHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            Handler handler = new Handler(Looper.getMainLooper());
            final boolean[] drawn = {false};
            Runnable drawTask = () -> {
                rootView.draw(canvas);
                drawn[0] = true;
            };
            handler.post(drawTask);
            // Đợi tối đa 40ms cho việc draw trên luồng UI hoàn thành
            long t0 = System.currentTimeMillis();
            while (!drawn[0] && System.currentTimeMillis() - t0 < 40) {
                Thread.sleep(2);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}