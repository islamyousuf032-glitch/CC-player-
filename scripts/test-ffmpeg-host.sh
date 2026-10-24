#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ffmpeg-components.sh
source "${ROOT_DIR}/scripts/ffmpeg-components.sh"
OUTPUT="${1:-${ROOT_DIR}/media/ffmpeg/build/host-verification}"
JOBS="${2:-4}"
CMAKE_BIN="${CMAKE_BIN:-$(command -v cmake || true)}"
[[ -n "${CMAKE_BIN}" ]] || { echo "CMake is required (set CMAKE_BIN)" >&2; exit 2; }
mkdir -p "${OUTPUT}"
OUTPUT="$(cd "${OUTPUT}" && pwd)"
DOWNLOADS="${OUTPUT}/downloads"
SOURCES="${OUTPUT}/sources"
WORK="${OUTPUT}/work"
PREFIX="${OUTPUT}/install"
mkdir -p "${DOWNLOADS}" "${SOURCES}" "${WORK}" "${PREFIX}"

FFMPEG_URL="https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.1.3"
FFMPEG_SHA="09f990289327d3ebfedf4fc15cae3f884a65dab7a5aac7bbb5b01ff83900efd9"
OPENAPV_URL="https://codeload.github.com/AcademySoftwareFoundation/openapv/tar.gz/b4ff69856b4c3e8849df431ee3db54fd535f9bf3"
OPENAPV_SHA="56b2a82babc60623cddf24880e03b64b70aa4cfc6548746da6230829d2d965be"
ZLIB_URL="https://codeload.github.com/madler/zlib/tar.gz/refs/tags/v1.3.1"
ZLIB_SHA="17e88863f3600672ab49182f217281b6fc4d3c762bde361935e436a95214d05c"

fetch() {
  local url="$1" file="$2" hash="$3"
  if [[ ! -f "${file}" ]]; then
    curl -fL --retry 4 --retry-delay 2 -o "${file}.partial" "${url}"
    mv "${file}.partial" "${file}"
  fi
  echo "${hash}  ${file}" | sha256sum -c -
}
extract() {
  local file="$1" target="$2"
  if [[ ! -d "${target}" ]]; then
    rm -rf "${target}.tmp"
    mkdir -p "${target}.tmp"
    tar -xzf "${file}" -C "${target}.tmp" --strip-components=1
    mv "${target}.tmp" "${target}"
  fi
}
run_log() {
  local log="$1"; shift
  if ! "$@" > "${log}" 2>&1; then
    tail -n 250 "${log}" >&2
    return 1
  fi
}

fetch "${FFMPEG_URL}" "${DOWNLOADS}/ffmpeg.tar.gz" "${FFMPEG_SHA}"
fetch "${OPENAPV_URL}" "${DOWNLOADS}/openapv.tar.gz" "${OPENAPV_SHA}"
fetch "${ZLIB_URL}" "${DOWNLOADS}/zlib.tar.gz" "${ZLIB_SHA}"
extract "${DOWNLOADS}/ffmpeg.tar.gz" "${SOURCES}/ffmpeg"
extract "${DOWNLOADS}/openapv.tar.gz" "${SOURCES}/openapv"
extract "${DOWNLOADS}/zlib.tar.gz" "${SOURCES}/zlib"
if ! grep -q 'oapvm_create(&metadata_desc' "${SOURCES}/ffmpeg/libavcodec/liboapvenc.c"; then
  patch -d "${SOURCES}/ffmpeg" -p1 < "${ROOT_DIR}/third_party/patches/ffmpeg-n8.1.3-openapv-1.1.3.patch"
fi

run_log "${WORK}/zlib-configure.log" "${CMAKE_BIN}" -S "${SOURCES}/zlib" -B "${WORK}/zlib" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="${PREFIX}" -DCMAKE_INSTALL_LIBDIR=lib \
  -DBUILD_SHARED_LIBS=OFF -DZLIB_BUILD_EXAMPLES=OFF
run_log "${WORK}/zlib-build.log" "${CMAKE_BIN}" --build "${WORK}/zlib" --parallel "${JOBS}" --target install
run_log "${WORK}/openapv-configure.log" "${CMAKE_BIN}" -S "${SOURCES}/openapv" -B "${WORK}/openapv" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="${PREFIX}" -DCMAKE_INSTALL_LIBDIR=lib \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DOAPV_BUILD_APPS=OFF -DOAPV_BUILD_STATIC_LIB=ON -DOAPV_BUILD_SHARED_LIB=OFF
run_log "${WORK}/openapv-build.log" "${CMAKE_BIN}" --build "${WORK}/openapv" --parallel "${JOBS}" --target install

PKG_CONFIG="${WORK}/pkg-config"
cat > "${PKG_CONFIG}" <<EOF
#!/bin/sh
case "\$1" in
  --version) echo '2.0.0'; exit 0 ;;
  --exists) exit 0 ;;
  --cflags) echo '-I${PREFIX}/include -I${PREFIX}/include/oapv -DOAPV_STATIC_DEFINE'; exit 0 ;;
  --libs) echo '-L${PREFIX}/lib -loapv -lm -pthread'; exit 0 ;;
  --modversion) echo '1.1.3.0'; exit 0 ;;
  --variable=includedir) echo '${PREFIX}/include'; exit 0 ;;
  *) exit 1 ;;
esac
EOF
chmod +x "${PKG_CONFIG}"
mkdir -p "${WORK}/ffmpeg"
CONFIGURE_ARGS=(
  "--prefix=${PREFIX}"
  "--pkg-config=${PKG_CONFIG}"
  "--extra-cflags=-O2 -fPIC -D_FILE_OFFSET_BITS=64 -I${PREFIX}/include"
  "--extra-ldflags=-L${PREFIX}/lib"
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-debug
  --disable-network --disable-avdevice --disable-avfilter --disable-x86asm
  --disable-shared --enable-static --enable-pic --enable-optimizations
  --enable-zlib --enable-liboapv --enable-avcodec --enable-avformat --enable-avutil
  --enable-swscale --enable-swresample
)
CONFIGURE_ARGS+=("${CCPLAYER_FFMPEG_COMPONENT_ARGS[@]}")
(
  cd "${WORK}/ffmpeg"
  run_log "${WORK}/ffmpeg-configure.log" "${SOURCES}/ffmpeg/configure" "${CONFIGURE_ARGS[@]}"
  run_log "${WORK}/ffmpeg-build.log" make -j"${JOBS}"
  run_log "${WORK}/ffmpeg-install.log" make install
)

BINARY="${OUTPUT}/native_media_test"
run_log "${WORK}/host-test-compile.log" g++ -std=c++17 -g -O1 -fsanitize=address -fno-omit-frame-pointer \
  -Wall -Wextra -Wpedantic \
  -I"${PREFIX}/include" -I"${ROOT_DIR}/media/ffmpeg/src/main/cpp/include" -I"${ROOT_DIR}/media/ffmpeg/src/main/cpp" \
  "${ROOT_DIR}/media/ffmpeg/src/test/cpp/native_media_test.cpp" \
  "${ROOT_DIR}/media/ffmpeg/src/main/cpp/native_media.cpp" \
  "${ROOT_DIR}/media/ffmpeg/src/main/cpp/color_pipeline.cpp" \
  "${ROOT_DIR}/media/ffmpeg/src/main/cpp/audio_pipeline.cpp" \
  "${ROOT_DIR}/media/ffmpeg/src/main/cpp/transcode.cpp" \
  -L"${PREFIX}/lib" -lavformat -lavcodec -lswresample -lswscale -lavutil -loapv -lz -lpthread -lm \
  -o "${BINARY}"
ASAN_OPTIONS=detect_leaks=0 "${BINARY}" | tee "${OUTPUT}/native-test-results.log"
