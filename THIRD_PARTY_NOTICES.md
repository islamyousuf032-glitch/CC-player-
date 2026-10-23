# Third-party notices

CC Player is proprietary. The following dependencies are not CC Player-owned code and retain their
original licenses.

## Integrated in this build

| Component | Use | License | Source |
| --- | --- | --- | --- |
| MotionCam decoder, pinned at `b931ec0e2466c8217c49f13df925531b78f76e33` | Native MCRAW container decoding | Apache-2.0 | https://github.com/mirsadm/motioncam-decoder |
| MotionCam decoder bundled nlohmann/json and SIMDe headers | Decoder dependencies | MIT | Contained in the pinned upstream source archive |
| AndroidX Media3 and AndroidX libraries | Playback, effects, export, Android UI | Apache-2.0 | https://github.com/androidx/media and https://github.com/androidx/androidx |
| Kotlin / JetBrains runtime | Language runtime | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Gradle wrapper | Build bootstrap | Apache-2.0 | https://github.com/gradle/gradle |

MotionCam decoder headers carry `Copyright 2023 MotionCam`. The applicable license texts are
reproduced in `licenses/MotionCam-Decoder-LICENSE.txt`, `licenses/nlohmann-json-LICENSE.txt`, and
`licenses/SIMDe-LICENSE.txt`. The same Apache-2.0 text applies to the Apache-licensed dependencies.
The MotionCam source archive URL and SHA-256 are pinned in
`third_party/native-dependencies.lock.json` and in the CMake configuration. The application Gradle
configuration bundles these notices and license texts as APK assets; release packaging must preserve
them and any additional notices introduced by future dependencies.

## Evaluated but not linked into this build

- **OpenAPV** — BSD-3-Clause; https://github.com/AcademySoftwareFoundation/openapv
- **OpenColorIO** — BSD-3-Clause; https://github.com/AcademySoftwareFoundation/OpenColorIO
- **FFmpeg** — planned as a separately replaceable, dynamically linked, no-GPL/no-nonfree build.
  FFmpeg is LGPL-2.1-or-later in that configuration. Its corresponding source, license notices,
  replacement mechanism, and legal checklist are release blockers. Enabling GPL or nonfree options
  is forbidden for this closed-source product's default build.

## Reference-only projects

GPL-licensed editors and players may be compared at a feature/workflow level. Their implementation
code must not be copied into this proprietary source tree. In particular, Graden, Open Video Editor,
Next Player/NextLib, Shotcut, Kdenlive, Olive, and Gyroflow are not dependencies of this build.

Codec patent/licensing questions are separate from copyright licenses and require review before a
commercial release. This document is engineering guidance, not legal advice.
