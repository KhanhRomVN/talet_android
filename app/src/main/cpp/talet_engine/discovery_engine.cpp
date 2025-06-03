#include "discovery_engine.h"
#include "network_utils.h"
#include <android/log.h>
#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <chrono>

#define LOG_TAG "DiscoveryEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DiscoveryEngine::DiscoveryEngine(JavaVM* jvm, jobject activity)
    : jvm_(jvm) {
    // Tạo global reference để sử dụng trong các luồng
    JNIEnv* env;
    jint res = jvm_->AttachCurrentThread(&env, nullptr);
    if (res != JNI_OK || env == nullptr) {
        LOGE("Failed to attach thread in constructor");
        activity_ = nullptr;
        return;
    }
    activity_ = env->NewGlobalRef(activity);
    // Không detach thread ở đây vì thread chính đã attach sẵn

    // --- Broadcast addresses tự động ---
    broadcast_addresses_.clear();
    auto broadcasts = talet_engine::get_all_broadcast();
    for (const auto& bc : broadcasts) {
        LOGI("[INIT] Auto broadcast addr found: %s", bc.c_str());
        broadcast_addresses_.push_back(bc);
    }
    // Add multicast for discovery
    broadcast_addresses_.push_back("239.255.42.99");
}

DiscoveryEngine::~DiscoveryEngine() {
    stop();
    // Không giải phóng global ref ở đây, phải dùng cleanup từ Java thread
}

void DiscoveryEngine::start() {
    if (running_) return;
    
    running_ = true;
    callOnScanStatusChanged("Scanning started");
    reply_thread_ = std::thread(&DiscoveryEngine::discoveryReplyThread, this);
    discovery_thread_ = std::thread(&DiscoveryEngine::activeDiscoveryThread, this);
}

void DiscoveryEngine::cleanup(JNIEnv* env) {
    // Giải phóng global reference nếu còn
    if (activity_ != nullptr) {
        env->DeleteGlobalRef(activity_);
        activity_ = nullptr;
    }
}

void DiscoveryEngine::stop() {
    if (!running_) return;
    
    running_ = false;
    if (reply_thread_.joinable()) reply_thread_.join();
    if (discovery_thread_.joinable()) discovery_thread_.join();
    callOnScanStatusChanged("Scanning stopped");
    callOnScanFinished();
}

std::string DiscoveryEngine::getLocalIp() {
    return talet_engine::get_local_ip();
}

void DiscoveryEngine::setScanInterval(int interval_ms) {
    scan_interval_ms_ = interval_ms;
}

void DiscoveryEngine::setBroadcastAddresses(const std::vector<std::string>& addresses) {
    broadcast_addresses_ = addresses;
}

void DiscoveryEngine::callOnDeviceFound(const std::string& name, const std::string& ip) {
    JNIEnv* env;
    jint res = jvm_->AttachCurrentThread(&env, nullptr);
    if (res != JNI_OK || env == nullptr) {
        LOGE("Failed to attach thread for callOnDeviceFound");
        return;
    }

    jclass activityClass = env->GetObjectClass(activity_);
    jmethodID method = env->GetMethodID(activityClass, "onDeviceFound", "(Ljava/lang/String;Ljava/lang/String;)V");
    
    if (method) {
        jstring jname = env->NewStringUTF(name.c_str());
        jstring jip = env->NewStringUTF(ip.c_str());
        env->CallVoidMethod(activity_, method, jname, jip);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(jip);
    } else {
        LOGE("onDeviceFound method not found");
    }
    // ĐỪNG gọi DetachCurrentThread ở đây, tránh crash nếu đang ở thread Java
}

void DiscoveryEngine::callOnScanStatusChanged(const std::string& status) {
    JNIEnv* env;
    jint res = jvm_->AttachCurrentThread(&env, nullptr);
    if (res != JNI_OK || env == nullptr) {
        LOGE("Failed to attach thread for callOnScanStatusChanged");
        return;
    }

    jclass activityClass = env->GetObjectClass(activity_);
    jmethodID method = env->GetMethodID(activityClass, "onScanStatusChanged", "(Ljava/lang/String;)V");
    
    if (method) {
        jstring jstatus = env->NewStringUTF(status.c_str());
        env->CallVoidMethod(activity_, method, jstatus);
        env->DeleteLocalRef(jstatus);
    } else {
        LOGE("onScanStatusChanged method not found");
    }
    // ĐỪNG gọi DetachCurrentThread ở đây, tránh crash nếu đang ở thread Java
}

void DiscoveryEngine::callOnScanFinished() {
    JNIEnv* env;
    jint res = jvm_->AttachCurrentThread(&env, nullptr);
    if (res != JNI_OK || env == nullptr) {
        LOGE("Failed to attach thread for callOnScanFinished");
        return;
    }

    jclass activityClass = env->GetObjectClass(activity_);
    jmethodID method = env->GetMethodID(activityClass, "onScanFinished", "()V");
    
    if (method) {
        env->CallVoidMethod(activity_, method);
    } else {
        LOGE("onScanFinished method not found");
    }
    // ĐỪNG gọi DetachCurrentThread ở đây, tránh crash nếu đang ở thread Java
}

void DiscoveryEngine::discoveryReplyThread() {
    LOGI("Discovery reply thread started");

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        LOGE("Failed to create reply socket: %s", strerror(errno));
        callOnScanStatusChanged("Error: Failed to create socket");
        return;
    }

    int yes = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));

    // Join multicast group 239.255.42.99
    struct ip_mreq mreq;
    memset(&mreq, 0, sizeof(mreq));
    mreq.imr_multiaddr.s_addr = inet_addr("239.255.42.99");
    mreq.imr_interface.s_addr = htonl(INADDR_ANY);
    if (setsockopt(sock, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq)) < 0) {
        LOGE("Failed to join multicast group: %s", strerror(errno));
    } else {
        LOGI("Joined multicast group 239.255.42.99");
    }

    struct sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27183);
    addr.sin_addr.s_addr = htonl(INADDR_ANY);

    if (bind(sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Failed to bind reply socket: %s", strerror(errno));
        close(sock);
        callOnScanStatusChanged("Error: Failed to bind socket");
        return;
    }

    struct timeval tv {5, 0}; // Timeout tăng lên 5 giây
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    char buf[1024]; // Buffer lớn hơn

    std::string local_ip = getLocalIp();
    auto is_loopback = [](const std::string& ip) -> bool {
        return ip.rfind("127.", 0) == 0 || ip == "::1";
    };

    while (running_) {
        struct sockaddr_in from;
        socklen_t fromlen = sizeof(from);
        int ret = recvfrom(sock, buf, sizeof(buf)-1, 0, (struct sockaddr*)&from, &fromlen);

        if (ret > 0) {
            buf[ret] = 0;
            std::string msg(buf);
            std::string from_ip = inet_ntoa(from.sin_addr);

            LOGI("Received %d bytes from %s: %s", ret, from_ip.c_str(), buf);

            if (msg.find("TALET_DISCOVERY_v1") != std::string::npos) {
                if (from_ip == local_ip || is_loopback(from_ip)) continue;
                char reply[128];
                snprintf(reply, sizeof(reply), "TALET_DEVICE:Android_%s", local_ip.c_str());

                if (sendto(sock, reply, strlen(reply), 0, (struct sockaddr*)&from, fromlen) > 0) {
                    LOGI("Sent reply to %s", from_ip.c_str());
                } else {
                    LOGE("Failed to send reply: %s", strerror(errno));
                }
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    close(sock);
    LOGI("Discovery reply thread ended");
}

void DiscoveryEngine::activeDiscoveryThread() {
    LOGI("Active discovery thread started");

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        LOGE("Failed to create discovery socket: %s", strerror(errno));
        callOnScanStatusChanged("Error: Failed to create socket");
        return;
    }

    int yes = 1;
    setsockopt(sock, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
    struct timeval tv {1, 0};
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    char msg[] = "TALET_DISCOVERY_v1";
    char buf[256];
    std::string local_ip = getLocalIp();

    LOGI("[DEBUG] DiscoveryClient: local_ip=%s", local_ip.c_str());
    for (const auto& addr : broadcast_addresses_) {
        LOGI("[DEBUG] Broadcast target: %s:27183", addr.c_str());
    }

    while (running_) {
        callOnScanStatusChanged("Scanning network...");

        for (const std::string& bcast_addr : broadcast_addresses_) {
            struct sockaddr_in addr {};
            addr.sin_family = AF_INET;
            addr.sin_port = htons(27183);
            inet_aton(bcast_addr.c_str(), &addr.sin_addr);
            LOGI("[DEBUG] -> Sending discovery to %s", bcast_addr.c_str());
            int rs = sendto(sock, msg, strlen(msg), 0, (struct sockaddr*)&addr, sizeof(addr));
            if (rs <= 0) {
                LOGE("Failed to send to %s: %s", bcast_addr.c_str(), strerror(errno));
            } else {
                LOGI("[DEBUG] >> Sent TALET_DISCOVERY_v1 to %s", bcast_addr.c_str());
            }
        }

        auto start_time = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - start_time < std::chrono::milliseconds(800)) {
            struct sockaddr_in from;
            socklen_t fromlen = sizeof(from);
            int ret = recvfrom(sock, buf, sizeof(buf)-1, 0, (struct sockaddr*)&from, &fromlen);

            if (ret > 0) {
                buf[ret] = 0;
                std::string msg_recv(buf);
                std::string from_ip = inet_ntoa(from.sin_addr);
                LOGI("[DEBUG] <- Received %d bytes from %s: %s", ret, from_ip.c_str(), msg_recv.c_str());

                if (msg_recv.find("TALET_DEVICE") != std::string::npos && from_ip != local_ip) {
                    LOGI("[DEBUG] >>> Device found: %s (%s)", msg_recv.c_str(), from_ip.c_str());
                    std::string name = "Talet PC (" + from_ip + ")";
                    callOnDeviceFound(name, from_ip);
                    callOnScanStatusChanged("Found device: " + from_ip);
                }
            } else {
                LOGI("[DEBUG] ...no device reply this round");
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(scan_interval_ms_));
    }

    close(sock);
    callOnScanStatusChanged("Discovery stopped");
    LOGI("Active discovery thread ended");
}