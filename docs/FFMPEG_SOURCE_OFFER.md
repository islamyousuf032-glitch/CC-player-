# Native corresponding source and build configuration

This application contains FFmpeg `n8.1.3` under LGPL-2.1-or-later and OpenAPV `v1.1.3.0` under
BSD-3-Clause. The original CC Player application remains proprietary; these third-party rights are
not restricted by that status.

## Exact source

| Component | Source archive | SHA-256 |
| --- | --- | --- |
| FFmpeg | https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n8.1.3 | `09f990289327d3ebfedf4fc15cae3f884a65dab7a5aac7bbb5b01ff83900efd9` |
| OpenAPV | https://codeload.github.com/AcademySoftwareFoundation/openapv/tar.gz/b4ff69856b4c3e8849df431ee3db54fd535f9bf3 | `56b2a82babc60623cddf24880e03b64b70aa4cfc6548746da6230829d2d965be` |

One small compatibility patch is applied to FFmpeg's `libavcodec/liboapvenc.c` for the pinned
OpenAPV metadata-container API. It is distributed as
`third_party/patches/ffmpeg-n8.1.3-openapv-1.1.3.patch` in the corresponding-source bundle.

CI publishes **`cc-player-native-source-and-relink`** beside the APK. It contains the exact archives,
SHA-256 lock file, patch, component allowlist, build scripts, per-ABI configure commands/config.h,
license texts, and the library-replacement guide. Any non-CI binary distribution must make this same
corresponding-source bundle available with the binary and retain these notices. Do not treat a link
to upstream `main` as corresponding source: only the pinned archive plus the supplied patch is exact.

FFmpeg is linked as separate replaceable shared libraries. OpenAPV is a PIC static BSD-licensed
component inside `libavcodec.so`; replacing that FFmpeg library also replaces the bundled OpenAPV
code. The build intentionally has no GPL/nonfree options, network protocols, or autodetected host
libraries. Patent and codec licensing review is separate from copyright compliance.
