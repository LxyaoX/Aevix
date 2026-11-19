cmake_minimum_required(VERSION 3.10)

project(external)

# CMake 3.28 and later scan for C++ modules by default from C++20 on, and for the standard library
# it runs clang-scan-deps against the *host's* libstdc++ sources -- /usr/include/c++/15/bits/std.cc
# on the CI image. The sysroot is the NDK's, which has no such file, so the scan fails with
# "fatal error: 'bits/stdc++.h' file not found" and takes the whole build down.
#
# Nothing here imports a module, so the scan has nothing to find. It only ever passed before
# because ccache still held the result from a configuration where VERSION_CODE had not changed;
# a clean build fails every time.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)

set(CMAKE_CXX_STANDARD 23)

set(XZ_SOURCES
	xz_crc32.c
	xz_crc64.c
	# xz_dec_bcj.c
	xz_dec_lzma2.c
	xz_dec_stream.c)
set(XZ_INCLUDES xz-embedded/linux/include/linux xz-embedded/userspace)
list(TRANSFORM XZ_SOURCES PREPEND xz-embedded/linux/lib/xz/)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ_INCLUDES})

option(LSPLANT_BUILD_SHARED OFF)
option(Plugin.SymbolResolver OFF)
option(FMT_INSTALL OFF)

add_subdirectory(dobby)
add_subdirectory(fmt)
add_subdirectory(lsplant/lsplant/src/main/jni)
target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct
	# lsplant.cc's `operator""_uarr()` is a string literal operator template, which is a GNU
	# extension clang warns about under -Wpedantic. Upstream code we do not own, and the
	# warning was printed once per ABI on every build.
	-Wno-gnu-string-literal-operator-template)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)
