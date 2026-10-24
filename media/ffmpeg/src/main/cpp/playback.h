#pragma once

#include "ffmpeg_raii.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace ccplayer::ffmpeg {

class PlaybackSink {
public:
    virtual ~PlaybackSink() = default;
    virtual void renderVideo(const AVFrame* frame, int64_t presentationUs, const std::array<float, 16>& grade) = 0;
    virtual void writeAudio(
        const int16_t* samples,
        size_t sampleFrames,
        int sampleRate,
        int channels,
        int64_t presentationUs,
        uint64_t generation
    ) = 0;
    virtual int64_t audioPositionUs() = 0; // Negative means no reliable hardware timestamp yet.
    virtual void setVideoRotation(int clockwiseDegrees) { (void)clockwiseDegrees; }
    virtual void flushAudio() = 0;
    virtual void setAudioSpeed(float speed) = 0;
};

struct PlaybackListener {
    std::function<void(bool playing, bool ended)> onState;
    std::function<void(int width, int height, float pixelAspectRatio)> onVideoSize;
    std::function<void(const std::string& message)> onError;
};

/** A seekable, clocked FFmpeg decoder. Android supplies a Surface/AudioTrack sink; tests can supply a memory sink. */
class NativePlayer final {
public:
    NativePlayer();
    ~NativePlayer();
    NativePlayer(const NativePlayer&) = delete;
    NativePlayer& operator=(const NativePlayer&) = delete;

    void open(const std::string& inputPath, std::shared_ptr<PlaybackSink> sink, PlaybackListener listener);
    void close() noexcept;
    void play();
    void pause();
    void seekTo(int64_t positionUs);
    void setSpeed(float speed);
    void setRepeat(bool repeat);
    void setGrade(const std::array<float, 16>& grade);
    int64_t positionUs() const;
    int64_t durationUs() const;

private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};

} // namespace ccplayer::ffmpeg
