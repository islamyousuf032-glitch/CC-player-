cmake_minimum_required(VERSION 3.22)
project(ccplayer_ffmpeg LANGUAGES CXX)

if(NOT FFMPEG_INSTALL_ROOT)
    message(FATAL_ERROR "FFMPEG_INSTALL_ROOT must point to the pinned per-ABI native build")
endif()

set(FFMPEG_PREFIX "${FFMPEG_INSTALL_ROOT}/${ANDROID_ABI}")

add_library(ccplayer_ffmpeg SHARED
    FFmpegJni.cpp
    native_media.cpp
    color_pipeline.cpp
    audio_pipeline.cpp
    playback.cpp
    transcode.cpp
)
if(ANDROID)
    target_sources(ccplayer_ffmpeg PRIVATE FFmpegPlayerJni.cpp playback_android.cpp)
endif()
target_compile_features(ccplayer_ffmpeg PRIVATE cxx_std_17)
target_compile_options(ccplayer_ffmpeg PRIVATE -Wall -Wextra -Wpedantic -Werror=return-type)
target_include_directories(ccplayer_ffmpeg PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${FFMPEG_PREFIX}/include"
)
target_link_directories(ccplayer_ffmpeg PRIVATE "${FFMPEG_PREFIX}/lib")
target_link_libraries(ccplayer_ffmpeg PRIVATE
    avformat
    avcodec
    swresample
    swscale
    avutil
    android
    log
    z
    m
)
target_link_options(ccplayer_ffmpeg PRIVATE "-Wl,-z,max-page-size=16384")
