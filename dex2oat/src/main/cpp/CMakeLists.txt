cmake_minimum_required(VERSION 3.10)
project(dex2oat)

add_executable(dex2oat dex2oat.cpp)
add_library(oat_hook SHARED oat_hook.cpp)

OPTION(LSPLT_BUILD_SHARED OFF)
add_subdirectory(${VECTOR_ROOT}/external/lsplt/lsplt/src/main/jni external)

target_include_directories(oat_hook PUBLIC include)
target_include_directories(dex2oat PUBLIC include)
target_link_libraries(dex2oat log)
target_link_libraries(oat_hook log lsplt_static)

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
