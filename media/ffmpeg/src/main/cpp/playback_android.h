#pragma once

#include "color_pipeline.h"
#include "playback.h"

#include <jni.h>
#include <android/native_window.h>

#include <atomic>
#include <mutex>
#include <vector>

namespace ccplayer::ffmpeg {

class AndroidPlaybackSink final : public PlaybackSink {
public:
    AndroidPlaybackSink(JavaVM* javaVm, jobject target);
    ~AndroidPlaybackSink() override;

    void setSurface(JNIEnv* env, jobject surface);
    void renderVideo(const AVFrame* frame, int64_t presentationUs, const std::array<float, 16>& grade) override;
    void writeAudio(const int16_t* samples, size_t sampleFrames, int sampleRate, int channels, int64_t presentationUs, uint64_t generation) override;
    int64_t audioPositionUs() override;
    void setVideoRotation(int clockwiseDegrees) override { rotation.store(clockwiseDegrees); }
    void flushAudio() override;
    void setAudioSpeed(float speed) override;

    void notifyState(bool playing, bool ended);
    void notifyVideoSize(int width, int height, float pixelAspectRatio);
    void notifyError(const std::string& message);

private:
    JNIEnv* environment();
    void checkJavaException(JNIEnv* env);

    JavaVM* vm;
    jobject target;
    jmethodID audioMethod;
    jmethodID audioPositionMethod;
    jmethodID audioFlushMethod;
    jmethodID audioSpeedMethod;
    jmethodID stateMethod;
    jmethodID videoSizeMethod;
    jmethodID errorMethod;

    std::mutex windowMutex;
    ANativeWindow* window = nullptr;
    int windowWidth = 0;
    int windowHeight = 0;
    std::atomic<int> rotation{0};
    std::unique_ptr<ColorPipeline> colorPipeline;
    int pipelineWidth = 0;
    int pipelineHeight = 0;
    std::vector<uint32_t> rotatedPixels;
    std::vector<int16_t> audioBuffer;
};

} // namespace ccplayer::ffmpeg
