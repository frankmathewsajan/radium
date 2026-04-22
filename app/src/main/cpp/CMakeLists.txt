cmake_minimum_required(VERSION 3.22.1)
project("radium")

# --- 1. NEURAL ENGINE OPTIMIZATIONS ---
# These flags are absolutely critical. They tell the compiler to strip away
# safety checks and prioritize pure mathematical speed for the NPU/CPU.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -fno-finite-math-only -flto")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -fno-finite-math-only -flto")

# --- 2. CRYPTOGRAPHY DEPENDENCIES ---
# Tell CMake to find the OpenSSL package downloaded by Gradle Prefab
find_package(openssl REQUIRED CONFIG)

# --- 3. Llama.cpp INJECTION ---
# Make sure you ran: git clone https://github.com/ggerganov/llama.cpp.git inside the cpp folder
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable Llama tests")
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable Llama examples")
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable Llama server")
add_subdirectory(llama.cpp)

# --- 4. THE RADIUM BRIDGE ---
add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp)

# Finds the Android logging library
find_library(log-lib log)

# --- 5. THE FINAL LINK ---
# Merges your Radio Crypto (OpenSSL/Z) with the Neural Engine (llama/ggml)
target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    ${log-lib}
    openssl::crypto
    openssl::ssl
    z
    llama
    ggml
)