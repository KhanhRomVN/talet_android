package com.kr.talet;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup; // FIXED: Needed for ViewGroup
import android.widget.Button;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private TextView statusText;
    private TextView localIpText;
    private ProgressBar progressBar;
    private RecyclerView deviceList;
    private DeviceAdapter adapter;
    private final List<DeviceItem> devices = new ArrayList<>();
    private Button scanButton;
    private boolean isScanning = false;

    static {
        System.loadLibrary("talet_engine");
    }

    public native void startDiscovery();
    public native void stopDiscovery();
    public native String getLocalIp();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        localIpText = findViewById(R.id.local_ip);
        progressBar = findViewById(R.id.progress_bar);
        deviceList = findViewById(R.id.device_list);
        scanButton = findViewById(R.id.scan_button);

        scanButton.setOnClickListener(v -> toggleScan());

        // ---------------------
        // Thêm nút "Kết nối thủ công" (Manual connect)
        Button manualConnectButton = new Button(this);
        manualConnectButton.setText("Kết nối thủ công");
        manualConnectButton.setBackgroundColor(0xff31e981);
        manualConnectButton.setTextColor(0xff111111);
        manualConnectButton.setAllCaps(false);
        manualConnectButton.setTextSize(16f);
        manualConnectButton.setPadding(16,12,16,12);
        manualConnectButton.setOnClickListener(v -> showManualConnectDialog());
        ((ViewGroup)findViewById(R.id.device_list).getParent()).addView(manualConnectButton);

        // Cấu hình RecyclerView
        adapter = new DeviceAdapter(devices);
        deviceList.setLayoutManager(new LinearLayoutManager(this));
        deviceList.setAdapter(adapter);
        deviceList.setVisibility(View.GONE);

        // Lắng nghe click trên từng device trong list
        adapter.setOnItemClickListener(device -> showPinDialog(device));

        // Lấy IP local
        localIpText.setText("Your IP: " + getLocalIp());

        // Yêu cầu quyền nếu cần
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                1001
            );
        }
    }

    // FIXED: Thêm hàm showManualConnectDialog đúng định nghĩa
    private void showManualConnectDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Kết nối thủ công");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Dán địa chỉ: talet://192.168.1.100:27200?pin=xxxxx");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        builder.setView(input);
        builder.setMessage("Nhập/paste đường dẫn, app sẽ tự động tách IP, port và mã PIN để kết nối:");

        builder.setPositiveButton("Kết nối", (dialog, which) -> {
            String val = input.getText().toString().trim();
            try {
                // parse talet://<ip>:<port>?pin=xxxxx
                java.util.regex.Pattern pat = java.util.regex.Pattern.compile("^talet://([\\d\\.]+):(\\d+)\\?pin=(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE);
                java.util.regex.Matcher matcher = pat.matcher(val);
                if (!matcher.find()) {
                    showAlert("Sai định dạng", "Chuỗi nhập không đúng mẫu!");
                    return;
                }
                String ip = matcher.group(1);
                String portStr = matcher.group(2);
                String pin = matcher.group(3);

                DeviceItem manual = new DeviceItem("Thủ công", ip);
                verifyPinManual(manual, pin, Integer.parseInt(portStr));
            } catch (Exception e) {
                showAlert("Lỗi", "Không thể phân tích chuỗi!");
            }
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void verifyPinManual(DeviceItem device, String pin, int port) {
        new Thread(() -> {
            java.net.DatagramSocket recvSock = null;
            int myListenPort = 0;
            try {
                // Tạo socket UDP động để nhận phản hồi (port bất kỳ)
                recvSock = new java.net.DatagramSocket(0);
                myListenPort = recvSock.getLocalPort();

                // Gửi PIN kèm port động lên PC
                String msg = "TALET_PIN_OK:" + pin + ":" + myListenPort;
                byte[] sendData = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.net.DatagramPacket packet = new java.net.DatagramPacket(
                        sendData, sendData.length,
                        java.net.InetAddress.getByName(device.getIp()), port + 1 // port nhập vào + 1
                );
                java.net.DatagramSocket sendSock = new java.net.DatagramSocket();
                sendSock.send(packet);
                sendSock.close();

                int finalMyListenPort = myListenPort;
                runOnUiThread(() -> showToast("Đã gửi yêu cầu xác thực (port " + finalMyListenPort + "), chờ xác nhận..."));

                waitForPairingResultManual(device, pin, myListenPort);

            } catch (Exception e) {
                e.printStackTrace();
                showToast("Lỗi gửi mã PIN: " + e.getMessage());
                showAlert("Ghép nối không thành công", "Lỗi gửi mã PIN: " + e.getMessage());
            } finally {
                // Nếu vẫn có socket tồn tại, dọn sạch
                if (recvSock != null && !recvSock.isClosed()) recvSock.close();
            }
        }).start();
    }

    private void waitForPairingResultManual(final DeviceItem device, final String pin, final int resultPort) {
        new Thread(() -> {
            boolean success = false;
            String errorMsg = "Không nhận được phản hồi từ PC.";
            java.net.DatagramSocket socket = null;
            try {
                android.util.Log.i("TALET_PAIR", "Listening for UDP reply on port " + resultPort + " (port động)");
                socket = new java.net.DatagramSocket(resultPort);
                socket.setSoTimeout(4000); // 4s timeout

                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 4000) { // 4s, poll liên tục
                    byte[] buf = new byte[128];
                    java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                        String msg = new String(buf, 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        android.util.Log.i("TALET_PAIR", "Received UDP: " + msg + " from " + p.getAddress().getHostAddress() + ":" + p.getPort());
                        if (msg.equals("TALET_PAIR_OK:" + pin)) {
                            success = true;
                            break;
                        } else if (msg.startsWith("TALET_PAIR_FAIL")) {
                            errorMsg = "Sai mã PIN hoặc hết hạn, hãy kiểm tra lại.";
                            break;
                        }
                    } catch (java.net.SocketTimeoutException ignore) {
                        android.util.Log.w("TALET_PAIR", "Socket timeout when waiting for reply...");
                    } catch (Exception ex) {
                        android.util.Log.e("TALET_PAIR", "Error receiving reply: " + ex.getMessage(), ex);
                    }
                }
            } catch (Exception e) {
                errorMsg = "Lỗi xác nhận thủ công: " + e.getMessage();
                android.util.Log.e("TALET_PAIR", "Failed to create DatagramSocket: " + e.getMessage(), e);
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }

            if (success) {
                showToast("✅ Ghép nối thành công với " + device.getName() + "! Đang chuyển sang streaming...");
                runOnUiThread(() -> goToStreamingScreen(device));
            } else {
                showAlert("Ghép nối không thành công", errorMsg);
                android.util.Log.e("TALET_PAIR", "Pairing failed, reason: " + errorMsg);
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Quyền đã được cấp, có thể bắt đầu quét nếu cần
            }
        }
    }

    private void toggleScan() {
        if (!isScanning) {
            // Bắt đầu scan
            progressBar.setVisibility(View.VISIBLE);
            statusText.setText("Scanning...");
            devices.clear();
            adapter.notifyDataSetChanged();
            deviceList.setVisibility(View.VISIBLE);
            scanButton.setText("Turn off scan");
            startDiscovery();
            isScanning = true;
        } else {
            // Tắt scan
            stopDiscovery();
            progressBar.setVisibility(View.GONE);
            devices.clear();
            adapter.notifyDataSetChanged();
            deviceList.setVisibility(View.GONE);
            statusText.setText("Scan stopped");
            scanButton.setText("Scan devices");
            isScanning = false;
        }
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    // Reset danh sách thiết bị
    public void resetDeviceList() {
        runOnUiThread(() -> {
            devices.clear();
            adapter.notifyDataSetChanged();
            deviceList.setVisibility(View.GONE);
            statusText.setText("No devices found");
            if (scanButton != null) {
                scanButton.setText("Scan devices");
                isScanning = false;
            }
        });
    }

    // Các phương thức JNI callback
    public synchronized void onDeviceFound(String name, String ip) {
        runOnUiThread(() -> {
            // Chống spam: chỉ add nếu chưa tồn tại IP
            boolean existed = false;
            for (DeviceItem d : devices) {
                if (d.getIp().equals(ip)) {
                    existed = true;
                    break;
                }
            }
            if (!existed) {
                devices.add(new DeviceItem(name, ip));
                adapter.notifyItemInserted(devices.size() - 1);
                statusText.setText("Found " + devices.size() + " devices");
            } else {
                // Nếu spam, chỉ cập nhật tiêu đề hoặc âm thầm bỏ qua
                statusText.setText("Found " + devices.size() + " devices (duplicate ignored)");
            }
        });
    }

    public void onScanStatusChanged(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
        });
    }

    public void onScanFinished() {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (devices.isEmpty()) {
                statusText.setText("No devices found");
            }
        });
    }

    // Hiển thị dialog nhập mã PIN 5 số để xác thực device
    private void showPinDialog(DeviceItem device) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Nhập mã PIN từ PC");

        // Ô nhập PIN
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("PIN 5 số");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setMessage("Nhập mã PIN hiển thị trên PC để xác thực ghép nối với thiết bị '" + device.getName() + "' (" + device.getIp() +")");

        builder.setPositiveButton("Xác nhận", (dialog, which) -> {
            String pin = input.getText().toString().trim();
            if (pin.length() != 5) {
                showToast("Vui lòng nhập đúng 5 số!");
                return;
            }
            verifyPin(device, pin);
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // Gửi mã PIN sang PC qua UDP để xác thực, nhận phản hồi (thực thi trên background thread)
    private void verifyPin(DeviceItem device, String pin) {
        new Thread(() -> {
            try {
                java.net.DatagramSocket socket = new java.net.DatagramSocket();
                socket.setSoTimeout(2000); // 2s timeout
                String msg = "TALET_PIN_OK:" + pin;
                byte[] sendData = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.net.DatagramPacket packet = new java.net.DatagramPacket(
                    sendData, sendData.length,
                    java.net.InetAddress.getByName(device.getIp()), 27184 // cổng PC+1
                );
                socket.send(packet);
                socket.close();

                // Chuyển sang bước chờ xác nhận phản hồi pairing từ PC
                runOnUiThread(() -> showToast("Đã gửi yêu cầu xác thực, chờ xác nhận từ PC..."));
                waitForPairingResult(device, pin);

            } catch (Exception e) {
                e.printStackTrace();
                showToast("Lỗi gửi mã PIN: " + e.getMessage());
                showAlert("Ghép nối không thành công", "Lỗi gửi mã PIN: " + e.getMessage());
            }
        }).start();
    }

    // Lắng nghe xác thực pairing thành công/thất bại: nếu PC gửi về TALET_PAIR_OK thì báo thành công, nếu không có phản hồi sau timeout thì báo lỗi
    private void waitForPairingResult(final DeviceItem device, final String pin) {
        new Thread(() -> {
            boolean success = false;
            String errorMsg = "Không nhận được phản hồi từ PC.";
            try {
                // Lắng nghe đáp án TALET_PAIR_OK trên một cổng UDP tạm (dùng cổng 27185)
                java.net.DatagramSocket socket = new java.net.DatagramSocket(27185);
                socket.setSoTimeout(4000); // 4s timeout

                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 4000) { // 4s, poll liên tục
                    byte[] buf = new byte[128];
                    java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(p);
                        String msg = new String(buf, 0, p.getLength(), java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (msg.equals("TALET_PAIR_OK:" + pin)) {
                            success = true;
                            break;
                        } else if (msg.startsWith("TALET_PAIR_FAIL")) {
                            errorMsg = "Sai mã PIN hoặc hết hạn, hãy kiểm tra lại.";
                            break;
                        }
                    } catch (java.net.SocketTimeoutException ignore) {} // tiếp tục retry tới hết timeout
                }
                socket.close();
            } catch (Exception e) {
                errorMsg = "Lỗi khi xác nhận ghép nối: " + e.getMessage();
            }

            if (success) {
                showToast("✅ Ghép nối thành công với " + device.getName() + "! Đang chuyển sang streaming...");
                runOnUiThread(() -> goToStreamingScreen(device));
            } else {
                showAlert("Ghép nối không thành công", errorMsg);
            }
        }).start();
    }

    // Chuyển sang giao diện/màn hình streaming, tuỳ ý tuỳ chỉnh lại sau này
    private void goToStreamingScreen(DeviceItem device) {
        // TODO: Tùy vào cấu trúc app, chuyển Activity/Fragment, hoặc update UI streaming mode
        statusText.setText("Đã kết nối tới " + device.getName() + "\n(Giả lập chuyển giao diện streaming)");
        showToast("🎥 Đã sẵn sàng streaming, hãy bắt đầu!");
        // Có thể cập nhật UI, ẩn deviceList, hiển thị các control streaming...
        deviceList.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
    }

    // Báo lỗi ghép nối
    private void showAlert(final String title, final String message) {
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", (d, w) -> d.dismiss())
                    .show();
        });
    }

    private void showToast(final String message) {
        runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_SHORT).show());
    }
}