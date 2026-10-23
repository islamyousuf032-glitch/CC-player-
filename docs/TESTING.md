# Verification and performance plan

## Automated gates

Current CI runs on every push to the working branch:

```sh
./gradlew :core:media:testDebugUnitTest :app:assembleDebug
```

The build compiles the native MCRAW decoder for `arm64-v8a` and `x86_64`, runs core tests and creates
an installable debug APK. The following gates should be added with their respective implementation
phases, not replaced by screenshot-only tests:

- Android lint and release minification/JNI smoke test.
- Kotlin timeline/property tests and timestamp round trips.
- Native decoder fuzzing with malformed/truncated inputs and bounded allocation.
- Bitstream conformance vectors for each advertised codec/profile.
- Golden-frame comparisons for demosaic, color transforms, crop and compositing.
- CPU-versus-GPU color parity and preview-versus-export frame parity.
- Export ffprobe validation: actual codec, profile, container, dimensions, frame rate, duration,
  audio, color metadata, alpha and fast-start flags.
- Process death, URI permission renewal, cancellation and destination-failure tests.

## Media corpus

Keep large test media outside Git. Store only a manifest with source, license, SHA-256 and expected
properties. Include:

- H.264/HEVC/VP9/AV1: 8/10-bit, SDR/HDR, VFR, B-frames, long GOP, rotation and unusual aspect ratios.
- APV MP4 and raw APV elementary streams across supported profiles/chroma formats.
- ProRes 422 variants and 4444 with alpha; ProRes RAW separately.
- MCRAW with/without audio, gyro and accelerometer; current and legacy compression; corrupted index.
- CinemaDNG/EXR/DPX/PNG sequences with gaps, padding, varying frame rates and large frames.
- Interlaced MPEG-2/H.264, telecine, timecode and variable-frame-rate phone footage.
- Multichannel PCM/AAC/FLAC/ALAC/Opus and audio/video offset cases.

Do not redistribute camera manufacturers' sample footage unless its license allows it.

## Device lanes

| Lane | Purpose |
| --- | --- |
| API 29 / 31 arm64 mid-range | Minimum-SDK behavior, memory pressure and software fallback |
| API 33 / 34 arm64 flagship | HDR, 10-bit and modern MediaCodec behavior |
| API 35 / 36 arm64 flagship | 16 KiB pages, thermal behavior and APV hardware where present |
| API 37 current reference | Build and platform compatibility |
| x86_64 emulator | UI and non-hardware-native smoke tests only; not a performance claim |
| At least one Samsung and one non-Samsung flagship | Vendor codec, storage and color/HDR differences |

## Performance budgets (targets, not current claims)

Measure on named devices with release builds and real media:

- No unbounded frame queues; cache memory stays within a declared byte budget.
- Hardware 1080p/4K playback: no visible stalls in a 60-second run; report dropped frames and audio
  underruns rather than hiding them.
- Scrubbing: report first-frame latency and P50/P95 seek latency by codec/GOP.
- MCRAW: report decode, demosaic, color, upload and total frame time separately. A draft still-frame
  preview must not be presented as meeting a real-time target.
- Export: report source fps versus render fps, elapsed time, peak memory, storage free space and
  thermal state. Verify that proxy preview never reduces final quality unintentionally.
- Thirty-minute soak test for playback and a large-file export test across low-storage and
  low-battery conditions.

## Release acceptance

An advertised format is enabled only when its decode/encode path, failure behavior, license gate,
metadata behavior and test vectors are present. “Opens on one phone” is not enough to mark a format
as universally supported.
