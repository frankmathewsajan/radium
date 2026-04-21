cmake_minimum_required(VERSION 3.22.1)

project("radium")

# 1. NEW: Tell CMake to find the OpenSSL package downloaded by Gradle Prefab
find_package(openssl REQUIRED CONFIG)

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp)

# Finds the Android logging library
find_library(log-lib log)

# 2. FIX: Link the specific Prefab OpenSSL targets, not the generic hidden ones
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    ${log-lib}
    openssl::crypto
    openssl::ssl
    z
    )