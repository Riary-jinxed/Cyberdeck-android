# llama.cpp, for the GGUF models, and the small JNI bridge the app talks to.
cmake_minimum_required(VERSION 3.22.1)
project(datesllama C CXX)

# Only the library: no tools, tests, server or network.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Dot product instructions: every arm64 phone since about 2019, and twice as fast on Q4 models.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${LLAMA_SRC} llama)

add_library(datesllama SHARED llama_jni.cpp)
target_compile_features(datesllama PRIVATE cxx_std_17)
target_link_libraries(datesllama PRIVATE llama android log)
