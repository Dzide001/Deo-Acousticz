#include "vulkan_compute.h"

#ifdef __ANDROID__

#include <android/log.h>
#include <android/asset_manager.h>
#include <cstring>
#include <vector>
#include <string>

#define TAG  "VulkanCompute"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Convenience macro — log and return false on Vulkan error
#define VK_CHECK(expr)                                              \
    do {                                                            \
        VkResult _r = (expr);                                       \
        if (_r != VK_SUCCESS) {                                     \
            LOGE(#expr " failed: VkResult=%d  line=%d", _r, __LINE__); \
            return false;                                           \
        }                                                           \
    } while (0)

namespace dac {

// =============================================================================
// Public API
// =============================================================================

bool VulkanCompute::init(AAssetManager* assetManager) {
    if (mInitialised) return true;

    if (!createInstance())           { LOGE("createInstance failed");           return false; }
    if (!selectPhysicalDevice())     { LOGE("selectPhysicalDevice failed");     return false; }
    if (!createDevice())             { LOGE("createDevice failed");             return false; }
    if (!createCommandPool())        { LOGE("createCommandPool failed");        return false; }
    if (!loadDistanceShader(assetManager)) { LOGE("loadDistanceShader failed"); return false; }
    if (!createDescriptorPool())     { LOGE("createDescriptorPool failed");     return false; }
    if (!createDistancePipeline())   { LOGE("createDistancePipeline failed");   return false; }

    mInitialised = true;
    LOGI("Vulkan compute pipeline ready");
    return true;
}

void VulkanCompute::destroy() {
    if (!mDevice) return;

    // Destroy in reverse creation order
    if (mDistPipeline)  { vkDestroyPipeline(mDevice, mDistPipeline, nullptr);             mDistPipeline  = VK_NULL_HANDLE; }
    if (mDistPipeLayout){ vkDestroyPipelineLayout(mDevice, mDistPipeLayout, nullptr);     mDistPipeLayout= VK_NULL_HANDLE; }
    if (mDistDSLayout)  { vkDestroyDescriptorSetLayout(mDevice, mDistDSLayout, nullptr);  mDistDSLayout  = VK_NULL_HANDLE; }
    if (mDescPool)      { vkDestroyDescriptorPool(mDevice, mDescPool, nullptr);           mDescPool      = VK_NULL_HANDLE; }
    if (mDistShader)    { vkDestroyShaderModule(mDevice, mDistShader, nullptr);           mDistShader    = VK_NULL_HANDLE; }
    if (mCmdPool)       { vkDestroyCommandPool(mDevice, mCmdPool, nullptr);               mCmdPool       = VK_NULL_HANDLE; }
    if (mDevice)        { vkDestroyDevice(mDevice, nullptr);                              mDevice        = VK_NULL_HANDLE; }
    if (mInstance)      { vkDestroyInstance(mInstance, nullptr);                          mInstance      = VK_NULL_HANDLE; }

    mInitialised = false;
    LOGI("Vulkan resources destroyed");
}

bool VulkanCompute::computeDistances(
    const float* speakerPositions, uint32_t speakerCount,
    const float* gridPoints,       uint32_t gridCount,
    float*       outDistances)
{
    if (!mInitialised) return false;

    VkDeviceSize speakerBytes = sizeof(float) * speakerCount * 4; // vec4 per speaker (xyz + pad)
    VkDeviceSize gridBytes    = sizeof(float) * gridCount    * 4; // vec4 per point
    VkDeviceSize outBytes     = sizeof(float) * gridCount * speakerCount;

    // ─── Staging buffers (host-visible for data upload) ───────────────────────
    VkBuffer       stagingSpeaker, stagingGrid;
    VkDeviceMemory stagingSpeakerMem, stagingGridMem;

    if (!createBuffer(speakerBytes,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            stagingSpeaker, stagingSpeakerMem)) return false;

    if (!createBuffer(gridBytes,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            stagingGrid, stagingGridMem)) return false;

    // ─── Upload speaker positions (pack into vec4 with w=0) ──────────────────
    {
        float* mapped = nullptr;
        vkMapMemory(mDevice, stagingSpeakerMem, 0, speakerBytes, 0,
                    reinterpret_cast<void**>(&mapped));
        for (uint32_t s = 0; s < speakerCount; ++s) {
            mapped[s*4+0] = speakerPositions[s*3+0];
            mapped[s*4+1] = speakerPositions[s*3+1];
            mapped[s*4+2] = speakerPositions[s*3+2];
            mapped[s*4+3] = 0.f;
        }
        vkUnmapMemory(mDevice, stagingSpeakerMem);
    }

    // ─── Upload grid points ───────────────────────────────────────────────────
    {
        float* mapped = nullptr;
        vkMapMemory(mDevice, stagingGridMem, 0, gridBytes, 0,
                    reinterpret_cast<void**>(&mapped));
        for (uint32_t g = 0; g < gridCount; ++g) {
            mapped[g*4+0] = gridPoints[g*3+0];
            mapped[g*4+1] = gridPoints[g*3+1];
            mapped[g*4+2] = gridPoints[g*3+2];
            mapped[g*4+3] = 0.f;
        }
        vkUnmapMemory(mDevice, stagingGridMem);
    }

    // ─── Device-local compute buffers ─────────────────────────────────────────
    VkBuffer       speakerBuf, gridBuf, outBuf;
    VkDeviceMemory speakerMem, gridMem, outMem;

    if (!createBuffer(speakerBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            speakerBuf, speakerMem)) return false;

    if (!createBuffer(gridBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            gridBuf, gridMem)) return false;

    if (!createBuffer(outBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
            outBuf, outMem)) return false;

    // ─── Copy staging → device ────────────────────────────────────────────────
    VkCommandBufferAllocateInfo cmdAI{};
    cmdAI.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAI.commandPool        = mCmdPool;
    cmdAI.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAI.commandBufferCount = 1;

    VkCommandBuffer cmd;
    VK_CHECK(vkAllocateCommandBuffers(mDevice, &cmdAI, &cmd));

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(cmd, &beginInfo));

    VkBufferCopy copyRegion{};
    copyRegion.size = speakerBytes;
    vkCmdCopyBuffer(cmd, stagingSpeaker, speakerBuf, 1, &copyRegion);
    copyRegion.size = gridBytes;
    vkCmdCopyBuffer(cmd, stagingGrid, gridBuf, 1, &copyRegion);

    // ─── Dispatch compute ─────────────────────────────────────────────────────
    if (!dispatchDistanceKernel(speakerBuf, gridBuf, outBuf, speakerCount, gridCount)) {
        vkFreeCommandBuffers(mDevice, mCmdPool, 1, &cmd);
        return false;
    }

    // ─── Read-back staging buffer ─────────────────────────────────────────────
    VkBuffer       readbackBuf;
    VkDeviceMemory readbackMem;
    if (!createBuffer(outBytes,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            readbackBuf, readbackMem)) return false;

    copyRegion.size = outBytes;
    vkCmdCopyBuffer(cmd, outBuf, readbackBuf, 1, &copyRegion);

    VK_CHECK(vkEndCommandBuffer(cmd));

    // Submit and wait
    VkSubmitInfo submitInfo{};
    submitInfo.sType              = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers    = &cmd;
    VK_CHECK(vkQueueSubmit(mComputeQueue, 1, &submitInfo, VK_NULL_HANDLE));
    VK_CHECK(vkQueueWaitIdle(mComputeQueue));

    // ─── Copy output to caller's buffer ──────────────────────────────────────
    float* mapped = nullptr;
    vkMapMemory(mDevice, readbackMem, 0, outBytes, 0, reinterpret_cast<void**>(&mapped));
    std::memcpy(outDistances, mapped, outBytes);
    vkUnmapMemory(mDevice, readbackMem);

    // ─── Cleanup temp resources ───────────────────────────────────────────────
    vkFreeCommandBuffers(mDevice, mCmdPool, 1, &cmd);
    vkDestroyBuffer(mDevice, stagingSpeaker, nullptr); vkFreeMemory(mDevice, stagingSpeakerMem, nullptr);
    vkDestroyBuffer(mDevice, stagingGrid,    nullptr); vkFreeMemory(mDevice, stagingGridMem,    nullptr);
    vkDestroyBuffer(mDevice, speakerBuf,     nullptr); vkFreeMemory(mDevice, speakerMem,        nullptr);
    vkDestroyBuffer(mDevice, gridBuf,        nullptr); vkFreeMemory(mDevice, gridMem,           nullptr);
    vkDestroyBuffer(mDevice, outBuf,         nullptr); vkFreeMemory(mDevice, outMem,            nullptr);
    vkDestroyBuffer(mDevice, readbackBuf,    nullptr); vkFreeMemory(mDevice, readbackMem,       nullptr);

    LOGD("computeDistances: %u speakers × %u grid points — GPU done", speakerCount, gridCount);
    return true;
}

// =============================================================================
// Private initialisation helpers
// =============================================================================

bool VulkanCompute::createInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType            = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "DroidAcousticPro";
    appInfo.apiVersion       = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ci{};
    ci.sType            = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &appInfo;

#if defined(DAC_DEBUG)
    // Validation layers — debug builds only
    const char* validationLayer = "VK_LAYER_KHRONOS_validation";
    ci.enabledLayerCount   = 1;
    ci.ppEnabledLayerNames = &validationLayer;
    LOGD("Vulkan validation layers ENABLED");
#endif

    VK_CHECK(vkCreateInstance(&ci, nullptr, &mInstance));
    LOGI("VkInstance created");
    return true;
}

bool VulkanCompute::selectPhysicalDevice() {
    uint32_t count = 0;
    vkEnumeratePhysicalDevices(mInstance, &count, nullptr);
    if (count == 0) { LOGE("No Vulkan physical devices found"); return false; }

    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(mInstance, &count, devices.data());

    // Choose the first device with a compute queue (in practice: the GPU)
    for (auto& dev : devices) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qProps.data());

        for (uint32_t i = 0; i < qCount; ++i) {
            if (qProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                mPhysDevice    = dev;
                mComputeFamily = i;

                VkPhysicalDeviceProperties props{};
                vkGetPhysicalDeviceProperties(dev, &props);
                LOGI("Selected GPU: %s  (compute queue family %u)", props.deviceName, i);
                return true;
            }
        }
    }
    LOGE("No GPU with compute queue found");
    return false;
}

bool VulkanCompute::createDevice() {
    float priority = 1.f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType            = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = mComputeFamily;
    qci.queueCount       = 1;
    qci.pQueuePriorities = &priority;

    VkDeviceCreateInfo ci{};
    ci.sType                = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.queueCreateInfoCount = 1;
    ci.pQueueCreateInfos    = &qci;

    VK_CHECK(vkCreateDevice(mPhysDevice, &ci, nullptr, &mDevice));
    vkGetDeviceQueue(mDevice, mComputeFamily, 0, &mComputeQueue);
    LOGI("VkDevice and compute queue created");
    return true;
}

bool VulkanCompute::createCommandPool() {
    VkCommandPoolCreateInfo ci{};
    ci.sType            = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.queueFamilyIndex = mComputeFamily;
    ci.flags            = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    VK_CHECK(vkCreateCommandPool(mDevice, &ci, nullptr, &mCmdPool));
    return true;
}

bool VulkanCompute::loadDistanceShader(AAssetManager* assetManager) {
    if (!assetManager) {
        LOGE("AssetManager is null — cannot load distance.spv");
        return false;
    }

    AAsset* asset = AAssetManager_open(assetManager, "shaders/distance.spv", AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open assets/shaders/distance.spv — was it compiled?");
        return false;
    }

    size_t size = static_cast<size_t>(AAsset_getLength(asset));
    std::vector<uint32_t> spv(size / sizeof(uint32_t));
    AAsset_read(asset, spv.data(), size);
    AAsset_close(asset);

    VkShaderModuleCreateInfo ci{};
    ci.sType    = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = size;
    ci.pCode    = spv.data();

    VK_CHECK(vkCreateShaderModule(mDevice, &ci, nullptr, &mDistShader));
    LOGI("distance.spv loaded (%zu bytes)", size);
    return true;
}

bool VulkanCompute::createDescriptorPool() {
    // 3 storage buffers per dispatch (speakers, grid, output)
    VkDescriptorPoolSize poolSize{};
    poolSize.type            = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 3;

    VkDescriptorPoolCreateInfo ci{};
    ci.sType         = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.poolSizeCount = 1;
    ci.pPoolSizes    = &poolSize;
    ci.maxSets       = 1;

    VK_CHECK(vkCreateDescriptorPool(mDevice, &ci, nullptr, &mDescPool));
    return true;
}

bool VulkanCompute::createDistancePipeline() {
    // ─── Descriptor set layout — 3 storage buffer bindings ───────────────────
    VkDescriptorSetLayoutBinding bindings[3] = {};
    for (uint32_t i = 0; i < 3; ++i) {
        bindings[i].binding         = i;
        bindings[i].descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags      = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo dslCI{};
    dslCI.sType        = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslCI.bindingCount = 3;
    dslCI.pBindings    = bindings;
    VK_CHECK(vkCreateDescriptorSetLayout(mDevice, &dslCI, nullptr, &mDistDSLayout));

    // ─── Push constants — speakerCount + gridCount ────────────────────────────
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset     = 0;
    pushRange.size       = sizeof(uint32_t) * 2;

    VkPipelineLayoutCreateInfo plCI{};
    plCI.sType                  = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plCI.setLayoutCount         = 1;
    plCI.pSetLayouts            = &mDistDSLayout;
    plCI.pushConstantRangeCount = 1;
    plCI.pPushConstantRanges    = &pushRange;
    VK_CHECK(vkCreatePipelineLayout(mDevice, &plCI, nullptr, &mDistPipeLayout));

    // ─── Compute pipeline ─────────────────────────────────────────────────────
    VkComputePipelineCreateInfo cpCI{};
    cpCI.sType        = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpCI.stage.sType  = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpCI.stage.stage  = VK_SHADER_STAGE_COMPUTE_BIT;
    cpCI.stage.module = mDistShader;
    cpCI.stage.pName  = "main";
    cpCI.layout       = mDistPipeLayout;

    VK_CHECK(vkCreateComputePipelines(mDevice, VK_NULL_HANDLE, 1, &cpCI, nullptr, &mDistPipeline));
    LOGI("Distance compute pipeline created");
    return true;
}

bool VulkanCompute::dispatchDistanceKernel(
    VkBuffer speakerBuf, VkBuffer gridBuf, VkBuffer outBuf,
    uint32_t speakerCount, uint32_t gridCount)
{
    // ─── Allocate + update descriptor set ────────────────────────────────────
    VkDescriptorSetAllocateInfo dsAI{};
    dsAI.sType              = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAI.descriptorPool     = mDescPool;
    dsAI.descriptorSetCount = 1;
    dsAI.pSetLayouts        = &mDistDSLayout;

    VkDescriptorSet ds;
    VK_CHECK(vkAllocateDescriptorSets(mDevice, &dsAI, &ds));

    VkDescriptorBufferInfo bufInfos[3] = {
        { speakerBuf, 0, VK_WHOLE_SIZE },
        { gridBuf,    0, VK_WHOLE_SIZE },
        { outBuf,     0, VK_WHOLE_SIZE },
    };
    VkWriteDescriptorSet writes[3] = {};
    for (uint32_t i = 0; i < 3; ++i) {
        writes[i].sType           = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet          = ds;
        writes[i].dstBinding      = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType  = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo     = &bufInfos[i];
    }
    vkUpdateDescriptorSets(mDevice, 3, writes, 0, nullptr);

    // ─── Record dispatch ──────────────────────────────────────────────────────
    VkCommandBufferAllocateInfo cmdAI{};
    cmdAI.sType              = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAI.commandPool        = mCmdPool;
    cmdAI.level              = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAI.commandBufferCount = 1;

    VkCommandBuffer cmd;
    VK_CHECK(vkAllocateCommandBuffers(mDevice, &cmdAI, &cmd));

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_CHECK(vkBeginCommandBuffer(cmd, &beginInfo));

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mDistPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, mDistPipeLayout,
                            0, 1, &ds, 0, nullptr);

    uint32_t pushData[2] = { speakerCount, gridCount };
    vkCmdPushConstants(cmd, mDistPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(pushData), pushData);

    // workgroup size = 64 (matches local_size_x in distance.comp)
    uint32_t groupCount = (gridCount + 63) / 64;
    vkCmdDispatch(cmd, groupCount, 1, 1);

    VK_CHECK(vkEndCommandBuffer(cmd));

    VkSubmitInfo submitInfo{};
    submitInfo.sType              = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers    = &cmd;
    VK_CHECK(vkQueueSubmit(mComputeQueue, 1, &submitInfo, VK_NULL_HANDLE));
    VK_CHECK(vkQueueWaitIdle(mComputeQueue));

    vkFreeCommandBuffers(mDevice, mCmdPool, 1, &cmd);
    vkResetDescriptorPool(mDevice, mDescPool, 0);
    return true;
}

bool VulkanCompute::createBuffer(
    VkDeviceSize size, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props,
    VkBuffer& outBuffer, VkDeviceMemory& outMemory)
{
    VkBufferCreateInfo ci{};
    ci.sType       = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size        = size;
    ci.usage       = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_CHECK(vkCreateBuffer(mDevice, &ci, nullptr, &outBuffer));

    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(mDevice, outBuffer, &memReq);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType           = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize  = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReq.memoryTypeBits, props);
    if (allocInfo.memoryTypeIndex == UINT32_MAX) {
        LOGE("No suitable memory type for buffer");
        return false;
    }

    VK_CHECK(vkAllocateMemory(mDevice, &allocInfo, nullptr, &outMemory));
    VK_CHECK(vkBindBufferMemory(mDevice, outBuffer, outMemory, 0));
    return true;
}

uint32_t VulkanCompute::findMemoryType(uint32_t filter, VkMemoryPropertyFlags props) const {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(mPhysDevice, &memProps);

    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((filter & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

} // namespace dac

#else
// =============================================================================
// Desktop / CI stub — CPU implementation so gtest can run without a GPU
// =============================================================================

#include <cmath>

namespace dac {

bool VulkanCompute::init(AAssetManager*) {
    // CPU stub — always succeeds so the engine can run in unit tests.
    return false; // Return false so AcousticEngine uses the CPU fallback path.
}

void VulkanCompute::destroy() {}

bool VulkanCompute::computeDistances(
    const float* /*speakerPositions*/, uint32_t /*speakerCount*/,
    const float* /*gridPoints*/,       uint32_t /*gridCount*/,
    float*       /*outDistances*/)
{
    // Should never be called — init() returns false, engine uses CPU path.
    return false;
}

} // namespace dac

#endif // __ANDROID__
