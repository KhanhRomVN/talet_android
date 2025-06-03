#include "network_utils.h"
#include <ifaddrs.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <linux/if.h>
#include <vector>
#include <string>

#define LOG_TAG "NetworkUtils"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace talet_engine {

// Helper function - moved to implementation file
static bool is_valid_ipv4(const std::string& ip) {
    // Simple validation without regex
    int a, b, c, d;
    if (sscanf(ip.c_str(), "%d.%d.%d.%d", &a, &b, &c, &d) != 4) {
        return false;
    }
    return a >= 0 && a <= 255 && 
           b >= 0 && b <= 255 && 
           c >= 0 && c <= 255 && 
           d >= 0 && d <= 255;
}

std::string get_local_ip() {
    struct ifaddrs *ifaddrs_ptr, *ifa;
    std::string result = "127.0.0.1";
    std::string candidate = "127.0.0.1";
    
    if (getifaddrs(&ifaddrs_ptr) == -1) {
        LOGE("Failed to get network interfaces: %s", strerror(errno));
        return result;
    }
    
    for (ifa = ifaddrs_ptr; ifa != nullptr; ifa = ifa->ifa_next) {
        if (ifa->ifa_addr == nullptr) continue;
        
        if (ifa->ifa_addr->sa_family == AF_INET && 
            !(ifa->ifa_flags & IFF_LOOPBACK) &&
            (ifa->ifa_flags & IFF_UP)) {
            
            char ip_str[INET_ADDRSTRLEN];
            struct sockaddr_in* addr_in = (struct sockaddr_in*)ifa->ifa_addr;
            inet_ntop(AF_INET, &(addr_in->sin_addr), ip_str, INET_ADDRSTRLEN);
            
            std::string ip(ip_str);
            
            // Lưu candidate đầu tiên không phải loopback
            if (candidate == "127.0.0.1" && is_valid_ipv4(ip)) {
                candidate = ip;
            }
            
            // Ưu tiên private IP ranges
            if (is_valid_ipv4(ip) && 
               (ip.rfind("192.168.", 0) == 0 || 
                ip.rfind("10.", 0) == 0 || 
                ip.rfind("172.", 0) == 0)) {
                result = ip;
                // Không break, tiếp tục tìm kiếm
            }
        }
    }
    
    // Nếu không tìm thấy private IP, dùng candidate
    if (result == "127.0.0.1" && candidate != "127.0.0.1") {
        result = candidate;
    }
    
    freeifaddrs(ifaddrs_ptr);
    LOGI("Selected local IP: %s", result.c_str());
    return result;
}

std::vector<std::string> get_all_broadcast() {
    std::vector<std::string> broadcasts;
    struct ifaddrs *ifap, *ifa;
    if (getifaddrs(&ifap) == 0) {
        for (ifa = ifap; ifa; ifa = ifa->ifa_next) {
            if (!ifa->ifa_addr) continue;
            if (ifa->ifa_addr->sa_family == AF_INET && (ifa->ifa_flags & IFF_UP)) {
                if (ifa->ifa_flags & IFF_LOOPBACK) continue;
                struct sockaddr_in *sin = (struct sockaddr_in*)ifa->ifa_addr;
                struct sockaddr_in *netmask = (struct sockaddr_in*)ifa->ifa_netmask;
                struct in_addr bcast_addr;
                bcast_addr.s_addr = (sin->sin_addr.s_addr & netmask->sin_addr.s_addr) | (~netmask->sin_addr.s_addr);
                char buf[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &bcast_addr, buf, INET_ADDRSTRLEN);
                broadcasts.emplace_back(buf);
                LOGI("Calculated broadcast for %s: %s", ifa->ifa_name, buf);
            }
        }
        freeifaddrs(ifap);
    }
    if (broadcasts.empty()) broadcasts.push_back("255.255.255.255");
    return broadcasts;
}

} // namespace talet_engine