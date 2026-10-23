# CC Player architecture

## Current modules

```text
:app
  Compose UI + ViewModel + Storage Access Framework
       |                 |                    |
       v                 v                    v
:core:media       :media:android         :media:mcraw
  time/rationals     PlaybackEngine          MotionCam JNI decoder
  immutable edits    Media3 implementation   metadata + draft frames
  color model        codec inspection
  export profiles    shared RGB effect
                     Media3 export
```

The app has no broad storage permission and no Internet permission. Media is read through
user-granted URIs. MCRAW native code receives a duplicated file descriptor, so Android storage
ownership stays in Kotlin while native code can seek within the file.

## Target media graph

```text
                               +------------------+
Source URI / sequence -------->| Source probe     |
                               +--------+---------+
                                        |
                       +----------------+----------------+
                       |                                 |
                +------v------+                   +------v-------+
                | Demuxer     |                   | RAW container|
                | Media3/FFmpeg|                  | MCRAW/vendor |
                +------+------+                   +------+-------+
                       |                                 |
                +------v------+                   +------v-------+
                | Compressed  |                   | Sensor frame |
                | decoder     |                   | development  |
                | HW / SW     |                   | + demosaic   |
                +------+------+                   +------+-------+
                       |                                 |
                       +----------------+---------------+
                                        |
                              +---------v---------+
                              | Input color transform
                              | linear FP16 working frames
                              +---------+---------+
                                        |
                              +---------v---------+
                              | Shared frame graph |
                              | grade / transform  |
                              | layers / effects   |
                              +----+-----------+---+
                                   |           |
                            +------v-----+  +---v------------+
                            | Preview    |  | Export encoder |
                            | GPU surface|  | HW / SW + muxer|
                            +------------+  +----------------+

Audio: demux -> decode -> resample/mix -> master clock -> AudioTrack / export encoder
```

## Non-negotiable design rules

### 1. One clock, one time base

Integer microseconds are used for timeline edits. Frame rates remain rational (for example,
30000/1001). The audio clock drives normal playback; video frames are scheduled against it. Export
uses deterministic source timestamps rather than wall-clock playback timing.

### 2. Preview and export share semantics

A grade/effect is data plus a renderer contract. The current `ColorGradeEffect` already runs in both
ExoPlayer and Transformer. Future compositing, crop, LUT and tone mapping must have the same
preview/export ordering and color-space interpretation.

### 3. Zero-copy where it matters

- MediaCodec decodes to a `Surface` for platform playback.
- The target graph uses GPU textures / `AHardwareBuffer` and bounded pools.
- CPU readback is only for scopes, thumbnails, software encoders or an explicitly selected fallback.
- The draft MCRAW CPU preview is an onboarding/diagnostic path, not the performance architecture for
  final real-time RAW playback.

### 4. Bounded work and backpressure

Decoder, demosaic, color, compositor and encoder queues must have fixed limits. A slow encoder or
storage device applies backpressure rather than growing memory until Android kills the process.
Caches use byte budgets, not just frame counts, because 8-bit 1080p and 16-bit 8K frames differ
orders of magnitude in size.

### 5. Capability negotiation is explicit

A source request is matched with actual bitstream properties and runtime codec features. A request
for ProRes 4444, 10-bit HEVC or APV cannot silently become H.264. The user sees whether a hardware or
software path is active and whether preview quality differs from export quality.

### 6. Main thread never performs media I/O or decoding

Kotlin coroutines and native worker threads own I/O, decoding and rendering. UI receives immutable
snapshots. Export progress and cancellation come from the engine, not a fake timer.

## Color pipeline direction

The implemented matrix is a small **linear-light basic grade**. It preserves HDR values instead of
clamping them, but it is not a complete professional color-management system.

The professional stage must define, in order:

1. RAW black/white level, lens shading and white balance where applicable.
2. High-quality demosaic for sensor RAW.
3. Camera/input gamut and transfer interpretation.
4. Scene-linear working space (FP16 minimum for the GPU path).
5. Primary grade, ASC CDL, curves, lift/gamma/gain/offset and looks.
6. Gamut mapping and HDR/SDR output transforms.
7. Output encoding metadata and verification.

OpenColorIO/ACES and `.cube`/CLF support should be compiled into mobile-friendly GPU operations or
LUTs where possible. A display LUT must never be mistaken for a camera input transform.

## Export lifecycle direction

The current single-clip export is foreground and temporary-file based, then copies to a SAF URI.
The production queue needs:

- foreground service / WorkManager coordination appropriate to the export duration;
- persisted job state, cancel and recovery;
- free-space and thermal checks;
- original-source rendering even when a proxy was used for preview;
- output bitstream verification (codec/profile/container/color/fast-start where requested);
- checksums and a manifest for professional interchange;
- no stale temporary file or empty destination presented as success.

## Change management

New user features should be added behind the existing source, frame-graph, playback or export
contracts. Record a new ADR only when a decision changes these boundaries. Keep the support matrix
and roadmap status truthful in the same change.
