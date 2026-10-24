#include "ffmpeg_raii.h"
#include "native_media.h"

#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>

using namespace ccplayer::ffmpeg;
using namespace ccplayer::ffmpeg::detail;

namespace {
constexpr int kWidth = 320;
constexpr int kHeight = 240;
constexpr int kFrames = 12;
constexpr int kSampleRate = 48000;
constexpr int kSamplesPerFrame = kSampleRate / 24;

void require(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

void writeEncoded(AVFormatContext* output, AVCodecContext* encoder, AVStream* stream, const LogCapture& logs) {
    while (true) {
        auto packet = makePacket();
        const int received = avcodec_receive_packet(encoder, packet.get());
        if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) return;
        check(received, "fixture encode", logs);
        packet->stream_index = stream->index;
        av_packet_rescale_ts(packet.get(), encoder->time_base, stream->time_base);
        check(av_interleaved_write_frame(output, packet.get()), "fixture mux", logs);
    }
}

void makeFixture(const std::string& path) {
    LogCapture logs;
    AVFormatContext* raw = nullptr;
    check(avformat_alloc_output_context2(&raw, nullptr, nullptr, path.c_str()), "fixture container", logs);
    OutputPtr output(raw);
    check(avio_open(&output->pb, path.c_str(), AVIO_FLAG_WRITE), "fixture open", logs);

    const auto* videoCodec = avcodec_find_encoder_by_name("ffv1");
    const auto* audioCodec = avcodec_find_encoder_by_name("pcm_s16le");
    require(videoCodec && audioCodec, "fixture encoders unavailable");
    CodecPtr video(avcodec_alloc_context3(videoCodec));
    CodecPtr audio(avcodec_alloc_context3(audioCodec));
    video->width = kWidth;
    video->height = kHeight;
    video->pix_fmt = AV_PIX_FMT_RGB32;
    video->time_base = {1, 24};
    video->framerate = {24, 1};
    video->color_primaries = AVCOL_PRI_BT709;
    video->color_trc = AVCOL_TRC_BT709;
    video->colorspace = AVCOL_SPC_RGB;
    video->color_range = AVCOL_RANGE_JPEG;
    audio->sample_rate = kSampleRate;
    audio->sample_fmt = AV_SAMPLE_FMT_S16;
    audio->time_base = {1, kSampleRate};
    av_channel_layout_default(&audio->ch_layout, 2);
    check(avcodec_open2(video.get(), videoCodec, nullptr), "fixture video encoder", logs);
    check(avcodec_open2(audio.get(), audioCodec, nullptr), "fixture audio encoder", logs);
    auto* videoStream = avformat_new_stream(output.get(), nullptr);
    auto* audioStream = avformat_new_stream(output.get(), nullptr);
    videoStream->time_base = video->time_base;
    audioStream->time_base = audio->time_base;
    check(avcodec_parameters_from_context(videoStream->codecpar, video.get()), "fixture video parameters", logs);
    check(avcodec_parameters_from_context(audioStream->codecpar, audio.get()), "fixture audio parameters", logs);
    check(avformat_write_header(output.get(), nullptr), "fixture header", logs);

    for (int index = 0; index < kFrames; ++index) {
        auto frame = makeFrame();
        frame->format = video->pix_fmt;
        frame->width = video->width;
        frame->height = video->height;
        check(av_frame_get_buffer(frame.get(), 64), "fixture video allocation", logs);
        for (int y = 0; y < kHeight; ++y) {
            for (int x = 0; x < kWidth; ++x) {
                auto* pixel = reinterpret_cast<uint32_t*>(frame->data[0] + y * frame->linesize[0]) + x;
                const uint32_t red = static_cast<uint32_t>((x + index * 13) & 255);
                const uint32_t green = static_cast<uint32_t>((y * 2 + index * 7) & 255);
                const uint32_t blue = static_cast<uint32_t>((x + y + index * 19) & 255);
                *pixel = 0xff000000U | (red << 16) | (green << 8) | blue;
            }
        }
        frame->pts = index;
        frame->color_primaries = video->color_primaries;
        frame->color_trc = video->color_trc;
        frame->colorspace = video->colorspace;
        frame->color_range = video->color_range;
        check(avcodec_send_frame(video.get(), frame.get()), "fixture video submit", logs);
        writeEncoded(output.get(), video.get(), videoStream, logs);

        auto samples = makeFrame();
        samples->format = audio->sample_fmt;
        samples->sample_rate = audio->sample_rate;
        samples->nb_samples = kSamplesPerFrame;
        check(av_channel_layout_copy(&samples->ch_layout, &audio->ch_layout), "fixture layout", logs);
        check(av_frame_get_buffer(samples.get(), 0), "fixture audio allocation", logs);
        auto* pcm = reinterpret_cast<int16_t*>(samples->data[0]);
        for (int sample = 0; sample < kSamplesPerFrame; ++sample) {
            const double phase = (index * kSamplesPerFrame + sample) * 2.0 * 3.141592653589793 * 440.0 / kSampleRate;
            const int16_t value = static_cast<int16_t>(std::sin(phase) * 12000.0);
            pcm[sample * 2] = value;
            pcm[sample * 2 + 1] = static_cast<int16_t>(value / 2);
        }
        samples->pts = static_cast<int64_t>(index) * kSamplesPerFrame;
        check(avcodec_send_frame(audio.get(), samples.get()), "fixture audio submit", logs);
        writeEncoded(output.get(), audio.get(), audioStream, logs);
    }
    check(avcodec_send_frame(video.get(), nullptr), "fixture video flush", logs);
    writeEncoded(output.get(), video.get(), videoStream, logs);
    check(avcodec_send_frame(audio.get(), nullptr), "fixture audio flush", logs);
    writeEncoded(output.get(), audio.get(), audioStream, logs);
    check(av_write_trailer(output.get()), "fixture trailer", logs);
    check(avio_closep(&output->pb), "fixture close", logs);
}

ExportRequest request(const std::string& input, const std::string& output) {
    ExportRequest value;
    value.inputPath = input;
    value.outputPath = output;
    value.threadCount = 2;
    return value;
}

void run() {
    const std::string directory = "/tmp/ccplayer-native-tests";
    std::string command = "mkdir -p " + directory;
    require(std::system(command.c_str()) == 0, "create test directory");
    const auto input = directory + "/input.mkv";
    makeFixture(input);
    const auto source = probe(input);
    require(source.streams.size() == 2, "fixture probe stream count");

    auto prores = request(input, directory + "/prores-hq.mov");
    prores.codec = "prores_ks";
    prores.profile = "hq";
    prores.pixelFormat = "yuv422p10";
    prores.audioCodec = "pcm_s24le";
    const auto proresResult = transcode(prores, {}, {});
    require(proresResult.encodedFrames == kFrames, "ProRes HQ frame count");
    require(proresResult.bitDepth == 10 && av_get_pix_fmt(proresResult.pixelFormat.c_str()) == AV_PIX_FMT_YUV422P10, "ProRes HQ precision");
    std::cout << "ProRes HQ: " << toJson(proresResult) << '\n';

    auto prores4444 = request(input, directory + "/prores-4444.mov");
    prores4444.codec = "prores_ks";
    prores4444.profile = "4444";
    prores4444.pixelFormat = "yuva444p10";
    prores4444.audioCodec = "COPY";
    prores4444.grade[0] = prores4444.grade[5] = prores4444.grade[10] = 1.2f;
    const auto alphaResult = transcode(prores4444, {}, {});
    require(alphaResult.encodedFrames == kFrames, "ProRes 4444 frame count");
    require(av_get_pix_fmt(alphaResult.pixelFormat.c_str()) == AV_PIX_FMT_YUVA444P12 && alphaResult.bitDepth == 12,
        "ProRes 4444 10-bit encoder input / 12-bit decoder output contract");
    std::cout << "ProRes 4444 graded: " << toJson(alphaResult) << '\n';

    auto dnxhr = request(input, directory + "/dnxhr-hqx.mov");
    dnxhr.codec = "dnxhd";
    dnxhr.profile = "dnxhr_hqx";
    dnxhr.pixelFormat = "yuv422p10";
    dnxhr.audioCodec = "pcm_s16le";
    dnxhr.trimStartUs = 83333;
    dnxhr.trimEndUs = 375000;
    const auto dnxhrResult = transcode(dnxhr, {}, {});
    require(dnxhrResult.encodedFrames == 7, "DNxHR sample/timestamp trim frame count");
    std::cout << "DNxHR trimmed: " << toJson(dnxhrResult) << '\n';

    auto ffv1 = request(input, directory + "/lossless.mkv");
    ffv1.codec = "ffv1";
    ffv1.profile = "default";
    ffv1.pixelFormat = "yuv444p16";
    ffv1.audioCodec = "flac";
    ffv1.grade[0] = 1.05f;
    ffv1.grade[10] = 0.95f;
    const auto ffv1Result = transcode(ffv1, {}, {});
    require(ffv1Result.encodedFrames == kFrames, "FFV1 frame count");
    require(ffv1Result.bitDepth == 16, "FFV1 16-bit output");
    std::cout << "FFV1 graded: " << toJson(ffv1Result) << '\n';

    auto apv = request(input, directory + "/apv-422-10.mp4");
    apv.codec = "liboapv";
    apv.profile = "422_10";
    apv.pixelFormat = "yuv422p10";
    apv.audioCodec = "aac";
    apv.videoQuality = 20;
    const auto apvResult = transcode(apv, {}, {});
    require(apvResult.encodedFrames == kFrames && apvResult.bitDepth == 10, "OpenAPV 422 10-bit export");
    std::cout << "OpenAPV 422: " << toJson(apvResult) << '\n';

    auto apvAlpha = request(input, directory + "/apv-4444-12.mp4");
    apvAlpha.codec = "liboapv";
    apvAlpha.profile = "4444_12";
    apvAlpha.pixelFormat = "yuva444p12";
    apvAlpha.audioCodec = "NONE";
    apvAlpha.videoQuality = 20;
    const auto apvAlphaResult = transcode(apvAlpha, {}, {});
    require(apvAlphaResult.encodedFrames == kFrames && apvAlphaResult.bitDepth == 12, "OpenAPV 4444 12-bit export");
    std::cout << "OpenAPV 4444: " << toJson(apvAlphaResult) << '\n';

    struct ApvVariant { const char* profile; const char* format; int depth; };
    const ApvVariant additionalApv[] = {
        {"400_10", "gray10", 10},
        {"422_12", "yuv422p12", 12},
        {"444_10", "yuv444p10", 10},
        {"444_12", "yuv444p12", 12},
        {"4444_10", "yuva444p10", 10},
    };
    for (const auto& variant : additionalApv) {
        auto variantRequest = request(input, directory + "/apv-" + variant.profile + ".mp4");
        variantRequest.codec = "liboapv";
        variantRequest.profile = variant.profile;
        variantRequest.pixelFormat = variant.format;
        variantRequest.audioCodec = "NONE";
        variantRequest.videoQuality = 20;
        const auto variantResult = transcode(variantRequest, {}, {});
        require(variantResult.encodedFrames == kFrames && variantResult.bitDepth == variant.depth,
            std::string("OpenAPV variant ") + variant.profile);
        std::cout << "OpenAPV " << variant.profile << ": " << toJson(variantResult) << '\n';
    }

    struct ProresVariant { const char* profile; const char* format; int depth; };
    const ProresVariant additionalProres[] = {
        {"proxy", "yuv422p10", 10}, {"lt", "yuv422p10", 10}, {"standard", "yuv422p10", 10},
        {"4444", "yuv444p10", 12}, {"4444xq", "yuv444p10", 12}, {"4444xq", "yuva444p10", 12},
    };
    int proresIndex = 0;
    for (const auto& variant : additionalProres) {
        auto variantRequest = request(input, directory + "/prores-variant-" + std::to_string(proresIndex++) + ".mov");
        variantRequest.codec = "prores_ks";
        variantRequest.profile = variant.profile;
        variantRequest.pixelFormat = variant.format;
        variantRequest.audioCodec = "NONE";
        const auto variantResult = transcode(variantRequest, {}, {});
        require(variantResult.encodedFrames == kFrames && variantResult.bitDepth == variant.depth,
            std::string("ProRes variant ") + variant.profile);
    }

    struct DnxVariant { const char* profile; const char* format; int depth; };
    const DnxVariant dnxVariants[] = {
        {"dnxhr_lb", "yuv422p", 8}, {"dnxhr_sq", "yuv422p", 8}, {"dnxhr_hq", "yuv422p", 8},
        {"dnxhr_444", "yuv444p10", 10},
    };
    for (const auto& variant : dnxVariants) {
        auto variantRequest = request(input, directory + "/dnx-" + variant.profile + ".mov");
        variantRequest.codec = "dnxhd";
        variantRequest.profile = variant.profile;
        variantRequest.pixelFormat = variant.format;
        variantRequest.audioCodec = "NONE";
        const auto variantResult = transcode(variantRequest, {}, {});
        require(variantResult.encodedFrames == kFrames && variantResult.bitDepth == variant.depth,
            std::string("DNxHR variant ") + variant.profile);
    }

    auto ffv1Alpha = request(input, directory + "/lossless-alpha.mkv");
    ffv1Alpha.codec = "ffv1";
    ffv1Alpha.profile = "default";
    ffv1Alpha.pixelFormat = "yuva444p16";
    ffv1Alpha.audioCodec = "NONE";
    const auto ffv1AlphaResult = transcode(ffv1Alpha, {}, {});
    require(ffv1AlphaResult.encodedFrames == kFrames && ffv1AlphaResult.bitDepth == 16, "FFV1 alpha 16-bit export");

    auto cancelled = request(input, directory + "/cancelled.mov");
    cancelled.codec = "prores_ks";
    cancelled.profile = "proxy";
    cancelled.pixelFormat = "yuv422p10";
    cancelled.audioCodec = "NONE";
    int callbacks = 0;
    const auto cancelledResult = transcode(cancelled, {}, [&]() { return ++callbacks >= 2; });
    require(cancelledResult.cancelled, "cancellation result");
    require(!std::ifstream(cancelled.outputPath).good(), "cancelled partial output removed");

    auto invalid = request(input, directory + "/invalid.mov");
    invalid.codec = "prores_ks";
    invalid.profile = "hq";
    invalid.pixelFormat = "yuv422p10";
    invalid.width = 321;
    invalid.audioCodec = "NONE";
    bool rejected = false;
    try {
        transcode(invalid, {}, {});
    } catch (const Error&) {
        rejected = true;
    }
    require(rejected, "odd 4:2:2 width must be rejected, not rounded");
    require(!std::ifstream(invalid.outputPath).good(), "invalid output removed");

    std::cout << "All native host integration tests passed.\n";
}
}

int main() {
    try {
        run();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "TEST FAILURE: " << error.what() << '\n';
        return 1;
    }
}
