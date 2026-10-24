#include "playback.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <utility>

namespace ccplayer::ffmpeg {
using namespace detail;
using Clock = std::chrono::steady_clock;

namespace {
constexpr auto kWaitSlice = std::chrono::microseconds(20000);
constexpr int64_t kLateFrameThresholdUs = 150000;

AVCodecContext* openDecoder(AVFormatContext* input, int streamIndex, const LogCapture& logs, CodecPtr& output) {
    const auto* decoder = avcodec_find_decoder(input->streams[streamIndex]->codecpar->codec_id);
    if (!decoder) throw Error(AVERROR_DECODER_NOT_FOUND, "No enabled decoder for " + std::string(avcodec_get_name(input->streams[streamIndex]->codecpar->codec_id)));
    output.reset(avcodec_alloc_context3(decoder));
    if (!output) throw Error(ENOMEM, "Could not allocate a decoder");
    check(avcodec_parameters_to_context(output.get(), input->streams[streamIndex]->codecpar), "Could not copy decoder parameters", logs);
    output->pkt_timebase = input->streams[streamIndex]->time_base;
    output->thread_count = 0;
    output->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    check(avcodec_open2(output.get(), decoder, nullptr), "Could not open decoder " + std::string(decoder->name), logs);
    return output.get();
}

int displayRotation(AVStream* stream) {
    const auto* sideData = av_packet_side_data_get(
        stream->codecpar->coded_side_data, stream->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX
    );
    if (!sideData || sideData->size < 9 * sizeof(int32_t)) return 0;
    const auto* matrix = reinterpret_cast<const int32_t*>(sideData->data);
    const double rotation = av_display_rotation_get(matrix);
    if (!std::isfinite(rotation)) return 0;
    int normalized = static_cast<int>(std::lround(-rotation)) % 360;
    if (normalized < 0) normalized += 360;
    return (normalized / 90) * 90;
}

struct StateSnapshot {
    bool playing = false;
    float speed = 1;
    int64_t basePositionUs = 0;
    Clock::time_point anchor = Clock::now();
};
}

struct NativePlayer::Impl {
    std::shared_ptr<PlaybackSink> sink;
    PlaybackListener listener;
    InputPtr input;
    CodecPtr videoDecoder;
    CodecPtr audioDecoder;
    SwrPtr resampler;
    AVStream* videoStream = nullptr;
    AVStream* audioStream = nullptr;
    int videoIndex = -1;
    int audioIndex = -1;
    AVChannelLayout audioLayout = {};
    AVSampleFormat audioFormat = AV_SAMPLE_FMT_NONE;
    int audioSampleRate = 0;
    int64_t nextAudioPositionUs = 0;
    int64_t nextVideoPositionUs = 0;
    int rotation = 0;

    std::thread worker;
    mutable std::mutex stateMutex;
    std::condition_variable wake;
    std::array<float, 16> grade = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    std::atomic<bool> playing{false};
    std::atomic<bool> ended{false};
    std::atomic<bool> repeat{false};
    std::atomic<bool> previewPending{false};
    bool inputEof = false;
    std::atomic<bool> stopping{false};
    float speed = 1;
    int64_t basePositionUs = 0;
    int64_t mediaDurationUs = 0;
    Clock::time_point anchor = Clock::now();
    std::atomic<int64_t> requestedPositionUs{0};
    std::atomic<uint64_t> generation{1};
    uint64_t appliedGeneration = 0;

    ~Impl() { av_channel_layout_uninit(&audioLayout); }

    StateSnapshot stateSnapshot() const {
        std::lock_guard<std::mutex> lock(stateMutex);
        return {playing, speed, basePositionUs, anchor};
    }

    int64_t position() const {
        const auto state = stateSnapshot();
        int64_t result = state.basePositionUs;
        if (state.playing) {
            if (audioDecoder) {
                const int64_t audioPosition = sink ? sink->audioPositionUs() : -1;
                if (audioPosition >= 0) result = audioPosition;
                else result += static_cast<int64_t>(std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - state.anchor).count() * state.speed);
            } else {
                result += static_cast<int64_t>(std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - state.anchor).count() * state.speed);
            }
        }
        if (mediaDurationUs > 0) result = std::min(result, mediaDurationUs);
        return std::max<int64_t>(0, result);
    }

    void notifyState() {
        const auto state = stateSnapshot();
        if (listener.onState) listener.onState(state.playing, ended);
    }

    void requestSeek(int64_t targetUs) {
        if (mediaDurationUs > 0) targetUs = std::clamp<int64_t>(targetUs, 0, mediaDurationUs);
        targetUs = std::max<int64_t>(0, targetUs);
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            basePositionUs = targetUs;
            anchor = Clock::now();
            ended = false;
            previewPending = videoDecoder != nullptr;
        }
        requestedPositionUs.store(targetUs);
        generation.fetch_add(1);
        if (sink) sink->flushAudio();
        wake.notify_all();
        notifyState();
    }

    void performSeek(uint64_t targetGeneration) {
        LogCapture logs;
        const int64_t target = requestedPositionUs.load();
        const int64_t timestamp = av_rescale_q(target, AV_TIME_BASE_Q, AV_TIME_BASE_Q);
        check(av_seek_frame(input.get(), -1, timestamp, AVSEEK_FLAG_BACKWARD), "Could not seek native media", logs);
        if (videoDecoder) avcodec_flush_buffers(videoDecoder.get());
        if (audioDecoder) avcodec_flush_buffers(audioDecoder.get());
        if (resampler) {
            swr_close(resampler.get());
            check(swr_init(resampler.get()), "Could not reset the audio resampler", logs);
        }
        if (sink) sink->flushAudio();
        inputEof = false;
        nextAudioPositionUs = target;
        nextVideoPositionUs = target;
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            basePositionUs = target;
            anchor = Clock::now();
            previewPending = videoDecoder != nullptr;
            ended = false;
        }
        appliedGeneration = targetGeneration;
    }

    bool generationChanged(uint64_t expected) const { return generation.load() != expected || stopping; }

    bool waitForPresentation(int64_t presentationUs, uint64_t expectedGeneration) {
        while (!generationChanged(expectedGeneration)) {
            const auto state = stateSnapshot();
            if (!state.playing) return false;
            const int64_t currentPosition = position();
            const int64_t remaining = presentationUs - currentPosition;
            if (remaining <= 0) return currentPosition - presentationUs <= kLateFrameThresholdUs;
            std::unique_lock<std::mutex> lock(stateMutex);
            wake.wait_for(lock, std::min(kWaitSlice, std::chrono::microseconds(std::max<int64_t>(remaining, 1))));
        }
        return false;
    }

    void processVideo(FramePtr frame, uint64_t expectedGeneration) {
        if (generationChanged(expectedGeneration)) return;
        int64_t timestamp = frame->best_effort_timestamp;
        if (timestamp == AV_NOPTS_VALUE) timestamp = frame->pts;
        const int64_t presentation = timestamp == AV_NOPTS_VALUE
            ? nextVideoPositionUs
            : av_rescale_q(timestamp, videoStream->time_base, AV_TIME_BASE_Q);
        int64_t frameDuration = 0;
        if (frame->duration > 0) frameDuration = av_rescale_q(frame->duration, videoStream->time_base, AV_TIME_BASE_Q);
        if (frameDuration <= 0 && videoDecoder->framerate.num > 0 && videoDecoder->framerate.den > 0) {
            frameDuration = av_rescale_q(1, av_inv_q(videoDecoder->framerate), AV_TIME_BASE_Q);
        }
        if (frameDuration <= 0) frameDuration = 40000;
        nextVideoPositionUs = presentation + frameDuration;

        const auto state = stateSnapshot();
        if (!state.playing) {
            if (!previewPending || presentation + frameDuration <= requestedPositionUs.load()) return;
            std::array<float, 16> currentGrade;
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                if (!previewPending) return;
                previewPending = false;
                currentGrade = grade;
                basePositionUs = std::max<int64_t>(0, presentation);
                anchor = Clock::now();
            }
            sink->renderVideo(frame.get(), presentation, currentGrade);
            return;
        }
        if (!waitForPresentation(presentation, expectedGeneration)) return;
        std::array<float, 16> currentGrade;
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            currentGrade = grade;
            previewPending = false;
        }
        sink->renderVideo(frame.get(), presentation, currentGrade);
    }

    void initializeResampler(const AVFrame* frame, const LogCapture& logs) {
        audioFormat = static_cast<AVSampleFormat>(frame->format);
        audioSampleRate = frame->sample_rate > 0 ? frame->sample_rate : audioDecoder->sample_rate;
        if (audioSampleRate <= 0) throw Error(AVERROR(EINVAL), "Decoded audio has no valid sample rate");
        if (frame->ch_layout.nb_channels > 0 && frame->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC) {
            check(av_channel_layout_copy(&audioLayout, &frame->ch_layout), "Could not copy decoded audio layout", logs);
        } else {
            av_channel_layout_default(&audioLayout, frame->ch_layout.nb_channels > 0 ? frame->ch_layout.nb_channels : 2);
        }
        SwrContext* raw = nullptr;
        check(swr_alloc_set_opts2(
            &raw, &audioLayout, AV_SAMPLE_FMT_S16, audioSampleRate,
            &audioLayout, audioFormat, audioSampleRate, 0, nullptr
        ), "Could not allocate playback audio resampler", logs);
        resampler.reset(raw);
        check(swr_init(raw), "Could not initialize playback audio resampler", logs);
    }

    void processAudio(FramePtr frame, uint64_t expectedGeneration) {
        if (generationChanged(expectedGeneration) || frame->nb_samples <= 0) return;
        LogCapture logs;
        if (!resampler) {
            initializeResampler(frame.get(), logs);
        } else if (audioFormat != frame->format ||
                   (frame->sample_rate > 0 && frame->sample_rate != audioSampleRate) ||
                   (frame->ch_layout.order != AV_CHANNEL_ORDER_UNSPEC && av_channel_layout_compare(&frame->ch_layout, &audioLayout) != 0)) {
            throw Error(AVERROR(EINVAL), "Mid-stream audio format/rate/layout changes require an explicit playback re-open");
        }
        const int capacity = frame->nb_samples + static_cast<int>(swr_get_delay(resampler.get(), audioSampleRate)) + 32;
        auto converted = makeFrame();
        converted->format = AV_SAMPLE_FMT_S16;
        converted->sample_rate = audioSampleRate;
        converted->nb_samples = capacity;
        check(av_channel_layout_copy(&converted->ch_layout, &audioLayout), "Could not set playback audio layout", logs);
        check(av_frame_get_buffer(converted.get(), 0), "Could not allocate playback PCM", logs);
        const int samples = swr_convert(
            resampler.get(), converted->data, capacity,
            const_cast<const uint8_t**>(frame->extended_data), frame->nb_samples
        );
        check(samples, "Could not convert playback audio", logs);
        if (samples <= 0 || generationChanged(expectedGeneration)) return;

        int64_t timestamp = frame->best_effort_timestamp;
        if (timestamp == AV_NOPTS_VALUE) timestamp = frame->pts;
        const int64_t presentation = timestamp == AV_NOPTS_VALUE
            ? nextAudioPositionUs
            : av_rescale_q(timestamp, audioStream->time_base, AV_TIME_BASE_Q);
        nextAudioPositionUs = presentation + av_rescale_q(samples, {1, audioSampleRate}, AV_TIME_BASE_Q);
        int firstSample = 0;
        int64_t outputPresentation = presentation;
        const int64_t target = requestedPositionUs.load();
        if (outputPresentation < target) {
            firstSample = static_cast<int>(std::clamp<int64_t>(
                av_rescale_q_rnd(target - outputPresentation, AV_TIME_BASE_Q, {1, audioSampleRate}, AV_ROUND_UP),
                0, samples
            ));
            if (firstSample >= samples) return;
            outputPresentation += av_rescale_q(firstSample, {1, audioSampleRate}, AV_TIME_BASE_Q);
        }
        sink->writeAudio(
            reinterpret_cast<const int16_t*>(converted->data[0]) + static_cast<ptrdiff_t>(firstSample) * audioLayout.nb_channels,
            static_cast<size_t>(samples - firstSample), audioSampleRate, audioLayout.nb_channels,
            outputPresentation, expectedGeneration
        );
    }

    void decode(AVCodecContext* decoder, AVPacket* packet, bool video, uint64_t expectedGeneration) {
        LogCapture logs;
        const int sent = avcodec_send_packet(decoder, packet);
        if (sent < 0 && sent != AVERROR_INVALIDDATA) check(sent, "Could not submit a compressed packet", logs);
        while (!generationChanged(expectedGeneration)) {
            auto frame = makeFrame();
            const int received = avcodec_receive_frame(decoder, frame.get());
            if (received == AVERROR(EAGAIN) || received == AVERROR_EOF) break;
            if (received == AVERROR_INVALIDDATA) continue;
            check(received, "Could not decode a media frame", logs);
            if (video) processVideo(std::move(frame), expectedGeneration);
            else processAudio(std::move(frame), expectedGeneration);
        }
    }

    void finishPlayback() {
        {
            std::lock_guard<std::mutex> lock(stateMutex);
            if (mediaDurationUs > 0) basePositionUs = mediaDurationUs;
            else basePositionUs = std::max(nextVideoPositionUs, nextAudioPositionUs);
            anchor = Clock::now();
            playing = false;
            ended = true;
            previewPending = false;
        }
        notifyState();
    }

    void run() {
        LogCapture logs;
        try {
            while (!stopping) {
                const uint64_t requestedGeneration = generation.load();
                if (appliedGeneration != requestedGeneration) {
                    performSeek(requestedGeneration);
                    continue;
                }
                if (inputEof) {
                    std::unique_lock<std::mutex> lock(stateMutex);
                    wake.wait(lock, [&] { return stopping || appliedGeneration != generation.load(); });
                    continue;
                }
                {
                    std::unique_lock<std::mutex> lock(stateMutex);
                    wake.wait(lock, [&] { return stopping || playing || previewPending || appliedGeneration != generation.load(); });
                    if (stopping || appliedGeneration != generation.load()) continue;
                }

                auto packet = makePacket();
                const int read = av_read_frame(input.get(), packet.get());
                if (read == AVERROR_EOF) {
                    if (videoDecoder) decode(videoDecoder.get(), nullptr, true, requestedGeneration);
                    if (audioDecoder && playing) decode(audioDecoder.get(), nullptr, false, requestedGeneration);
                    if (generationChanged(requestedGeneration)) continue;
                    if (repeat) {
                        requestedPositionUs.store(0);
                        generation.fetch_add(1);
                        continue;
                    }
                    inputEof = true;
                    finishPlayback();
                    continue;
                }
                check(read, "Could not read a compressed media packet", logs);
                if (packet->stream_index == videoIndex) {
                    decode(videoDecoder.get(), packet.get(), true, requestedGeneration);
                } else if (packet->stream_index == audioIndex && playing) {
                    decode(audioDecoder.get(), packet.get(), false, requestedGeneration);
                }
            }
        } catch (const std::exception& error) {
            {
                std::lock_guard<std::mutex> lock(stateMutex);
                playing = false;
                ended = false;
                previewPending = false;
            }
            notifyState();
            if (listener.onError) listener.onError(error.what());
        } catch (...) {
            if (listener.onError) listener.onError("Unknown native playback failure");
        }
    }
};

NativePlayer::NativePlayer() : impl(std::make_unique<Impl>()) {}
NativePlayer::~NativePlayer() { close(); }

void NativePlayer::open(const std::string& inputPath, std::shared_ptr<PlaybackSink> outputSink, PlaybackListener outputListener) {
    if (impl->input || impl->worker.joinable()) throw Error(AVERROR(EINVAL), "Native player is already open");
    impl->sink = std::move(outputSink);
    impl->listener = std::move(outputListener);
    if (!impl->sink) throw Error(AVERROR(EINVAL), "A playback sink is required");
    LogCapture logs;
    impl->input = openInput(inputPath, logs);
    impl->mediaDurationUs = impl->input->duration == AV_NOPTS_VALUE ? 0 : impl->input->duration;
    impl->videoIndex = av_find_best_stream(impl->input.get(), AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (impl->videoIndex >= 0) {
        impl->videoStream = impl->input->streams[impl->videoIndex];
        if (impl->videoStream->disposition & AV_DISPOSITION_ATTACHED_PIC) impl->videoIndex = -1;
    }
    if (impl->videoIndex >= 0) {
        openDecoder(impl->input.get(), impl->videoIndex, logs, impl->videoDecoder);
        impl->videoStream = impl->input->streams[impl->videoIndex];
        impl->rotation = displayRotation(impl->videoStream);
        impl->sink->setVideoRotation(impl->rotation);
        const bool swap = impl->rotation == 90 || impl->rotation == 270;
        const auto sar = av_guess_sample_aspect_ratio(impl->input.get(), impl->videoStream, nullptr);
        const float pixelAspect = sar.num > 0 && sar.den > 0 ? static_cast<float>(sar.num) / sar.den : 1.0f;
        if (impl->listener.onVideoSize) impl->listener.onVideoSize(
            swap ? impl->videoDecoder->height : impl->videoDecoder->width,
            swap ? impl->videoDecoder->width : impl->videoDecoder->height,
            swap && pixelAspect > 0 ? 1.0f / pixelAspect : pixelAspect
        );
    }
    impl->audioIndex = av_find_best_stream(impl->input.get(), AVMEDIA_TYPE_AUDIO, -1, impl->videoIndex, nullptr, 0);
    if (impl->audioIndex >= 0) {
        impl->audioStream = impl->input->streams[impl->audioIndex];
        openDecoder(impl->input.get(), impl->audioIndex, logs, impl->audioDecoder);
    }
    if (!impl->videoDecoder && !impl->audioDecoder) throw Error(AVERROR(EINVAL), "The file has no enabled audio or video stream");
    impl->previewPending = impl->videoDecoder != nullptr;
    impl->worker = std::thread([impl = impl.get()] { impl->run(); });
}

void NativePlayer::close() noexcept {
    if (!impl) return;
    {
        std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->stopping = true;
        impl->playing = false;
        impl->previewPending = false;
    }
    impl->generation.fetch_add(1);
    if (impl->sink) {
        try { impl->sink->flushAudio(); } catch (...) { /* release must continue */ }
    }
    impl->wake.notify_all();
    if (impl->worker.joinable()) impl->worker.join();
    impl->worker = {};
    impl->resampler.reset();
    impl->videoDecoder.reset();
    impl->audioDecoder.reset();
    impl->input.reset();
}

void NativePlayer::play() {
    if (!impl->input || impl->playing.load()) return;
    const int64_t position = impl->ended ? 0 : impl->position();
    impl->requestSeek(position);
    {
        std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->basePositionUs = position;
        impl->anchor = Clock::now();
        impl->playing = true;
        impl->ended = false;
    }
    impl->sink->setAudioSpeed(impl->stateSnapshot().speed);
    impl->wake.notify_all();
    impl->notifyState();
}

void NativePlayer::pause() {
    if (!impl->input) return;
    const int64_t position = impl->position();
    {
        std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->playing = false;
        impl->basePositionUs = position;
        impl->anchor = Clock::now();
    }
    impl->requestSeek(position);
    impl->notifyState();
}

void NativePlayer::seekTo(int64_t positionUs) { impl->requestSeek(positionUs); }

void NativePlayer::setSpeed(float value) {
    if (!std::isfinite(value) || value < 0.25f || value > 4.0f) throw Error(AVERROR(EINVAL), "Playback speed must be between 0.25 and 4");
    const int64_t position = impl->position();
    {
        std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->speed = value;
        impl->basePositionUs = position;
        impl->anchor = Clock::now();
    }
    impl->sink->setAudioSpeed(value);
    impl->requestSeek(position);
}

void NativePlayer::setRepeat(bool value) {
    std::lock_guard<std::mutex> lock(impl->stateMutex);
    impl->repeat = value;
}

void NativePlayer::setGrade(const std::array<float, 16>& value) {
    const bool wasPlaying = impl->playing.load();
    const int64_t position = wasPlaying ? 0 : impl->position();
    {
        std::lock_guard<std::mutex> lock(impl->stateMutex);
        impl->grade = value;
    }
    if (!wasPlaying && impl->input) impl->requestSeek(position);
    impl->wake.notify_all();
}
int64_t NativePlayer::positionUs() const { return impl->position(); }
int64_t NativePlayer::durationUs() const { return impl->mediaDurationUs; }

} // namespace ccplayer::ffmpeg
