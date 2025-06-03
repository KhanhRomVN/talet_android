package com.kr.talet;

import android.content.Intent;

// Singleton holder "hacks" for MediaProjection permission Intent (Android quirk-safe)
public class StreamIntentCache {
    private static volatile Intent permissionIntent = null;

    public static void set(Intent intent) {
        permissionIntent = intent;
    }

    public static Intent getAndClear() {
        Intent temp = permissionIntent;
        permissionIntent = null;
        return temp;
    }
}