# CC Player

Advanced Android video editor-এর জন্য একটি proprietary media-engine canvas। এটি এখন early foundation;
“সব ফরম্যাট চলে/এক্সপোর্ট হয়” এমন অবাস্তব দাবি নয়—hardware-first playback, native compatibility,
professional export এবং shared color pipeline ধাপে ধাপে যুক্ত করার কার্যকর ভিত্তি তৈরি করা হয়েছে।

## বর্তমান সংস্করণে যা আছে

- Kotlin + Jetpack Compose-এর local video/MCRAW import UI; Storage Access Framework, broad storage
  permission নেই।
- Media3/ExoPlayer playback, decoder fallback, buffer tuning, audio focus, fast scrubbing এবং final
  exact seek।
- Play/pause, ±10 সেকেন্ড, speed, repeat, resize এবং real device codec capability report।
- Basic linear-light grade; **একই effect preview এবং export-এ** ব্যবহৃত হয়।
- Actual H.264/HEVC MP4 export; device encoder থাকলে APV option। Progress, cancel এবং SAF destination।
- Original Apache-2.0 MotionCam decoder-এর pinned native integration: MCRAW metadata এবং **draft**
  RAW frame preview/scrubbing।
- Integer-microsecond edit model, rational frame rate, undo/redo এবং unit tests।
- GitHub Actions-এ unit test ও debug APK build।

## এখনও যা নেই

Real-time MCRAW playback/audio, FFmpeg/ProRes playback/export, software APV fallback, professional
OCIO/ACES color, image-sequence playback/export এবং multi-track editing এখনও connected নয়। এগুলোর
জন্য interface ও পর্যায়ক্রমিক plan আছে; UI-তে এগুলোকে implemented বলে দেখানো হয়নি।

## শুরু করুন

প্রয়োজন: current Android Studio, JDK 17, Android SDK 37, NDK `28.2.13676358`, CMake `3.31.6`।

```bash
./gradlew :app:assembleDebug
./gradlew :core:media:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Native MCRAW source configure/build-এর সময় pinned GitHub archive থেকে আসে; URL ও SHA-256
`third_party/native-dependencies.lock.json`-এ আছে। Android 10 (API 29) এবং 64-bit `arm64-v8a`/`x86_64`
target করা হয়েছে। Physical-device performance ও codec support আলাদা করে পরীক্ষা করতে হবে।

## নথিপত্র

- [বাংলা গবেষণা ও পরিকল্পনা](docs/RESEARCH_AND_PLAN.bn.md)
- [বিস্তারিত roadmap](docs/ROADMAP.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Research findings](docs/research/01-findings.md)
- [সত্যিকারের format support matrix](docs/research/02-format-support.md)
- [Testing/performance plan](docs/TESTING.md)
- [Hybrid architecture ADR](docs/adr/0001-hybrid-media-architecture.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## Proprietary / privacy

মূল CC Player code-এর জন্য open-source license দেওয়া হয়নি; দেখুন [LICENSE.md](LICENSE.md)। তৃতীয়-পক্ষ
dependency-গুলোর নিজস্ব license প্রযোজ্য। Repository-টি public থাকলে source সবাই দেখতে পারে—closed-source
confidentiality দরকার হলে repository private করুন। Public/private status বদলানো এই implementation-এর
অংশ নয়।
