#include <jni.h>
#include <string>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <cmath>
#include <mutex>

#define TAG "TrekoAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex streamMutex;
static AAudioStream *recordStream = nullptr;

static void closeRecordStreamLocked() {
    if (recordStream != nullptr) {
        AAudioStream_requestStop(recordStream);
        AAudioStream_close(recordStream);
        recordStream = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeInitAudio(
    JNIEnv * /* env */,
    jobject /* this */,
    jint sampleRate,
    jint channels
) {
    LOGI("Inicializando motor de audio nativo AAudio: %d Hz, %d canales", sampleRate, channels);
    return (sampleRate > 0 && channels > 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeStartRecording(
    JNIEnv * /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(streamMutex);
    closeRecordStreamLocked();

    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        LOGE("Fallo al crear AAudioStreamBuilder: %s", AAudio_convertResultToText(result));
        return JNI_FALSE;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(builder, 16000);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);

    result = AAudioStreamBuilder_openStream(builder, &recordStream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || recordStream == nullptr) {
        LOGE("Fallo al abrir record stream: %s", AAudio_convertResultToText(result));
        recordStream = nullptr;
        return JNI_FALSE;
    }

    result = AAudioStream_requestStart(recordStream);
    if (result != AAUDIO_OK) {
        LOGE("Fallo al iniciar record stream: %s", AAudio_convertResultToText(result));
        closeRecordStreamLocked();
        return JNI_FALSE;
    }

    LOGI("Captura de audio nativa AAudio iniciada exitosamente.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeReadPcm(
    JNIEnv *env,
    jobject /* this */,
    jshortArray buffer,
    jint numSamples
) {
    if (env == nullptr || buffer == nullptr || numSamples <= 0) {
        return -1;
    }

    const jsize arrayLen = env->GetArrayLength(buffer);
    if (numSamples > arrayLen) {
        LOGE("nativeReadPcm: numSamples (%d) > arrayLen (%d)", numSamples, arrayLen);
        return -1;
    }

    std::lock_guard<std::mutex> lock(streamMutex);
    if (recordStream == nullptr) {
        return -1;
    }

    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);
    if (bufPtr == nullptr) {
        return -1;
    }

    const int64_t timeoutNanoseconds = 20LL * 1000LL * 1000LL; // 20 ms
    const aaudio_result_t framesRead = AAudioStream_read(recordStream, bufPtr, numSamples, timeoutNanoseconds);

    env->ReleaseShortArrayElements(buffer, bufPtr, framesRead > 0 ? 0 : JNI_ABORT);
    if (framesRead == AAUDIO_ERROR_DISCONNECTED) {
        LOGE("nativeReadPcm: stream desconectado, cerrando handle nativo.");
        closeRecordStreamLocked();
    }
    return static_cast<jint>(framesRead);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeCalculateRms(
    JNIEnv *env,
    jobject /* this */,
    jshortArray buffer,
    jint length
) {
    if (env == nullptr || buffer == nullptr || length <= 0) {
        return 0.0f;
    }

    const jsize arrayLen = env->GetArrayLength(buffer);
    const jint safeLen = length < arrayLen ? length : arrayLen;
    if (safeLen <= 0) {
        return 0.0f;
    }

    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);
    if (bufPtr == nullptr) {
        return 0.0f;
    }

    double sum = 0.0;
    for (jint i = 0; i < safeLen; ++i) {
        const double val = bufPtr[i] / 32768.0;
        sum += val * val;
    }
    env->ReleaseShortArrayElements(buffer, bufPtr, JNI_ABORT);

    return static_cast<jfloat>(std::sqrt(sum / safeLen));
}

extern "C" JNIEXPORT void JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeStopRecording(
    JNIEnv * /* env */,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(streamMutex);
    if (recordStream != nullptr) {
        closeRecordStreamLocked();
        LOGI("Captura de audio nativa AAudio detenida.");
    }
}
