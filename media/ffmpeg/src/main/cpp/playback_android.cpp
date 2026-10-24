#include "playback_android.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace ccplayer::ffmpeg {
using namespace detail;

namespace {
struct JvmThreadState {
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    bool owned = false;
    ~JvmThreadState() {
        if (owned && vm) vm->DetachCurrentThread();
    }
};
thread_local JvmThreadState threadState;

bool identityGrade(const std::array<float, 16>& grade) {
    constexpr float identity[16] = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    for (int index = 0; index < 16; ++index) {
        if (std::abs(grade[index] - identity[index]) > 1e-6f) return false;
    }
    return true;
}
}

AndroidPlaybackSink::AndroidPlaybackSink(JavaVM* javaVm, jobject player) : vm(javaVm) {
    JNIEnv* env = environment();
    target = env->NewGlobalRef(player);
    if (!target) throw Error(ENOMEM, "Could not retain the Android playback target");
    const auto targetClass = env->GetObjectClass(target);
    audioMethod = env->GetMethodID(targetClass, "onNativeAudio", "(Ljava/nio/ByteBuffer;IIJJ)V");
    audioPositionMethod = env->GetMethodID(targetClass, "onNativeAudioPosition", "()J");
    audioFlushMethod = env->GetMethodID(targetClass, "onNativeAudioFlush", "()V");
    audioSpeedMethod = env->GetMethodID(targetClass, "onNativeAudioSpeed", "(F)V");
    stateMethod = env->GetMethodID(targetClass, "onNativeState", "(ZZ)V");
    videoSizeMethod = env->GetMethodID(targetClass, "onNativeVideoSize", "(IIF)V");
    errorMethod = env->GetMethodID(targetClass, "onNativePlaybackError", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(targetClass);
    if (!audioMethod || !audioPositionMethod || !audioFlushMethod || !audioSpeedMethod || !stateMethod || !videoSizeMethod || !errorMethod) {
        throw Error(AVERROR(EINVAL), "Android native playback callback methods are unavailable");
    }
}

AndroidPlaybackSink::~AndroidPlaybackSink() {
    {
        std::lock_guard<std::mutex> lock(windowMutex);
        if (window) ANativeWindow_release(window);
        window = nullptr;
    }
    if (target) {
        try {
            JNIEnv* env = environment();
            env->DeleteGlobalRef(target);
        } catch (...) { /* JNI may already be shutting down */ }
    }
}

JNIEnv* AndroidPlaybackSink::environment() {
    if (threadState.env && threadState.vm == vm) return threadState.env;
    JNIEnv* env = nullptr;
    const int status = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) throw Error(AVERROR_EXTERNAL, "Could not attach native playback thread to the JVM");
        threadState = {vm, env, true};
    } else if (status == JNI_OK) {
        threadState = {vm, env, false};
    } else {
        throw Error(AVERROR_EXTERNAL, "Could not acquire a JNI environment for native playback");
    }
    return env;
}

void AndroidPlaybackSink::checkJavaException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return;
    env->ExceptionClear();
    throw Error(AVERROR_EXTERNAL, "Android audio/callback failed during native playback");
}

void AndroidPlaybackSink::setSurface(JNIEnv* env, jobject surface) {
    ANativeWindow* replacement = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(windowMutex);
    if (window) ANativeWindow_release(window);
    window = replacement;
    windowWidth = 0;
    windowHeight = 0;
}

void AndroidPlaybackSink::renderVideo(const AVFrame* source, int64_t, const std::array<float, 16>& grade) {
    if (!source || source->width <= 0 || source->height <= 0) return;
    LogCapture logs;
    if (!colorPipeline || pipelineWidth != source->width || pipelineHeight != source->height) {
        colorPipeline = std::make_unique<ColorPipeline>(
            source->width, source->height, AV_PIX_FMT_RGBA, AVCOL_SPC_RGB, AVCOL_RANGE_JPEG,
            grade, false, AVCOL_TRC_IEC61966_2_1
        );
        pipelineWidth = source->width;
        pipelineHeight = source->height;
    }
    const bool hdr = source->color_trc == AVCOL_TRC_SMPTE2084 || source->color_trc == AVCOL_TRC_ARIB_STD_B67;
    colorPipeline->setGrade(grade, identityGrade(grade) && !hdr);
    auto converted = colorPipeline->process(source, logs);

    const int angle = rotation.load();
    const int outputWidth = angle == 90 || angle == 270 ? converted->height : converted->width;
    const int outputHeight = angle == 90 || angle == 270 ? converted->width : converted->height;
    const uint8_t* pixels = converted->data[0];
    int inputStride = converted->linesize[0];
    if (angle != 0) {
        rotatedPixels.resize(static_cast<size_t>(outputWidth) * outputHeight);
        for (int y = 0; y < converted->height; ++y) {
            const auto* inputRow = reinterpret_cast<const uint32_t*>(converted->data[0] + y * converted->linesize[0]);
            for (int x = 0; x < converted->width; ++x) {
                int outputX = x;
                int outputY = y;
                if (angle == 90) { outputX = converted->height - 1 - y; outputY = x; }
                else if (angle == 180) { outputX = converted->width - 1 - x; outputY = converted->height - 1 - y; }
                else if (angle == 270) { outputX = y; outputY = converted->width - 1 - x; }
                rotatedPixels[static_cast<size_t>(outputY) * outputWidth + outputX] = inputRow[x];
            }
        }
        pixels = reinterpret_cast<const uint8_t*>(rotatedPixels.data());
        inputStride = outputWidth * static_cast<int>(sizeof(uint32_t));
    }

    std::lock_guard<std::mutex> lock(windowMutex);
    if (!window) return;
    if (windowWidth != outputWidth || windowHeight != outputHeight) {
        const int result = ANativeWindow_setBuffersGeometry(window, outputWidth, outputHeight, WINDOW_FORMAT_RGBA_8888);
        if (result != 0) throw Error(result, "Could not configure the Android video surface");
        windowWidth = outputWidth;
        windowHeight = outputHeight;
    }
    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0 || !buffer.bits) throw Error(AVERROR_EXTERNAL, "Could not lock the Android video surface");
    const int rowBytes = outputWidth * static_cast<int>(sizeof(uint32_t));
    for (int y = 0; y < outputHeight; ++y) {
        std::memcpy(
            static_cast<uint8_t*>(buffer.bits) + static_cast<ptrdiff_t>(y) * buffer.stride * sizeof(uint32_t),
            pixels + static_cast<ptrdiff_t>(y) * inputStride,
            std::min(rowBytes, buffer.stride * static_cast<int>(sizeof(uint32_t)))
        );
    }
    ANativeWindow_unlockAndPost(window);
}

void AndroidPlaybackSink::writeAudio(const int16_t* samples, size_t sampleFrames, int sampleRate, int channels, int64_t presentationUs, uint64_t generation) {
    const size_t count = sampleFrames * static_cast<size_t>(channels);
    audioBuffer.assign(samples, samples + count);
    JNIEnv* env = environment();
    const auto buffer = env->NewDirectByteBuffer(audioBuffer.data(), static_cast<jlong>(audioBuffer.size() * sizeof(int16_t)));
    if (!buffer) throw Error(ENOMEM, "Could not allocate a direct PCM buffer");
    env->CallVoidMethod(
        target, audioMethod, buffer, static_cast<jint>(sampleRate), static_cast<jint>(channels),
        static_cast<jlong>(presentationUs), static_cast<jlong>(generation)
    );
    env->DeleteLocalRef(buffer);
    checkJavaException(env);
}

int64_t AndroidPlaybackSink::audioPositionUs() {
    JNIEnv* env = environment();
    const auto result = env->CallLongMethod(target, audioPositionMethod);
    checkJavaException(env);
    return static_cast<int64_t>(result);
}

void AndroidPlaybackSink::flushAudio() {
    JNIEnv* env = environment();
    env->CallVoidMethod(target, audioFlushMethod);
    checkJavaException(env);
}

void AndroidPlaybackSink::setAudioSpeed(float speed) {
    JNIEnv* env = environment();
    env->CallVoidMethod(target, audioSpeedMethod, static_cast<jfloat>(speed));
    checkJavaException(env);
}

void AndroidPlaybackSink::notifyState(bool playing, bool ended) {
    JNIEnv* env = environment();
    env->CallVoidMethod(target, stateMethod, static_cast<jboolean>(playing), static_cast<jboolean>(ended));
    checkJavaException(env);
}

void AndroidPlaybackSink::notifyVideoSize(int width, int height, float pixelAspectRatio) {
    JNIEnv* env = environment();
    env->CallVoidMethod(target, videoSizeMethod, width, height, pixelAspectRatio);
    checkJavaException(env);
}

void AndroidPlaybackSink::notifyError(const std::string& message) {
    JNIEnv* env = environment();
    const auto text = env->NewStringUTF(message.c_str());
    env->CallVoidMethod(target, errorMethod, text);
    env->DeleteLocalRef(text);
    checkJavaException(env);
}

} // namespace ccplayer::ffmpeg
