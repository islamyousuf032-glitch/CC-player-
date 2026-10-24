#include "native_media.h"
#include "ffmpeg_raii.h"
#include "color_pipeline.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>

namespace ccplayer::ffmpeg {
using namespace detail;

MediaInfo probe(const std::string& inputPath) {
    LogCapture logs;
    auto input = openInput(inputPath, logs);
    MediaInfo result;
    result.format = input->iformat && input->iformat->name ? input->iformat->name : "unknown";
    result.formatLongName = input->iformat && input->iformat->long_name ? input->iformat->long_name : "";
    result.durationUs = input->duration == AV_NOPTS_VALUE ? 0 : av_rescale_q(input->duration, AV_TIME_BASE_Q, AV_TIME_BASE_Q);
    result.startTimeUs = input->start_time == AV_NOPTS_VALUE ? 0 : input->start_time;
    result.bitRate = input->bit_rate;

    for (unsigned int position = 0; position < input->nb_streams; ++position) {
        auto* stream = input->streams[position];
        const auto* parameters = stream->codecpar;
        const auto* codec = avcodec_find_decoder(parameters->codec_id);
        StreamInfo info;
        info.index = static_cast<int>(position);
        info.type = mediaTypeName(parameters->codec_type);
        info.codec = avcodec_get_name(parameters->codec_id);
        info.codecLongName = codec && codec->long_name ? codec->long_name : "";
        info.profileId = parameters->profile;
        if (codec) {
            const auto* name = av_get_profile_name(codec, parameters->profile);
            info.profile = name ? name : (parameters->profile == AV_PROFILE_UNKNOWN ? "unknown" : std::to_string(parameters->profile));
        }
        info.bitRate = parameters->bit_rate;
        info.bitDepth = streamBitDepth(stream);
        info.durationUs = stream->duration == AV_NOPTS_VALUE ? result.durationUs : av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
        info.defaultDisposition = stream->disposition & AV_DISPOSITION_DEFAULT;
        info.attachedPicture = stream->disposition & AV_DISPOSITION_ATTACHED_PIC;
        info.timeBaseNum = stream->time_base.num;
        info.timeBaseDen = stream->time_base.den;
        info.frameCount = stream->nb_frames;
        info.startTimeUs = stream->start_time == AV_NOPTS_VALUE ? 0 : av_rescale_q(stream->start_time, stream->time_base, AV_TIME_BASE_Q);
        info.codecTag = parameters->codec_tag;
        info.colorPrimaries = parameters->color_primaries;
        info.colorTransfer = parameters->color_trc;
        info.colorSpace = parameters->color_space;
        info.colorRange = parameters->color_range;
        info.sampleAspectRatioNum = stream->sample_aspect_ratio.num;
        info.sampleAspectRatioDen = stream->sample_aspect_ratio.den;
        if (const auto* language = av_dict_get(stream->metadata, "language", nullptr, 0)) info.language = language->value;

        if (parameters->codec_type == AVMEDIA_TYPE_VIDEO) {
            info.width = parameters->width;
            info.height = parameters->height;
            if (parameters->format >= 0) {
                const auto* name = av_get_pix_fmt_name(static_cast<AVPixelFormat>(parameters->format));
                info.pixelFormat = name ? name : "unknown";
            }
            const auto frameRate = av_guess_frame_rate(input.get(), stream, nullptr);
            info.frameRateNum = frameRate.num;
            info.frameRateDen = frameRate.den;
            const auto* sideData = av_packet_side_data_get(
                stream->codecpar->coded_side_data, stream->codecpar->nb_coded_side_data,
                AV_PKT_DATA_DISPLAYMATRIX
            );
            const auto* matrix = sideData ? reinterpret_cast<const int32_t*>(sideData->data) : nullptr;
            if (matrix && sideData->size >= 9 * sizeof(int32_t)) {
                info.rotationDegrees = av_display_rotation_get(matrix);
                if (!std::isfinite(info.rotationDegrees)) info.rotationDegrees = 0;
            }
        } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO) {
            info.sampleRate = parameters->sample_rate;
            info.channels = parameters->ch_layout.nb_channels;
            if (parameters->format >= 0) {
                const auto* name = av_get_sample_fmt_name(static_cast<AVSampleFormat>(parameters->format));
                info.sampleFormat = name ? name : "unknown";
            }
        }
        result.streams.push_back(std::move(info));
    }
    return result;
}

ImageFrame decodeImage(const std::string& inputPath, int maxDimension) {
    if (maxDimension < 64 || maxDimension > 4096) throw Error(AVERROR(EINVAL), "Image preview dimension must be between 64 and 4096");
    LogCapture logs;
    auto input = openInput(inputPath, logs);
    const int streamIndex = av_find_best_stream(input.get(), AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (streamIndex < 0) throw Error(AVERROR(EINVAL), "The image/sequence frame has no decodable video stream");
    auto decoder = openDecoder(input.get(), streamIndex, 2, logs);
    FramePtr decoded;
    auto receive = [&]() -> bool {
        while (true) {
            auto frame = makeFrame();
            const int result = avcodec_receive_frame(decoder.get(), frame.get());
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return false;
            if (result == AVERROR_INVALIDDATA) continue;
            check(result, "Could not decode image frame", logs);
            decoded = std::move(frame);
            return true;
        }
    };
    bool reachedEof = false;
    while (!decoded) {
        auto packet = makePacket();
        const int read = av_read_frame(input.get(), packet.get());
        if (read == AVERROR_EOF) {
            if (reachedEof) break;
            reachedEof = true;
            check(avcodec_send_packet(decoder.get(), nullptr), "Could not drain image decoder", logs);
        } else {
            check(read, "Could not read image packet", logs);
            if (packet->stream_index != streamIndex) continue;
            const int sent = avcodec_send_packet(decoder.get(), packet.get());
            if (sent < 0 && sent != AVERROR_INVALIDDATA) check(sent, "Could not submit image packet", logs);
        }
        receive();
    }
    if (!decoded) throw Error(AVERROR(EINVAL), "No image frame could be decoded");
    const float scale = std::min(1.0f, static_cast<float>(maxDimension) / std::max(decoded->width, decoded->height));
    const int width = std::max(1, static_cast<int>(std::lround(decoded->width * scale)));
    const int height = std::max(1, static_cast<int>(std::lround(decoded->height * scale)));
    const std::array<float, 16> identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    ColorPipeline color(width, height, AV_PIX_FMT_BGRA, AVCOL_SPC_RGB, AVCOL_RANGE_JPEG, identity, false, AVCOL_TRC_IEC61966_2_1);
    auto converted = color.process(decoded.get(), logs);
    ImageFrame result;
    result.width = width;
    result.height = height;
    result.pixels.resize(static_cast<size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        std::memcpy(
            result.pixels.data() + static_cast<size_t>(y) * width,
            converted->data[0] + static_cast<ptrdiff_t>(y) * converted->linesize[0],
            static_cast<size_t>(width) * sizeof(uint32_t)
        );
    }
    return result;
}

std::string toJson(const MediaInfo& info) {
    std::ostringstream out;
    out << "{\"format\":\"" << jsonEscape(info.format)
        << "\",\"formatLongName\":\"" << jsonEscape(info.formatLongName)
        << "\",\"durationUs\":" << info.durationUs
        << ",\"bitRate\":" << info.bitRate
        << ",\"startTimeUs\":" << info.startTimeUs
        << ",\"streams\":[";
    bool first = true;
    for (const auto& stream : info.streams) {
        if (!first) out << ',';
        first = false;
        out << "{\"index\":" << stream.index
            << ",\"type\":\"" << jsonEscape(stream.type)
            << "\",\"codec\":\"" << jsonEscape(stream.codec)
            << "\",\"codecLongName\":\"" << jsonEscape(stream.codecLongName)
            << "\",\"profile\":\"" << jsonEscape(stream.profile)
            << "\",\"pixelFormat\":\"" << jsonEscape(stream.pixelFormat)
            << "\",\"sampleFormat\":\"" << jsonEscape(stream.sampleFormat)
            << "\",\"language\":\"" << jsonEscape(stream.language)
            << "\",\"width\":" << stream.width
            << ",\"height\":" << stream.height
            << ",\"profileId\":" << stream.profileId
            << ",\"bitDepth\":" << stream.bitDepth
            << ",\"sampleRate\":" << stream.sampleRate
            << ",\"channels\":" << stream.channels
            << ",\"bitRate\":" << stream.bitRate
            << ",\"durationUs\":" << stream.durationUs
            << ",\"frameRateNum\":" << stream.frameRateNum
            << ",\"frameRateDen\":" << stream.frameRateDen
            << ",\"timeBaseNum\":" << stream.timeBaseNum
            << ",\"timeBaseDen\":" << stream.timeBaseDen
            << ",\"sampleAspectRatioNum\":" << stream.sampleAspectRatioNum
            << ",\"sampleAspectRatioDen\":" << stream.sampleAspectRatioDen
            << ",\"colorPrimaries\":" << stream.colorPrimaries
            << ",\"colorTransfer\":" << stream.colorTransfer
            << ",\"colorSpace\":" << stream.colorSpace
            << ",\"colorRange\":" << stream.colorRange
            << ",\"codecTag\":" << stream.codecTag
            << ",\"frameCount\":" << stream.frameCount
            << ",\"startTimeUs\":" << stream.startTimeUs
            << ",\"rotationDegrees\":" << stream.rotationDegrees
            << ",\"attachedPicture\":" << (stream.attachedPicture ? "true" : "false")
            << ",\"defaultDisposition\":" << (stream.defaultDisposition ? "true" : "false")
            << '}';
    }
    out << "]}";
    return out.str();
}

std::string runtimeInfoJson() {
    initializeLogging();
    std::ostringstream out;
    out << "{\"version\":\"" << jsonEscape(av_version_info())
        << "\",\"configuration\":\"" << jsonEscape(avcodec_configuration())
        << "\",\"license\":\"" << jsonEscape(avcodec_license())
        << "\",\"encoders\":{";
    const std::pair<const char*, const char*> encoders[] = {
        {"prores_ks", "ProRes (all six profiles)"},
        {"liboapv", "OpenAPV software"},
        {"dnxhd", "DNxHD / DNxHR"},
        {"ffv1", "FFV1"},
        {"aac", "AAC"},
        {"flac", "FLAC"},
        {"pcm_s16le", "PCM 16-bit"},
        {"pcm_s24le", "PCM 24-bit"},
    };
    bool first = true;
    for (const auto& entry : encoders) {
        if (!first) out << ',';
        first = false;
        out << '"' << entry.first << "\":" << (avcodec_find_encoder_by_name(entry.first) ? "true" : "false");
    }
    out << "},\"decoders\":{";
    const AVCodecID decoders[] = {
        AV_CODEC_ID_PRORES, AV_CODEC_ID_PRORES_RAW, AV_CODEC_ID_APV, AV_CODEC_ID_DNXHD,
        AV_CODEC_ID_CFHD, AV_CODEC_ID_FFV1, AV_CODEC_ID_H264, AV_CODEC_ID_HEVC,
        AV_CODEC_ID_AV1, AV_CODEC_ID_VP9, AV_CODEC_ID_EXR, AV_CODEC_ID_DPX,
    };
    first = true;
    for (const auto id : decoders) {
        if (!first) out << ',';
        first = false;
        out << '"' << avcodec_get_name(id) << "\":" << (avcodec_find_decoder(id) ? "true" : "false");
    }
    out << "}}";
    return out.str();
}

} // namespace ccplayer::ffmpeg
