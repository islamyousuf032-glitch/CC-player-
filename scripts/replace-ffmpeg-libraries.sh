#!/usr/bin/env bash
set -euo pipefail

INPUT=""
OUTPUT=""
LIBS_ROOT=""
APKSIGNER=""
KEYSTORE=""
ALIAS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --libs-root) LIBS_ROOT="$2"; shift 2 ;;
    --apksigner) APKSIGNER="$2"; shift 2 ;;
    --keystore) KEYSTORE="$2"; shift 2 ;;
    --alias) ALIAS="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "${INPUT}" || -z "${OUTPUT}" || -z "${LIBS_ROOT}" || -z "${APKSIGNER}" || -z "${KEYSTORE}" ]]; then
  echo "Required: --input APK --output APK --libs-root DIR --apksigner PATH --keystore PATH [--alias NAME]" >&2
  exit 2
fi
if [[ -z "${CCPLAYER_KS_PASS:-}" || -z "${CCPLAYER_KEY_PASS:-}" ]]; then
  echo "Set CCPLAYER_KS_PASS and CCPLAYER_KEY_PASS in the environment; credentials are never written to this script or its logs." >&2
  exit 2
fi
for command in zip sha256sum realpath; do command -v "${command}" >/dev/null || { echo "Missing ${command}" >&2; exit 2; }; done
[[ -f "${INPUT}" && -x "${APKSIGNER}" && -f "${KEYSTORE}" ]] || { echo "Invalid APK, apksigner, or keystore path" >&2; exit 2; }
INPUT="$(realpath "${INPUT}")"
OUTPUT="$(realpath -m "${OUTPUT}")"
LIBS_ROOT="$(realpath "${LIBS_ROOT}")"
APKSIGNER="$(realpath "${APKSIGNER}")"
KEYSTORE="$(realpath "${KEYSTORE}")"
mkdir -p "$(dirname "${OUTPUT}")"

TEMP="$(mktemp -d)"
trap 'rm -rf "${TEMP}"' EXIT
for abi in arm64-v8a x86_64; do
  for library in libavcodec.so libavformat.so libavutil.so libswresample.so libswscale.so; do
    source="${LIBS_ROOT}/${abi}/${library}"
    [[ -f "${source}" ]] || { echo "Missing replacement ${source}" >&2; exit 2; }
    mkdir -p "${TEMP}/lib/${abi}"
    cp "${source}" "${TEMP}/lib/${abi}/${library}"
  done
done
UNSIGNED="${TEMP}/relinked-unsigned.apk"
cp "${INPUT}" "${UNSIGNED}"
(
  cd "${TEMP}"
  zip -q -r "${UNSIGNED}" lib
)
SIGN_ARGS=(--ks "${KEYSTORE}" --ks-pass env:CCPLAYER_KS_PASS --key-pass env:CCPLAYER_KEY_PASS)
if [[ -n "${ALIAS}" ]]; then SIGN_ARGS+=(--ks-key-alias "${ALIAS}"); fi
"${APKSIGNER}" sign "${SIGN_ARGS[@]}" --out "${OUTPUT}" "${UNSIGNED}"
"${APKSIGNER}" verify --verbose "${OUTPUT}"
sha256sum "${OUTPUT}"
