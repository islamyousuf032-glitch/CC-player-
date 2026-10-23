# ADR 0001: Hybrid playback with a shared render/export contract

- Status: Accepted
- Date: 2026-10-23

## Context

The product needs smooth Android playback, professional codecs, MCRAW/RAW sources, export and color
grading, while remaining proprietary. Media3 alone cannot decode/encode every professional format.
FFmpeg alone cannot provide the best low-power hardware path on every phone. A desktop editor/player
fork would bring GPL, UI and rendering assumptions that conflict with the product.

## Decision

Use a hybrid engine:

1. Media3/MediaCodec is the platform-first playback and hardware export path.
2. A pinned native FFmpeg shared-library backend will provide additional demux/decode/export
   capabilities after its LGPL release gates are satisfied.
3. MotionCam's Apache-2.0 decoder is a native MCRAW source, not a complete player.
4. OpenAPV is the APV software candidate; Android hardware APV remains preferred when present.
5. All sources must eventually feed a common color-managed frame graph used by both preview and
   export.
6. GPL application code is reference-only and is not copied into this tree.

## Consequences

### Positive

- Hardware efficiency and broad compatibility are not mutually exclusive.
- Compose UI and project/edit models survive backend changes.
- ProRes/APV/export support can grow without pretending Media3 supports it.
- The same architecture supports proxy preview and original-quality export.

### Costs

- A native frame, clock, audio and memory-management layer is substantial engineering work.
- FFmpeg's corresponding-source and replacement obligations need release engineering.
- Device-specific codec behavior requires a real-device test matrix.
- Multiple decoders can produce different color/metadata interpretations; conformance tests are
  required at the graph boundary.

## Alternatives rejected

- **Media3 only:** insufficient for MCRAW, ProRes software fallback and professional export.
- **FFmpeg command wrapper only:** preview/export divergence, weak seeking and no common GPU graph.
- **Fork a GPL editor:** incompatible with the proprietary product requirement.
- **Port a desktop player wholesale:** player-centric architecture and desktop rendering/hardware
  assumptions do not solve mobile editing/export.
