#pragma once

#include "ffmpeg_raii.h"

#include <array>
#include <vector>

namespace ccplayer::ffmpeg {

class ColorPipeline {
public:
    ColorPipeline(
        int outputWidth,
        int outputHeight,
        AVPixelFormat outputFormat,
        AVColorSpace outputMatrix,
        AVColorRange outputRange,
        const std::array<float, 16>& grade,
        bool identity
    );

    detail::FramePtr process(const AVFrame* source, const detail::LogCapture& logs);

private:
    struct TransferLut {
        AVColorTransferCharacteristic transfer = AVCOL_TRC_UNSPECIFIED;
        std::array<float, 65536> decode{};
        std::vector<uint16_t> encode;
    };

    detail::FramePtr convert(
        const AVFrame* source,
        int width,
        int height,
        AVPixelFormat format,
        AVColorSpace matrix,
        AVColorRange range,
        detail::SwsPtr& cached,
        const detail::LogCapture& logs
    );
    void applyGrade(AVFrame* frame, AVColorTransferCharacteristic transfer);

    int outputWidth;
    int outputHeight;
    AVPixelFormat outputFormat;
    AVColorSpace outputMatrix;
    AVColorRange outputRange;
    std::array<float, 16> grade;
    bool identity;
    bool outputAlpha;
    detail::SwsPtr toWorking;
    detail::SwsPtr toOutput;
    detail::SwsPtr direct;
    TransferLut transferLut;
    AVColorTransferCharacteristic lutTransfer = AVCOL_TRC_UNSPECIFIED;
};

} // namespace ccplayer::ffmpeg
