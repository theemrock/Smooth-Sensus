cmake_minimum_required(VERSION 3.22.1)

project(smooth_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "M05A supports only Android arm64-v8a")
endif()

if(SMOOTH_OPTIMIZED_ARM)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
else()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
endif()
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
if(SMOOTH_OPTIMIZED_ARM)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
else()
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
endif()
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(smooth_llama SHARED smooth_llama_jni.cpp)

if(SMOOTH_OPTIMIZED_ARM)
    target_compile_definitions(smooth_llama PRIVATE SMOOTH_CPU_VARIANTS=1)
endif()

target_include_directories(smooth_llama PRIVATE "${LLAMA_CPP_DIR}/include" "${LLAMA_CPP_DIR}/common")
target_link_libraries(smooth_llama PRIVATE llama llama-common android log)

# Experiment 1: optimize native inference only, retaining Debug assertions,
# the baseline CPU feature target, backends and all runtime settings.
foreach(inference_target smooth_llama llama llama-common ggml ggml-base ggml-cpu)
    if(TARGET ${inference_target})
        target_compile_options(${inference_target} PRIVATE $<$<CONFIG:Debug>:-O3>)
    endif()
endforeach()
