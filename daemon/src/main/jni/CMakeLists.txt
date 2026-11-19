cmake_minimum_required(VERSION 3.10)
project(daemon)

# CMake 3.28 and later scan for C++ modules by default from C++20 on, and for the standard library
# it runs clang-scan-deps against the *host's* libstdc++ sources -- /usr/include/c++/15/bits/std.cc
# on the CI image. The sysroot is the NDK's, which has no such file, so the scan fails with
# "fatal error: 'bits/stdc++.h' file not found" and takes the whole build down.
#
# `native`, `zygisk` and `dex2oat` all say this already; this one did not. It stayed green only
# while ccache still held a result from a configuration whose VERSION_CODE had not changed -- and
# the NDK 30 toolchain then overwrote the cache variable Gradle passes, which is what made it fail
# with everything else unchanged.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 23)

add_subdirectory(${AEVIX_ROOT}/external external)

set(SOURCES
        dex2oat.cpp
        logcat.cpp
        obfuscation.cpp
        )

add_library(${PROJECT_NAME} SHARED ${SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(${PROJECT_NAME} PRIVATE lsplant_static dex_builder_static android log)

# The JNI functions are JNIEXPORT, which is visibility("default") on the NDK, so they stay; the
# static libraries behind them do not need to be reachable from outside.
set_target_properties(${PROJECT_NAME} PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        C_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
# Same for lsplant and the dex reader: the Java_* JNIEXPORT names stay, everything else goes.
target_link_options(${PROJECT_NAME} PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/daemon.map")

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
            COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${PROJECT_NAME}>
            ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${PROJECT_NAME}>
            COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${PROJECT_NAME}.debug
            $<TARGET_FILE:${PROJECT_NAME}>)
endif()
