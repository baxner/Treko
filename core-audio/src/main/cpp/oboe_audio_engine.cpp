#include <jni.h>
#include <string>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include <vector>
#include <cmath>

#define TAG "TrekoAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static AAudioStream *recordStream = nullptr;
static AAudioStream *playbackStream = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeInitAudio(
    JNIEnv *env,
    jobject /* this */,
    jint sampleRate,
    jint channels
) {
    LOGI("Inicializando motor de audio nativo AAudio: %d Hz, %d canales", sampleRate, channels);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeStartRecording(
    JNIEnv *env,
    jobject /* this */
) {
    AAudioStreamBuilder *builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
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

    if (result != AAUDIO_OK) {
        LOGE("Fallo al abrir record stream: %s", AAudio_convertResultToText(result));
        return JNI_FALSE;
    }

    result = AAudioStream_requestStart(recordStream);
    if (result != AAUDIO_OK) {
        LOGE("Fallo al iniciar record stream: %s", AAudio_convertResultToText(result));
        AAudioStream_close(recordStream);
        recordStream = nullptr;
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
    if (!recordStream) return -1;

    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);
    int64_t timeoutNanoseconds = 20 * 1000 * 1000; // 20 ms
    aaudio_result_t framesRead = AAudioStream_read(recordStream, bufPtr, numSamples, timeoutNanoseconds);

    env->ReleaseShortArrayElements(buffer, bufPtr, 0);
    return (jint) framesRead;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeCalculateRms(
    JNIEnv *env,
    jobject /* this */,
    jshortArray buffer,
    jint length
) {
    if (length <= 0) return 0.0f;
    jshort *bufPtr = env->GetShortArrayElements(buffer, nullptr);

    double sum = 0.0;
    for (int i = 0; i < length; ++i) {
        double val = bufPtr[i] / 32768.0;
        sum += val * val;
    }
    env->ReleaseShortArrayElements(buffer, bufPtr, JNI_ABORT);

    float rms = static_cast<float>(std::sqrt(sum / length));
    return rms;
}

extern "C" JNIEXPORT void JNICALL
Java_com_treko_core_audio_NativeAudioEngine_nativeStopRecording(
    JNIEnv *env,
    jobject /* this */
) {
    if (recordStream) {
        AAudioStream_requestStop(recordStream);
        AAudioStream_close(recordStream);
        recordStream = nullptr;
        LOGI("Captura de audio nativa AAudio detenida.");
    }
}
