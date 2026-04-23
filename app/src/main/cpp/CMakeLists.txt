cmake_minimum_required(VERSION 3.22.1)
project("portaserver")

include(FetchContent)

# Disable llama.cpp extras that are not needed on Android
set(GGML_METAL     OFF CACHE BOOL "" FORCE)
set(GGML_CUDA      OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN    OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP    OFF CACHE BOOL "" FORCE)
set(GGML_BLAS      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER      OFF CACHE BOOL "" FORCE)

# Pin to a recent stable release for broader GGUF architecture support
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b8895
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

add_library(
    llama_bridge
    SHARED
    llama_bridge.cpp
)

target_include_directories(
    llama_bridge PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}
)

target_link_libraries(
    llama_bridge
    llama
    ggml
    android
    log
)
