# Implementation roadmap

This plan is intentionally incremental. Each phase has an observable acceptance gate; “all formats”
is expanded through the support matrix rather than marked done by adding a dependency.

## Phase 0 — Research and product constraints ✅

- [x] Identify MCRAW as MotionCam RAW and locate its original decoder.
- [x] Compare Media3, FFmpeg, OpenAPV, OpenColorIO, mpv and desktop editor/player options.
- [x] Separate codec, container, RAW development, color and export responsibilities.
- [x] Establish proprietary-source and GPL/LGPL constraints.
- [x] Choose a hybrid hardware-first/native-fallback architecture.

**Gate:** ADR, research notes, format matrix and licensing policy exist.

## Phase 1 — Working Android canvas ✅ (implementation present; device QA continues)

- [x] Multi-module Android project, Kotlin, Compose, version catalog and Gradle wrapper.
- [x] Local SAF file import without broad storage permission.
- [x] Surface-based playback boundary and Media3 implementation.
- [x] Decoder fallback enabled, tuned buffering, audio focus and noisy-output handling.
- [x] Frequent scrubbing mode plus exact final seek.
- [x] Play/pause, ±10 seconds, speed, repeat, fit/fill/crop and runtime codec report.
- [x] Immutable timeline/time-base/color/export models and unit tests.
- [x] Shared linear-light grade in preview and actual Transformer export.
- [x] H.264/HEVC export; APV export offered only if the device reports an encoder.
- [x] Native pinned MCRAW integration: probe, metadata and draft frame preview/scrubbing.
- [x] CI unit-test/APK job and native arm64/x86_64 compilation.

**Gate:** CI is green, followed by basic playback/export and MCRAW smoke tests on physical devices.
The MCRAW draft preview is not an acceptance claim for real-time playback.

## Phase 2 — Playback hardening and native compatibility backend (P0, next)

### 2A. Platform playback quality

- [ ] Persist recent files, resume position and user playback preferences.
- [ ] Embedded/external subtitle and audio-track selection UI.
- [ ] Track/bitstream probe screen: codec, profile, chroma, bit depth, color, fps and timecode.
- [ ] Rotation, VFR, interlaced and malformed-input test corpus.
- [ ] Frame-drop, decoder, memory and seek-latency telemetry behind a debug diagnostics panel.
- [ ] Background/media-session behavior only if independently required; editor preview remains the
      default scope.

### 2B. LGPL-safe FFmpeg build

- [ ] Pin FFmpeg source and checksums; create reproducible NDK builds for arm64-v8a/x86_64.
- [ ] Dynamic shared libraries, no `--enable-gpl`, no `--enable-nonfree`, no accidental GPL encoders.
- [ ] Generate corresponding-source archive, configuration log, license notices and replacement
      mechanism as CI artifacts.
- [ ] Start with demux/decode/mux capabilities needed by ProRes and common professional sources;
      keep binary size controlled with an explicit allowlist rather than an unreviewed “everything” build.
- [ ] Fuzz demux/parser entry points and enforce allocation/time limits.

### 2C. Native playback engine

- [ ] Implement `PlaybackEngine` with the same surface/control contract as Media3.
- [ ] Bounded packet/frame queues, audio master clock, exact seek and cancellation.
- [ ] MediaCodec hardware decode where the FFmpeg route can use it cleanly; CPU software fallback.
- [ ] GPU texture upload without per-frame Kotlin/CPU copies where possible.
- [ ] Automatic engine selection after bitstream probe, with manual debug override and visible active
      backend.
- [ ] ProRes 422/4444 playback, then older/interlaced formats and additional audio decoders.

**Gate:** identical control behavior across engines; ProRes vectors play and seek correctly; no
unbounded memory; a failed hardware path reaches software fallback without a black screen.

## Phase 3 — Real-time MCRAW and sequence sources (P0)

- [ ] Turn the current decoder into a clocked `RawFrameSource` with frame-index seeking.
- [ ] Add MCRAW PCM audio loading/mixing and preserve audio/video timestamps.
- [ ] Implement GPU bilinear first, then higher-quality selectable demosaic (for example MHC after
      correctness/performance comparison).
- [ ] Honor black/white levels, CFA layout, crop/active area, orientation and per-frame metadata.
- [ ] Apply camera matrices/white balance in an explicit linear working space.
- [ ] Byte-bounded read-ahead/frame cache, preview-resolution control and adaptive proxy mode.
- [ ] Preserve gyro/accelerometer timestamps for a later stabilization module.
- [ ] Add CinemaDNG and EXR/PNG sequence readers with frame-rate, sorting and gap policies.

**Gate:** named-device MCRAW playback report includes full pipeline timing, dropped frames and memory;
frame stepping is exact; audio remains synchronized; export can render from original RAW frames.

## Phase 4 — Professional export pipeline (P0)

- [ ] Extract a common `ExportEngine` contract and persist a real export queue.
- [ ] Foreground execution, cancel/recovery, free-space preflight, progress/ETA and error diagnostics.
- [ ] Native frame-graph-to-FFmpeg/OpenAPV encoder bridge with backpressure.
- [ ] ProRes 422 profiles in MOV, then ProRes 4444/4444 XQ with alpha verification.
- [ ] APV software profiles and hardware/software selection based on actual capabilities.
- [ ] DNxHR and FFV1 after their own bitstream/license/performance gates.
- [ ] Explicit chroma, bit depth, bitrate/QP, GOP, frame rate and color metadata controls.
- [ ] PNG/TIFF/EXR/CinemaDNG sequence sinks with naming, compression and storage checks.
- [ ] Optional professional interchange manifest and checksums.
- [ ] Output re-probe and preview/export frame parity checks before reporting success.

**Gate:** a requested profile is verified in the resulting bitstream. Any unsupported setting fails
before or during export with an actionable error—never a silent H.264/resolution/HDR substitution.

## Phase 5 — Professional color page (P1)

- [ ] FP16 scene-linear GPU working frames and explicit input/output color metadata.
- [ ] Camera input transforms and white balance; no double gamma or accidental display-LUT input.
- [ ] Exposure, temperature/tint with documented chromatic adaptation, contrast/pivot and mid-detail.
- [ ] Lift/gamma/gain/offset, ASC CDL, RGB curves and HSL qualifiers.
- [ ] `.cube` LUT import with size/domain validation; evaluate OCIO/CLF/ACES integration.
- [ ] HDR10/HLG/PQ and SDR tone/gamut mapping with test frames.
- [ ] Histogram, waveform, RGB parade and vectorscope computed from declared signal stages.
- [ ] Split before/after, still grab and per-clip/shared grades.
- [ ] CPU/GPU and preview/export parity tolerances checked on real devices.

**Gate:** reference frames retain the expected transform order, metadata and numerical tolerance;
export is not dependent on a screenshot or display-only effect.

## Phase 6 — Non-linear editor on top of the canvas (P1)

- [ ] Versioned project persistence and autosave/migration.
- [ ] Multi-track timeline, trim, split, ripple, slip/slide, snapping and magnetic selection.
- [ ] Undo/redo using the existing immutable edit model; grouped operations and gesture transactions.
- [ ] Proxy/optimized-media generation and original-source relinking.
- [ ] Transform/crop, speed, reverse (with resource limits), transitions and compositing.
- [ ] Audio lanes, waveforms, gain/fades/keyframes and loudness metering.
- [ ] Text/overlay/subtitle layers and keyframed effects.
- [ ] Background render cache with dependency invalidation rather than full-timeline rerenders.

**Gate:** long timelines remain responsive, edit operations preserve A/V timing and projects reopen
without losing source/color/export semantics.

## Phase 7 — Performance, release and extensibility (continuous)

- [ ] Baseline Profiles, Macrobenchmark and release-native/JNI smoke tests.
- [ ] ABI splits/App Bundle, native binary-size reporting and 16 KiB page validation.
- [ ] Thermal and long-export soak tests; memory-pressure and low-storage recovery.
- [ ] Third-party SBOM, pinned checksums, license/source-offer artifacts and security updates.
- [ ] Codec patent/SDK/trademark review for each commercial distribution region.
- [ ] Documented plugin boundaries for new decoders, RAW developers, effects and encoders.

## Next implementation batch (ordered)

1. Run CI/device smoke tests and fix the Phase 1 baseline.
2. Add detailed media probe and playback diagnostics.
3. Produce the reproducible LGPL FFmpeg native build and compliance artifacts.
4. Implement native demux/decode with a clocked ProRes playback proof of concept.
5. Connect software frames to the shared GPU effect path and enable automatic engine routing.
6. Convert MCRAW draft decoding into the clocked GPU RAW source.
7. Add native ProRes export through the same graph.
8. Add OpenAPV software export and image-sequence source/sink modules.

## Change protocol

If a new feature is requested, classify it as **source**, **clock/time**, **frame graph**, **color**,
**audio**, **export**, **project/UI**, or **distribution**. Update only the relevant phase and ADR;
do not fork the architecture or hide the new work inside an unrelated codec module. Any changed
support claim must update `docs/research/02-format-support.md` in the same commit.
