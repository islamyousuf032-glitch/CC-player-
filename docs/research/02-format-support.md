# Format and codec support contract

Status date: **23 October 2026**. “Implemented” means a code path exists in this repository; it does
not mean every phone supports every profile, resolution or frame rate. Runtime probing and device
tests remain mandatory.

## Input / playback matrix

| Media family | Current foundation | Target route | Important limitation |
| --- | --- | --- | --- |
| H.264 / AVC | **Implemented through Media3** | MediaCodec first | Profile/level and resolution still depend on device. |
| H.265 / HEVC, including HDR where supported | **Implemented through Media3** | MediaCodec first; FFmpeg fallback for software-decodable variants | HDR display and 10-bit decoder support are separate capabilities. |
| VP8 / VP9 / AV1 | **Implemented through Media3** | MediaCodec first; native fallback where built | AV1 hardware is not universal; high-bit-depth software playback may be slow. |
| APV in MP4 | **Media3 path implemented** | Android 16+ hardware; OpenAPV/FFmpeg fallback | Current Media3 path still needs an actual device decoder. Raw `.apv` elementary streams need native demux handling. |
| ProRes 422 Proxy/LT/422/HQ | Honest unsupported error in this build | FFmpeg software decode -> common frame graph | MOV container alone is not enough to identify ProRes. |
| ProRes 4444 / 4444 XQ, alpha | Honest unsupported error in this build | FFmpeg decode -> alpha-capable GPU frame | Alpha must survive preview, compositing and export; ordinary H.264 export drops it. |
| ProRes RAW | Not connected | FFmpeg decoder evaluation -> camera-specific RAW development | ProRes RAW is not ordinary ProRes. Decoding does not imply a raw encoder or correct color without metadata. |
| MotionCam **MCRAW** | **Native probe + draft frame preview/scrubbing implemented; no real-time playback claim** | MotionCam decoder -> high-quality demosaic -> color-managed clocked renderer | Audio, gyro, sensor metadata and frame timestamps must remain synchronized. |
| CinemaDNG / DNG sequence | Not connected | Sequence reader + DNG decoder + RAW development | Requires frame ordering, frame rate, missing-frame policy, orientation and camera color metadata. |
| EXR / DPX / TIFF / PNG sequence | Not connected | Sequence source -> half/float or integer frame conversion | A single image is not automatically a sequence. EXR can be scene-linear and multi-layer. |
| DNxHD / DNxHR | Not connected | FFmpeg decode -> common frame graph | Profile, bit depth and alpha need test vectors. |
| CineForm | Not connected | FFmpeg/native decoder evaluation | Do not assume every variant is supported because the brand name is recognized. |
| FFV1 / lossless intermediates | Not connected | FFmpeg decode | High throughput storage and CPU are often the bottleneck. |
| H.266 / VVC and emerging codecs | Runtime-dependent | MediaCodec where present; current FFmpeg capability probe | No universal claim. |
| MPEG-2, MPEG-4 Part 2, H.263, older AVI/VOB/MTS families | Platform attempt for recognized containers; native route not connected yet | FFmpeg demux/decode + deinterlace + pulldown/field handling | Playback must handle interlacing and timecodes, not only decode frames. |
| BRAW, RED R3D, Canon CRM/RAW, ARRIRAW and other camera RAW | Explicit vendor-SDK route; not connected | Vendor SDK or legally compatible decoder, then camera-specific development | No open-source “FFmpeg supports everything” assumption. SDK redistribution and phone ABI support are release blockers. |
| Network HLS/DASH/RTSP | Not a product goal in this foundation | Media3 can be added separately | Local professional editing and streaming have different buffering/export requirements. |
| DRM-protected media | Not promised | MediaDrm only if a later product requirement justifies it | Offline/export rights and protected surfaces are separate from decoding. |

## Export matrix

| Output | Current foundation | Target route / notes |
| --- | --- | --- |
| H.264 + AAC in MP4 | **Implemented with actual Transformer export** | Device encoder; shared preview color effect; source dimensions; no background-export claim yet. |
| HEVC + AAC in MP4 | **Implemented when a device encoder is reported** | 10-bit/HDR settings still need explicit profile/color validation. |
| APV + audio in MP4 | **Media3 path implemented when a device encoder is reported** | Android 16+/device test gate. Software OpenAPV path planned for broader coverage. |
| ProRes 422 Proxy/LT/422/HQ | Planned | FFmpeg LGPL-safe encoder investigation -> MOV muxer. Preserve requested profile; never substitute H.264. |
| ProRes 4444 / 4444 XQ | Planned | Requires alpha-capable frames, correct chroma/bit depth and MOV metadata. |
| APV software encode | Planned | OpenAPV directly or FFmpeg `liboapv`, with profile/chroma/bit-depth validation. |
| DNxHR | Planned | FFmpeg/native encode; MOV first, MXF only after explicit mux/timecode work. |
| FFV1 in Matroska | Planned | Useful lossless intermediate/archive route, not a universally playable delivery format. |
| CinemaDNG sequence | Planned after MCRAW RAW development | Must preserve source RAW or clearly define redeveloped RAW; audio/timecode sidecars need a contract. |
| EXR / PNG / TIFF sequence | Planned | Color space, bit depth, alpha, compression, numbering and storage space must be explicit. |
| MCRAW | **No general encoder claimed** | Upstream decoder is not a writer. Consider bit-preserving trims only if the container writer and metadata are implemented and tested. Otherwise export DNG/EXR/ProRes. |
| ProRes RAW / BRAW / R3D | **No export claim** | A decoder does not grant a raw writer. Vendor SDKs and camera metadata are required. |
| “Same as input” | Not a universal button | Only valid through a compatibility-checked transmux path. Edited/graded frames require re-encoding. |

## Capability rules

1. Probe the actual bitstream and device; never route only by file extension.
2. Display the selected backend, codec, container, chroma, bit depth, color space and fallback.
3. A professional export request fails loudly rather than silently changing codec, dimensions, HDR
   mode or alpha.
4. A software decoder is allowed to degrade to a lower-resolution **preview proxy**. Final export
   must still use the original source unless the user explicitly chooses otherwise.
5. Unsupported formats get an actionable message (install codec capability, use proxy, or choose a
   supported delivery format), not a crash or a false success.
