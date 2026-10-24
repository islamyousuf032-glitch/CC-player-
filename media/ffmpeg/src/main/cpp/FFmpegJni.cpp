#include "native_media.h"

#include <jni.h>

#include <array>
#include <cerrno>
#include <exception>
#include <string>

namespace {
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

void throwException(JNIEnv* env, const std::string& message) {
    const auto* exceptionClass = env->FindClass("com/ccplayer/media/ffmpeg/FFmpegException");
    if (exceptionClass) env->ThrowNew(exceptionClass, message.c_str());
}

template<typename Function>
jstring guarded(JNIEnv* env, Function&& function) {
    try {
        return env->NewStringUTF(function().c_str());
    } catch (const ccplayer::ffmpeg::Error& error) {
        throwException(env, error.what());
    } catch (const std::exception& error) {
        throwException(env, error.what());
    } catch (...) {
        throwException(env, "Unknown native FFmpeg failure");
    }
    return nullptr;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegNative_nativeProbe(JNIEnv* env, jobject, jstring inputPath) {
    UtfString path(env, inputPath);
    return guarded(env, [&]() { return ccplayer::ffmpeg::toJson(ccplayer::ffmpeg::probe(path.get())); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegNative_nativeRuntimeInfo(JNIEnv* env, jobject) {
    return guarded(env, []() { return ccplayer::ffmpeg::runtimeInfoJson(); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ccplayer_media_ffmpeg_FFmpegNative_nativeExport(
    JNIEnv* env,
    jobject,
    jstring inputPath,
    jstring outputPath,
    jstring codec,
    jstring profile,
    jstring pixelFormat,
    jstring audioCodec,
    jint width,
    jint height,
    jint frameRateNum,
    jint frameRateDen,
    jint audioBitRate,
    jint audioSampleRate,
    jint videoQuality,
    jint threadCount,
    jlong trimStartUs,
    jlong trimEndUs,
    jboolean useHdr,
    jfloatArray grade,
    jobject listener
) {
    return guarded(env, [&]() {
        ccplayer::ffmpeg::ExportRequest request;
        request.inputPath = UtfString(env, inputPath).get();
        request.outputPath = UtfString(env, outputPath).get();
        request.codec = UtfString(env, codec).get();
        request.profile = UtfString(env, profile).get();
        request.pixelFormat = UtfString(env, pixelFormat).get();
        request.audioCodec = UtfString(env, audioCodec).get();
        request.width = width;
        request.height = height;
        request.frameRateNum = frameRateNum;
        request.frameRateDen = frameRateDen;
        request.audioBitRate = audioBitRate;
        request.audioSampleRate = audioSampleRate;
        request.videoQuality = videoQuality;
        request.threadCount = threadCount;
        request.trimStartUs = trimStartUs;
        request.trimEndUs = trimEndUs;
        request.useHdr = useHdr == JNI_TRUE;
        if (env->GetArrayLength(grade) != 16) throw ccplayer::ffmpeg::Error(EINVAL, "Color matrix must contain 16 floats");
        env->GetFloatArrayRegion(grade, 0, 16, request.grade.data());

        const auto listenerClass = env->GetObjectClass(listener);
        const auto progressMethod = env->GetMethodID(listenerClass, "onProgress", "(IJ)V");
        const auto cancelMethod = env->GetMethodID(listenerClass, "isCancelled", "()Z");
        if (!progressMethod || !cancelMethod) throw ccplayer::ffmpeg::Error(EINVAL, "Native export listener methods are unavailable");
        const auto onProgress = [&](int percent, int64_t positionUs) {
            env->CallVoidMethod(listener, progressMethod, static_cast<jint>(percent), static_cast<jlong>(positionUs));
        };
        int cancelCheck = 0;
        const auto isCancelled = [&]() {
            if ((cancelCheck++ % 8) != 0) return false;
            return env->CallBooleanMethod(listener, cancelMethod) == JNI_TRUE;
        };
        return ccplayer::ffmpeg::toJson(ccplayer::ffmpeg::transcode(request, onProgress, isCancelled));
    });
}
