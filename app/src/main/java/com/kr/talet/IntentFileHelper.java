package com.kr.talet;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.io.FileInputStream;

/**
 * Helper để lưu/đọc Intent (permission capture) an toàn mọi Android version thông qua file tạm trong app cache.
 * Sử dụng: ghi -> file, truyền path tuyệt đối vào Intent, service đọc lại và xóa file đó.
 */
public class IntentFileHelper {
    private static final String FILENAME = "projection_permission.tmp";

    // Lưu Intent xuống file (gọi từ Activity)
    public static String saveIntent(Context ctx, Intent intent) throws IOException {
        File file = new File(ctx.getCacheDir(), FILENAME);
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file));
        oos.writeObject(intent);
        oos.close();
        return file.getAbsolutePath();
    }

    // Đọc Intent từ file (gọi từ Service), xong xóa file để tránh rò rỉ quyền
    public static Intent loadIntent(Context ctx) throws IOException, ClassNotFoundException {
        File file = new File(ctx.getCacheDir(), FILENAME);
        Intent intent = null;
        ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file));
        intent = (Intent) ois.readObject();
        ois.close();
        file.delete();
        return intent;
    }
}