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

public class H264ScreenStreamer {
    private static final String MIME_TYPE = "video/avc";
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

    // Luôn prepend SPS+PPS vào mỗi frame để đảm bảo client PC recover ngay khi resume!
    private void encodeLoop() {
        DatagramSocket udpSocket = null;
        byte[] spsData = null;
        byte[] ppsData = null;
        try {
            udpSocket = new DatagramSocket();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            android.util.Log.i(TAG, "encodeLoop started (thread=" + Thread.currentThread().getName() + ")");
            int totalFrames = 0, frameIdx = 0;
            boolean sentConfigWarning = false;
            while (streaming) {
                int outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outIndex);
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] frameData = new byte[bufferInfo.size];
                        encodedData.get(frameData);

                        boolean hasSps = spsData != null && spsData.length > 0;
                        boolean hasPps = ppsData != null && ppsData.length > 0;
                        byte[] finalFrameData;
                        if (hasSps && hasPps) {
                            finalFrameData = new byte[spsData.length + ppsData.length + frameData.length];
                            System.arraycopy(spsData, 0, finalFrameData, 0, spsData.length);
                            System.arraycopy(ppsData, 0, finalFrameData, spsData.length, ppsData.length);
                            System.arraycopy(frameData, 0, finalFrameData, spsData.length + ppsData.length, frameData.length);
                            if (totalFrames % 10 == 0)
                                android.util.Log.i(TAG, "[LOG-SPSPPS-ALLFRAME] Prepend SPS/PPS EVERY FRAME. frameIdx=" + frameIdx + ", total=" + totalFrames + ", size=" + finalFrameData.length);
                        } else {
                            finalFrameData = frameData;
                        }

                        int mtu = 1300;
                        int nChunks = (finalFrameData.length + mtu - 1) / mtu;
                        int totalsz = finalFrameData.length;
                        for (int chunk_id = 0; chunk_id < nChunks; chunk_id++) {
                            int offset = chunk_id * mtu;
                            int len = Math.min(mtu, finalFrameData.length - offset);
                            byte[] header = new byte[12];
                            header[0] = (byte)((frameIdx >> 24) & 0xFF);
                            header[1] = (byte)((frameIdx >> 16) & 0xFF);
                            header[2] = (byte)((frameIdx >> 8) & 0xFF);
                            header[3] = (byte)(frameIdx & 0xFF);
                            header[4] = (byte)((nChunks >> 8) & 0xFF);
                            header[5] = (byte)(nChunks & 0xFF);
                            header[6] = (byte)((chunk_id >> 8) & 0xFF);
                            header[7] = (byte)(chunk_id & 0xFF);
                            header[8] = (byte)((totalsz >> 24) & 0xFF);
                            header[9] = (byte)((totalsz >> 16) & 0xFF);
                            header[10] = (byte)((totalsz >> 8) & 0xFF);
                            header[11] = (byte)(totalsz & 0xFF);

                            byte[] packetBytes = new byte[12 + len];
                            System.arraycopy(header, 0, packetBytes, 0, 12);
                            System.arraycopy(finalFrameData, offset, packetBytes, 12, len);

                            if (chunk_id == 0) {
                                android.util.Log.d(TAG, "[SEND] frame=" + frameIdx + " chunk=" + chunk_id + " len=" + len +
                                        " total_chunks=" + nChunks + " totalsz=" + totalsz +
                                        " header=" + bytesToHex(header) +
                                        " payload_head=" + bytesToHex(finalFrameData, Math.min(finalFrameData.length, 32)));
                            }

                            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, serverIp, serverPort);
                            udpSocket.send(packet);
                        }
                        totalFrames++;
                        frameIdx++;
                        if (totalFrames % 20 == 0)
                            android.util.Log.i(TAG, "[STREAM] UDP Send video frame=" + totalFrames + " chunk=" + nChunks + " total=" + finalFrameData.length + "B");
                    }
                    encoder.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    android.util.Log.i(TAG, "MediaCodec.INFO_OUTPUT_FORMAT_CHANGED");
                    try {
                        MediaFormat format = encoder.getOutputFormat();
                        if (format != null && format.containsKey("csd-0") && format.containsKey("csd-1")) {
                            ByteBuffer csd0 = format.getByteBuffer("csd-0");
                            ByteBuffer csd1 = format.getByteBuffer("csd-1");
                            if (csd0 != null && csd1 != null) {
                                spsData = new byte[csd0.remaining()];
                                csd0.get(spsData);
                                ppsData = new byte[csd1.remaining()];
                                csd1.get(ppsData);
                                android.util.Log.i(TAG,"[SPS/PPS] Cập nhật lại SPS/PPS " + spsData.length + "/" + ppsData.length + " bytes.");
                                android.util.Log.i(TAG, "[LOG-SPS] " + bytesToHex(spsData));
                                android.util.Log.i(TAG, "[LOG-PPS] " + bytesToHex(ppsData));
                            }
                        }
                    } catch (Exception ex) {
                        android.util.Log.w(TAG,"[SPS/PPS] Không lấy được csd khi format changed: " + ex.getMessage());
                    }
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

    private String bytesToHex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            String hex = Integer.toHexString(data[i] & 0xFF);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }
    private String bytesToHex(byte[] data, int limit) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, limit); i++) {
            String hex = Integer.toHexString(data[i] & 0xFF);
            if (hex.length() == 1) sb.append('0');
            sb.append(hex);
        }
        if (limit < data.length) sb.append("...");
        return sb.toString();
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

    public static MediaProjection requestMediaProjection(Activity activity, int resultCode, Intent data) {
        MediaProjectionManager mpm = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.getMediaProjection(resultCode, data);
    }
}