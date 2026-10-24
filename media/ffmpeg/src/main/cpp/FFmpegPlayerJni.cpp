#include "playback.h"
#include "playback_android.h"

#include <jni.h>

#include <array>
#include <memory>
#include <string>
#include <type_traits>

namespace {
struct PlayerHandle {
    std::shared_ptr<ccplayer::ffmpeg::AndroidPlaybackSink> sink;
    std::unique_ptr<ccplayer::ffmpeg::NativePlayer> player;
};

PlayerHandle* handle(jlong value) {
    auto* result = reinterpret_cast<PlayerHandle*>(value);
    if (!result || !result->player) throw ccplayer::ffmpeg::Error(AVERROR(EINVAL), "Native player is closed");
    return result;
}

void throwException(JNIEnv* env, const std::string& message) {
    const auto type = env->FindClass("com/ccplayer/media/ffmpeg/FFmpegException");
    if (type) env->ThrowNew(type, message.c_str());
}

template<typename Function>
auto guarded(JNIEnv* env, Function&& function) -> decltype(function()) {
    using Result = decltype(function());
    try {
        return function();
    } catch (const std::exception& error) {
        throwException(env, error.what());
    } catch (...) {
        throwException(env, "Unknown native playback failure");
    }
    if constexpr (!std::is_void_v<Result>) return Result{};
}

class UtfString {
public:
    UtfString(JNIEnv* environment, jstring value) : env(environment), string(value), chars(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfString() { if (chars) env->ReleaseStringUTFChars(string, chars); }
    const char* get() const { return chars ? chars : ""; }
private:
    JNIEnv* env;
    jstring string;
    const char* chars;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeCreate(JNIEnv* env, jobject target) {
    return guarded(env, [&]() -> jlong {
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK) throw ccplayer::ffmpeg::Error(AVERROR_EXTERNAL, "Could not acquire the Java VM");
        auto result = std::make_unique<PlayerHandle>();
        result->sink = std::make_shared<ccplayer::ffmpeg::AndroidPlaybackSink>(vm, target);
        result->player = std::make_unique<ccplayer::ffmpeg::NativePlayer>();
        return reinterpret_cast<jlong>(result.release());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeOpen(JNIEnv* env, jobject, jlong pointer, jstring path) {
    guarded(env, [&] {
        auto* player = handle(pointer);
        UtfString input(env, path);
        ccplayer::ffmpeg::PlaybackListener listener;
        const auto sink = player->sink;
        listener.onState = [sink](bool playing, bool ended) { sink->notifyState(playing, ended); };
        listener.onVideoSize = [sink](int width, int height, float aspect) { sink->notifyVideoSize(width, height, aspect); };
        listener.onError = [sink](const std::string& message) { sink->notifyError(message); };
        player->player->open(input.get(), sink, std::move(listener));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeSetSurface(JNIEnv* env, jobject, jlong pointer, jobject surface) {
    guarded(env, [&] { handle(pointer)->sink->setSurface(env, surface); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativePlay(JNIEnv* env, jobject, jlong pointer) {
    guarded(env, [&] { handle(pointer)->player->play(); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativePause(JNIEnv* env, jobject, jlong pointer) {
    guarded(env, [&] { handle(pointer)->player->pause(); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeSeek(JNIEnv* env, jobject, jlong pointer, jlong positionUs) {
    guarded(env, [&] { handle(pointer)->player->seekTo(positionUs); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeSetSpeed(JNIEnv* env, jobject, jlong pointer, jfloat speed) {
    guarded(env, [&] { handle(pointer)->player->setSpeed(speed); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeSetRepeat(JNIEnv* env, jobject, jlong pointer, jboolean repeat) {
    guarded(env, [&] { handle(pointer)->player->setRepeat(repeat == JNI_TRUE); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeSetGrade(JNIEnv* env, jobject, jlong pointer, jfloatArray values) {
    guarded(env, [&] {
        if (env->GetArrayLength(values) != 16) throw ccplayer::ffmpeg::Error(AVERROR(EINVAL), "Color matrix must contain 16 floats");
        std::array<float, 16> grade;
        env->GetFloatArrayRegion(values, 0, 16, grade.data());
        handle(pointer)->player->setGrade(grade);
    });
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativePosition(JNIEnv* env, jobject, jlong pointer) {
    return guarded(env, [&]() -> jlong { return handle(pointer)->player->positionUs(); });
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeDuration(JNIEnv* env, jobject, jlong pointer) {
    return guarded(env, [&]() -> jlong { return handle(pointer)->player->durationUs(); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegPlayer_nativeRelease(JNIEnv* env, jobject, jlong pointer) {
    guarded(env, [&] {
        auto* player = handle(pointer);
        player->player->close();
        delete player;
    });
}
