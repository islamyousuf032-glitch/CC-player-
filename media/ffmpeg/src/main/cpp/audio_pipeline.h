#pragma once

#include "ffmpeg_raii.h"
#include "native_media.h"

namespace ccplayer::ffmpeg {

class AudioPipeline {
public:
    AudioPipeline(
        AVFormatContext* input,
        AVFormatContext* output,
        int inputStreamIndex,
        AVCodecContext* decoder,
        const ExportRequest& request,
        const detail::LogCapture& logs
    );

    ~AudioPipeline();
    AVStream* outputStream() const { return stream; }
    void sendPacket(AVPacket* packet);
    void finish();

private:
    detail::FramePtr allocateFrame(int samples);
    void processFrame(detail::FramePtr frame);
    void resample(const AVFrame* inputFrame);
    void drainAvailable(bool final);
    void sendFrame(detail::FramePtr frame);
    void drainEncoder();
    int64_t originPts() const;
    int64_t endPts() const;

    AVFormatContext* input;
    AVFormatContext* output;
    AVStream* inputStream;
    AVCodecContext* decoder;
    const ExportRequest& request;
    const detail::LogCapture& logs;
    const AVCodec* encoder = nullptr;
    detail::CodecPtr encoderContext;
    AVStream* stream = nullptr;
    detail::SwrPtr resampler;
    detail::FifoPtr fifo;
    AVSampleFormat inputSampleFormat = AV_SAMPLE_FMT_NONE;
    AVChannelLayout inputLayout = {};
    int inputSampleRate = 0;
    int64_t nextPts = 0;
    bool finished = false;
};

} // namespace ccplayer::ffmpeg
