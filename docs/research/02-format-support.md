# Format and codec support contract

Status date: **24 October 2026**. “Implemented” means a real code path exists; it does not mean every
phone sustains every resolution/frame rate. Host ASan vectors, Android compilation and physical-device
performance are separate gates.

## Input / playback matrix

| Media family | Current implementation | Important limitation |
| --- | --- | --- |
| H.264 / HEVC / VP8 / VP9 / AV1 | **Media3/MediaCodec first, clocked FFmpeg fallback** | Profile/level/HDR and sustained performance remain device-dependent. |
| APV in MP4 and raw `.apv` | **FFmpeg native decode route plus Media3 where supported** | Android hardware APV is not universal; software playback may be CPU-bound. |
| ProRes 422 Proxy/LT/422/HQ | **Native FFmpeg playback and export implemented; host vectors pass** | MOV is probed rather than assumed to be ProRes; high-resolution CPU throughput needs device QA. |
| ProRes 4444 / 4444 XQ, alpha | **Native decode/export implemented, including 10-bit encoder input / 12-bit decoded output verification** | Native preview preserves pixel alpha; ordinary H.264 export does not. |
| ProRes RAW | Decoder name is probed, but no camera-RAW development claim | ProRes RAW is not ordinary ProRes and needs camera-specific processing. |
| MotionCam **MCRAW** | **Persistent native decoder, exact frame timestamps, clocked draft playback, frame seek and chunked PCM audio** | Current 2×2 CPU demosaic/white balance is draft quality, not final camera color/GPU demosaic. |
| EXR / DPX / TIFF / PNG / JPEG sequence | **SAF folder reader, natural numeric sorting, explicit gap report, rational-fps playback and FFmpeg frame decode** | Multi-layer EXR interpretation, audio sidecars and sequence export are not complete. |
| CinemaDNG / DNG sequence | Enters the same explicit sequence route when FFmpeg can decode the frame | Not every DNG/CinemaDNG variant is a supported developed RAW image. |
| DNxHD / DNxHR / CineForm / FFV1 | **Native FFmpeg decode route; DNxHR and FFV1 export vectors also pass** | Variant/interlace/alpha coverage and storage/CPU throughput need broader tests. |
| MPEG-2, MPEG-4 Part 2, H.263, AVI/VOB/MTS | **Native demux/decode route for enabled variants** | Deinterlace, pulldown and timecode presentation still need dedicated work. |
| BRAW, R3D, CRM, ARRIRAW | Explicit vendor-SDK route; not connected | Vendor licensing, metadata and Android ABI support remain blockers. |
| Network HLS/DASH/RTSP and DRM | Not a current product claim | Local professional media is the current scope. |

## Export matrix

| Output | Current implementation | Verification / limitation |
| --- | --- | --- |
| H.264 / HEVC + AAC in MP4 | **Actual Media3 Transformer export when the device encoder is available** | Device profile/HDR capability still needs explicit validation. |
| APV 422-10 hardware | **Media3 path when reported by the device** | Android/device gate remains. |
| ProRes 422 Proxy/LT/422/HQ | **Native `prores_ks` MOV export** | Host output is re-probed and fully decoded; profile, 10-bit format, audio and frame count checked. |
| ProRes 4444 / 4444 XQ | **Native MOV export with and without alpha** | 10-bit request can legitimately decode as 12-bit; this exact promotion is checked, not hidden. |
| APV software | **OpenAPV through FFmpeg: 400-10, 422-10/12, 444-10/12, 4444-10/12** | Host variants verify profile ID, pixel format, bit depth, frames and decode; no audio in raw APV elementary output. |
| DNxHR LB/SQ/HQ/HQX/444 | **Native MOV export** | Exact profile and 8/10-bit pixel format are verified. |
| FFV1 / FFV1-alpha in Matroska | **Native export, including 16-bit and alpha variants** | FLAC/PCM options depend on the selected profile; lossless throughput is storage/CPU intensive. |
| Foreground export operation | **Foreground media-processing service, cancel, temporary-file cleanup, free-space preflight, progress/ETA and SAF copy** | A durable multi-job render queue/resume is not yet implemented. |
| PNG / TIFF / DPX / OpenEXR sequence | **Implemented: RGB/RGBA, 16-bit integer or EXR 32-bit float, numbered SAF files and SHA-256 manifest** | Host full-decode/frame-count/pixel-format checks pass; destination folder must be empty. EXR is not yet an ACES/camera-linear transform. CinemaDNG writing remains unconnected. |
| MCRAW / ProRes RAW / BRAW / R3D | **No general RAW writer claimed** | Decode access does not imply a legal/functional RAW encoder. |
| “Same as input” | Only codec copy for the explicit compatible native audio case; no universal graded transmux button | Graded/edited frames require re-encoding. |

## Capability rules

1. Probe the actual bitstream and device; never infer a codec from a container extension.
2. Show the active backend and detailed stream/color/time-base information.
3. Never silently substitute codec, profile, dimensions, bit depth, HDR mode or alpha in professional export.
4. A lower-resolution preview must remain an explicit quality choice; final export uses original source frames.
5. Unsupported variants fail with an actionable error rather than a crash, black frame or false success.
