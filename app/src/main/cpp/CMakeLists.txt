cmake_minimum_required(VERSION 3.22.1)
project(rwkv_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NATIVE_SOURCES
    native_rwkv_bridge.cpp
)

if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/third_party/rwkv.cpp)
    message(STATUS "Using real rwkv.cpp source")
    list(APPEND NATIVE_SOURCES third_party/rwkv.cpp)
    add_definitions(-DUSE_REAL_RWKV)
else()
    message(WARNING "third_party/rwkv.cpp not found; building with stub engine")
    list(APPEND NATIVE_SOURCES rwkv_engine_stub.cpp)
endif()

add_library(rwkv_jni SHARED ${NATIVE_SOURCES})

find_library(log-lib log)
target_link_libraries(rwkv_jni ${log-lib})
