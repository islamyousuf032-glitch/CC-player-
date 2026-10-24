# CC Player

Advanced Android video player/editor-এর জন্য একটি proprietary media-engine canvas। Implementation-এ
hardware-first playback, native FFmpeg compatibility, professional export, RAW/sequence sources এবং
shared color/edit models একই project-এ যুক্ত হচ্ছে। প্রতিটি codec-এর resolution/frame-rate/device
performance আলাদা verification gate; “সব ফরম্যাট সব ফোনে চলে” দাবি নয়।

## বর্তমান source implementation

- Kotlin + Jetpack Compose, Storage Access Framework; broad storage permission বা cloud account নেই।
- Media3/ExoPlayer hardware-first playback এবং একই control contract-এর **clocked native FFmpeg
  fallback**; Surface video, AudioTrack PCM, exact seek, speed, repeat, fit/fill/crop।
- Native ProRes/DNxHR/CineForm/FFV1/APV এবং enabled older-format demux/decode; detailed codec,
  profile, chroma, bit depth, color, fps, time-base ও stream probe।
- Persistent native MCRAW session: exact frame-index/timestamp, clocked draft playback, PCM audio,
  one-frame read-ahead এবং drop telemetry। Current CPU demosaic draft quality।
- SAF EXR/DPX/PNG/TIFF/JPEG image-sequence folder reader, natural numeric sorting, gap report এবং
  rational-fps playback।
- Recent files, resume position এবং local playback preferences।
- Basic linear-light grade preview/export-এ shared। Actual H.264/HEVC MP4 এবং supported device-এ
  hardware APV export।
- Verified native ProRes (422 ও 4444 family), DNxHR, FFV1/alpha এবং software OpenAPV
  400/422/444/4444 10/12-bit export; output re-probe/full decode, cancellation ও cleanup।
- Foreground media-processing export, free-space preflight, progress/ETA এবং SAF destination copy।
- Verified PNG/TIFF/DPX/OpenEXR RGB/RGBA sequence export, numbered-file cleanup এবং per-frame
  SHA-256 JSON manifest; EXR float sink-কে full ACES/scene-linear color management বলা হচ্ছে না।
- Integer-microsecond multi-track edit model, rational clip speed, split/trim/ripple/slip/slide/snap,
  undo/redo এবং versioned project JSON।
- Reproducible pinned FFmpeg/OpenAPV builds, host ASan integration matrix, Android CI, license/source
  offer ও replaceable-library documentation।

## এখনও সম্পূর্ণ নয়

High-quality GPU MCRAW demosaic/camera transforms, professional OCIO/ACES/LUT/scopes, CinemaDNG export,
multi-track compositing UI/render/export, durable multi-job export queue এবং broad physical-device
performance/interlace/audio-track QA এখনও বাকি। Existing interfaces ও roadmap flexible; নতুন feature
একই source/clock/frame-graph/export boundaries-তে যুক্ত হবে।

## শুরু করুন

প্রয়োজন: current Android Studio, JDK 17, Android SDK 37, NDK `28.2.13676358`, CMake `3.31.6`।

```bash
./gradlew :app:assembleDebug
./gradlew :core:media:testDebugUnitTest
bash scripts/test-ffmpeg-host.sh /tmp/ccplayer-host-test 4
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

MCRAW ও FFmpeg/OpenAPV source configure/build-এর সময় pinned, SHA-verified GitHub archives থেকে আসে;
URL/hash `third_party/native-dependencies.lock.json`-এ আছে। Android 10 (API 29) এবং 64-bit
`arm64-v8a`/`x86_64` target করা হয়েছে। Physical-device performance ও codec support আলাদা করে পরীক্ষা
করতে হবে।

## নথিপত্র

- [বাংলা গবেষণা ও পরিকল্পনা](docs/RESEARCH_AND_PLAN.bn.md)
- [Flexible roadmap](docs/ROADMAP.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Research findings](docs/research/01-findings.md)
- [সত্যিকারের format support matrix](docs/research/02-format-support.md)
- [Testing/performance plan](docs/TESTING.md)
- [Hybrid architecture ADR](docs/adr/0001-hybrid-media-architecture.md)
- [FFmpeg corresponding source](docs/FFMPEG_SOURCE_OFFER.md) ও [relinking](docs/FFMPEG_RELINKING.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Proprietary / privacy

মূল CC Player code-এর জন্য open-source license দেওয়া হয়নি; দেখুন [LICENSE.md](LICENSE.md)। তৃতীয়-পক্ষ
dependency-গুলোর নিজস্ব license প্রযোজ্য। Repository public থাকলে source সবাই দেখতে পারে—closed-source
confidentiality দরকার হলে repository private করুন। Public/private status বদলানো এই implementation-এর
অংশ নয়।
