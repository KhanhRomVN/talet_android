package com.kr.talet;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

public class H264StreamActivity extends Activity {
    private static final int REQUEST_MEDIA_PROJECTION = 2001;
    // XOÁ HẾT MediaProjection khỏi Activity!
    private String serverIp = "192.168.102.121"; // <--- Địa chỉ PC thay đổi phù hợp
    private int serverPort = 5000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button btnStart = new Button(this);
        btnStart.setText("Start Stream");
        btnStart.setOnClickListener(v -> startMediaProjectionRequest());
        setContentView(btnStart);
    }

    private void startMediaProjectionRequest() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = mpm.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            android.util.Log.i("H264StreamActivity", "onActivityResult: code=" + resultCode + ", data=" + data);
            if (resultCode == RESULT_OK && data != null) {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                // KHÔNG ĐƯỢC gọi mpm.getMediaProjection ở Activity!
                android.util.Log.i("H264StreamActivity", "Got permission intent, starting service...");
                // Patch rollback: truyền permission intent qua static holder (StreamIntentCache)
                com.kr.talet.StreamIntentCache.set(data);
                Intent serviceIntent = new Intent(this, H264StreamService.class);
                serviceIntent.putExtra(H264StreamService.EXTRA_RESULT_CODE, resultCode);
                serviceIntent.putExtra(H264StreamService.EXTRA_PC_IP, serverIp);
                serviceIntent.putExtra(H264StreamService.EXTRA_PC_PORT, serverPort);

                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Streaming foreground service started!", Toast.LENGTH_LONG).show();
            } else {
                android.util.Log.e("H264StreamActivity", "PERMISSION DENIED or intent DATA null");
                Toast.makeText(this, "Permission denied!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}