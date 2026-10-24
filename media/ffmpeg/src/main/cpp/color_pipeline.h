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
        bool identity,
        AVColorTransferCharacteristic outputTransfer = AVCOL_TRC_UNSPECIFIED
    );

    detail::FramePtr process(const AVFrame* source, const detail::LogCapture& logs);
    void setGrade(const std::array<float, 16>& colorMatrix, bool isIdentity) {
        grade = colorMatrix;
        identity = isIdentity;
    }

private:
    struct DecodeLut {
        AVColorTransferCharacteristic transfer = AVCOL_TRC_UNSPECIFIED;
        std::array<float, 65536> values{};
    };
    struct EncodeLut {
        AVColorTransferCharacteristic transfer = AVCOL_TRC_UNSPECIFIED;
        std::vector<uint16_t> values;
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
    void applyGrade(AVFrame* frame, AVColorTransferCharacteristic inputTransfer, AVColorTransferCharacteristic targetTransfer);

    int outputWidth;
    int outputHeight;
    AVPixelFormat outputFormat;
    AVColorSpace outputMatrix;
    AVColorRange outputRange;
    std::array<float, 16> grade;
    bool identity;
    bool outputAlpha;
    AVColorTransferCharacteristic outputTransfer;
    detail::SwsPtr toWorking;
    detail::SwsPtr toOutput;
    detail::SwsPtr direct;
    DecodeLut decodeLut;
    EncodeLut encodeLut;
};

} // namespace ccplayer::ffmpeg
