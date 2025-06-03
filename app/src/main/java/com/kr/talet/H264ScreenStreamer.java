package com.kr.talet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * SỬA: lấy resolution thực tế, giữ instance VirtualDisplay để không bị thu hồi tự động
 *      loop encode không break silent, always check exception/log
 */
public class H264ScreenStreamer {
    private static final String MIME_TYPE = "video/avc"; // H.264

    private static final String TAG = "H264ScreenStreamer";
    private MediaProjection mediaProjection;
    private MediaCodec encoder;
    private Surface inputSurface;
    private Thread encodeThread;
    private InetAddress serverIp;
    private int serverPort;
    private boolean streaming = false;
    private VirtualDisplay virtualDisplay = null;
    private int videoWidth;
    private int videoHeight;
    private int densityDpi;

    public H264ScreenStreamer(MediaProjection projection, Context context, String ip, int port) throws Exception {
        this.mediaProjection = projection;
        this.serverIp = InetAddress.getByName(ip);
        this.serverPort = port;
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        this.videoWidth = metrics.widthPixels;
        this.videoHeight = metrics.heightPixels;
        this.densityDpi = metrics.densityDpi;
    }

    public void start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(600_000, videoWidth * videoHeight * 4));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = encoder.createInputSurface();
        encoder.start();

        android.util.Log.i(TAG, "MediaCodec + MediaProjection Initialized: WxH=" + videoWidth + "x" + videoHeight + ", Addr=" + serverIp.getHostAddress() + ":" + serverPort);

        streaming = true;
        encodeThread = new Thread(this::encodeLoop, "H264EncodeThread");
        encodeThread.start();

        // GIỮ instance VirtualDisplay, không tạo biến cục bộ!
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            videoWidth, videoHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            inputSurface,
            null,
            null
        );
        android.util.Log.i(TAG, "VirtualDisplay created");
    }

    private void encodeLoop() {
        DatagramSocket udpSocket = null;
        try {
            udpSocket = new DatagramSocket();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            android.util.Log.i(TAG, "encodeLoop started (thread=" + Thread.currentThread().getName() + ")");
            int totalFrames = 0;
            while (streaming) {
                int outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outIndex);
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] buf = new byte[bufferInfo.size];
                        encodedData.get(buf);
                        int offset = 0;
                        int mtu = 1300;
                        int sentBytes = 0;
                        int nChunks = 0;
                        while (offset < buf.length) {
                            int len = Math.min(mtu, buf.length - offset);
                            DatagramPacket packet = new DatagramPacket(buf, offset, len, serverIp, serverPort);
                            udpSocket.send(packet);
                            sentBytes += len; nChunks++;
                            offset += len;
                        }
                        totalFrames++;
                        if (totalFrames % 20 == 0)
                            android.util.Log.i(TAG, "[STREAM] Sent video frame=" + totalFrames + ", size=" + buf.length + "B, chunks=" + nChunks);
                    }
                    encoder.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.util.Log.i(TAG, "MediaCodec.INFO_OUTPUT_FORMAT_CHANGED");
                }
            }
            android.util.Log.i(TAG, "encodeLoop exited normally");
        } catch (Exception e) {
            android.util.Log.e(TAG, "encodeLoop Exception: " + e.getMessage(), e);
        } finally {
            if (udpSocket != null) udpSocket.close();
            android.util.Log.i(TAG, "encodeLoop finished, UDP socket closed");
        }
    }

    public void stop() {
        streaming = false;
        android.util.Log.i(TAG, "stop() called");
        if (encodeThread != null) encodeThread.interrupt();
        if (encoder != null) encoder.stop();
        if (encoder != null) encoder.release();
        if (virtualDisplay != null) virtualDisplay.release();
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    // Xin MediaProjection (outside integration)
    public static MediaProjection requestMediaProjection(Activity activity, int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.getMediaProjection(resultCode, data);
    }
}