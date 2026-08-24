// =============================================================================
// jni_bridge.cpp  —  JNI glue between Kotlin AcousticEngine and C++ engine
// =============================================================================
//
// Naming convention follows JNI spec:
//   Java_<package_underscored>_<class>_<method>
//
// Companion object methods get an extra $Companion suffix in the mangled name.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>

// Pull in the full engine API
#include "AcousticEngine.h"

#define TAG  "JniBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Convenience: cast jlong back to EngineState*
static inline dac::EngineState* asState(jlong h) {
    return reinterpret_cast<dac::EngineState*>(static_cast<uintptr_t>(h));
}

// =============================================================================
// Companion object (static) methods
// =============================================================================

extern "C" JNIEXPORT jstring JNICALL
Java_com_droidacoustic_pro_engine_AcousticEngine_00024Companion_nativeVersion(
    JNIEnv* env, jobject /* companion */)
{
    return env->NewStringUTF(dac::engine_version());
}

// =============================================================================
// Instance methods
// =============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_com_droidacoustic_pro_engine_AcousticEngine_nativeCreate(
    JNIEnv* /* env */, jobject /* thiz */)
{
    dac::EngineState* state = dac::engine_create();
    if (!state) LOGE("engine_create() returned nullptr");
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(state));
}

// -----------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_droidacoustic_pro_engine_AcousticEngine_nativeDestroy(
    JNIEnv* /* env */, jobject /* thiz */, jlong handle)
{
    dac::engine_destroy(asState(handle));
}

// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_droidacoustic_pro_engine_AcousticEngine_nativeInitVulkan(
    JNIEnv* env, jobject /* thiz */, jlong handle, jobject jassetManager)
{
    AAssetManager* mgr = AAssetManager_fromJava(env, jassetManager);
    bool ok = dac::engine_init_vulkan(asState(handle), mgr);
    LOGD("nativeInitVulkan → %s", ok ? "OK" : "FAILED (CPU fallback active)");
    return static_cast<jboolean>(ok);
}

// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_droidacoustic_pro_engine_AcousticEngine_nativeComputeDistances(
    JNIEnv* env, jobject /* thiz */,
    jlong      handle,
    jfloatArray jSpeakers,    jint speakerCount,
    jfloatArray jGrid,        jint gridCount,
    jfloatArray jOutDistances)
{
    // GetFloatArrayElements returns a direct pointer (or a copy on some JVMs)
    jfloat* speakers = env->GetFloatArrayElements(jSpeakers, nullptr);
    jfloat* grid     = env->GetFloatArrayElements(jGrid,     nullptr);
    jfloat* out      = env->GetFloatArrayElements(jOutDistances, nullptr);

    bool ok = dac::engine_compute_distances(
        asState(handle),
        speakers, static_cast<uint32_t>(speakerCount),
        grid,     static_cast<uint32_t>(gridCount),
        out
    );

    // Release — mode 0 copies back to the Java array (critical for 'out')
    env->ReleaseFloatArrayElements(jSpeakers,      speakers, JNI_ABORT); // no copy-back needed
    env->ReleaseFloatArrayElements(jGrid,          grid,     JNI_ABORT);
    env->ReleaseFloatArrayElements(jOutDistances,  out,      0);         // copy back results

    return static_cast<jboolean>(ok);
}
