#include <jni.h>
#include <android/log.h>
#include "talet_engine/discovery_engine.h"

#define LOG_TAG "NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static DiscoveryEngine* engine = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_kr_talet_MainActivity_startDiscovery(JNIEnv* env, jobject thiz) {
    if (!engine) {
        JavaVM* jvm;
        env->GetJavaVM(&jvm);
        engine = new DiscoveryEngine(jvm, env->NewGlobalRef(thiz));
    }
    engine->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_kr_talet_MainActivity_stopDiscovery(JNIEnv* env, jobject thiz) {
    if (engine) {
        engine->stop();
        engine->cleanup(env);  // Giải phóng global ref JNI trước khi xoá engine
        delete engine;
        engine = nullptr;

        // Reset danh sách thiết bị khi dừng
        jclass activityClass = env->GetObjectClass(thiz);
        jmethodID resetMethod = env->GetMethodID(activityClass, "resetDeviceList", "()V");
        if (resetMethod) {
            env->CallVoidMethod(thiz, resetMethod);
        } else {
            LOGE("resetDeviceList method not found");
        }
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kr_talet_MainActivity_getLocalIp(JNIEnv* env, jobject thiz) {
    if (engine) {
        return env->NewStringUTF(engine->getLocalIp().c_str());
    }
    return env->NewStringUTF("127.0.0.1");
}