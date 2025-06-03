#ifndef TALET_ENGINE_NETWORK_UTILS_H
#define TALET_ENGINE_NETWORK_UTILS_H

#include <string>

namespace talet_engine {
    std::string get_local_ip();
    std::vector<std::string> get_all_broadcast();
}

#endif // TALET_ENGINE_NETWORK_UTILS_H