cmake_minimum_required(VERSION 3.22.1)
project(airrelay_native C CXX)

# One shared library, everything else static: OpenSSL, Oboe, libc++, FFmpeg (ALAC only),
# libplist, llhttp. Unused code is dropped at link time (gc-sections), the release build is
# stripped by AGP.
#
# Licence split (LICENSE-EXCEPTION.md, THIRD-PARTY.md): two UxPlay parts are GPL-3.0 only
# and are NOT linked into libairrelay_native.so:
#   lib/crypto.c, lib/crypto.h   replaced by AirRelay's own crypto/airrelay_crypto.c
#   lib/playfair/                built as its own program, libairrelay_playfair.so (an
#                                executable), run per key through playfair/playfair_pipe.c
# cmake/CheckLicenseSplit.cmake fails the build if either finds its way back in.

set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)

# No -fvisibility=hidden here: these flags are also forwarded to the OpenSSL build, whose
# own libcrypto.so/libssl.so link would break. Symbols of the static archives are hidden
# in the final library by --exclude-libs,ALL; our own sources get hidden visibility below.
set(SIZE_FLAGS "-Os -ffunction-sections -fdata-sections")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${SIZE_FLAGS} -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=. -ffile-prefix-map=${ANDROID_NDK}=.")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${SIZE_FLAGS} -ffile-prefix-map=${CMAKE_SOURCE_DIR}=. -ffile-prefix-map=${CMAKE_BINARY_DIR}=. -ffile-prefix-map=${ANDROID_NDK}=.")

set(UXPLAY_LIB third_party/UxPlay/lib)

# ---------- OpenSSL 3 (from source via openssl-cmake, static, only what UxPlay needs) ----------
# UxPlay uses: AES-128 (CTR, CBC, GCM), SHA-1/2, MD5, BIGNUM (SRP), Ed25519, X25519, PEM.
set(BUILD_OPENSSL ON CACHE BOOL "" FORCE)
set(OPENSSL_BUILD_VERSION "3.4.4" CACHE STRING "" FORCE)
set(OPENSSL_USE_STATIC_LIBS ON CACHE STRING "" FORCE)
set(CROSS_ANDROID ON CACHE BOOL "" FORCE)
string(REGEX MATCH "[0-9]+" AIRRELAY_ANDROID_API "${ANDROID_PLATFORM}")
# CMAKE_C_FLAGS (size flags above) are forwarded to the OpenSSL build by openssl-cmake.
set(OPENSSL_PARAMS "-D__ANDROID_API__=${AIRRELAY_ANDROID_API}" CACHE STRING "" FORCE)
set(OPENSSL_MODULES "no-apps no-docs no-tests no-ui-console no-engine no-dso no-module no-legacy no-deprecated no-comp no-ssl3 no-dtls no-psk no-srp no-srtp no-ct no-ocsp no-cms no-ts no-cmp no-http no-quic no-sock no-autoload-config no-async no-md2 no-md4 no-mdc2 no-rc2 no-rc4 no-rc5 no-idea no-des no-bf no-cast no-camellia no-seed no-aria no-sm2 no-sm3 no-sm4 no-whirlpool no-rmd160 no-blake2 no-siphash no-scrypt no-gost no-dsa no-ec2m no-capieng no-heartbeats" CACHE STRING "" FORCE)
set(FORWARD_ANDROID_NDK_ROOT "${ANDROID_NDK}" CACHE STRING "" FORCE)

list(PREPEND CMAKE_MODULE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/cmake)
add_subdirectory(third_party/openssl-cmake)

# ---------- Oboe (audio output), static from source ----------
add_subdirectory(third_party/oboe)

# ---------- FFmpeg: only libavcodec + libavutil with the ALAC decoder ----------
set(AIRPLAY_ANDROID_API ${AIRRELAY_ANDROID_API})
include(BuildFFmpeg)

# ---------- libplist ----------
set(PLIST_SRC third_party/libplist/src)
set(PLIST_CNARY third_party/libplist/libcnary)
add_library(plist STATIC
    ${PLIST_SRC}/base64.c
    ${PLIST_SRC}/bplist.c
    ${PLIST_SRC}/bytearray.c
    ${PLIST_SRC}/hashtable.c
    ${PLIST_SRC}/jplist.c
    ${PLIST_SRC}/jsmn.c
    ${PLIST_SRC}/oplist.c
    ${PLIST_SRC}/out-default.c
    ${PLIST_SRC}/out-limd.c
    ${PLIST_SRC}/out-plutil.c
    ${PLIST_SRC}/plist.c
    ${PLIST_SRC}/ptrarray.c
    ${PLIST_SRC}/time64.c
    ${PLIST_SRC}/xplist.c
    ${PLIST_CNARY}/cnary.c
    ${PLIST_CNARY}/node.c
    ${PLIST_CNARY}/node_list.c
)
target_include_directories(plist
    PUBLIC third_party/libplist/include
    PRIVATE ${PLIST_SRC} ${PLIST_CNARY}/include
)
target_compile_definitions(plist PRIVATE _GNU_SOURCE HAVE_STRNDUP PACKAGE_VERSION="2.6.0")

# ---------- llhttp (embedded in UxPlay, MIT) ----------
add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

# ---------- playfair helper: a separate program, never linked into the library ----------
# Named lib*.so so that the packager puts it into the APK's lib/<abi>/ and Android extracts
# it into nativeLibraryDir, the one app directory it may be executed from. stdin: 236 bytes,
# stdout: 16 bytes (playfair/playfair_pipe.h).
add_executable(airrelay_playfair
    playfair/playfair_helper.c
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(airrelay_playfair SYSTEM PRIVATE ${UXPLAY_LIB}/playfair)
set_source_files_properties(playfair/playfair_helper.c PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")
set_target_properties(airrelay_playfair PROPERTIES
    PREFIX "" SUFFIX "" OUTPUT_NAME "libairrelay_playfair.so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(airrelay_playfair PRIVATE "-Wl,--build-id=none" "-Wl,--gc-sections")
target_link_libraries(airrelay_playfair m)

# ---------- UxPlay core ----------
# lib/ is copied into the build tree without crypto.c and crypto.h, so that the quoted
# #include "crypto.h" in the UxPlay sources (searched first in their own directory) can only
# find crypto/crypto.h. configure_file re-copies a file when it changes (patches).
set(UXPLAY_STAGE ${CMAKE_CURRENT_BINARY_DIR}/uxplay-lib)
file(GLOB UXPLAY_LIB_FILES RELATIVE ${CMAKE_CURRENT_SOURCE_DIR}/${UXPLAY_LIB}
    ${CMAKE_CURRENT_SOURCE_DIR}/${UXPLAY_LIB}/*.c ${CMAKE_CURRENT_SOURCE_DIR}/${UXPLAY_LIB}/*.h)
list(REMOVE_ITEM UXPLAY_LIB_FILES crypto.c crypto.h)
foreach(f IN LISTS UXPLAY_LIB_FILES)
    configure_file(${UXPLAY_LIB}/${f} ${UXPLAY_STAGE}/${f} COPYONLY)
endforeach()
# http_request.c includes "llhttp/llhttp.h" relative to its own directory; the staged copy
# needs the header next to it as well (the llhttp sources stay in ${UXPLAY_LIB}/llhttp).
configure_file(${UXPLAY_LIB}/llhttp/llhttp.h ${UXPLAY_STAGE}/llhttp/llhttp.h COPYONLY)

# airplay_video.c is only reached with HLS enabled, which this receiver never does, but
# raop.c links against it.
set(UXPLAY_SOURCES
    ${UXPLAY_STAGE}/airplay_video.c
    ${UXPLAY_STAGE}/byteutils.c
    ${UXPLAY_STAGE}/compat.c
    ${UXPLAY_STAGE}/dnssd.c
    ${UXPLAY_STAGE}/fairplay_playfair.c
    ${UXPLAY_STAGE}/http_request.c
    ${UXPLAY_STAGE}/http_response.c
    ${UXPLAY_STAGE}/httpd.c
    ${UXPLAY_STAGE}/logger.c
    ${UXPLAY_STAGE}/mirror_buffer.c
    ${UXPLAY_STAGE}/netutils.c
    ${UXPLAY_STAGE}/pairing.c
    ${UXPLAY_STAGE}/raop.c
    ${UXPLAY_STAGE}/raop_buffer.c
    ${UXPLAY_STAGE}/raop_ntp.c
    ${UXPLAY_STAGE}/raop_rtp.c
    ${UXPLAY_STAGE}/raop_rtp_mirror.c
    ${UXPLAY_STAGE}/srp.c
    ${UXPLAY_STAGE}/utils.c
)

add_library(airrelay_native SHARED
    ${UXPLAY_SOURCES}
    crypto/airrelay_crypto.c
    playfair/playfair_pipe.c
    playfair/playfair_bridge.c
    android_dnssd_shim.c
    android_raop_callbacks.c
    native_bridge.cpp
    audio_engine.cpp
)
add_dependencies(airrelay_native ffmpeg_ep airrelay_playfair)

target_include_directories(airrelay_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/crypto ${CMAKE_CURRENT_SOURCE_DIR}/playfair)
# third-party headers as SYSTEM, so the strict warnings below apply to our code only.
# ${UXPLAY_LIB} itself is not on the path: it still holds the unused crypto.h.
target_include_directories(airrelay_native SYSTEM PRIVATE
    ${UXPLAY_STAGE}
    ${UXPLAY_LIB}/llhttp
    third_party/oboe/include
)

target_compile_options(airrelay_native PRIVATE -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)

# own sources: all warnings are errors (third-party code keeps its own warning level)
set_source_files_properties(native_bridge.cpp android_raop_callbacks.c android_dnssd_shim.c audio_engine.cpp
    crypto/airrelay_crypto.c playfair/playfair_pipe.c playfair/playfair_bridge.c
    PROPERTIES COMPILE_OPTIONS "-Wall;-Wextra;-Werror")

target_compile_definitions(airrelay_native PRIVATE
    ANDROID
    __STDC_CONSTANT_MACROS
    PLIST_210
    TARGET_RT_LITTLE_ENDIAN=1
)

target_link_options(airrelay_native PRIVATE
    "-Wl,--build-id=none"
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--icf=all"
)

target_link_libraries(airrelay_native
    llhttp
    crypto
    plist
    oboe
    ffmpeg::avcodec
    mediandk
    OpenSLES
    m
    log
    android
)

# Blocker: the library must not contain playfair or UxPlay's crypto.c, and the helper must.
add_custom_command(TARGET airrelay_native POST_BUILD
    COMMAND ${CMAKE_COMMAND} -DLIB=$<TARGET_FILE:airrelay_native> -DHELPER=$<TARGET_FILE:airrelay_playfair>
            -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/CheckLicenseSplit.cmake
    VERBATIM)
