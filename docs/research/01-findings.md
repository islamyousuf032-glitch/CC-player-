# Media engine research — 23 October 2026

## Requirement interpretation

The requested foundation is for a **proprietary, highly advanced Android video editor**, not just a
gallery player. It must grow in four directions without being rewritten:

1. Smooth playback across common, professional and camera-RAW media.
2. MCRAW, image/RAW sequences, APV and ProRes support.
3. Export through a similarly extensible codec/container pipeline.
4. Color grading that is identical in preview and final export.

“Every format” cannot be a truthful one-time checkbox. Android devices differ in decoder, encoder,
HDR, bit depth, thermal and storage capabilities; some camera RAW formats require vendor SDKs; and
some formats have decoders but no legal/practical encoder. The product should instead promise an
**extensible engine with runtime capability reporting and no silent substitution**.

## The most important technical distinctions

- A **container** (MOV, MP4, MKV, MXF) is not a **codec** (ProRes, H.264, APV). A `.mov` extension
  cannot tell us whether Media3 can play the file.
- **Demuxing**, **decoding**, **RAW development**, **color transformation**, **encoding**, and
  **muxing** are separate stages. Supporting one stage says nothing about the others.
- ProRes and ProRes RAW are different. MCRAW and a CinemaDNG sequence are also different from an
  already debayered RGB video.
- A desktop decoder may be portable while its renderer is not. CUDA, NVENC, DirectX, FUSE and Qt UI
  code do not become mobile-compatible merely because the project builds on a PC.
- Preview and export must consume the same edit/color graph. Running unrelated FFmpeg commands for
  export while previewing through an Android player would create color and framing mismatches.

## Evaluated foundations

| Project | License / mobile relevance | Decision |
| --- | --- | --- |
| [AndroidX Media3](https://github.com/androidx/media) | Apache-2.0; native Android playback, effects and Transformer pipeline. Current source also contains APV MIME/extractor support. | **Primary platform path.** Use MediaCodec hardware first, GPU effects and device capability queries. It is not the universal software decoder. |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | LGPL in a no-GPL configuration; broad demux/decode/encode/mux support. APV, ProRes and ProRes RAW support make it the strongest compatibility candidate. | **Next native backend**, built from a pinned source as replaceable shared libraries without `--enable-gpl` or `--enable-nonfree`. Do not use it as a command-line-only editor. |
| [OpenAPV](https://github.com/AcademySoftwareFoundation/openapv) | BSD-3-Clause; portable C, ARM NEON optimization, encoder/decoder and tiled processing. | **APV software option and performance reference.** Prefer Android 16 hardware APV when available; retain software fallback for other devices/profiles. |
| [MotionCam decoder](https://github.com/mirsadm/motioncam-decoder) | Apache-2.0; portable C++17 MCRAW container and RAW frame decoder. No real-time player or general MCRAW writer. | **Integrated now** at a pinned commit for metadata and draft frame development. Build a proper clocked RAW frame source next. |
| [OpenColorIO](https://github.com/AcademySoftwareFoundation/OpenColorIO) | BSD-3-Clause; professional color transforms, LUT formats, CPU/GPU processors and ACES workflows. | **Professional color reference/candidate.** A mobile dependency and shader-size audit is required before bundling. Basic linear-light controls are implemented independently now. |
| [mpv](https://github.com/mpv-player/mpv) / [mpv-android](https://github.com/mpv-android/mpv-android) | Strong desktop/Android playback and shader workflow; Android wrapper and native dependencies have separate licenses. | Useful as a **playback diagnostic/reference**, not the main editor engine. Its internal player pipeline is not our export/edit graph. |
| [MLT](https://github.com/mltframework/mlt), Shotcut, Kdenlive, Olive | Mature desktop editing ideas; MLT is LGPL, the named applications are GPL. Qt/desktop rendering and plugin assumptions need substantial mobile adaptation. | Study timeline/workflow concepts only. Do not fork the applications for this closed-source Android product. |
| [Graden](https://github.com/Pixelgamer4k/graden) | Android, MCRAW, color and multi-layer features; GPL-3.0. | **Feature benchmark only. No source copying.** Its existence does not make its code compatible with a proprietary app. |
| [Open Video Editor](https://github.com/devhyper/open-video-editor) | Media3/Compose editor; GPL-3.0. | Reference for scope comparison, not a code base for this product. |
| [LibreCuts](https://github.com/tharunbirla/LibreCuts) | MIT; active Android editor with hardware export. | Useful comparison for straightforward mobile workflows, but it does not remove the need for our native professional pipeline. |
| [MCRAW Studio](https://github.com/ACES2065-1/MCRAW_Studio) | Apache-2.0 desktop C++ MCRAW/ACES/transcode workflows. | Relevant architecture reference. CUDA/NVENC/desktop-specific paths must not be ported blindly. |
| [LibRaw](https://github.com/LibRaw/LibRaw) / [RawSpeed](https://github.com/darktable-org/rawspeed) | Still-camera RAW decoding; licenses and supported camera sets need individual review. | Evaluate only for still DNG/image-sequence ingestion. They do not solve MCRAW, BRAW or video playback by themselves. |

## Why a hybrid engine was selected

A single backend cannot provide the best result in every stage:

- Media3/MediaCodec gives low-power, low-copy hardware playback and hardware encoding.
- FFmpeg supplies broad container/codec compatibility and professional software encoders.
- OpenAPV supplies a focused APV implementation with ARM optimization.
- The MotionCam decoder supplies MCRAW bytes and metadata, but our engine must supply demosaic,
  color management, audio, caching, seeking and presentation.
- OpenGL ES/Vulkan provides the common frame-processing surface for grading, compositing and later
  timeline effects.

The architecture therefore routes sources to different decoders but brings decoded frames into one
explicit render/export contract. It must not route a ProRes export request to H.264 merely because
that is the only encoder available.

## Closed-source constraints

1. **No GPL application fork or copied GPL implementation code.** Reading public feature lists is
   not a license to copy code.
2. FFmpeg's default build must be LGPL-only, dynamically linked and replaceable. The exact source,
   build recipe, notices and corresponding-source offer are release artifacts, not optional docs.
3. GPL external encoders such as typical x264/x265 builds must not be pulled in accidentally. Use
   device MediaCodec for H.264/HEVC and review each additional encoder.
4. Open-source copyright compliance does not resolve codec patents, trademarks, camera SDK terms or
   commercial distribution rights. These need a separate release review.
5. This repository was public when the foundation was created. A proprietary license prevents an
   open-source grant, but it does not make source invisible. Make the repository private if source
   confidentiality is required.

## Research output applied to the code

- A surface-based `PlaybackEngine` boundary keeps Compose independent of Media3 and a future native
  player.
- `ColorGradeEffect` is shared by ExoPlayer and Transformer rather than duplicated as preview-only
  UI math.
- Export has an explicit backend/profile model and rejects ProRes through Media3.
- Device codec support is queried from `MediaCodecList`, not inferred only from Android version.
- MCRAW is compiled from a pinned, checksummed Apache-2.0 source archive and clearly labelled as a
  draft preview until a real-time color-managed renderer exists.
- The edit core uses integer microseconds and rational frame rates to avoid floating-point timeline
  drift.

## Sources for platform capabilities

- [Android supported media formats](https://developer.android.com/media/platform/supported-formats)
- [Android 16 APV feature](https://developer.android.com/about/versions/16/features)
- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [Media3 Transformer supported formats](https://developer.android.com/media/media3/transformer/supported-formats)
- [Media3 Transformer customization](https://developer.android.com/media/media3/transformer/customization)
- [FFmpeg legal checklist](https://www.ffmpeg.org/legal.html)
