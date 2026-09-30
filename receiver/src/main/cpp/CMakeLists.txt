cmake_minimum_required(VERSION 3.22.1)
project(airrelay_native C CXX)

# One shared library, everything else static: OpenSSL, Oboe, libc++, FFmpeg (ALAC only),
# libplist, llhttp, playfair. Unused code is dropped at link time (gc-sections), the
# release build is stripped by AGP.

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

# ---------- llhttp and playfair (embedded in UxPlay) ----------
add_library(llhttp STATIC
    ${UXPLAY_LIB}/llhttp/api.c
    ${UXPLAY_LIB}/llhttp/http.c
    ${UXPLAY_LIB}/llhttp/llhttp.c
)
target_include_directories(llhttp PUBLIC ${UXPLAY_LIB}/llhttp)

add_library(playfair STATIC
    ${UXPLAY_LIB}/playfair/hand_garble.c
    ${UXPLAY_LIB}/playfair/modified_md5.c
    ${UXPLAY_LIB}/playfair/omg_hax.c
    ${UXPLAY_LIB}/playfair/playfair.c
    ${UXPLAY_LIB}/playfair/sap_hash.c
)
target_include_directories(playfair PUBLIC ${UXPLAY_LIB}/playfair)

# ---------- UxPlay core ----------
# airplay_video.c is only reached with HLS enabled, which this receiver never does, but
# raop.c links against it.
set(UXPLAY_SOURCES
    ${UXPLAY_LIB}/airplay_video.c
    ${UXPLAY_LIB}/byteutils.c
    ${UXPLAY_LIB}/compat.c
    ${UXPLAY_LIB}/crypto.c
    ${UXPLAY_LIB}/dnssd.c
    ${UXPLAY_LIB}/fairplay_playfair.c
    ${UXPLAY_LIB}/http_request.c
    ${UXPLAY_LIB}/http_response.c
    ${UXPLAY_LIB}/httpd.c
    ${UXPLAY_LIB}/logger.c
    ${UXPLAY_LIB}/mirror_buffer.c
    ${UXPLAY_LIB}/netutils.c
    ${UXPLAY_LIB}/pairing.c
    ${UXPLAY_LIB}/raop.c
    ${UXPLAY_LIB}/raop_buffer.c
    ${UXPLAY_LIB}/raop_ntp.c
    ${UXPLAY_LIB}/raop_rtp.c
    ${UXPLAY_LIB}/raop_rtp_mirror.c
    ${UXPLAY_LIB}/srp.c
    ${UXPLAY_LIB}/utils.c
)

add_library(airrelay_native SHARED
    ${UXPLAY_SOURCES}
    android_dnssd_shim.c
    android_raop_callbacks.c
    native_bridge.cpp
    audio_engine.cpp
)
add_dependencies(airrelay_native ffmpeg_ep)

target_include_directories(airrelay_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# third-party headers as SYSTEM, so the strict warnings below apply to our code only
target_include_directories(airrelay_native SYSTEM PRIVATE
    ${UXPLAY_LIB}
    ${UXPLAY_LIB}/llhttp
    ${UXPLAY_LIB}/playfair
    third_party/oboe/include
)

target_compile_options(airrelay_native PRIVATE -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)

# own sources: all warnings are errors (third-party code keeps its own warning level)
set_source_files_properties(native_bridge.cpp android_raop_callbacks.c android_dnssd_shim.c audio_engine.cpp
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
    playfair
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
