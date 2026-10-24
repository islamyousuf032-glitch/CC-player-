#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace ccplayer::ffmpeg {

class Error final : public std::runtime_error {
public:
    Error(int code, const std::string& message) : std::runtime_error(message), code(code) {}
    const int code;
};

struct StreamInfo {
    int index = -1;
    std::string type;
    std::string codec;
    std::string codecLongName;
    std::string profile;
    std::string pixelFormat;
    std::string sampleFormat;
    std::string language;
    int width = 0;
    int height = 0;
    int profileId = -99;
    int bitDepth = 0;
    int sampleRate = 0;
    int channels = 0;
    int64_t bitRate = 0;
    int64_t durationUs = -1;
    int frameRateNum = 0;
    int frameRateDen = 0;
    int timeBaseNum = 0;
    int timeBaseDen = 1;
    int sampleAspectRatioNum = 0;
    int sampleAspectRatioDen = 1;
    int colorPrimaries = 2;
    int colorTransfer = 2;
    int colorSpace = 2;
    int colorRange = 0;
    uint32_t codecTag = 0;
    int64_t frameCount = 0;
    int64_t startTimeUs = 0;
    double rotationDegrees = 0.0;
    bool attachedPicture = false;
    bool defaultDisposition = false;
};

struct MediaInfo {
    std::string format;
    std::string formatLongName;
    int64_t durationUs = 0;
    int64_t bitRate = 0;
    int64_t startTimeUs = 0;
    std::vector<StreamInfo> streams;
};

MediaInfo probe(const std::string& inputPath);
std::string toJson(const MediaInfo& info);
std::string runtimeInfoJson();

struct ImageFrame {
    int width = 0;
    int height = 0;
    std::vector<uint32_t> pixels; // Android ARGB_8888 integers on little-endian targets.
};

ImageFrame decodeImage(const std::string& inputPath, int maxDimension);

struct ExportRequest {
    std::string inputPath;
    std::string outputPath;
    std::string codec = "prores_ks";
    std::string profile = "hq";
    std::string pixelFormat = "yuv422p10";
    std::string audioCodec = "COPY";
    int width = 0;
    int height = 0;
    int frameRateNum = 0;
    int frameRateDen = 0;
    int audioBitRate = 256000;
    int audioSampleRate = 0;
    int videoQuality = -1;
    int threadCount = 0;
    int64_t trimStartUs = 0;
    int64_t trimEndUs = -1;
    bool useHdr = false;
    std::array<float, 16> grade = {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1,
    };
};

struct ExportResult {
    bool cancelled = false;
    std::string codec;
    std::string profile;
    std::string pixelFormat;
    std::string audioCodec;
    int width = 0;
    int height = 0;
    int bitDepth = 0;
    int frameRateNum = 0;
    int frameRateDen = 0;
    int64_t durationUs = 0;
    int64_t fileSizeBytes = 0;
    int64_t encodedFrames = 0;
};

using ProgressCallback = std::function<void(int percent, int64_t positionUs)>;
using CancelCallback = std::function<bool()>;

ExportResult transcode(
    const ExportRequest& request,
    const ProgressCallback& onProgress,
    const CancelCallback& isCancelled
);
std::string toJson(const ExportResult& result);

} // namespace ccplayer::ffmpeg
