# SPDX-License-Identifier: LGPL-3.0-or-later
cmake_minimum_required(VERSION 3.31)
project(whisp_native LANGUAGES C CXX)
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

    # 1. Pinned libnfs 8.0.0 (LGPL-2.1-or-later / BSD)
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

    # Clear libnfs's global cache variables before configuring libsmb2 (both use CORE_LIBRARIES).
    unset(CORE_LIBRARIES CACHE)
    set(CORE_LIBRARIES "")
    unset(core_DEPENDS CACHE)
    set(core_DEPENDS "")

    # 2. Pinned libsmb2 7.0.0 (LGPL-2.1-or-later)
    set(ENABLE_LIBKRB5 OFF CACHE BOOL "" FORCE)
    set(ENABLE_GSSAPI OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(libsmb2
        URL https://codeload.github.com/sahlberg/libsmb2/tar.gz/62a861a043fadb69ee375464efb17e94099f8f76
        URL_HASH SHA256=b5a55dd1597f1de81f927015cf4cb865fe58ea48d80a0a6006f74c94eb915e29
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    FetchContent_MakeAvailable(libsmb2)
    set_property(TARGET smb2 PROPERTY VERSION)
    set_property(TARGET smb2 PROPERTY SOVERSION)

    # 3. STL-free JNI bridges (NFS, SMB2/3, NEON Audio DSP)
    add_library(whisp_nfs SHARED nfs_bridge.cpp)
    target_compile_features(whisp_nfs PRIVATE cxx_std_17)
    target_compile_options(whisp_nfs PRIVATE -Wall -Wextra -Werror -fvisibility=hidden
                                             -fno-exceptions -fno-rtti)
    target_include_directories(whisp_nfs PRIVATE ${libnfs_SOURCE_DIR}/include)
    target_link_libraries(whisp_nfs PRIVATE nfs)

    add_library(whisp_smb SHARED smb_bridge.cpp)
    target_compile_features(whisp_smb PRIVATE cxx_std_17)
    target_compile_options(whisp_smb PRIVATE -Wall -Wextra -Werror -fvisibility=hidden
                                             -fno-exceptions -fno-rtti)
    target_include_directories(whisp_smb PRIVATE ${libsmb2_SOURCE_DIR}/include)
    target_link_libraries(whisp_smb PRIVATE smb2)

    add_library(whisp_dsp SHARED dsp_bridge.cpp)
    target_compile_features(whisp_dsp PRIVATE cxx_std_17)
    target_compile_options(whisp_dsp PRIVATE -Wall -Wextra -Werror -fvisibility=hidden
                                             -fno-exceptions -fno-rtti -O3)
    target_link_libraries(whisp_dsp PRIVATE m)

    if(NOT ANDROID)
        find_package(JNI REQUIRED COMPONENTS JVM)
        target_include_directories(whisp_nfs PRIVATE ${JNI_INCLUDE_DIRS})
        target_include_directories(whisp_smb PRIVATE ${JNI_INCLUDE_DIRS})
        target_include_directories(whisp_dsp PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
else()
    enable_testing()
    find_package(JNI REQUIRED COMPONENTS JVM)

    foreach(test_target nfs_bridge_test smb_bridge_test dsp_bridge_test)
        add_executable(${test_target} ${test_target}.cpp)
        target_compile_features(${test_target} PRIVATE cxx_std_17)
        target_compile_options(${test_target} PRIVATE -Wall -Wextra -Werror -O1 -g
                                                      -fsanitize=address,undefined
                                                      -fno-omit-frame-pointer)
        target_link_options(${test_target} PRIVATE -fsanitize=address,undefined)
        target_include_directories(${test_target} PRIVATE ${JNI_INCLUDE_DIRS}
                                                          ${CMAKE_CURRENT_SOURCE_DIR}/test_stubs)
        add_test(NAME ${test_target} COMMAND ${test_target})
    endforeach()
    target_link_libraries(dsp_bridge_test PRIVATE m)
endif()
