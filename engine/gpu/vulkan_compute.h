// =============================================================================
// gpu/vulkan_compute.h  —  Vulkan headless compute pipeline
// =============================================================================
//
// Manages the Vulkan instance, device, buffers, and compute pipelines for
// all GPU-accelerated kernels.
//
// Kernel sequence (from Phase 0 spec, Section 7):
//   Kernel 1: Distance + directivity (distance.comp — Phase 0 has distance only)
//   Kernel 2: Atmospheric absorption + energy accumulation (Phase 5)
//   ...
//
// On Android: real Vulkan path via vulkan/vulkan.h (NDK).
// On desktop: CPU stub so unit tests compile and run without a GPU.

#pragma once

#include <cstdint>
#include <vector>

#ifdef __ANDROID__
  #include <android/asset_manager.h>
  // Vulkan headers — available on Android 28+ via NDK
  #include <vulkan/vulkan.h>
#else
  // Desktop build — Vulkan types not needed (CPU stub).
  // Define the minimal types needed to compile the header.
  #ifndef AASSETMANAGER_DEFINED
  #define AASSETMANAGER_DEFINED
  typedef void AAssetManager;  // matches AcousticEngine.h; AAssetManager* == void*
  #endif
  #define VK_NULL_HANDLE nullptr
  typedef void* VkInstance;
  typedef void* VkPhysicalDevice;
  typedef void* VkDevice;
  typedef void* VkQueue;
  typedef void* VkCommandPool;
  typedef void* VkShaderModule;
  typedef void* VkDescriptorSetLayout;
  typedef void* VkPipelineLayout;
  typedef void* VkPipeline;
  typedef void* VkDescriptorPool;
  typedef void* VkDescriptorSet;
  typedef void* VkBuffer;
  typedef void* VkDeviceMemory;
  typedef uint64_t VkDeviceSize;
  typedef uint32_t VkFlags;
  typedef uint32_t VkBufferUsageFlags;
  typedef uint32_t VkMemoryPropertyFlags;
#endif

namespace dac {

class VulkanCompute {
public:
    VulkanCompute()  = default;
    ~VulkanCompute() { destroy(); }

    // Non-copyable
    VulkanCompute(const VulkanCompute&)            = delete;
    VulkanCompute& operator=(const VulkanCompute&) = delete;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /// Initialise Vulkan instance, physical device, compute pipeline.
    /// Loads distance.spv from Android assets.
    ///
    /// @param assetManager  Android AssetManager (nullptr on desktop → CPU stub).
    /// @return true on success. On failure, computeDistances() uses CPU fallback.
    bool init(AAssetManager* assetManager);

    /// Release all Vulkan resources. Safe to call if init() was not called.
    void destroy();

    // ─── Compute kernels ─────────────────────────────────────────────────────

    /// GPU distance kernel (Kernel 1 — Phase 0).
    /// @see AcousticEngine.h engine_compute_distances() for full documentation.
    bool computeDistances(
        const float* speakerPositions, uint32_t speakerCount,
        const float* gridPoints,       uint32_t gridCount,
        float*       outDistances
    );

private:
#ifdef __ANDROID__
    // ─── Vulkan handles ───────────────────────────────────────────────────────
    VkInstance             mInstance        = VK_NULL_HANDLE;
    VkPhysicalDevice       mPhysDevice      = VK_NULL_HANDLE;
    VkDevice               mDevice          = VK_NULL_HANDLE;
    VkQueue                mComputeQueue    = VK_NULL_HANDLE;
    VkCommandPool          mCmdPool         = VK_NULL_HANDLE;

    // Distance pipeline
    VkShaderModule         mDistShader      = VK_NULL_HANDLE;
    VkDescriptorSetLayout  mDistDSLayout    = VK_NULL_HANDLE;
    VkPipelineLayout       mDistPipeLayout  = VK_NULL_HANDLE;
    VkPipeline             mDistPipeline    = VK_NULL_HANDLE;
    VkDescriptorPool       mDescPool        = VK_NULL_HANDLE;

    uint32_t               mComputeFamily   = UINT32_MAX;
    bool                   mInitialised     = false;

    // ─── Private initialisation steps ────────────────────────────────────────
    bool createInstance();
    bool selectPhysicalDevice();
    bool createDevice();
    bool createCommandPool();
    bool loadDistanceShader(AAssetManager* assetManager);
    bool createDistancePipeline();
    bool createDescriptorPool();

    // ─── Buffer helpers ───────────────────────────────────────────────────────
    bool createBuffer(
        VkDeviceSize         size,
        VkBufferUsageFlags   usage,
        VkMemoryPropertyFlags props,
        VkBuffer&            outBuffer,
        VkDeviceMemory&      outMemory
    );
    uint32_t findMemoryType(uint32_t filter, VkMemoryPropertyFlags props) const;

    // ─── Compute dispatch ─────────────────────────────────────────────────────
    bool dispatchDistanceKernel(
        VkBuffer speakerBuf, VkBuffer gridBuf, VkBuffer outBuf,
        uint32_t speakerCount, uint32_t gridCount
    );
#endif
};

} // namespace dac
