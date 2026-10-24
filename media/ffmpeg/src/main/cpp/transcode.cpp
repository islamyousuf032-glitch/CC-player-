#include "audio_pipeline.h"
#include "color_pipeline.h"
#include "ffmpeg_raii.h"
#include "native_media.h"

#include <algorithm>
#include <cstdio>
#include <memory>
#include <sstream>
#include <sys/stat.h>

namespace ccplayer::ffmpeg {
using namespace detail;

namespace {
struct Cancelled final {};

bool isIdentity(const std::array<float, 16>& matrix) {
    const std::array<float, 16> identity = {
        1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
    };
    return matrix == identity;
}

int bitDepth(AVPixelFormat format) {
    const auto* descriptor = av_pix_fmt_desc_get(format);
    return descriptor ? descriptor->comp[0].depth : 0;
}

int apvProfile(const std::string& profile) {
    if (profile == "400_10") return AV_PROFILE_APV_400_10;
    if (profile == "422_10") return AV_PROFILE_APV_422_10;
    if (profile == "422_12") return AV_PROFILE_APV_422_12;
    if (profile == "444_10") return AV_PROFILE_APV_444_10;
    if (profile == "444_12") return AV_PROFILE_APV_444_12;
    if (profile == "4444_10") return AV_PROFILE_APV_4444_10;
    if (profile == "4444_12") return AV_PROFILE_APV_4444_12;
    throw Error(AVERROR(EINVAL), "Unknown OpenAPV profile: " + profile);
}

class Exporter {
public:
    Exporter(
        const ExportRequest& exportRequest,
        const ProgressCallback& progress,
        const CancelCallback& cancel,
        const LogCapture& capture
    ) : request(exportRequest), onProgress(progress), isCancelled(cancel), logs(capture) {
        if (request.inputPath.empty() || request.outputPath.empty()) throw Error(AVERROR(EINVAL), "Input and output paths are required");
        if (request.inputPath == request.outputPath) throw Error(AVERROR(EINVAL), "Export cannot overwrite its source file");
        if (request.trimStartUs < 0 || (request.trimEndUs >= 0 && request.trimEndUs <= request.trimStartUs)) {
            throw Error(AVERROR(EINVAL), "Trim end must be greater than trim start");
        }
        input = openInput(request.inputPath, logs);
        if (input->duration > 0) {
            if (request.trimStartUs >= input->duration) throw Error(AVERROR(EINVAL), "Trim start is beyond the source duration");
            if (request.trimEndUs > input->duration) throw Error(AVERROR(EINVAL), "Trim end is beyond the source duration");
        }
        videoStreamIndex = av_find_best_stream(input.get(), AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (videoStreamIndex < 0) fail(videoStreamIndex, "No decodable video stream was found", logs);
        inputVideo = input->streams[videoStreamIndex];
        if (inputVideo->disposition & AV_DISPOSITION_ATTACHED_PIC) {
            throw Error(AVERROR(EINVAL), "An attached cover image is not a video track; select the actual video stream");
        }
        videoDecoder = openDecoder(input.get(), videoStreamIndex, request.threadCount, logs);

        if (request.audioCodec != "NONE") {
            audioStreamIndex = av_find_best_stream(input.get(), AVMEDIA_TYPE_AUDIO, -1, videoStreamIndex, nullptr, 0);
            if (audioStreamIndex >= 0) {
                inputAudio = input->streams[audioStreamIndex];
                if (request.audioCodec != "COPY") audioDecoder = openDecoder(input.get(), audioStreamIndex, request.threadCount, logs);
            }
        }
        if (request.audioCodec == "COPY" && inputAudio && (request.trimStartUs > 0 || request.trimEndUs >= 0)) {
            throw Error(AVERROR(EINVAL), "Sample-accurate trim cannot use compressed audio copy. Choose PCM, AAC, FLAC, or no audio.");
        }

        AVFormatContext* rawOutput = nullptr;
        check(avformat_alloc_output_context2(&rawOutput, nullptr, nullptr, request.outputPath.c_str()), "Could not select an output container from the destination extension", logs);
        output.reset(rawOutput);
        output->avoid_negative_ts = AVFMT_AVOID_NEG_TS_DISABLED;
        av_dict_copy(&output->metadata, input->metadata, 0);
        setupVideo();
        setupAudio();
        if (!(output->oformat->flags & AVFMT_NOFILE)) {
            check(avio_open(&output->pb, request.outputPath.c_str(), AVIO_FLAG_WRITE), "Could not create temporary output file", logs);
        }
        check(avformat_write_header(output.get(), nullptr), "Could not write output container header", logs);
    }

    ExportResult run() {
        if (request.trimStartUs > 0) {
            const int64_t seekTarget = (input->start_time == AV_NOPTS_VALUE ? 0 : input->start_time) + request.trimStartUs;
            check(av_seek_frame(input.get(), -1, seekTarget, AVSEEK_FLAG_BACKWARD), "Could not seek to trim start", logs);
            avcodec_flush_buffers(videoDecoder.get());
            if (audioDecoder) avcodec_flush_buffers(audioDecoder.get());
        }

        auto packet = makePacket();
        while (true) {
            cancelIfNeeded();
            const int read = av_read_frame(input.get(), packet.get());
            if (read == AVERROR_EOF) break;
            if (read < 0) {
                av_packet_unref(packet.get());
                check(read, "Could not read source packet", logs);
            }
            if (packet->stream_index == videoStreamIndex) {
                sendVideoPacket(packet.get());
            } else if (packet->stream_index == audioStreamIndex && inputAudio) {
                if (audioPipeline) audioPipeline->sendPacket(packet.get());
                if (request.audioCodec == "COPY") copyAudioPacket(packet.get());
            }
            av_packet_unref(packet.get());
        }
        sendVideoPacket(nullptr);
        encodeVideoFrame(FramePtr{});
        if (audioPipeline) {
            audioPipeline->sendPacket(nullptr);
            audioPipeline->finish();
        }
        if (encodedFrames == 0) throw Error(AVERROR_INVALIDDATA, "The selected range produced no video frames");
        check(av_write_trailer(output.get()), "Could not finalize output container", logs);
        if (output->pb) check(avio_closep(&output->pb), "Could not flush output file", logs);

        ExportResult result;
        result.codec = request.codec;
        result.pixelFormat = request.pixelFormat;
        result.width = videoEncoder->width;
        result.height = videoEncoder->height;
        result.bitDepth = bitDepth(videoEncoder->pix_fmt);
        result.frameRateNum = frameRate.num;
        result.frameRateDen = frameRate.den;
        result.encodedFrames = encodedFrames;
        result.audioCodec = inputAudio ? request.audioCodec : "NONE";
        return result;
    }

private:
    void cancelIfNeeded() const {
        if (isCancelled && isCancelled()) throw Cancelled{};
    }

    int64_t videoOrigin() const {
        const int64_t streamStart = inputVideo->start_time == AV_NOPTS_VALUE ? 0 : inputVideo->start_time;
        return streamStart + av_rescale_q(request.trimStartUs, AV_TIME_BASE_Q, inputVideo->time_base);
    }

    int64_t videoEnd() const {
        if (request.trimEndUs < 0) return AV_NOPTS_VALUE;
        const int64_t streamStart = inputVideo->start_time == AV_NOPTS_VALUE ? 0 : inputVideo->start_time;
        return streamStart + av_rescale_q(request.trimEndUs, AV_TIME_BASE_Q, inputVideo->time_base);
    }

    void setupVideo() {
        const auto* encoder = avcodec_find_encoder_by_name(request.codec.c_str());
        if (!encoder) throw Error(AVERROR_ENCODER_NOT_FOUND, "Requested encoder is not compiled: " + request.codec);
        videoEncoder.reset(avcodec_alloc_context3(encoder));
        if (!videoEncoder) throw Error(ENOMEM, "Could not allocate video encoder");

        videoEncoder->width = request.width > 0 ? request.width : videoDecoder->width;
        videoEncoder->height = request.height > 0 ? request.height : videoDecoder->height;
        if (videoEncoder->width <= 0 || videoEncoder->height <= 0) throw Error(AVERROR(EINVAL), "Source video dimensions are unknown");
        videoEncoder->pix_fmt = av_get_pix_fmt(request.pixelFormat.c_str());
        if (videoEncoder->pix_fmt == AV_PIX_FMT_NONE) throw Error(AVERROR(EINVAL), "Unknown output pixel format: " + request.pixelFormat);
        const auto* descriptor = av_pix_fmt_desc_get(videoEncoder->pix_fmt);
        const int horizontalUnit = 1 << descriptor->log2_chroma_w;
        const int verticalUnit = 1 << descriptor->log2_chroma_h;
        if (videoEncoder->width % horizontalUnit || videoEncoder->height % verticalUnit) {
            std::ostringstream message;
            message << request.pixelFormat << " requires dimensions divisible by " << horizontalUnit << 'x' << verticalUnit
                    << "; requested " << videoEncoder->width << 'x' << videoEncoder->height << " was not silently rounded";
            throw Error(AVERROR(EINVAL), message.str());
        }

        if (request.frameRateNum > 0 || request.frameRateDen > 0) {
            if (request.frameRateNum <= 0 || request.frameRateDen <= 0) throw Error(AVERROR(EINVAL), "Both frame-rate numerator and denominator are required");
            frameRate = {request.frameRateNum, request.frameRateDen};
        } else {
            frameRate = av_guess_frame_rate(input.get(), inputVideo, nullptr);
        }
        if (frameRate.num <= 0 || frameRate.den <= 0) throw Error(AVERROR(EINVAL), "Source frame rate is unknown; specify a rational frame rate explicitly");
        videoEncoder->time_base = av_inv_q(frameRate);
        videoEncoder->framerate = frameRate;
        videoEncoder->gop_size = 1;
        videoEncoder->max_b_frames = 0;
        videoEncoder->thread_count = request.threadCount;
        videoEncoder->bits_per_raw_sample = bitDepth(videoEncoder->pix_fmt);
        videoEncoder->sample_aspect_ratio = videoDecoder->sample_aspect_ratio.num > 0 ? videoDecoder->sample_aspect_ratio : inputVideo->sample_aspect_ratio;
        videoEncoder->color_primaries = videoDecoder->color_primaries == AVCOL_PRI_UNSPECIFIED ? AVCOL_PRI_BT709 : videoDecoder->color_primaries;
        videoEncoder->color_trc = videoDecoder->color_trc == AVCOL_TRC_UNSPECIFIED ? AVCOL_TRC_BT709 : videoDecoder->color_trc;
        videoEncoder->colorspace = videoDecoder->colorspace;
        if (videoEncoder->colorspace == AVCOL_SPC_UNSPECIFIED || videoEncoder->colorspace == AVCOL_SPC_RGB) {
            videoEncoder->colorspace = videoEncoder->width >= 1280 || videoEncoder->height > 576 ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M;
        }
        videoEncoder->color_range = request.codec == "liboapv" && videoDecoder->color_range == AVCOL_RANGE_JPEG ? AVCOL_RANGE_JPEG : AVCOL_RANGE_MPEG;
        videoEncoder->chroma_sample_location = videoDecoder->chroma_sample_location;
        videoEncoder->field_order = videoDecoder->field_order;
        if (videoEncoder->field_order != AV_FIELD_PROGRESSIVE && videoEncoder->field_order != AV_FIELD_UNKNOWN) {
            videoEncoder->flags |= AV_CODEC_FLAG_INTERLACED_DCT;
        }
        if (output->oformat->flags & AVFMT_GLOBALHEADER) videoEncoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

        if (request.codec == "liboapv") {
            videoEncoder->profile = apvProfile(request.profile);
        } else if (!request.profile.empty() && request.profile != "default") {
            check(av_opt_set(videoEncoder->priv_data, "profile", request.profile.c_str(), 0), "Encoder rejected profile " + request.profile, logs);
        }
        if (request.codec == "liboapv") {
            check(av_opt_set(videoEncoder->priv_data, "preset", "medium", 0), "Could not set OpenAPV preset", logs);
            if (request.videoQuality >= 0) check(av_opt_set_int(videoEncoder->priv_data, "qp", request.videoQuality, 0), "Could not set OpenAPV QP", logs);
        } else if (request.codec == "prores_ks" && request.videoQuality > 0) {
            check(av_opt_set_int(videoEncoder->priv_data, "bits_per_mb", request.videoQuality, 0), "Could not set ProRes bits per macroblock", logs);
        } else if (request.codec == "ffv1") {
            check(av_opt_set_int(videoEncoder->priv_data, "slicecrc", 1, 0), "Could not enable FFV1 slice CRC", logs);
        }
        check(avcodec_open2(videoEncoder.get(), encoder, nullptr), "Could not open video encoder with the exact requested settings", logs);
        if (videoEncoder->pix_fmt != av_get_pix_fmt(request.pixelFormat.c_str())) {
            throw Error(AVERROR(EINVAL), "Encoder changed the requested pixel format; export stopped instead of reducing precision");
        }

        outputVideo = avformat_new_stream(output.get(), nullptr);
        if (!outputVideo) throw Error(ENOMEM, "Could not create output video stream");
        outputVideo->time_base = videoEncoder->time_base;
        outputVideo->avg_frame_rate = frameRate;
        outputVideo->r_frame_rate = frameRate;
        outputVideo->sample_aspect_ratio = videoEncoder->sample_aspect_ratio;
        check(avcodec_parameters_from_context(outputVideo->codecpar, videoEncoder.get()), "Could not write video codec parameters", logs);
        av_dict_copy(&outputVideo->metadata, inputVideo->metadata, 0);
        av_dict_set(&outputVideo->metadata, "rotate", nullptr, 0);
        copyDisplayMatrix();
        colorPipeline = std::make_unique<ColorPipeline>(
            videoEncoder->width, videoEncoder->height, videoEncoder->pix_fmt,
            videoEncoder->colorspace, videoEncoder->color_range, request.grade, isIdentity(request.grade)
        );
    }

    void copyDisplayMatrix() {
        const auto* source = av_packet_side_data_get(inputVideo->codecpar->coded_side_data, inputVideo->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
        if (!source || source->size < 9 * sizeof(int32_t)) return;
        auto* target = av_packet_side_data_new(
            &outputVideo->codecpar->coded_side_data, &outputVideo->codecpar->nb_coded_side_data,
            AV_PKT_DATA_DISPLAYMATRIX, source->size, 0
        );
        if (!target) throw Error(ENOMEM, "Could not preserve source rotation metadata");
        std::memcpy(target->data, source->data, source->size);
    }

    void setupAudio() {
        if (!inputAudio || request.audioCodec == "NONE") return;
        if (request.audioCodec == "COPY") {
            if (!avformat_query_codec(output->oformat, inputAudio->codecpar->codec_id, FF_COMPLIANCE_NORMAL)) {
                throw Error(AVERROR(EINVAL), "The destination container does not accept source audio codec " + std::string(avcodec_get_name(inputAudio->codecpar->codec_id)) + "; choose an explicit audio encoder");
            }
            outputAudio = avformat_new_stream(output.get(), nullptr);
            if (!outputAudio) throw Error(ENOMEM, "Could not create copied audio stream");
            check(avcodec_parameters_copy(outputAudio->codecpar, inputAudio->codecpar), "Could not copy source audio parameters", logs);
            outputAudio->codecpar->codec_tag = 0;
            outputAudio->time_base = inputAudio->time_base;
            av_dict_copy(&outputAudio->metadata, inputAudio->metadata, 0);
        } else {
            audioPipeline = std::make_unique<AudioPipeline>(input.get(), output.get(), audioStreamIndex, audioDecoder.get(), request, logs);
            outputAudio = audioPipeline->outputStream();
            av_dict_copy(&outputAudio->metadata, inputAudio->metadata, 0);
        }
    }

    void sendVideoPacket(AVPacket* packet) {
        check(avcodec_send_packet(videoDecoder.get(), packet), "Could not submit compressed video", logs);
        while (true) {
            auto frame = makeFrame();
            const int received = avcodec_receive_frame(videoDecoder.get(), frame.get());
            if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
            check(received, "Could not decode video frame", logs);
            processVideoFrame(std::move(frame));
        }
    }

    void processVideoFrame(FramePtr frame) {
        cancelIfNeeded();
        int64_t timestamp = frame->best_effort_timestamp;
        if (timestamp == AV_NOPTS_VALUE) timestamp = frame->pts;
        if (timestamp == AV_NOPTS_VALUE) timestamp = videoOrigin() + av_rescale_q(encodedFrames, videoEncoder->time_base, inputVideo->time_base);
        if (timestamp < videoOrigin()) return;
        const auto end = videoEnd();
        if (end != AV_NOPTS_VALUE && timestamp >= end) return;
        auto converted = colorPipeline->process(frame.get(), logs);
        int64_t outputPts = av_rescale_q(timestamp - videoOrigin(), inputVideo->time_base, videoEncoder->time_base);
        if (haveLastPts && outputPts <= lastPts) outputPts = lastPts + 1;
        converted->pts = outputPts;
        converted->pkt_dts = AV_NOPTS_VALUE;
        converted->duration = 0;
        lastPts = outputPts;
        haveLastPts = true;
        encodeVideoFrame(std::move(converted));
        ++encodedFrames;
        if (onProgress && (encodedFrames % 10 == 0 || encodedFrames == 1)) {
            const int64_t positionUs = av_rescale_q(timestamp - videoOrigin(), inputVideo->time_base, AV_TIME_BASE_Q);
            const int64_t duration = request.trimEndUs >= 0 ? request.trimEndUs - request.trimStartUs : std::max<int64_t>(input->duration - request.trimStartUs, 0);
            int percent = duration > 0 ? static_cast<int>(std::clamp<int64_t>(positionUs * 100 / duration, 0, 99)) : 0;
            onProgress(percent, positionUs);
        }
    }

    void encodeVideoFrame(FramePtr frame) {
        check(avcodec_send_frame(videoEncoder.get(), frame.get()), "Could not submit frame to video encoder", logs);
        while (true) {
            auto packet = makePacket();
            const int received = avcodec_receive_packet(videoEncoder.get(), packet.get());
            if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
            check(received, "Video encoding failed", logs);
            packet->stream_index = outputVideo->index;
            av_packet_rescale_ts(packet.get(), videoEncoder->time_base, outputVideo->time_base);
            check(av_interleaved_write_frame(output.get(), packet.get()), "Could not mux encoded video", logs);
        }
    }

    void copyAudioPacket(AVPacket* packet) {
        const int64_t origin = av_rescale_q(request.trimStartUs, AV_TIME_BASE_Q, inputAudio->time_base) + (inputAudio->start_time == AV_NOPTS_VALUE ? 0 : inputAudio->start_time);
        if (packet->pts != AV_NOPTS_VALUE && packet->pts < origin) return;
        if (packet->pts != AV_NOPTS_VALUE) packet->pts -= origin;
        if (packet->dts != AV_NOPTS_VALUE) packet->dts -= origin;
        if (packet->pts == AV_NOPTS_VALUE || packet->pts < 0 || (packet->dts != AV_NOPTS_VALUE && packet->dts < 0)) {
            throw Error(AVERROR(EINVAL), "Copied audio would start at a negative timestamp; choose audio re-encoding for a precise zero-based export");
        }
        packet->stream_index = outputAudio->index;
        av_packet_rescale_ts(packet, inputAudio->time_base, outputAudio->time_base);
        check(av_interleaved_write_frame(output.get(), packet), "Could not mux copied audio", logs);
    }

    const ExportRequest& request;
    const ProgressCallback& onProgress;
    const CancelCallback& isCancelled;
    const LogCapture& logs;
    InputPtr input;
    OutputPtr output;
    CodecPtr videoDecoder;
    CodecPtr videoEncoder;
    CodecPtr audioDecoder;
    AVStream* inputVideo = nullptr;
    AVStream* inputAudio = nullptr;
    AVStream* outputVideo = nullptr;
    AVStream* outputAudio = nullptr;
    int videoStreamIndex = -1;
    int audioStreamIndex = -1;
    AVRational frameRate = {};
    std::unique_ptr<ColorPipeline> colorPipeline;
    std::unique_ptr<AudioPipeline> audioPipeline;
    int64_t encodedFrames = 0;
    int64_t lastPts = 0;
    bool haveLastPts = false;
};

void verifyOutput(const ExportRequest& request, int64_t expectedFrames, const CancelCallback& isCancelled) {
    LogCapture logs;
    auto input = openInput(request.outputPath, logs);
    const int index = av_find_best_stream(input.get(), AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (index < 0) fail(index, "Verification found no output video stream", logs);
    auto decoder = openDecoder(input.get(), index, request.threadCount, logs);
    auto packet = makePacket();
    int64_t frames = 0;
    const auto decodeAvailable = [&]() {
        while (true) {
            auto frame = makeFrame();
            const int received = avcodec_receive_frame(decoder.get(), frame.get());
            if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) return;
            check(received, "Verification decode failed", logs);
            ++frames;
        }
    };
    while (true) {
        if (isCancelled && isCancelled()) throw Cancelled{};
        const int read = av_read_frame(input.get(), packet.get());
        if (read == AVERROR_EOF) break;
        check(read, "Verification demux failed", logs);
        if (packet->stream_index == index) {
            check(avcodec_send_packet(decoder.get(), packet.get()), "Verification could not submit output packet", logs);
            decodeAvailable();
        }
        av_packet_unref(packet.get());
    }
    check(avcodec_send_packet(decoder.get(), nullptr), "Verification could not flush decoder", logs);
    decodeAvailable();
    if (frames != expectedFrames) {
        std::ostringstream message;
        message << "Output verification decoded " << frames << " frames, expected " << expectedFrames;
        throw Error(AVERROR_INVALIDDATA, message.str());
    }
}

} // namespace

ExportResult transcode(const ExportRequest& request, const ProgressCallback& onProgress, const CancelCallback& isCancelled) {
    try {
        LogCapture logs;
        ExportResult result = Exporter(request, onProgress, isCancelled, logs).run();
        verifyOutput(request, result.encodedFrames, isCancelled);
        const auto verified = probe(request.outputPath);
        const auto video = std::find_if(verified.streams.begin(), verified.streams.end(), [](const StreamInfo& stream) {
            return stream.type == "video" && !stream.attachedPicture;
        });
        if (video == verified.streams.end()) throw Error(AVERROR_INVALIDDATA, "Output probe found no video stream");
        const auto expectedCodec = avcodec_find_encoder_by_name(request.codec.c_str());
        if (!expectedCodec || video->codec != avcodec_get_name(expectedCodec->id)) {
            throw Error(AVERROR_INVALIDDATA, "Output codec does not match the request: " + video->codec);
        }
        const auto actualFormat = av_get_pix_fmt(video->pixelFormat.c_str());
        const auto requestedFormat = av_get_pix_fmt(request.pixelFormat.c_str());
        const bool prores4444DecodePromotion = request.codec == "prores_ks" &&
            (request.profile == "4444" || request.profile == "4444xq") &&
            ((requestedFormat == AV_PIX_FMT_YUVA444P10 && actualFormat == AV_PIX_FMT_YUVA444P12) ||
             (requestedFormat == AV_PIX_FMT_YUV444P10 && actualFormat == AV_PIX_FMT_YUV444P12));
        if (video->width != result.width || video->height != result.height ||
            (actualFormat != requestedFormat && !prores4444DecodePromotion)) {
            throw Error(AVERROR_INVALIDDATA, "Output dimensions or pixel format differ from the exact export request: got " +
                std::to_string(video->width) + "x" + std::to_string(video->height) + " " + video->pixelFormat +
                ", requested " + std::to_string(result.width) + "x" + std::to_string(result.height) + " " + request.pixelFormat);
        }
        result.codec = video->codec;
        result.profile = video->profile;
        result.pixelFormat = video->pixelFormat;
        result.width = video->width;
        result.height = video->height;
        result.bitDepth = video->bitDepth;
        result.durationUs = verified.durationUs;
        struct stat status {};
        if (stat(request.outputPath.c_str(), &status) == 0) result.fileSizeBytes = status.st_size;
        if (onProgress) onProgress(100, result.durationUs);
        return result;
    } catch (const Cancelled&) {
        std::remove(request.outputPath.c_str());
        ExportResult cancelled;
        cancelled.cancelled = true;
        return cancelled;
    } catch (...) {
        std::remove(request.outputPath.c_str());
        throw;
    }
}

std::string toJson(const ExportResult& result) {
    std::ostringstream out;
    out << "{\"cancelled\":" << (result.cancelled ? "true" : "false")
        << ",\"verified\":" << (!result.cancelled ? "true" : "false")
        << ",\"codec\":\"" << detail::jsonEscape(result.codec)
        << "\",\"profile\":\"" << detail::jsonEscape(result.profile)
        << "\",\"pixelFormat\":\"" << detail::jsonEscape(result.pixelFormat)
        << "\",\"audioCodec\":\"" << detail::jsonEscape(result.audioCodec)
        << "\",\"width\":" << result.width
        << ",\"height\":" << result.height
        << ",\"bitDepth\":" << result.bitDepth
        << ",\"frameRateNum\":" << result.frameRateNum
        << ",\"frameRateDen\":" << result.frameRateDen
        << ",\"durationUs\":" << result.durationUs
        << ",\"fileSizeBytes\":" << result.fileSizeBytes
        << ",\"encodedFrames\":" << result.encodedFrames
        << '}';
    return out.str();
}

} // namespace ccplayer::ffmpeg
