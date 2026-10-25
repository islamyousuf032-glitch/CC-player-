#include <jni.h>
#include <motioncam/Decoder.hpp>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>

namespace {

using json = nlohmann::json;
using Rgb = std::array<float, 3>;

struct NativeSession {
    std::unique_ptr<motioncam::Decoder> decoder;
    motioncam::AudioChunkLoader* audioLoader = nullptr;
};

NativeSession* sessionFrom(jlong pointer) {
    auto* session = reinterpret_cast<NativeSession*>(pointer);
    if (!session || !session->decoder) throw std::runtime_error("MCRAW session is closed");
    return session;
}

std::unique_ptr<motioncam::Decoder> decoderFromFd(int fd) {
#ifdef F_DUPFD_CLOEXEC
    const int duplicate = fcntl(fd, F_DUPFD_CLOEXEC, 0);
#else
    const int duplicate = fcntl(fd, F_DUPFD, 0);
#endif
    if (duplicate < 0) throw std::runtime_error("Could not duplicate input file descriptor");

    FILE* file = fdopen(duplicate, "rb");
    if (!file) {
        close(duplicate);
        throw std::runtime_error("Could not open input file descriptor");
    }
    // Decoder owns and closes the duplicated FILE*. The Kotlin-owned descriptor remains open.
    return std::make_unique<motioncam::Decoder>(file);
}

void throwJava(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = env->FindClass("java/io/IOException");
    if (exceptionClass) env->ThrowNew(exceptionClass, message.c_str());
}

float positiveJsonFloat(const json& object, const char* key, float fallback) {
    const auto it = object.find(key);
    if (it == object.end() || !it->is_number()) return fallback;
    const float value = it->get<float>();
    return std::isfinite(value) && value > 0 ? value : fallback;
}

std::vector<jint> renderDraft(motioncam::Decoder& decoder, int frameIndex, int maxDimension,
                              int& outputWidth, int& outputHeight, int quality = 0) {
    const auto& frames = decoder.getFrames();
    if (frameIndex < 0 || static_cast<size_t>(frameIndex) >= frames.size()) {
        throw std::runtime_error("Frame index is outside the MCRAW recording");
    }
    if (maxDimension < 64 || maxDimension > 4096) {
        throw std::runtime_error("Preview dimension must be between 64 and 4096");
    }
    if (quality < 0 || quality > 1) throw std::runtime_error("Unknown MCRAW demosaic quality");

    std::vector<uint8_t> data;
    json frameMetadata;
    decoder.loadFrame(frames[static_cast<size_t>(frameIndex)], data, frameMetadata);
    const auto& container = decoder.getContainerMetadata();

    const int width = frameMetadata.at("width").get<int>();
    const int height = frameMetadata.at("height").get<int>();
    if (width < 2 || height < 2) throw std::runtime_error("RAW frame is too small");
    const size_t requiredBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 2;
    if (data.size() < requiredBytes) throw std::runtime_error("RAW frame buffer is incomplete");

    const std::string arrangement = container.value("sensorArrangment", std::string("rggb"));
    if (arrangement != "rggb" && arrangement != "bggr" && arrangement != "grbg" && arrangement != "gbrg") {
        throw std::runtime_error("Unsupported MCRAW color-filter arrangement");
    }

    std::array<float, 4> black = {0, 0, 0, 0};
    const auto blackIt = container.find("blackLevel");
    if (blackIt != container.end() && blackIt->is_array()) {
        for (size_t i = 0; i < std::min<size_t>(4, blackIt->size()); ++i) {
            if (blackIt->at(i).is_number()) black[i] = blackIt->at(i).get<float>();
        }
    }
    const float white = positiveJsonFloat(container, "whiteLevel", 65535.0f);

    Rgb neutral = {1, 1, 1};
    const auto neutralIt = frameMetadata.find("asShotNeutral");
    if (neutralIt != frameMetadata.end() && neutralIt->is_array() && neutralIt->size() >= 3) {
        for (size_t i = 0; i < 3; ++i) {
            if (neutralIt->at(i).is_number()) neutral[i] = neutralIt->at(i).get<float>();
        }
    }
    Rgb whiteBalance = {1, 1, 1};
    if (neutral[0] > 0 && neutral[1] > 0 && neutral[2] > 0) {
        whiteBalance = {neutral[1] / neutral[0], 1.0f, neutral[1] / neutral[2]};
    }

    auto readNormalized = [&](int x, int y) {
        const size_t offset = (static_cast<size_t>(y) * static_cast<size_t>(width) + static_cast<size_t>(x)) * 2;
        const float sample = static_cast<float>(data[offset] | (static_cast<uint16_t>(data[offset + 1]) << 8));
        const size_t cfaIndex = static_cast<size_t>((y & 1) * 2 + (x & 1));
        const float denominator = std::max(1.0f, white - black[cfaIndex]);
        return std::clamp((sample - black[cfaIndex]) / denominator, 0.0f, 1.0f);
    };

    // A 2x2 CFA cell becomes one linear RGB sample. This avoids a full-resolution intermediate
    // allocation for a draft thumbnail. The production renderer will use a higher-quality GPU
    // demosaic, camera matrices and an explicit working color space.
    auto readCell = [&](int cellX, int cellY) {
        const int x = cellX * 2;
        const int y = cellY * 2;
        const float p00 = readNormalized(x, y);
        const float p10 = readNormalized(x + 1, y);
        const float p01 = readNormalized(x, y + 1);
        const float p11 = readNormalized(x + 1, y + 1);
        Rgb rgb{};
        if (arrangement == "rggb") {
            rgb = {p00, (p10 + p01) * 0.5f, p11};
        } else if (arrangement == "bggr") {
            rgb = {p11, (p10 + p01) * 0.5f, p00};
        } else if (arrangement == "grbg") {
            rgb = {p10, (p00 + p11) * 0.5f, p01};
        } else {
            rgb = {p01, (p00 + p11) * 0.5f, p10};
        }
        for (size_t channel = 0; channel < 3; ++channel) {
            rgb[channel] = std::clamp(rgb[channel] * whiteBalance[channel], 0.0f, 1.0f);
        }
        return rgb;
    };

    auto cfaColor = [&](int x, int y) {
        const int index = (y & 1) * 2 + (x & 1);
        if (arrangement == "rggb") return std::array<int, 4>{0, 1, 1, 2}[index];
        if (arrangement == "bggr") return std::array<int, 4>{2, 1, 1, 0}[index];
        if (arrangement == "grbg") return std::array<int, 4>{1, 0, 2, 1}[index];
        return std::array<int, 4>{1, 2, 0, 1}[index];
    };
    auto readBilinear = [&](int x, int y) {
        Rgb rgb{};
        for (int channel = 0; channel < 3; ++channel) {
            float sum = 0.0f;
            int count = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    const int sampleX = x + dx;
                    const int sampleY = y + dy;
                    if (sampleX < 0 || sampleY < 0 || sampleX >= width || sampleY >= height ||
                        cfaColor(sampleX, sampleY) != channel) continue;
                    sum += readNormalized(sampleX, sampleY);
                    ++count;
                }
            }
            rgb[channel] = count > 0 ? std::clamp(sum / count * whiteBalance[channel], 0.0f, 1.0f) : 0.0f;
        }
        return rgb;
    };

    const int cellsWide = width / 2;
    const int cellsHigh = height / 2;
    const int sourceWidth = quality == 1 ? width : cellsWide;
    const int sourceHeight = quality == 1 ? height : cellsHigh;
    const float scale = std::min(1.0f, static_cast<float>(maxDimension) / static_cast<float>(std::max(sourceWidth, sourceHeight)));
    outputWidth = std::max(1, static_cast<int>(std::floor(sourceWidth * scale)));
    outputHeight = std::max(1, static_cast<int>(std::floor(sourceHeight * scale)));

    std::vector<jint> pixels;
    pixels.reserve(static_cast<size_t>(outputWidth) * static_cast<size_t>(outputHeight));
    for (int outY = 0; outY < outputHeight; ++outY) {
        const float sourceY = std::clamp((outY + 0.5f) * sourceHeight / outputHeight - 0.5f,
                                         0.0f, static_cast<float>(sourceHeight - 1));
        const int y0 = static_cast<int>(std::floor(sourceY));
        const int y1 = std::min(sourceHeight - 1, y0 + 1);
        const float fy = sourceY - y0;
        for (int outX = 0; outX < outputWidth; ++outX) {
            const float sourceX = std::clamp((outX + 0.5f) * sourceWidth / outputWidth - 0.5f,
                                             0.0f, static_cast<float>(sourceWidth - 1));
            const int x0 = static_cast<int>(std::floor(sourceX));
            const int x1 = std::min(sourceWidth - 1, x0 + 1);
            const float fx = sourceX - x0;
            const Rgb c00 = quality == 1 ? readBilinear(x0, y0) : readCell(x0, y0);
            const Rgb c10 = quality == 1 ? readBilinear(x1, y0) : readCell(x1, y0);
            const Rgb c01 = quality == 1 ? readBilinear(x0, y1) : readCell(x0, y1);
            const Rgb c11 = quality == 1 ? readBilinear(x1, y1) : readCell(x1, y1);

            std::array<int, 3> display{};
            for (size_t channel = 0; channel < 3; ++channel) {
                const float top = c00[channel] + (c10[channel] - c00[channel]) * fx;
                const float bottom = c01[channel] + (c11[channel] - c01[channel]) * fx;
                const float linear = std::clamp(top + (bottom - top) * fy, 0.0f, 1.0f);
                display[channel] = static_cast<int>(std::lround(std::pow(linear, 1.0f / 2.2f) * 255.0f));
            }
            const uint32_t argb = 0xff000000u |
                (static_cast<uint32_t>(display[0]) << 16) |
                (static_cast<uint32_t>(display[1]) << 8) |
                static_cast<uint32_t>(display[2]);
            pixels.push_back(static_cast<jint>(argb));
        }
    }
    return pixels;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_ccplayer_media_mcraw_McrawDecoder_nativeProbe(JNIEnv* env, jobject, jint fd) {
    try {
        auto decoder = decoderFromFd(fd);
        const auto& frames = decoder->getFrames();
        json result;
        result["frameCount"] = frames.size();
        result["timestampSpanUs"] = frames.size() > 1 ? (frames.back() - frames.front()) / 1000 : 0;
        result["hasGyro"] = decoder->hasGyroData();
        result["hasAccelerometer"] = decoder->hasAccelerometerData();
        result["sensorArrangement"] = decoder->getContainerMetadata().value("sensorArrangment", std::string(""));
        try {
            result["audioSampleRateHz"] = decoder->audioSampleRateHz();
            result["audioChannels"] = decoder->numAudioChannels();
        } catch (const std::exception&) {
            result["audioSampleRateHz"] = 0;
            result["audioChannels"] = 0;
        }
        if (!frames.empty()) {
            json metadata;
            decoder->loadFrameMetadata(frames.front(), metadata);
            result["width"] = metadata.value("width", 0);
            result["height"] = metadata.value("height", 0);
        } else {
            result["width"] = 0;
            result["height"] = 0;
        }
        const std::string encoded = result.dump();
        return env->NewStringUTF(encoded.c_str());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown native MCRAW decoder error");
    }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ccplayer_media_mcraw_McrawDecoder_nativeRenderDraftPreview(
    JNIEnv* env, jobject, jint fd, jint frameIndex, jint maxDimension) {
    try {
        auto decoder = decoderFromFd(fd);
        int width = 0;
        int height = 0;
        auto pixels = renderDraft(*decoder, frameIndex, maxDimension, width, height);
        const size_t valueCount = pixels.size() + 2;
        if (valueCount > static_cast<size_t>(INT32_MAX)) {
            throw std::runtime_error("Preview buffer is too large");
        }
        jintArray result = env->NewIntArray(static_cast<jsize>(valueCount));
        if (!result) return nullptr;
        const jint dimensions[2] = {width, height};
        env->SetIntArrayRegion(result, 0, 2, dimensions);
        env->SetIntArrayRegion(result, 2, static_cast<jsize>(pixels.size()), pixels.data());
        return result;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown native MCRAW render error");
    }
    return nullptr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeOpen(JNIEnv* env, jobject, jint fd) {
    try {
        auto session = std::make_unique<NativeSession>();
        session->decoder = decoderFromFd(fd);
        session->audioLoader = &session->decoder->loadAudio();
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Could not create persistent MCRAW session");
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeFrameCount(JNIEnv* env, jobject, jlong pointer) {
    try {
        return static_cast<jint>(sessionFrom(pointer)->decoder->getFrames().size());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeFrameTimestampUs(JNIEnv* env, jobject, jlong pointer, jint frameIndex) {
    try {
        const auto& frames = sessionFrom(pointer)->decoder->getFrames();
        if (frameIndex < 0 || static_cast<size_t>(frameIndex) >= frames.size()) throw std::runtime_error("MCRAW frame index is out of range");
        return static_cast<jlong>(frames[static_cast<size_t>(frameIndex)] / 1000);
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeRender(JNIEnv* env, jobject, jlong pointer, jint frameIndex, jint maxDimension, jint quality) {
    try {
        int width = 0;
        int height = 0;
        auto pixels = renderDraft(*sessionFrom(pointer)->decoder, frameIndex, maxDimension, width, height, quality);
        if (pixels.size() + 2 > static_cast<size_t>(INT32_MAX)) throw std::runtime_error("MCRAW preview is too large");
        auto result = env->NewIntArray(static_cast<jsize>(pixels.size() + 2));
        if (!result) return nullptr;
        const jint dimensions[2] = {width, height};
        env->SetIntArrayRegion(result, 0, 2, dimensions);
        env->SetIntArrayRegion(result, 2, static_cast<jsize>(pixels.size()), pixels.data());
        return result;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown persistent MCRAW render error");
    }
    return nullptr;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeNextAudioChunk(JNIEnv* env, jobject, jlong pointer, jlongArray timestampUs) {
    try {
        if (env->GetArrayLength(timestampUs) != 1) throw std::runtime_error("Audio timestamp output must contain one value");
        motioncam::AudioChunk chunk;
        if (!sessionFrom(pointer)->audioLoader->next(chunk)) return nullptr;
        if (chunk.second.size() > static_cast<size_t>(INT32_MAX)) throw std::runtime_error("MCRAW audio chunk is too large");
        const jlong timestamp = chunk.first < 0 ? -1 : chunk.first / 1000;
        env->SetLongArrayRegion(timestampUs, 0, 1, &timestamp);
        auto result = env->NewShortArray(static_cast<jsize>(chunk.second.size()));
        if (!result) return nullptr;
        env->SetShortArrayRegion(result, 0, static_cast<jsize>(chunk.second.size()), chunk.second.data());
        return result;
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) {
        throwJava(env, "Unknown MCRAW audio decode error");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ccplayer_media_mcraw_McrawSession_nativeClose(JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<NativeSession*>(pointer);
}
