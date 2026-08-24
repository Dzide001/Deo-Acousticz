package com.droidacoustic.pro.engine

import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper for the native C++ acoustic engine.
 *
 * CONTRACT:
 *  - All native calls are dispatched on [Dispatchers.Default] (never the main thread).
 *  - This class mirrors [AcousticEngine.h] — keep them in sync at all times.
 *  - Thread safety: a single engine instance must not be shared across coroutine contexts
 *    without external synchronisation.
 *
 * LIFECYCLE:
 *  1. Construct → 2. [init] → 3. use engine → 4. [destroy]
 */
class AcousticEngine {

    /** Opaque pointer to the C++ EngineState struct. 0 = uninitialised. */
    private var nativeHandle: Long = 0L

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Initialise the C++ engine and Vulkan compute pipeline.
     * Must be called once before any computation.
     *
     * @param assetManager Android AssetManager — passed to the Vulkan layer
     *                     so it can load compiled SPIR-V shaders from assets/.
     * @return `true` on success, `false` on failure (check Logcat for details).
     */
    suspend fun init(assetManager: AssetManager): Boolean = withContext(Dispatchers.Default) {
        nativeHandle = nativeCreate()
        if (nativeHandle == 0L) {
            Log.e(TAG, "engine_create() returned null — check native logs")
            return@withContext false
        }
        val vulkanOk = nativeInitVulkan(nativeHandle, assetManager)
        if (!vulkanOk) {
            Log.e(TAG, "Vulkan init failed — GPU compute unavailable, CPU fallback active")
            // Non-fatal: CPU fallback path will be used automatically.
        }
        Log.i(TAG, "Engine ready. Version=${nativeVersion()}  handle=0x${nativeHandle.toString(16)}")
        true
    }

    /**
     * Destroy the engine and release all native resources.
     * Safe to call even if [init] was not called or failed.
     */
    suspend fun destroy() = withContext(Dispatchers.Default) {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
            Log.i(TAG, "Engine destroyed")
        }
    }

    // ─── Phase 0 – GPU distance test ─────────────────────────────────────────

    /**
     * Run the Phase 0 GPU compute kernel: parallel distance calculation.
     *
     * Input layout:
     *  - [speakerPositions] flat float array  [x0,y0,z0, x1,y1,z1, ...]
     *  - [gridPoints]       flat float array  [x0,y0,z0, x1,y1,z1, ...]
     *
     * Output layout:
     *  - result[gridIdx * speakerCount + speakerIdx] = distance in metres
     *
     * @throws IllegalStateException if the engine has not been initialised.
     */
    suspend fun computeDistances(
        speakerPositions: FloatArray,
        gridPoints: FloatArray
    ): FloatArray = withContext(Dispatchers.Default) {

        check(nativeHandle != 0L) { "Engine not initialised — call init() first" }
        require(speakerPositions.size % 3 == 0) { "speakerPositions size must be a multiple of 3" }
        require(gridPoints.size % 3 == 0) { "gridPoints size must be a multiple of 3" }

        val speakerCount = speakerPositions.size / 3
        val gridCount    = gridPoints.size / 3
        val output       = FloatArray(gridCount * speakerCount)

        val ok = nativeComputeDistances(
            nativeHandle,
            speakerPositions, speakerCount,
            gridPoints,       gridCount,
            output
        )

        if (!ok) Log.e(TAG, "nativeComputeDistances returned false — results may be invalid")
        else    Log.d(TAG, "GPU distances: ${gridCount} points × ${speakerCount} speakers computed")

        output
    }

    // ─── Native declarations ─────────────────────────────────────────────────
    //  These map 1-to-1 to the functions in AcousticEngine.h via jni_bridge.cpp.

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeInitVulkan(handle: Long, assetManager: AssetManager): Boolean
    private external fun nativeComputeDistances(
        handle: Long,
        speakerPositions: FloatArray, speakerCount: Int,
        gridPoints:       FloatArray, gridCount:    Int,
        outDistances:     FloatArray
    ): Boolean

    companion object {
        private const val TAG = "AcousticEngine"

        /** Synchronous native version query — safe to call before [init]. */
        external fun nativeVersion(): String

        init {
            System.loadLibrary("droidacoustic")
            Log.i(TAG, "Native lib loaded. Engine version: ${nativeVersion()}")
        }
    }
}
