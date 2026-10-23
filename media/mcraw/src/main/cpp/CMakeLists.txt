cmake_minimum_required(VERSION 3.22)
project(ccplayer_mcraw LANGUAGES CXX)

include(FetchContent)

# Pinned upstream source. Keep this synchronized with third_party/native-dependencies.lock.json.
FetchContent_Declare(
    motioncam_decoder_source
    URL "https://codeload.github.com/mirsadm/motioncam-decoder/tar.gz/b931ec0e2466c8217c49f13df925531b78f76e33"
    URL_HASH "SHA256=9839a478d7e59724f6ebd833adb33dd8177ebb5eaa0928b2a607bd28b480402c"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)

FetchContent_GetProperties(motioncam_decoder_source)
if(NOT motioncam_decoder_source_POPULATED)
    FetchContent_Populate(motioncam_decoder_source)
endif()

# Build only the Apache-2.0 decoder library, not upstream desktop examples.
add_library(motioncam_decoder STATIC
    "${motioncam_decoder_source_SOURCE_DIR}/lib/Decoder.cpp"
    "${motioncam_decoder_source_SOURCE_DIR}/lib/RawData.cpp"
    "${motioncam_decoder_source_SOURCE_DIR}/lib/RawData_Legacy.cpp"
)
target_compile_features(motioncam_decoder PRIVATE cxx_std_17)
target_include_directories(motioncam_decoder PUBLIC
    "${motioncam_decoder_source_SOURCE_DIR}/lib/include"
    "${motioncam_decoder_source_SOURCE_DIR}/thirdparty"
)
set_target_properties(motioncam_decoder PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(ccplayer_mcraw SHARED McrawJni.cpp)
target_compile_features(ccplayer_mcraw PRIVATE cxx_std_17)
target_link_libraries(ccplayer_mcraw PRIVATE motioncam_decoder)

# Android 15+ 16 KiB page-size compatibility (also harmless on 4 KiB devices).
target_link_options(ccplayer_mcraw PRIVATE "-Wl,-z,max-page-size=16384")
