#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <vector>

#include "treko_aec3_core.hpp"

#define AEC_TAG "TrekoAec3Jni"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, AEC_TAG, __VA_ARGS__)

using treko::audio::TrekoAecProcessor;

extern "C" JNIEXPORT jlong JNICALL
Java_com_treko_core_audio_TrekoAecEngine_nativeCreate(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    auto* processor = new TrekoAecProcessor();
    ALOGI("WebRTC AEC3 processor initialized (16 kHz mono, 10ms sub-frames).");
    return reinterpret_cast<jlong>(processor);
}

extern "C" JNIEXPORT void JNICALL
Java_com_treko_core_audio_TrekoAecEngine_nativeFeedRender16k(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray render16k,
    jint count,
    jint playoutDelayMs
) {
    if (handle == 0 || render16k == nullptr || count <= 0) return;
    auto* processor = reinterpret_cast<TrekoAecProcessor*>(handle);

    const jsize len = env->GetArrayLength(render16k);
    const jsize safe_count = (count < len) ? count : len;
    if (safe_count <= 0) return;

    jboolean is_copy = JNI_FALSE;
    jshort* ptr = env->GetShortArrayElements(render16k, &is_copy);
    if (ptr == nullptr) return;

    processor->FeedRender16k(reinterpret_cast<const int16_t*>(ptr), static_cast<size_t>(safe_count), playoutDelayMs);
    env->ReleaseShortArrayElements(render16k, ptr, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_treko_core_audio_TrekoAecEngine_nativeProcessCapture16k(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray capture16k,
    jint count,
    jint delayMs,
    jshortArray outClean16k,
    jfloatArray outMetrics
) {
    if (handle == 0 || capture16k == nullptr || outClean16k == nullptr || count <= 0) {
        return JNI_FALSE;
    }
    auto* processor = reinterpret_cast<TrekoAecProcessor*>(handle);

    const jsize in_len = env->GetArrayLength(capture16k);
    const jsize out_len = env->GetArrayLength(outClean16k);
    jsize safe_count = count;
    if (safe_count > in_len) safe_count = in_len;
    if (safe_count > out_len) safe_count = out_len;
    if (safe_count <= 0) return JNI_FALSE;

    jshort* in_ptr = env->GetShortArrayElements(capture16k, nullptr);
    jshort* out_ptr = env->GetShortArrayElements(outClean16k, nullptr);
    if (in_ptr == nullptr || out_ptr == nullptr) {
        if (in_ptr) env->ReleaseShortArrayElements(capture16k, in_ptr, JNI_ABORT);
        if (out_ptr) env->ReleaseShortArrayElements(outClean16k, out_ptr, JNI_ABORT);
        return JNI_FALSE;
    }

    const auto metrics = processor->ProcessCapture16k(
        reinterpret_cast<const int16_t*>(in_ptr),
        static_cast<size_t>(safe_count),
        delayMs,
        reinterpret_cast<int16_t*>(out_ptr)
    );

    env->ReleaseShortArrayElements(capture16k, in_ptr, JNI_ABORT);
    env->ReleaseShortArrayElements(outClean16k, out_ptr, 0);

    if (outMetrics != nullptr && env->GetArrayLength(outMetrics) >= 2) {
        jfloat buf[2] = { metrics.erle_db, static_cast<jfloat>(metrics.delay_ms) };
        env->SetFloatArrayRegion(outMetrics, 0, 2, buf);
    }

    return metrics.aec_applied ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_treko_core_audio_TrekoAecEngine_nativeReset(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return;
    reinterpret_cast<TrekoAecProcessor*>(handle)->Reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_treko_core_audio_TrekoAecEngine_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return;
    auto* processor = reinterpret_cast<TrekoAecProcessor*>(handle);
    delete processor;
}
