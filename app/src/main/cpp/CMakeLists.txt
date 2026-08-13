cmake_minimum_required(VERSION 3.22.1)
project("portaserver")

include(FetchContent)

# AGP compiles the debug variant with CMAKE_BUILD_TYPE=Debug, which means -O0 for the
# tensor kernels. Measured on a Snapdragon 8 Gen 3, that made a 53-token prompt take 21
# seconds to decode; optimised, the same work is trivial. Debug symbols are unaffected,
# so native crashes still resolve -- only the optimisation level changes.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif()

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

# common carries the chat-template and tool-call machinery (jinja rendering, the PEG
# parser, json-schema-to-grammar). It is off by default under FetchContent because
# llama.cpp gates it on LLAMA_STANDALONE. Its dependencies are vendored, so unlike the
# Vulkan backend it needs nothing installed on the host.
set(LLAMA_BUILD_COMMON       ON CACHE BOOL "" FORCE)

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
    llama-common
    llama
    ggml
    android
    log
)
