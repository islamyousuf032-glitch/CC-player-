#include "color_pipeline.h"

#include <algorithm>
#include <cmath>

namespace ccplayer::ffmpeg {
using namespace detail;

namespace {
constexpr int kEncodeLutSize = 1 << 20;
constexpr float kEncodeLutMax = static_cast<float>(kEncodeLutSize - 1);

bool isRgb(AVPixelFormat format) {
    const auto* descriptor = av_pix_fmt_desc_get(format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_RGB);
}

bool hasAlpha(AVPixelFormat format) {
    const auto* descriptor = av_pix_fmt_desc_get(format);
    return descriptor && (descriptor->flags & AV_PIX_FMT_FLAG_ALPHA);
}

float signedPow(float value, float exponent) {
    return std::copysign(std::pow(std::fabs(value), exponent), value);
}

float decodeTransfer(float value, AVColorTransferCharacteristic transfer) {
    switch (transfer) {
        case AVCOL_TRC_LINEAR:
            return value;
        case AVCOL_TRC_IEC61966_2_1:
            return value <= 0.04045f ? value / 12.92f : signedPow((value + 0.055f) / 1.055f, 2.4f);
        case AVCOL_TRC_GAMMA22:
            return signedPow(value, 2.2f);
        case AVCOL_TRC_GAMMA28:
            return signedPow(value, 2.8f);
        case AVCOL_TRC_SMPTE2084: {
            constexpr float m1 = 2610.0f / 16384.0f;
            constexpr float m2 = 2523.0f / 4096.0f * 128.0f;
            constexpr float c1 = 3424.0f / 4096.0f;
            constexpr float c2 = 2413.0f / 4096.0f * 32.0f;
            constexpr float c3 = 2392.0f / 4096.0f * 32.0f;
            const float p = std::pow(std::max(value, 0.0f), 1.0f / m2);
            return std::pow(std::max(p - c1, 0.0f) / std::max(c2 - c3 * p, 1e-8f), 1.0f / m1);
        }
        case AVCOL_TRC_ARIB_STD_B67: {
            constexpr float a = 0.17883277f;
            constexpr float b = 0.28466892f;
            constexpr float c = 0.55991073f;
            return value <= 0.5f ? value * value / 3.0f : (std::exp((value - c) / a) + b) / 12.0f;
        }
        case AVCOL_TRC_BT709:
        case AVCOL_TRC_BT2020_10:
        case AVCOL_TRC_BT2020_12:
        case AVCOL_TRC_SMPTE170M:
        case AVCOL_TRC_SMPTE240M:
        case AVCOL_TRC_UNSPECIFIED:
        default:
            return value <= 0.081f ? value / 4.5f : signedPow((value + 0.099f) / 1.099f, 1.0f / 0.45f);
    }
}

float encodeTransfer(float value, AVColorTransferCharacteristic transfer) {
    switch (transfer) {
        case AVCOL_TRC_LINEAR:
            return value;
        case AVCOL_TRC_IEC61966_2_1:
            return value <= 0.0031308f ? value * 12.92f : 1.055f * signedPow(value, 1.0f / 2.4f) - 0.055f;
        case AVCOL_TRC_GAMMA22:
            return signedPow(value, 1.0f / 2.2f);
        case AVCOL_TRC_GAMMA28:
            return signedPow(value, 1.0f / 2.8f);
        case AVCOL_TRC_SMPTE2084: {
            constexpr float m1 = 2610.0f / 16384.0f;
            constexpr float m2 = 2523.0f / 4096.0f * 128.0f;
            constexpr float c1 = 3424.0f / 4096.0f;
            constexpr float c2 = 2413.0f / 4096.0f * 32.0f;
            constexpr float c3 = 2392.0f / 4096.0f * 32.0f;
            const float p = std::pow(std::max(value, 0.0f), m1);
            return std::pow((c1 + c2 * p) / (1.0f + c3 * p), m2);
        }
        case AVCOL_TRC_ARIB_STD_B67: {
            constexpr float a = 0.17883277f;
            constexpr float b = 0.28466892f;
            constexpr float c = 0.55991073f;
            return value <= 1.0f / 12.0f ? std::sqrt(std::max(3.0f * value, 0.0f)) : a * std::log(std::max(12.0f * value - b, 1e-8f)) + c;
        }
        case AVCOL_TRC_BT709:
        case AVCOL_TRC_BT2020_10:
        case AVCOL_TRC_BT2020_12:
        case AVCOL_TRC_SMPTE170M:
        case AVCOL_TRC_SMPTE240M:
        case AVCOL_TRC_UNSPECIFIED:
        default:
            return value <= 0.018f ? value * 4.5f : 1.099f * signedPow(value, 0.45f) - 0.099f;
    }
}

AVColorSpace defaultMatrix(int width, int height) {
    return width >= 1280 || height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
}

int swsMatrix(AVColorSpace space, int width, int height) {
    switch (space) {
        case AVCOL_SPC_BT709: return SWS_CS_ITU709;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return SWS_CS_BT2020;
        case AVCOL_SPC_SMPTE240M: return SWS_CS_SMPTE240M;
        case AVCOL_SPC_SMPTE170M:
        case AVCOL_SPC_BT470BG: return SWS_CS_ITU601;
        case AVCOL_SPC_FCC: return SWS_CS_FCC;
        default: return swsMatrix(defaultMatrix(width, height), width, height);
    }
}

} // namespace

ColorPipeline::ColorPipeline(
    int width,
    int height,
    AVPixelFormat format,
    AVColorSpace matrix,
    AVColorRange range,
    const std::array<float, 16>& colorMatrix,
    bool isIdentity,
    AVColorTransferCharacteristic targetTransfer
) : outputWidth(width),
    outputHeight(height),
    outputFormat(format),
    outputMatrix(matrix),
    outputRange(range),
    grade(colorMatrix),
    identity(isIdentity),
    outputAlpha(hasAlpha(format)),
    outputTransfer(targetTransfer) {}

FramePtr ColorPipeline::convert(
    const AVFrame* source,
    int width,
    int height,
    AVPixelFormat format,
    AVColorSpace matrix,
    AVColorRange range,
    SwsPtr& cached,
    const LogCapture& logs
) {
    auto output = makeFrame();
    check(av_frame_copy_props(output.get(), source), "Could not copy frame color properties", logs);
    output->format = format;
    output->width = width;
    output->height = height;
    output->color_range = range;
    output->colorspace = matrix;
    check(av_frame_get_buffer(output.get(), 64), "Could not allocate converted frame", logs);

    auto sourceMatrix = source->colorspace;
    if (sourceMatrix == AVCOL_SPC_UNSPECIFIED || sourceMatrix == AVCOL_SPC_RGB) {
        sourceMatrix = isRgb(static_cast<AVPixelFormat>(source->format)) ? matrix : defaultMatrix(source->width, source->height);
    }
    if (sourceMatrix == AVCOL_SPC_RGB) sourceMatrix = defaultMatrix(source->width, source->height);
    auto destinationMatrix = matrix == AVCOL_SPC_RGB ? sourceMatrix : matrix;
    if (destinationMatrix == AVCOL_SPC_UNSPECIFIED || destinationMatrix == AVCOL_SPC_RGB) {
        destinationMatrix = defaultMatrix(width, height);
    }
    const auto* inverse = sws_getCoefficients(swsMatrix(sourceMatrix, source->width, source->height));
    const auto* forward = sws_getCoefficients(swsMatrix(destinationMatrix, width, height));
    const int sourceRange = source->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    const int destinationRange = range == AVCOL_RANGE_JPEG || isRgb(format) ? 1 : 0;

    SwsContext* raw = sws_getCachedContext(
        cached.release(), source->width, source->height, static_cast<AVPixelFormat>(source->format),
        width, height, format, SWS_BICUBIC | SWS_ACCURATE_RND | SWS_FULL_CHR_H_INT,
        nullptr, nullptr, nullptr
    );
    if (!raw) throw Error(AVERROR(EINVAL), "This pixel-format conversion is not supported by libswscale");
    cached.reset(raw);
    if (sws_setColorspaceDetails(raw, inverse, sourceRange, forward, destinationRange, 0, 1 << 16, 1 << 16) < 0) {
        throw Error(AVERROR(EINVAL), "Could not set source/output color matrix and range");
    }
    const int scaled = sws_scale(raw, source->data, source->linesize, 0, source->height, output->data, output->linesize);
    if (scaled != height) throw Error(AVERROR_EXTERNAL, "libswscale returned an incomplete frame");
    return output;
}

void ColorPipeline::applyGrade(AVFrame* frame, AVColorTransferCharacteristic inputTransfer, AVColorTransferCharacteristic targetTransfer) {
    if (decodeLut.transfer != inputTransfer) {
        decodeLut.transfer = inputTransfer;
        for (int index = 0; index < 65536; ++index) {
            decodeLut.values[index] = decodeTransfer(index / 65535.0f, inputTransfer);
        }
    }
    if (encodeLut.transfer != targetTransfer) {
        encodeLut.transfer = targetTransfer;
        encodeLut.values.resize(kEncodeLutSize);
        for (int index = 0; index < kEncodeLutSize; ++index) {
            const float encoded = encodeTransfer(index / kEncodeLutMax, targetTransfer);
            encodeLut.values[index] = static_cast<uint16_t>(std::clamp(std::lround(encoded * 65535.0f), 0L, 65535L));
        }
    }

    for (int y = 0; y < frame->height; ++y) {
        auto* green = reinterpret_cast<uint16_t*>(frame->data[0] + y * frame->linesize[0]);
        auto* blue = reinterpret_cast<uint16_t*>(frame->data[1] + y * frame->linesize[1]);
        auto* red = reinterpret_cast<uint16_t*>(frame->data[2] + y * frame->linesize[2]);
        for (int x = 0; x < frame->width; ++x) {
            const float r = decodeLut.values[red[x]];
            const float g = decodeLut.values[green[x]];
            const float b = decodeLut.values[blue[x]];
            const float linear[3] = {
                grade[0] * r + grade[4] * g + grade[8] * b + grade[12],
                grade[1] * r + grade[5] * g + grade[9] * b + grade[13],
                grade[2] * r + grade[6] * g + grade[10] * b + grade[14],
            };
            const auto encode = [&](float value) {
                const auto index = static_cast<int>(std::lround(std::clamp(value, 0.0f, 1.0f) * kEncodeLutMax));
                return encodeLut.values[index];
            };
            red[x] = encode(linear[0]);
            green[x] = encode(linear[1]);
            blue[x] = encode(linear[2]);
        }
    }
}

FramePtr ColorPipeline::process(const AVFrame* source, const LogCapture& logs) {
    if (identity) {
        if (source->width == outputWidth && source->height == outputHeight && source->format == outputFormat) {
            FramePtr clone(av_frame_clone(source));
            if (!clone) throw Error(ENOMEM, "Could not reference an ungraded source frame");
            return clone;
        }
        return convert(source, outputWidth, outputHeight, outputFormat, outputMatrix, outputRange, direct, logs);
    }

    const auto workingFormat = outputAlpha ? AV_PIX_FMT_GBRAP16 : AV_PIX_FMT_GBRP16;
    auto working = convert(source, outputWidth, outputHeight, workingFormat, AVCOL_SPC_RGB, AVCOL_RANGE_JPEG, toWorking, logs);
    const auto inputTransfer = source->color_trc == AVCOL_TRC_UNSPECIFIED ? AVCOL_TRC_BT709 : source->color_trc;
    const auto targetTransfer = outputTransfer == AVCOL_TRC_UNSPECIFIED ? inputTransfer : outputTransfer;
    applyGrade(working.get(), inputTransfer, targetTransfer);
    working->color_trc = targetTransfer;
    if (workingFormat == outputFormat) {
        working->color_range = AVCOL_RANGE_JPEG;
        working->colorspace = AVCOL_SPC_RGB;
        return working;
    }
    return convert(working.get(), outputWidth, outputHeight, outputFormat, outputMatrix, outputRange, toOutput, logs);
}

} // namespace ccplayer::ffmpeg
