cmake_minimum_required(VERSION 3.22.1)
project(rwkv_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(INFER_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cpp")
set(NATIVE_SOURCES
    "${INFER_CPP_DIR}/native_rwkv_bridge.cpp"
    "${INFER_CPP_DIR}/rwkv_engine_core.cpp"
    "${INFER_CPP_DIR}/rwkv_engine_real_adapter.cpp"
    "${INFER_CPP_DIR}/rwkv_real_backend.cpp"
    "${INFER_CPP_DIR}/rwkv_sampler.cpp"
    "${INFER_CPP_DIR}/rwkv_tokenizer.cpp"
    "${INFER_CPP_DIR}/rwkv_weight_loader.cpp"
)

set(RWKV_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../cpp/third_party/rwkv_cpp")
if(EXISTS "${RWKV_CPP_DIR}/rwkv.cpp" AND EXISTS "${RWKV_CPP_DIR}/rwkv.h")
    set(HAS_REAL_RWKV_BACKEND 1)
    set(RWKV_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(RWKV_STANDALONE ON CACHE BOOL "" FORCE)
    set(RWKV_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_BLAS OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_HIP OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_KOMPUTE OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    set(GGML_RPC OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    add_subdirectory("${RWKV_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/rwkv_cpp" EXCLUDE_FROM_ALL)
else()
    set(HAS_REAL_RWKV_BACKEND 0)
endif()

add_library(rwkv_jni SHARED ${NATIVE_SOURCES})
target_compile_definitions(rwkv_jni PRIVATE RWKV_REAL_BACKEND_AVAILABLE=${HAS_REAL_RWKV_BACKEND})
target_include_directories(rwkv_jni PRIVATE "${INFER_CPP_DIR}")
if(HAS_REAL_RWKV_BACKEND)
    target_include_directories(rwkv_jni PRIVATE "${RWKV_CPP_DIR}")
    target_link_libraries(rwkv_jni PRIVATE rwkv)
endif()

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(rwkv_jni PRIVATE ${log-lib})
endif()
