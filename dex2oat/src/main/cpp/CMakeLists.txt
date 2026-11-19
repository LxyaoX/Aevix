cmake_minimum_required(VERSION 3.10)
project(dex2oat)

# CMake 3.28 and later scan for C++ modules by default from C++20 on, and for the standard library
# it runs clang-scan-deps against the *host's* libstdc++ sources -- /usr/include/c++/15/bits/std.cc
# on the CI image. The sysroot is the NDK's, which has no such file, so the scan fails with
# "fatal error: 'bits/stdc++.h' file not found" and takes the whole build down.
#
# Nothing here imports a module, so the scan has nothing to find. It only ever passed before
# because ccache still held the result from a configuration where VERSION_CODE had not changed;
# a clean build fails every time.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF CACHE BOOL "" FORCE)

add_executable(dex2oat dex2oat.cpp)
add_library(oat_hook SHARED oat_hook.cpp)

# Built into this library rather than shipped beside it: the hook has to be in place before
# dex2oat asks for its first OAT header, and a second .so would have to be preloaded for that.
# Static, so the only exported names are the ones this target defines.
set(YUKIPLT_LIBRARY_TYPE STATIC CACHE STRING "" FORCE)
# Its default. Full LTO is a long link for a library this size, and the option is an interface one
# for a static build, so leaving it on would put `-flto=full` on our link line as well.
set(YUKIPLT_LTO OFF CACHE BOOL "" FORCE)
add_subdirectory(${AEVIX_ROOT}/external/yukiplt external/yukiplt)

target_include_directories(oat_hook PUBLIC include)
target_include_directories(dex2oat PUBLIC include)
target_link_libraries(dex2oat log)
target_link_libraries(oat_hook log yukiplt)

# This one exports nothing: it is preloaded for its constructor, and dex2oat never looks a symbol
# up in it. Hiding the rest keeps the hook library's names out of the dynamic table.
set_target_properties(oat_hook PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        C_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
# YukiPLT is a static library with its own hidden preset, so its names never reach the dynamic
# table; this keeps anything else from adding one. Nothing outside this library looks anything up
# in it, so nothing is exported at all.
target_link_options(oat_hook PRIVATE
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/oat_hook.map")

if (DEFINED DEBUG_SYMBOLS_PATH)
    message(STATUS "Debug symbols will be placed at ${DEBUG_SYMBOLS_PATH}")
    # ${PROJECT_NAME} is `dex2oat`, so this used to strip only the executable: `oat_hook` kept its
    # -g sections and shipped 1.7 MB of .debug_* and .symtab for 300 KB of code. Both targets are
    # stripped now, and both keep their symbols under DEBUG_SYMBOLS_PATH.
    foreach (TARGET_NAME ${PROJECT_NAME} oat_hook)
        add_custom_command(TARGET ${TARGET_NAME} POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}
                COMMAND ${CMAKE_OBJCOPY} --only-keep-debug $<TARGET_FILE:${TARGET_NAME}>
                ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${TARGET_NAME}.debug
                COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${TARGET_NAME}>
                COMMAND ${CMAKE_OBJCOPY} --add-gnu-debuglink
                ${DEBUG_SYMBOLS_PATH}/${ANDROID_ABI}/${TARGET_NAME}.debug
                $<TARGET_FILE:${TARGET_NAME}>)
    endforeach()
endif()
