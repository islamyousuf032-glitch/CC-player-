#pragma once

#include "native_media.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
#include <libavutil/log.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace ccplayer::ffmpeg {
namespace detail {

inline thread_local std::string* activeLog = nullptr;

inline void logCallback(void*, int level, const char* format, va_list arguments) {
    if (!activeLog || level > AV_LOG_WARNING) return;
    char line[2048];
    const int length = std::vsnprintf(line, sizeof(line), format, arguments);
    if (length <= 0) return;
    if (activeLog->size() < 12000) activeLog->append(line, std::min<int>(length, sizeof(line) - 1));
}

inline void initializeLogging() {
    static std::once_flag once;
    std::call_once(once, [] {
        av_log_set_level(AV_LOG_INFO);
        av_log_set_callback(logCallback);
    });
}

class LogCapture {
public:
    LogCapture() {
        initializeLogging();
        previous = activeLog;
        activeLog = &messages;
    }
    ~LogCapture() { activeLog = previous; }
    std::string detail() const {
        auto value = messages;
        while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) value.pop_back();
        if (value.size() > 3500) value = value.substr(value.size() - 3500);
        return value;
    }
private:
    std::string messages;
    std::string* previous = nullptr;
};

inline std::string errorString(int code) {
    char value[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(code, value, sizeof(value));
    return value;
}

[[noreturn]] inline void fail(int code, const std::string& operation, const LogCapture& logs) {
    auto message = operation + ": " + errorString(code);
    const auto detail = logs.detail();
    if (!detail.empty()) message += "\n" + detail;
    throw Error(code, message);
}

inline void check(int code, const std::string& operation, const LogCapture& logs) {
    if (code < 0) fail(code, operation, logs);
}

struct InputDeleter {
    void operator()(AVFormatContext* value) const {
        if (value) avformat_close_input(&value);
    }
};
struct OutputDeleter {
    void operator()(AVFormatContext* value) const {
        if (!value) return;
        if (value->pb) avio_closep(&value->pb);
        avformat_free_context(value);
    }
};
struct CodecDeleter {
    void operator()(AVCodecContext* value) const { avcodec_free_context(&value); }
};
struct FrameDeleter {
    void operator()(AVFrame* value) const { av_frame_free(&value); }
};
struct PacketDeleter {
    void operator()(AVPacket* value) const { av_packet_free(&value); }
};
struct SwsDeleter {
    void operator()(SwsContext* value) const { sws_freeContext(value); }
};
struct SwrDeleter {
    void operator()(SwrContext* value) const { swr_free(&value); }
};
struct FifoDeleter {
    void operator()(AVAudioFifo* value) const { av_audio_fifo_free(value); }
};
struct DictionaryDeleter {
    void operator()(AVDictionary* value) const { av_dict_free(&value); }
};

using InputPtr = std::unique_ptr<AVFormatContext, InputDeleter>;
using OutputPtr = std::unique_ptr<AVFormatContext, OutputDeleter>;
using CodecPtr = std::unique_ptr<AVCodecContext, CodecDeleter>;
using FramePtr = std::unique_ptr<AVFrame, FrameDeleter>;
using PacketPtr = std::unique_ptr<AVPacket, PacketDeleter>;
using SwsPtr = std::unique_ptr<SwsContext, SwsDeleter>;
using SwrPtr = std::unique_ptr<SwrContext, SwrDeleter>;
using FifoPtr = std::unique_ptr<AVAudioFifo, FifoDeleter>;
using DictionaryPtr = std::unique_ptr<AVDictionary, DictionaryDeleter>;

inline FramePtr makeFrame() {
    FramePtr value(av_frame_alloc());
    if (!value) throw Error(ENOMEM, "Could not allocate a video/audio frame");
    return value;
}

inline PacketPtr makePacket() {
    PacketPtr value(av_packet_alloc());
    if (!value) throw Error(ENOMEM, "Could not allocate a media packet");
    return value;
}

inline InputPtr openInput(const std::string& path, const LogCapture& logs, AVDictionary** options = nullptr) {
    AVFormatContext* raw = nullptr;
    check(avformat_open_input(&raw, path.c_str(), nullptr, options), "Could not open input " + path, logs);
    InputPtr input(raw);
    check(avformat_find_stream_info(raw, nullptr), "Could not read stream information", logs);
    return input;
}

inline CodecPtr openDecoder(AVFormatContext* input, int streamIndex, int threadCount, const LogCapture& logs) {
    const auto* parameters = input->streams[streamIndex]->codecpar;
    const auto* decoder = avcodec_find_decoder(parameters->codec_id);
    if (!decoder) {
        throw Error(AVERROR_DECODER_NOT_FOUND,
            "No decoder was compiled for " + std::string(avcodec_get_name(parameters->codec_id)) +
            ". The file was not replaced with a lower-quality proxy.");
    }
    CodecPtr context(avcodec_alloc_context3(decoder));
    if (!context) throw Error(ENOMEM, "Could not allocate decoder");
    check(avcodec_parameters_to_context(context.get(), parameters), "Could not configure decoder", logs);
    context->pkt_timebase = input->streams[streamIndex]->time_base;
    context->thread_count = threadCount;
    if (decoder->capabilities & AV_CODEC_CAP_FRAME_THREADS) context->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    check(avcodec_open2(context.get(), decoder, nullptr),
        "Could not open decoder " + std::string(decoder->name), logs);
    return context;
}

inline std::string jsonEscape(const std::string& value) {
    std::string output;
    output.reserve(value.size() + 16);
    for (const unsigned char c : value) {
        switch (c) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\b': output += "\\b"; break;
            case '\f': output += "\\f"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[7];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
                    output += escaped;
                } else {
                    output += static_cast<char>(c);
                }
        }
    }
    return output;
}

inline const char* mediaTypeName(AVMediaType type) {
    switch (type) {
        case AVMEDIA_TYPE_VIDEO: return "video";
        case AVMEDIA_TYPE_AUDIO: return "audio";
        case AVMEDIA_TYPE_SUBTITLE: return "subtitle";
        case AVMEDIA_TYPE_DATA: return "data";
        case AVMEDIA_TYPE_ATTACHMENT: return "attachment";
        default: return "unknown";
    }
}

inline int streamBitDepth(const AVStream* stream) {
    const auto* parameters = stream->codecpar;
    if (parameters->bits_per_raw_sample > 0) return parameters->bits_per_raw_sample;
    if (parameters->format >= 0) {
        if (parameters->codec_type == AVMEDIA_TYPE_VIDEO) {
            const auto* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(parameters->format));
            if (descriptor) return descriptor->comp[0].depth;
        } else if (parameters->codec_type == AVMEDIA_TYPE_AUDIO) {
            return av_get_bytes_per_sample(static_cast<AVSampleFormat>(parameters->format)) * 8;
        }
    }
    if (parameters->bits_per_coded_sample > 0) return parameters->bits_per_coded_sample;
    return 0;
}

} // namespace detail
} // namespace ccplayer::ffmpeg
