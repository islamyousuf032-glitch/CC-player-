# Third-party notices

CC Player is proprietary. The following dependencies are not CC Player-owned code and retain their
original licenses.

## Integrated in this build

| Component | Use | License | Source |
| --- | --- | --- | --- |
| MotionCam decoder, pinned at `b931ec0e2466c8217c49f13df925531b78f76e33` | Native MCRAW container decoding | Apache-2.0 | https://github.com/mirsadm/motioncam-decoder |
| MotionCam decoder bundled nlohmann/json and SIMDe headers | Decoder dependencies | MIT | Contained in the pinned upstream source archive |
| FFmpeg `n8.1.3`, with the supplied OpenAPV compatibility patch | Native demux/decode, color conversion, professional encode/mux and verification | LGPL-2.1-or-later | https://github.com/FFmpeg/FFmpeg |
| OpenAPV `v1.1.3.0` at `b4ff69856b4c3e8849df431ee3db54fd535f9bf3` | Software APV encoding through `libavcodec.so` | BSD-3-Clause | https://github.com/AcademySoftwareFoundation/openapv |
| AndroidX Media3 and AndroidX libraries | Playback, effects, export, Android UI | Apache-2.0 | https://github.com/androidx/media and https://github.com/androidx/androidx |
| Kotlin / JetBrains runtime | Language runtime | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Gradle wrapper | Build bootstrap | Apache-2.0 | https://github.com/gradle/gradle |

MotionCam decoder headers carry `Copyright 2023 MotionCam`. The applicable license texts are
reproduced in `licenses/MotionCam-Decoder-LICENSE.txt`, `licenses/nlohmann-json-LICENSE.txt`, and
`licenses/SIMDe-LICENSE.txt`. The same Apache-2.0 text applies to the Apache-licensed dependencies.

FFmpeg is built only as separate, dynamically replaceable shared libraries with no GPL/nonfree
options, network protocols, or autodetected host libraries. OpenAPV is a PIC static BSD-licensed
component inside `libavcodec.so`. Their exact source URLs and SHA-256 hashes are locked in
`third_party/native-dependencies.lock.json`. `licenses/FFmpeg-LGPL-2.1.txt` and
`licenses/OpenAPV-BSD-3-Clause.txt` are bundled with the app. The APK also includes
`FFMPEG_SOURCE_OFFER.md` and `FFMPEG_RELINKING.md`; the corresponding-source/relinking bundle and
per-ABI build configuration are release requirements, not optional documentation. See
`docs/FFMPEG_SOURCE_OFFER.md` and `docs/FFMPEG_RELINKING.md`.

Android uses the NDK sysroot zlib. The separate pinned zlib downloaded by `scripts/test-ffmpeg-host.sh`
is host-test-only and is not packaged in the APK.

The application Gradle configuration bundles these notices and license texts as APK assets; release
packaging must preserve them and any additional notices introduced by future dependencies.

## Evaluated but not linked into this build

- **OpenColorIO** — BSD-3-Clause; https://github.com/AcademySoftwareFoundation/OpenColorIO

## Reference-only projects

GPL-licensed editors and players may be compared at a feature/workflow level. Their implementation
code must not be copied into this proprietary source tree. In particular, Graden, Open Video Editor,
Next Player/NextLib, Shotcut, Kdenlive, Olive, and Gyroflow are not dependencies of this build.

Codec patent/licensing questions are separate from copyright licenses and require review before a
commercial release. This document is engineering guidance, not legal advice.
