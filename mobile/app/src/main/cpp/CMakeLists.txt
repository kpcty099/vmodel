cmake_minimum_required(VERSION 3.22.1)

project("voiceagent")

# Set paths to submodules
set(MOBILE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(WHISPER_DIR "${MOBILE_DIR}/whisper.cpp")
set(LLAMA_DIR "${MOBILE_DIR}/llama.cpp")
set(PIPER_DIR "${MOBILE_DIR}/piper")
set(SQLITE_VEC_DIR "${MOBILE_DIR}/sqlite-vec")

# Configure Whisper.cpp
add_subdirectory("${WHISPER_DIR}" whisper_build)

# Configure Llama.cpp
# Enable NDK and CPU optimizations if needed
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server binary" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
add_subdirectory("${LLAMA_DIR}" llama_build)

# Configure sqlite-vec as a shared library
add_library(sqlite_vec SHARED
    "${SQLITE_VEC_DIR}/sqlite-vec.c"
)
target_include_directories(sqlite_vec PRIVATE "${SQLITE_VEC_DIR}")
target_link_libraries(sqlite_vec log)

# Core native library containing JNI bindings
add_library(native-lib SHARED
    native-lib.cpp
)

# Header search paths
target_include_directories(native-lib PRIVATE
    "${WHISPER_DIR}"
    "${LLAMA_DIR}"
    "${PIPER_DIR}"
    "${SQLITE_VEC_DIR}"
)

# Link native-lib to submodules and Android logging library
target_link_libraries(native-lib
    whisper
    llama
    sqlite_vec
    log
    android
)
