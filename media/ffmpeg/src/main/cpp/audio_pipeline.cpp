#include "audio_pipeline.h"

#include <algorithm>
#include <cmath>

namespace ccplayer::ffmpeg {
using namespace detail;

namespace {
AVSampleFormat outputSampleFormat(const std::string& codec) {
    if (codec == "aac") return AV_SAMPLE_FMT_FLTP;
    if (codec == "pcm_s16le") return AV_SAMPLE_FMT_S16;
    if (codec == "pcm_s24le" || codec == "flac") return AV_SAMPLE_FMT_S32;
    throw Error(AVERROR_ENCODER_NOT_FOUND, "Unsupported audio encoder request: " + codec);
}
}

AudioPipeline::AudioPipeline(
    AVFormatContext* inputContext,
    AVFormatContext* outputContext,
    int inputStreamIndex,
    AVCodecContext* audioDecoder,
    const ExportRequest& exportRequest,
    const LogCapture& capture
) : input(inputContext),
    output(outputContext),
    inputStream(inputContext->streams[inputStreamIndex]),
    decoder(audioDecoder),
    request(exportRequest),
    logs(capture) {
    encoder = avcodec_find_encoder_by_name(request.audioCodec.c_str());
    if (!encoder) throw Error(AVERROR_ENCODER_NOT_FOUND, "Audio encoder is not compiled: " + request.audioCodec);
    encoderContext.reset(avcodec_alloc_context3(encoder));
    if (!encoderContext) throw Error(ENOMEM, "Could not allocate audio encoder");

    encoderContext->sample_rate = request.audioSampleRate > 0 ? request.audioSampleRate : decoder->sample_rate;
    if (encoderContext->sample_rate <= 0) throw Error(AVERROR(EINVAL), "Source audio has no valid sample rate");
    if (decoder->ch_layout.nb_channels > 0 && decoder->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC) {
        check(av_channel_layout_copy(&encoderContext->ch_layout, &decoder->ch_layout), "Could not copy channel layout", logs);
    } else {
        av_channel_layout_default(&encoderContext->ch_layout, decoder->ch_layout.nb_channels > 0 ? decoder->ch_layout.nb_channels : 2);
    }
    encoderContext->sample_fmt = outputSampleFormat(request.audioCodec);
    encoderContext->time_base = {1, encoderContext->sample_rate};
    encoderContext->thread_count = request.threadCount;
    if (request.audioCodec == "aac") encoderContext->bit_rate = request.audioBitRate;
    if (output->oformat->flags & AVFMT_GLOBALHEADER) encoderContext->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    check(avcodec_open2(encoderContext.get(), encoder, nullptr), "Could not open audio encoder " + request.audioCodec, logs);

    stream = avformat_new_stream(output, nullptr);
    if (!stream) throw Error(ENOMEM, "Could not create output audio stream");
    stream->time_base = encoderContext->time_base;
    check(avcodec_parameters_from_context(stream->codecpar, encoderContext.get()), "Could not write audio codec parameters", logs);

    fifo.reset(av_audio_fifo_alloc(encoderContext->sample_fmt, encoderContext->ch_layout.nb_channels, 1));
    if (!fifo) throw Error(ENOMEM, "Could not allocate audio FIFO");
}

AudioPipeline::~AudioPipeline() {
    av_channel_layout_uninit(&inputLayout);
}

int64_t AudioPipeline::originPts() const {
    const int64_t streamStart = inputStream->start_time == AV_NOPTS_VALUE ? 0 : inputStream->start_time;
    return streamStart + av_rescale_q(request.trimStartUs, AV_TIME_BASE_Q, inputStream->time_base);
}

int64_t AudioPipeline::endPts() const {
    if (request.trimEndUs < 0) return AV_NOPTS_VALUE;
    const int64_t streamStart = inputStream->start_time == AV_NOPTS_VALUE ? 0 : inputStream->start_time;
    return streamStart + av_rescale_q(request.trimEndUs, AV_TIME_BASE_Q, inputStream->time_base);
}

FramePtr AudioPipeline::allocateFrame(int samples) {
    auto frame = makeFrame();
    frame->format = encoderContext->sample_fmt;
    frame->sample_rate = encoderContext->sample_rate;
    frame->nb_samples = samples;
    check(av_channel_layout_copy(&frame->ch_layout, &encoderContext->ch_layout), "Could not set output audio layout", logs);
    if (samples > 0) check(av_frame_get_buffer(frame.get(), 0), "Could not allocate audio samples", logs);
    return frame;
}

void AudioPipeline::sendPacket(AVPacket* packet) {
    const int sent = avcodec_send_packet(decoder, packet);
    if (sent < 0 && sent != AVERROR_INVALIDDATA) check(sent, "Could not submit compressed audio", logs);
    while (true) {
        auto frame = makeFrame();
        const int received = avcodec_receive_frame(decoder, frame.get());
        if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
        if (received == AVERROR_INVALIDDATA) continue;
        check(received, "Could not decode audio", logs);
        processFrame(std::move(frame));
    }
}

void AudioPipeline::processFrame(FramePtr frame) {
    if (frame->nb_samples <= 0) return;
    if (!resampler) {
        inputSampleFormat = static_cast<AVSampleFormat>(frame->format);
        inputSampleRate = frame->sample_rate > 0 ? frame->sample_rate : decoder->sample_rate;
        check(av_channel_layout_copy(&inputLayout, &frame->ch_layout), "Could not read decoded audio layout", logs);
        if (inputLayout.nb_channels <= 0) av_channel_layout_default(&inputLayout, encoderContext->ch_layout.nb_channels);
        SwrContext* raw = nullptr;
        check(swr_alloc_set_opts2(
            &raw, &encoderContext->ch_layout, encoderContext->sample_fmt, encoderContext->sample_rate,
            &inputLayout, inputSampleFormat, inputSampleRate, 0, nullptr
        ), "Could not allocate audio resampler", logs);
        resampler.reset(raw);
        check(swr_init(raw), "Could not initialize audio resampler", logs);
    } else if (frame->format != inputSampleFormat ||
               (frame->sample_rate > 0 && frame->sample_rate != inputSampleRate) ||
               av_channel_layout_compare(&frame->ch_layout, &inputLayout) != 0) {
        throw Error(AVERROR(EINVAL), "Mid-stream audio sample-format/rate/layout changes need an explicit re-open; export stopped instead of drifting audio");
    }

    int first = 0;
    int last = frame->nb_samples;
    int64_t timestamp = frame->best_effort_timestamp;
    if (timestamp == AV_NOPTS_VALUE) timestamp = frame->pts;
    if (timestamp != AV_NOPTS_VALUE) {
        const auto origin = originPts();
        const auto sampleTimeBase = AVRational{1, inputSampleRate};
        if (timestamp < origin) {
            const auto clipped = av_rescale_q_rnd(origin - timestamp, inputStream->time_base, sampleTimeBase, AV_ROUND_UP);
            first = static_cast<int>(std::clamp<int64_t>(clipped, 0, frame->nb_samples));
        }
        const auto end = endPts();
        if (end != AV_NOPTS_VALUE) {
            const auto frameDuration = av_rescale_q(frame->nb_samples, sampleTimeBase, inputStream->time_base);
            const auto frameEnd = timestamp + frameDuration;
            if (frameEnd > end) {
                const auto clipped = av_rescale_q_rnd(frameEnd - end, inputStream->time_base, sampleTimeBase, AV_ROUND_UP);
                last -= static_cast<int>(std::clamp<int64_t>(clipped, 0, frame->nb_samples));
            }
        }
    }
    if (first >= last) return;
    if (first > 0 || last < frame->nb_samples) {
        check(av_frame_make_writable(frame.get()), "Could not clip decoded audio", logs);
        const int bytesPerSample = av_get_bytes_per_sample(inputSampleFormat);
        const bool planar = av_sample_fmt_is_planar(inputSampleFormat);
        const int planes = planar ? inputLayout.nb_channels : 1;
        const int64_t byteOffset = static_cast<int64_t>(first) * bytesPerSample * (planar ? 1 : inputLayout.nb_channels);
        for (int plane = 0; plane < planes; ++plane) {
            if (frame->data[plane]) frame->data[plane] += byteOffset;
            if (frame->extended_data && plane < frame->ch_layout.nb_channels) frame->extended_data[plane] += byteOffset;
        }
        frame->nb_samples = last - first;
        if (timestamp != AV_NOPTS_VALUE) frame->pts = timestamp + av_rescale_q(first, {1, inputSampleRate}, inputStream->time_base);
    }
    resample(frame.get());
}

void AudioPipeline::resample(const AVFrame* inputFrame) {
    const int inputSamples = inputFrame ? inputFrame->nb_samples : 0;
    const int delay = static_cast<int>(swr_get_delay(resampler.get(), inputSampleRate));
    const int capacity = static_cast<int>(av_rescale_rnd(delay + inputSamples + 32, encoderContext->sample_rate, inputSampleRate, AV_ROUND_UP));
    auto converted = allocateFrame(std::max(capacity, 32));
    const uint8_t** inputData = inputFrame ? const_cast<const uint8_t**>(inputFrame->extended_data) : nullptr;
    const int count = swr_convert(resampler.get(), converted->data, converted->nb_samples, inputData, inputSamples);
    check(count, "Audio resampling failed", logs);
    if (count > 0) {
        converted->nb_samples = count;
        const int written = av_audio_fifo_write(fifo.get(), reinterpret_cast<void**>(converted->extended_data), count);
        if (written != count) throw Error(ENOMEM, "Could not buffer resampled audio");
        drainAvailable(false);
    }
}

void AudioPipeline::drainAvailable(bool final) {
    const int fixedSize = encoderContext->frame_size;
    while (av_audio_fifo_size(fifo.get()) > 0) {
        const int available = av_audio_fifo_size(fifo.get());
        int samples = fixedSize > 0 ? fixedSize : std::min(available, 1024);
        if (available < samples) {
            if (!final) break;
            if (fixedSize > 0 && !(encoder->capabilities & AV_CODEC_CAP_SMALL_LAST_FRAME)) {
                const int padding = fixedSize - available;
                auto silence = allocateFrame(padding);
                av_samples_set_silence(silence->extended_data, 0, padding, encoderContext->ch_layout.nb_channels, encoderContext->sample_fmt);
                if (av_audio_fifo_write(fifo.get(), reinterpret_cast<void**>(silence->extended_data), padding) != padding) {
                    throw Error(ENOMEM, "Could not pad final audio frame");
                }
            } else {
                samples = available;
            }
        }
        auto frame = allocateFrame(samples);
        const int read = av_audio_fifo_read(fifo.get(), reinterpret_cast<void**>(frame->extended_data), samples);
        if (read != samples) throw Error(AVERROR_EXTERNAL, "Audio FIFO returned an incomplete frame");
        frame->pts = nextPts;
        nextPts += samples;
        sendFrame(std::move(frame));
        if (!final && fixedSize <= 0 && available < 1024) break;
    }
}

void AudioPipeline::sendFrame(FramePtr frame) {
    check(avcodec_send_frame(encoderContext.get(), frame.get()), "Could not submit audio to encoder", logs);
    while (true) {
        auto packet = makePacket();
        const int received = avcodec_receive_packet(encoderContext.get(), packet.get());
        if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
        check(received, "Could not encode audio", logs);
        packet->stream_index = stream->index;
        av_packet_rescale_ts(packet.get(), encoderContext->time_base, stream->time_base);
        check(av_interleaved_write_frame(output, packet.get()), "Could not mux encoded audio", logs);
    }
}

void AudioPipeline::drainEncoder() {
    check(avcodec_send_frame(encoderContext.get(), nullptr), "Could not flush audio encoder", logs);
    while (true) {
        auto packet = makePacket();
        const int received = avcodec_receive_packet(encoderContext.get(), packet.get());
        if (received == AVERROR_EOF) break;
        if (received == AVERROR(EAGAIN)) continue;
        check(received, "Could not drain audio encoder", logs);
        packet->stream_index = stream->index;
        av_packet_rescale_ts(packet.get(), encoderContext->time_base, stream->time_base);
        check(av_interleaved_write_frame(output, packet.get()), "Could not mux drained audio", logs);
    }
}

void AudioPipeline::finish() {
    if (finished) return;
    finished = true;
    if (resampler) {
        while (true) {
            const int before = av_audio_fifo_size(fifo.get());
            resample(nullptr);
            const int delay = swr_get_delay(resampler.get(), inputSampleRate);
            if (delay <= 0 || av_audio_fifo_size(fifo.get()) == before) break;
        }
        drainAvailable(true);
    }
    drainEncoder();
}

} // namespace ccplayer::ffmpeg
