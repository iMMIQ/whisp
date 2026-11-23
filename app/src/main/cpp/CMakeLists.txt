# SPDX-License-Identifier: LGPL-3.0-or-later
cmake_minimum_required(VERSION 3.31)
project(whisp_nfs LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=/whisp/source"
                    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/whisp/build")
if(ANDROID)
    add_compile_options("-ffile-prefix-map=${CMAKE_ANDROID_NDK}=/android/ndk")
endif()

option(WHISP_BUILD_HOST_TESTS "Build standalone host JNI bridge tests with sanitizers" OFF)

if(NOT WHISP_BUILD_HOST_TESTS)
    include(FetchContent)
    set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
    set(ENABLE_UTILS OFF CACHE BOOL "" FORCE)
    set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(ENABLE_DOCUMENTATION OFF CACHE BOOL "" FORCE)
    set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
    set(ENABLE_TLS OFF CACHE STRING "" FORCE)
    FetchContent_Declare(libnfs
        URL https://codeload.github.com/sahlberg/libnfs/tar.gz/5b1bc60260b5ddac34900c5315010b88b73e7edd
        URL_HASH SHA256=80b67a8905f7e2051245c6fadc1c4beb7a6481da278e7664fee146e3bbe4b0f1
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_MakeAvailable(libnfs)
    set_property(TARGET nfs PROPERTY VERSION)
    set_property(TARGET nfs PROPERTY SOVERSION)

    add_library(whisp_nfs SHARED nfs_bridge.cpp)
    target_compile_features(whisp_nfs PRIVATE cxx_std_17)
    target_compile_options(whisp_nfs PRIVATE -Wall -Wextra -Werror)
    target_include_directories(whisp_nfs PRIVATE ${libnfs_SOURCE_DIR}/include)
    target_link_libraries(whisp_nfs PRIVATE nfs)
    if(NOT ANDROID)
        find_package(JNI REQUIRED COMPONENTS JVM)
        target_include_directories(whisp_nfs PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
else()
    enable_testing()
    find_package(JNI REQUIRED COMPONENTS JVM)
    add_executable(nfs_bridge_test nfs_bridge_test.cpp)
    target_compile_features(nfs_bridge_test PRIVATE cxx_std_17)
    target_compile_options(nfs_bridge_test PRIVATE -Wall -Wextra -Werror -O1 -g
                                                   -fsanitize=address,undefined
                                                   -fno-omit-frame-pointer)
    target_link_options(nfs_bridge_test PRIVATE -fsanitize=address,undefined)
    target_include_directories(nfs_bridge_test PRIVATE ${JNI_INCLUDE_DIRS}
                                                       ${CMAKE_CURRENT_SOURCE_DIR}/test_stubs)
    add_test(NAME nfs_bridge_test COMMAND nfs_bridge_test)
endif()
