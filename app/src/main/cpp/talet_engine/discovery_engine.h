#ifndef TALET_ENGINE_DISCOVERY_ENGINE_H
#define TALET_ENGINE_DISCOVERY_ENGINE_H

#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <thread>
#include <mutex>

struct DeviceInfo {
    std::string name;
    std::string ip;
};

class DiscoveryEngine {
public:
    DiscoveryEngine(JavaVM* jvm, jobject activity);
    ~DiscoveryEngine();

    void start();
    void stop();
    std::string getLocalIp();
    
    void setScanInterval(int interval_ms);
    void setBroadcastAddresses(const std::vector<std::string>& addresses);

    // Thêm hàm cleanup giải phóng reference JNI (gọi từ thread Java)
    void cleanup(JNIEnv* env);

private:
    void discoveryReplyThread();
    void activeDiscoveryThread();
    void callOnDeviceFound(const std::string& name, const std::string& ip);
    void callOnScanStatusChanged(const std::string& status);
    void callOnScanFinished();

    JavaVM* jvm_;
    jobject activity_;
    std::atomic<bool> running_{false};
    std::thread reply_thread_;
    std::thread discovery_thread_;
    std::mutex mutex_;
    
    int scan_interval_ms_ = 2000;
    std::vector<std::string> broadcast_addresses_;
};

#endif // TALET_ENGINE_DISCOVERY_ENGINE_H