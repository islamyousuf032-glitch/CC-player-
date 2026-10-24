#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=ffmpeg-components.sh
source "${ROOT_DIR}/scripts/ffmpeg-components.sh"

NDK=""
OUTPUT=""
ABIS="arm64-v8a,x86_64"
JOBS="4"
CMAKE_BIN=""
FFMPEG_VERSION="n8.1.3"
FFMPEG_URL="https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.1.3"
FFMPEG_SHA256="09f990289327d3ebfedf4fc15cae3f884a65dab7a5aac7bbb5b01ff83900efd9"
OPENAPV_COMMIT="b4ff69856b4c3e8849df431ee3db54fd535f9bf3"
OPENAPV_URL="https://codeload.github.com/AcademySoftwareFoundation/openapv/tar.gz/${OPENAPV_COMMIT}"
OPENAPV_SHA256="56b2a82babc60623cddf24880e03b64b70aa4cfc6548746da6230829d2d965be"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ndk) NDK="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --abis) ABIS="$2"; shift 2 ;;
    --jobs) JOBS="$2"; shift 2 ;;
    --cmake) CMAKE_BIN="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${NDK}" || -z "${OUTPUT}" ]]; then
  echo "Usage: $0 --ndk /path/to/ndk --output /path/to/build [--abis arm64-v8a,x86_64] [--jobs 4] [--cmake /path/to/cmake]" >&2
  exit 2
fi
NDK="$(cd "${NDK}" && pwd)"
mkdir -p "${OUTPUT}"
OUTPUT="$(cd "${OUTPUT}" && pwd)"
if [[ -z "${CMAKE_BIN}" ]]; then
  if command -v cmake >/dev/null 2>&1; then
    CMAKE_BIN="$(command -v cmake)"
  elif [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/cmake/3.31.6/bin/cmake" ]]; then
    CMAKE_BIN="${ANDROID_HOME}/cmake/3.31.6/bin/cmake"
  else
    echo "CMake 3.31.6 is required; pass --cmake" >&2
    exit 2
  fi
fi

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="${TOOLCHAIN}/sysroot"
[[ -x "${TOOLCHAIN}/bin/llvm-ar" ]] || { echo "Invalid NDK path: ${NDK}" >&2; exit 2; }
DOWNLOADS="${OUTPUT}/downloads"
SOURCES="${OUTPUT}/sources"
WORK="${OUTPUT}/work"
INSTALL="${OUTPUT}/install"
JNI_LIBS="${OUTPUT}/jniLibs"
CONFIGS="${OUTPUT}/config"
mkdir -p "${DOWNLOADS}" "${SOURCES}" "${WORK}" "${INSTALL}" "${JNI_LIBS}" "${CONFIGS}"
CURRENT_CONFIG=""
report_failure() {
  local code=$?
  trap - ERR
  if [[ -n "${CURRENT_CONFIG}" && -d "${CURRENT_CONFIG}" ]]; then
    for log in "${CURRENT_CONFIG}"/*.log; do
      [[ -f "${log}" ]] || continue
      echo "===== ${log} (tail) =====" >&2
      tail -n 120 "${log}" >&2
    done
  fi
  exit "${code}"
}
trap report_failure ERR

export LC_ALL=C
export TZ=UTC
export SOURCE_DATE_EPOCH=0
export PATH="${TOOLCHAIN}/bin:${PATH}"

fetch() {
  local url="$1" archive="$2" expected="$3"
  if [[ ! -f "${archive}" ]]; then
    curl -fL --retry 4 --retry-delay 2 --connect-timeout 30 -o "${archive}.partial" "${url}"
    mv "${archive}.partial" "${archive}"
  fi
  echo "${expected}  ${archive}" | sha256sum -c -
}

extract() {
  local archive="$1" destination="$2"
  if [[ ! -d "${destination}" ]]; then
    local temporary="${destination}.extracting"
    rm -rf "${temporary}"
    mkdir -p "${temporary}"
    tar -xzf "${archive}" -C "${temporary}" --strip-components=1
    mv "${temporary}" "${destination}"
  fi
}

FFMPEG_ARCHIVE="${DOWNLOADS}/ffmpeg-${FFMPEG_VERSION}.tar.gz"
OPENAPV_ARCHIVE="${DOWNLOADS}/openapv-${OPENAPV_COMMIT}.tar.gz"
fetch "${FFMPEG_URL}" "${FFMPEG_ARCHIVE}" "${FFMPEG_SHA256}"
fetch "${OPENAPV_URL}" "${OPENAPV_ARCHIVE}" "${OPENAPV_SHA256}"
extract "${FFMPEG_ARCHIVE}" "${SOURCES}/ffmpeg-${FFMPEG_VERSION}"
extract "${OPENAPV_ARCHIVE}" "${SOURCES}/openapv-${OPENAPV_COMMIT}"
FFMPEG_SOURCE="${SOURCES}/ffmpeg-${FFMPEG_VERSION}"
OPENAPV_SOURCE="${SOURCES}/openapv-${OPENAPV_COMMIT}"
if ! grep -q 'oapvm_create(&metadata_desc' "${FFMPEG_SOURCE}/libavcodec/liboapvenc.c"; then
  patch -d "${FFMPEG_SOURCE}" -p1 < "${ROOT_DIR}/third_party/patches/ffmpeg-n8.1.3-openapv-1.1.3.patch"
fi
INPUT_FINGERPRINT="$(cat "${ROOT_DIR}/scripts/build-ffmpeg-android.sh" "${ROOT_DIR}/scripts/ffmpeg-components.sh" "${ROOT_DIR}/third_party/native-dependencies.lock.json" "${ROOT_DIR}/third_party/patches/ffmpeg-n8.1.3-openapv-1.1.3.patch" | sha256sum | cut -d' ' -f1)"

IFS=',' read -ra ABI_LIST <<< "${ABIS}"
for ABI in "${ABI_LIST[@]}"; do
  case "${ABI}" in
    arm64-v8a)
      ARCH="aarch64"
      CPU="armv8-a"
      TRIPLE="aarch64-linux-android"
      OPENAPV_ARM=ON
      ;;
    x86_64)
      ARCH="x86_64"
      CPU="x86-64"
      TRIPLE="x86_64-linux-android"
      OPENAPV_ARM=OFF
      ;;
    *) echo "Unsupported ABI: ${ABI}" >&2; exit 2 ;;
  esac

  PREFIX="${INSTALL}/${ABI}"
  ABI_CONFIG="${CONFIGS}/${ABI}"
  CURRENT_CONFIG="${ABI_CONFIG}"
  mkdir -p "${ABI_CONFIG}"
  STAMP="${ABI_CONFIG}/build-input.sha256"
  if [[ -f "${STAMP}" ]] && [[ "$(cat "${STAMP}")" == "${INPUT_FINGERPRINT}" ]] \
      && [[ -f "${JNI_LIBS}/${ABI}/libavcodec.so" ]] \
      && [[ -f "${PREFIX}/include/libavcodec/avcodec.h" ]]; then
    echo "Native FFmpeg ${ABI}: up to date"
    continue
  fi

  rm -rf "${PREFIX}" "${JNI_LIBS}/${ABI}" "${WORK}/${ABI}"
  mkdir -p "${PREFIX}" "${JNI_LIBS}/${ABI}" "${WORK}/${ABI}/openapv" "${WORK}/${ABI}/ffmpeg"

  "${CMAKE_BIN}" \
    -S "${OPENAPV_SOURCE}" \
    -B "${WORK}/${ABI}/openapv" \
    -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DARM="${OPENAPV_ARM}" \
    -DOAPV_BUILD_APPS=OFF \
    -DOAPV_BUILD_STATIC_LIB=ON \
    -DOAPV_BUILD_SHARED_LIB=OFF \
    > "${ABI_CONFIG}/openapv-configure.log"
  "${CMAKE_BIN}" --build "${WORK}/${ABI}/openapv" --parallel "${JOBS}" --target install \
    > "${ABI_CONFIG}/openapv-build.log"

  PKG_CONFIG="${WORK}/${ABI}/pkg-config"
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

  CC="${TOOLCHAIN}/bin/${TRIPLE}29-clang"
  CXX="${TOOLCHAIN}/bin/${TRIPLE}29-clang++"
  [[ -x "${CC}" ]] || { echo "Missing NDK compiler: ${CC}" >&2; exit 2; }
  CONFIGURE_ARGS=(
    "--prefix=${PREFIX}"
    "--target-os=android"
    "--arch=${ARCH}"
    "--cpu=${CPU}"
    "--enable-cross-compile"
    "--sysroot=${SYSROOT}"
    "--cc=${CC}"
    "--cxx=${CXX}"
    "--ld=${CC}"
    "--ar=${TOOLCHAIN}/bin/llvm-ar"
    "--ranlib=${TOOLCHAIN}/bin/llvm-ranlib"
    "--nm=${TOOLCHAIN}/bin/llvm-nm"
    "--strip=${TOOLCHAIN}/bin/llvm-strip"
    "--host-cc=cc"
    "--pkg-config=${PKG_CONFIG}"
    "--extra-version=ccplayer-lgpl"
    "--extra-cflags=-O3 -fPIC -DANDROID -D_FILE_OFFSET_BITS=64 -I${PREFIX}/include"
    "--extra-ldflags=-L${PREFIX}/lib -Wl,-z,max-page-size=16384"
    --disable-everything
    --disable-autodetect
    --disable-programs
    --disable-doc
    --disable-debug
    --disable-network
    --disable-avdevice
    --disable-avfilter
    --disable-x86asm
    --disable-static
    --disable-symver
    --enable-shared
    --enable-pic
    --enable-optimizations
    --enable-zlib
    --enable-liboapv
    --enable-avcodec
    --enable-avformat
    --enable-avutil
    --enable-swscale
    --enable-swresample
  )
  CONFIGURE_ARGS+=("${CCPLAYER_FFMPEG_COMPONENT_ARGS[@]}")
  printf '%q ' "${CONFIGURE_ARGS[@]}" > "${ABI_CONFIG}/ffmpeg-configure-command.txt"
  printf '\n' >> "${ABI_CONFIG}/ffmpeg-configure-command.txt"
  (
    cd "${WORK}/${ABI}/ffmpeg"
    "${FFMPEG_SOURCE}/configure" "${CONFIGURE_ARGS[@]}" > "${ABI_CONFIG}/ffmpeg-configure.log"
    make -j"${JOBS}" > "${ABI_CONFIG}/ffmpeg-build.log"
    make install > "${ABI_CONFIG}/ffmpeg-install.log"
  )

  for library in libavcodec.so libavformat.so libavutil.so libswresample.so libswscale.so; do
    [[ -f "${PREFIX}/lib/${library}" ]] || { echo "Missing ${PREFIX}/lib/${library}" >&2; exit 3; }
    cp "${PREFIX}/lib/${library}" "${JNI_LIBS}/${ABI}/${library}"
    "${TOOLCHAIN}/bin/llvm-readelf" -d "${JNI_LIBS}/${ABI}/${library}" > "${ABI_CONFIG}/${library}.dynamic.txt"
    if grep -q 'Library soname:.*\.so\.' "${ABI_CONFIG}/${library}.dynamic.txt"; then
      echo "Versioned Android SONAME is not packageable: ${library}" >&2
      exit 3
    fi
  done
  if grep -q 'liboapv\.so' "${ABI_CONFIG}/libavcodec.so.dynamic.txt"; then
    echo "OpenAPV must be statically linked, not an undeclared shared dependency" >&2
    exit 3
  fi
  cp "${WORK}/${ABI}/ffmpeg/config.h" "${ABI_CONFIG}/ffmpeg-config.h"
  cp "${WORK}/${ABI}/ffmpeg/ffbuild/config.mak" "${ABI_CONFIG}/ffmpeg-config.mak"
  sha256sum "${JNI_LIBS}/${ABI}"/*.so > "${ABI_CONFIG}/SHA256SUMS"
  echo "${INPUT_FINGERPRINT}" > "${STAMP}"
  echo "Native FFmpeg + OpenAPV ${ABI}: built successfully"
done
