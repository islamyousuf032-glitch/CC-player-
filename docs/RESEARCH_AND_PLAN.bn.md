# CC Player — গবেষণা ও কাজের পরিকল্পনা (সংক্ষিপ্ত বাংলা সংস্করণ)

তারিখ: ২৩ অক্টোবর ২০২৬

## আপনার চাওয়াটাকে আমি যেভাবে বুঝেছি

এটি শুধু একটি video player নয়। ভবিষ্যতের advanced Android video editor-এর এমন একটি তৈরি canvas
দরকার, যার ওপর smooth playback, MCRAW/RAW sequence, APV, ProRes, professional export এবং color
grading যুক্ত করা যাবে। মূল project proprietary/closed-source থাকবে। পরে নতুন feature বললে সেটি plan-এ
যুক্ত করা যাবে।

## গবেষণার সিদ্ধান্ত

### ১. একটি library দিয়ে সত্যিকারের “সব format” সম্ভব নয়

MOV/MP4/MKV হলো container; ProRes/H.264/APV হলো codec। একই `.mov` ফাইলে ProRes বা H.264 থাকতে পারে।
তাই extension দেখে support ঘোষণা না করে bitstream এবং device capability probe করতে হবে। কোনো codec-এর
decoder থাকলেই encoder থাকে না; RAW decode আর RAW development-ও এক কাজ নয়।

### ২. Media3 প্রধান hardware path

AndroidX Media3 Apache-2.0 এবং Android-এর জন্য সবচেয়ে যথাযথ base। এতে MediaCodec hardware playback,
GPU effects এবং Transformer export আছে। বর্তমান Media3 source-এ APV support-ও আছে। কিন্তু Transformer
মূলত device MediaCodec encoder ব্যবহার করে; তাই ProRes export-এর নাম দিয়ে H.264 output দেওয়া যাবে না।

### ৩. FFmpeg compatibility backend

ProRes, অসংখ্য container/codec এবং professional export-এর জন্য FFmpeg সবচেয়ে শক্তিশালী পরের ধাপ।
Closed-source অ্যাপে সেটি pinned source থেকে **dynamic, no-GPL, no-nonfree** build করতে হবে। GPL-enabled
x264/x265 বা অন্য GPL dependency ভুল করে ঢুকলে licensing সমস্যা হবে। FFmpeg-এর corresponding source,
notice এবং replacement ব্যবস্থা release-এর আগে বাধ্যতামূলক।

### ৪. APV

Android 16+ এবং সমর্থিত device-এ hardware APV ব্যবহার করা হবে। OpenAPV BSD-3-Clause, portable C এবং ARM
NEON optimization আছে—তাই অন্য device/profile-এর জন্য software fallback-এর ভালো candidate। বর্তমান code-এ
Media3 APV playback/export route আছে, কিন্তু encoder/decoder device-এ না থাকলে option আসবে না বা কাজ
করবে না; এই সীমা লুকানো হচ্ছে না।

### ৫. MCRAW

MCRAW হলো MotionCam Pro-এর RAW video container। আসল decoder Apache-2.0, তাই proprietary project-এর
সঙ্গে উপযুক্ত। এটি এখন pinned করে native integration করা হয়েছে: file probe, frame count/metadata এবং
draft RAW frame preview/scrubbing। কিন্তু decoder একটি তৈরি player নয়। Real-time playback-এর জন্য audio
clock, frame cache, GPU demosaic, camera color transform এবং export frame source আলাদাভাবে তৈরি করতে হবে।

### ৬. Color grading

Preview-এ এক effect আর export-এ অন্য effect দিলে রং মিলবে না। তাই বর্তমান basic linear-light matrix একই
`ColorGradeEffect` দিয়ে preview ও export-এ চলে। Professional পর্যায়ে FP16 scene-linear working space,
camera input transform, ASC CDL, lift/gamma/gain, curves, LUT, ACES/OCIO, HDR/SDR tone mapping এবং scopes
যুক্ত করার পরিকল্পনা আছে। বর্তমান temperature/tint-কে final color-science solution বলা হচ্ছে না।

### ৭. PC software ব্যবহার করা হবে কি?

Portable C/C++ library ব্যবহার করা যাবে—MotionCam decoder, OpenAPV, FFmpeg এবং সম্ভব হলে OpenColorIO।
কিন্তু CUDA/NVENC, Qt UI, DirectX, FUSE বা desktop-only renderer সরাসরি mobile-এ চলবে না। Shotcut,
Kdenlive, Olive, Graden-এর মতো GPL project থেকে proprietary app-এ code copy করা হবে না। Feature এবং
workflow তুলনা করা যাবে; implementation নিজস্ব/backend-compatible রাখতে হবে।

## এখন কী তৈরি হয়েছে

- Android Studio/Gradle-এর চার-module project: app, core media, Android media, native MCRAW।
- SAF দিয়ে local video open; কোনো broad storage বা Internet permission নেই।
- Media3 player, tuned buffer, decoder fallback, audio focus, fast scrub ও final exact seek।
- Play/pause, ±10 second, speed, repeat, fit/fill/crop এবং প্রকৃত device codec report।
- Exposure, contrast, saturation, draft temperature/tint; preview ও export-এ একই effect।
- Actual MP4 export: H.264/HEVC এবং device support থাকলে APV; progress/cancel/destination save।
- MCRAW native metadata ও draft frame preview/scrubbing।
- Rational frame rate, integer-microsecond timeline, immutable edit/undo-redo এবং unit tests।
- GitHub Actions-এ automated unit test ও debug APK build।

## পরের বড় কাজগুলোর ক্রম

1. **Baseline যাচাই:** CI ও real-device playback/export/MCRAW smoke test।
2. **Media probe/diagnostics:** প্রকৃত codec/profile/fps/bit-depth/color, dropped frame ও seek latency।
3. **FFmpeg native backend:** reproducible LGPL-safe build, source-offer artifacts এবং ProRes decode।
4. **Clocked native player:** audio master clock, bounded queue, software fallback ও common GPU frame path।
5. **Real-time MCRAW:** GPU demosaic, audio sync, frame cache, camera color ও original-quality export source।
6. **Professional export:** native ProRes, OpenAPV, DNxHR/FFV1 ধাপে ধাপে; output bitstream যাচাই।
7. **RAW/image sequence:** CinemaDNG, EXR/PNG/TIFF reader এবং sequence exporter।
8. **Professional color:** FP16, OCIO/ACES, LUT, curves, scopes এবং preview/export parity tests।
9. **Advanced editor:** multi-track, proxy, transitions, keyframes, audio, text, render cache ও autosave।
10. **Release engineering:** thermal/soak tests, ABI package, SBOM, license এবং codec patent/SDK review।

## গ্রহণযোগ্যতার নিয়ম

- কোনো format device-এ চললেই সব device-এর support ঘোষণা করা হবে না।
- Requested ProRes/APV/10-bit output silently H.264/8-bit হয়ে যাবে না।
- Preview কম resolution/proxy হলেও export original source থেকে হবে, যতক্ষণ না user স্পষ্টভাবে অন্য কিছু বেছে নেয়।
- Unsupported format-এ actionable error দেখাবে; crash বা ভুয়া success নয়।
- প্রতিটি নতুন support claim-এর সঙ্গে test media, license check এবং support matrix update বাধ্যতামূলক।

## Repository privacy

Repository-টি public অবস্থায় পাওয়া গেছে। Proprietary license যোগ করা হয়েছে এবং কোনো open-source license
দেওয়া হয়নি। তবে source গোপন রাখতে হলে repository private করতে হবে—শুধু LICENSE ফাইল public code-কে অদৃশ্য
করে না।

বিস্তারিত technical plan: `docs/ROADMAP.md`; format matrix: `docs/research/02-format-support.md`।
