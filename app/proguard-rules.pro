# Treko Assistant ProGuard / R8 Rules

# Keep Sherpa-ONNX JNI native bindings
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Keep Treko native audio JNI signatures
-keep class com.treko.core.audio.NativeAudioEngine {
    native <methods>;
}

# Keep core models & JSON serialization classes
-keep class com.treko.core.common.model.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
