cmake_minimum_required(VERSION 3.22.1)

project(talet_engine)

add_library(talet_engine SHARED
    native-lib.cpp
    talet_engine/discovery_engine.cpp
    talet_engine/network_utils.cpp)

# Thêm cờ C++11
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

find_library(log-lib log)

target_include_directories(talet_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/talet_engine)

target_link_libraries(talet_engine
    android
    ${log-lib})