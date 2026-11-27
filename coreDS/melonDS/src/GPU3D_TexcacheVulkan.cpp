#include "../../bridge/ds_diagnostics.h"
#include <algorithm>
#include <cstring>
#include <mutex>

#include "GPU3D_TexcacheVulkan.h"

#include "Platform.h"
#include "VulkanContext.h"
#include "VulkanDispatch.h"

namespace melonDS
{

constexpr uint64_t kFenceWaitTimeoutNs = 2'000'000'000ull;
constexpr VkDeviceSize kDefaultTexcacheStagingBytes = 2u * 1024u * 1024u;

TexcacheVulkanLoader::TexcacheVulkanLoader()
    : State(std::make_shared<SharedState>())
{
}

TexcacheVulkanLoader::~TexcacheVulkanLoader()
{
    if (State != nullptr && State.use_count() == 1)
        CleanupVulkanState();
}

bool TexcacheVulkanLoader::EnsureVulkanState()
{
    if (State == nullptr)
        State = std::make_shared<SharedState>();

    if (State->Device != VK_NULL_HANDLE)
        return true;

    auto& context = VulkanContext::Get();
    if (!context.Acquire())
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to acquire Vulkan context"));
        return false;
    }

    State->ContextAcquired = true;
    State->Device = context.GetDevice();
    State->Queue = context.GetQueue();
    State->QueueFamilyIndex = context.GetQueueFamilyIndex();

    if (State->Device == VK_NULL_HANDLE || State->Queue == VK_NULL_HANDLE)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: invalid Vulkan context handles"));
        CleanupVulkanState();
        return false;
    }

    VkSamplerCreateInfo samplerCreateInfo{};
    samplerCreateInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerCreateInfo.magFilter = VK_FILTER_NEAREST;
    samplerCreateInfo.minFilter = VK_FILTER_NEAREST;
    samplerCreateInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerCreateInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    samplerCreateInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    samplerCreateInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    samplerCreateInfo.anisotropyEnable = VK_FALSE;
    samplerCreateInfo.maxAnisotropy = 1.0f;
    samplerCreateInfo.compareEnable = VK_FALSE;
    samplerCreateInfo.minLod = 0.0f;
    samplerCreateInfo.maxLod = 0.0f;
    samplerCreateInfo.borderColor = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
    samplerCreateInfo.unnormalizedCoordinates = VK_FALSE;
    if (vkCreateSampler(State->Device, &samplerCreateInfo, nullptr, &State->NearestSampler) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create shared sampler"));
        CleanupVulkanState();
        return false;
    }

    VkCommandPoolCreateInfo poolCreateInfo{};
    poolCreateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolCreateInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolCreateInfo.queueFamilyIndex = State->QueueFamilyIndex;
    if (vkCreateCommandPool(State->Device, &poolCreateInfo, nullptr, &State->CommandPool) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create command pool"));
        CleanupVulkanState();
        return false;
    }

    VkCommandBufferAllocateInfo commandBufferAllocateInfo{};
    commandBufferAllocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBufferAllocateInfo.commandPool = State->CommandPool;
    commandBufferAllocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBufferAllocateInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(State->Device, &commandBufferAllocateInfo, &State->CommandBuffer) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to allocate command buffer"));
        CleanupVulkanState();
        return false;
    }

    VkFenceCreateInfo fenceCreateInfo{};
    fenceCreateInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceCreateInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vkCreateFence(State->Device, &fenceCreateInfo, nullptr, &State->UploadFence) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create upload fence"));
        CleanupVulkanState();
        return false;
    }

    return true;
}

bool TexcacheVulkanLoader::EnsureStagingCapacity(VkDeviceSize requiredBytes)
{
    if (State == nullptr || State->Device == VK_NULL_HANDLE)
        return false;

    if (State->StagingBuffer != VK_NULL_HANDLE && State->StagingMapped != nullptr
        && State->StagingCapacity >= requiredBytes)
    {
        return true;
    }

    WaitForPendingUploads();

    if (State->StagingMapped != nullptr && State->StagingMemory != VK_NULL_HANDLE)
    {
        vkUnmapMemory(State->Device, State->StagingMemory);
        State->StagingMapped = nullptr;
    }
    if (State->StagingBuffer != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(State->Device, State->StagingBuffer, nullptr);
        State->StagingBuffer = VK_NULL_HANDLE;
    }
    if (State->StagingMemory != VK_NULL_HANDLE)
    {
        vkFreeMemory(State->Device, State->StagingMemory, nullptr);
        State->StagingMemory = VK_NULL_HANDLE;
    }
    State->StagingCapacity = 0;
    State->StagingUsed = 0;

    const VkDeviceSize allocSize = std::max(requiredBytes, kDefaultTexcacheStagingBytes);

    VkBufferCreateInfo stagingBufferCreateInfo{};
    stagingBufferCreateInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    stagingBufferCreateInfo.size = allocSize;
    stagingBufferCreateInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    stagingBufferCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(State->Device, &stagingBufferCreateInfo, nullptr, &State->StagingBuffer) != VK_SUCCESS)
        return false;

    VkMemoryRequirements memReqs{};
    vkGetBufferMemoryRequirements(State->Device, State->StagingBuffer, &memReqs);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = VulkanContext::Get().FindMemoryType(
        memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (allocInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(State->Device, &allocInfo, nullptr, &State->StagingMemory) != VK_SUCCESS
        || vkBindBufferMemory(State->Device, State->StagingBuffer, State->StagingMemory, 0) != VK_SUCCESS
        || vkMapMemory(State->Device, State->StagingMemory, 0, allocSize, 0, &State->StagingMapped) != VK_SUCCESS)
    {
        if (State->StagingBuffer != VK_NULL_HANDLE)
        {
            vkDestroyBuffer(State->Device, State->StagingBuffer, nullptr);
            State->StagingBuffer = VK_NULL_HANDLE;
        }
        if (State->StagingMemory != VK_NULL_HANDLE)
        {
            vkFreeMemory(State->Device, State->StagingMemory, nullptr);
            State->StagingMemory = VK_NULL_HANDLE;
        }
        State->StagingMapped = nullptr;
        return false;
    }

    State->StagingCapacity = allocSize;
    return true;
}

void TexcacheVulkanLoader::CleanupVulkanState()
{
    if (State == nullptr)
        return;

    auto& vulkanContext = VulkanContext::Get();

    std::unique_lock<std::mutex> queueLock(
        vulkanContext.GetQueueLock(), std::defer_lock);
    std::unique_lock<std::mutex> presentQueueLock(
        vulkanContext.GetPresentQueueLock(), std::defer_lock);
    if (State->Device != VK_NULL_HANDLE)
    {
        if (vulkanContext.IsPresentQueueDedicated())
            std::lock(queueLock, presentQueueLock);
        else
            queueLock.lock();
        vkDeviceWaitIdle(State->Device);
    }

    State->PendingInitialTransitions.clear();
    State->PendingCopies.clear();
    State->UploadInFlight = false;

    for (auto& [handle, textureArray] : State->TextureArrays)
    {
        (void)handle;
        DestroyTextureArray(textureArray);
    }
    State->TextureArrays.clear();
    State->NextHandle = 1;

    if (State->StagingMapped != nullptr && State->StagingMemory != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkUnmapMemory(State->Device, State->StagingMemory);
        State->StagingMapped = nullptr;
    }
    if (State->StagingBuffer != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkDestroyBuffer(State->Device, State->StagingBuffer, nullptr);
        State->StagingBuffer = VK_NULL_HANDLE;
    }
    if (State->StagingMemory != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkFreeMemory(State->Device, State->StagingMemory, nullptr);
        State->StagingMemory = VK_NULL_HANDLE;
    }
    State->StagingCapacity = 0;
    State->StagingUsed = 0;

    if (State->NearestSampler != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkDestroySampler(State->Device, State->NearestSampler, nullptr);
        State->NearestSampler = VK_NULL_HANDLE;
    }

    if (State->UploadFence != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkDestroyFence(State->Device, State->UploadFence, nullptr);
        State->UploadFence = VK_NULL_HANDLE;
    }

    if (State->CommandBuffer != VK_NULL_HANDLE && State->CommandPool != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkFreeCommandBuffers(State->Device, State->CommandPool, 1, &State->CommandBuffer);
    }
    State->CommandBuffer = VK_NULL_HANDLE;

    if (State->CommandPool != VK_NULL_HANDLE && State->Device != VK_NULL_HANDLE)
    {
        vkDestroyCommandPool(State->Device, State->CommandPool, nullptr);
        State->CommandPool = VK_NULL_HANDLE;
    }

    if (presentQueueLock.owns_lock())
        presentQueueLock.unlock();
    if (queueLock.owns_lock())
        queueLock.unlock();

    if (State->ContextAcquired)
    {
        VulkanContext::Get().Release();
        State->ContextAcquired = false;
    }

    State->Device = VK_NULL_HANDLE;
    State->Queue = VK_NULL_HANDLE;
    State->QueueFamilyIndex = 0;
}

void TexcacheVulkanLoader::DestroyTextureArray(TextureArray& textureArray)
{
    if (State == nullptr || State->Device == VK_NULL_HANDLE)
    {
        textureArray = TextureArray{};
        return;
    }

    VkDevice device = State->Device;

    if (textureArray.ArrayView != VK_NULL_HANDLE)
    {
        vkDestroyImageView(device, textureArray.ArrayView, nullptr);
        textureArray.ArrayView = VK_NULL_HANDLE;
    }

    if (textureArray.NormalizedArrayView != VK_NULL_HANDLE)
    {
        vkDestroyImageView(device, textureArray.NormalizedArrayView, nullptr);
        textureArray.NormalizedArrayView = VK_NULL_HANDLE;
    }

    if (textureArray.Image != VK_NULL_HANDLE)
    {
        vkDestroyImage(device, textureArray.Image, nullptr);
        textureArray.Image = VK_NULL_HANDLE;
    }

    if (textureArray.Memory != VK_NULL_HANDLE)
    {
        vkFreeMemory(device, textureArray.Memory, nullptr);
        textureArray.Memory = VK_NULL_HANDLE;
    }

    textureArray.Width = 0;
    textureArray.Height = 0;
    textureArray.Layers = 0;
}

void TexcacheVulkanLoader::WaitForPendingUploads()
{
    if (State == nullptr || State->Device == VK_NULL_HANDLE || !State->UploadInFlight || State->UploadFence == VK_NULL_HANDLE)
        return;

    const VkResult fenceStatus = vkGetFenceStatus(State->Device, State->UploadFence);
    if (fenceStatus == VK_NOT_READY)
        vkWaitForFences(State->Device, 1, &State->UploadFence, VK_TRUE, kFenceWaitTimeoutNs);
    State->UploadInFlight = false;
}

TexcacheVulkanLoader::TextureHandle TexcacheVulkanLoader::GenerateTexture(u32 width, u32 height, u32 layers)
{
    if (width == 0 || height == 0 || layers == 0)
        return 0;

    if (!EnsureVulkanState())
        return 0;

    TextureArray textureArray{};
    textureArray.Width = width;
    textureArray.Height = height;
    textureArray.Layers = layers;
    textureArray.LayerFlags.assign(layers, 0u);
    const bool normalizedTextureResources =
        GetVulkanTextureDescriptorPolicy().RequiresNormalizedTextureDescriptor();

    VkImageCreateInfo imageCreateInfo{};
    imageCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageCreateInfo.flags = normalizedTextureResources
        ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT
        : 0u;
    imageCreateInfo.imageType = VK_IMAGE_TYPE_2D;
    imageCreateInfo.format = VK_FORMAT_R8G8B8A8_UINT;
    imageCreateInfo.extent.width = width;
    imageCreateInfo.extent.height = height;
    imageCreateInfo.extent.depth = 1;
    imageCreateInfo.mipLevels = 1;
    imageCreateInfo.arrayLayers = layers;
    imageCreateInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageCreateInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageCreateInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageCreateInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageCreateInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (vkCreateImage(State->Device, &imageCreateInfo, nullptr, &textureArray.Image) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create image"));
        return 0;
    }

    VkMemoryRequirements imageMemoryRequirements{};
    vkGetImageMemoryRequirements(State->Device, textureArray.Image, &imageMemoryRequirements);

    VkMemoryAllocateInfo imageMemoryAllocateInfo{};
    imageMemoryAllocateInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageMemoryAllocateInfo.allocationSize = imageMemoryRequirements.size;
    imageMemoryAllocateInfo.memoryTypeIndex = VulkanContext::Get().FindMemoryType(
        imageMemoryRequirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (imageMemoryAllocateInfo.memoryTypeIndex == UINT32_MAX)
        imageMemoryAllocateInfo.memoryTypeIndex = VulkanContext::Get().FindMemoryType(imageMemoryRequirements.memoryTypeBits, 0);
    if (imageMemoryAllocateInfo.memoryTypeIndex == UINT32_MAX
        || vkAllocateMemory(State->Device, &imageMemoryAllocateInfo, nullptr, &textureArray.Memory) != VK_SUCCESS
        || vkBindImageMemory(State->Device, textureArray.Image, textureArray.Memory, 0) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to allocate image memory"));
        DestroyTextureArray(textureArray);
        return 0;
    }

    VkImageViewCreateInfo arrayViewCreateInfo{};
    arrayViewCreateInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    arrayViewCreateInfo.image = textureArray.Image;
    arrayViewCreateInfo.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    arrayViewCreateInfo.format = VK_FORMAT_R8G8B8A8_UINT;
    arrayViewCreateInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    arrayViewCreateInfo.subresourceRange.baseMipLevel = 0;
    arrayViewCreateInfo.subresourceRange.levelCount = 1;
    arrayViewCreateInfo.subresourceRange.baseArrayLayer = 0;
    arrayViewCreateInfo.subresourceRange.layerCount = layers;

    if (vkCreateImageView(State->Device, &arrayViewCreateInfo, nullptr, &textureArray.ArrayView) != VK_SUCCESS)
    {
        EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create array view"));
        DestroyTextureArray(textureArray);
        return 0;
    }

    if (normalizedTextureResources)
    {
        arrayViewCreateInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        if (vkCreateImageView(
                State->Device,
                &arrayViewCreateInfo,
                nullptr,
                &textureArray.NormalizedArrayView) != VK_SUCCESS)
        {
            EMUCORED_DIAGNOSTIC_LOG(Platform::Log(Platform::LogLevel::Error, "TexcacheVulkan: failed to create normalized array view"));
            DestroyTextureArray(textureArray);
            return 0;
        }
    }

    State->PendingInitialTransitions.push_back({textureArray.Image, layers});

    TextureHandle handle = State->NextHandle++;
    State->TextureArrays.emplace(handle, std::move(textureArray));
    return handle;
}

void TexcacheVulkanLoader::UploadTexture(TextureHandle handle, u32 width, u32 height, u32 layer, void* data)
{
    if (data == nullptr)
        return;

    if (!EnsureVulkanState())
        return;

    auto it = State->TextureArrays.find(handle);
    if (it == State->TextureArrays.end())
        return;

    TextureArray& textureArray = it->second;
    if (layer >= textureArray.Layers || textureArray.Width != width || textureArray.Height != height)
        return;

    const size_t layerPixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    bool layerOpaque = true;
    const u32* sourcePixels = static_cast<const u32*>(data);
    for (size_t pixel = 0; pixel < layerPixelCount; pixel++)
    {
        if (((sourcePixels[pixel] >> 24u) & 0x1Fu) != 0x1Fu)
        {
            layerOpaque = false;
            break;
        }
    }
    if (layer < textureArray.LayerFlags.size())
        textureArray.LayerFlags[layer] = (textureArray.LayerFlags[layer] & ~TextureArray::Opaque)
            | (layerOpaque ? TextureArray::Opaque : 0u);

    const VkDeviceSize requiredStagingSize = static_cast<VkDeviceSize>(layerPixelCount * sizeof(u32));
    if (!EnsureStagingCapacity(requiredStagingSize))
        return;

    VkDeviceSize alignedOffset = (State->StagingUsed + 15ull) & ~15ull;
    if (alignedOffset + requiredStagingSize > State->StagingCapacity)
    {
        FlushPendingUploads();
        WaitForPendingUploads();
        alignedOffset = 0;
    }
    if (State->UploadInFlight)
    {
        WaitForPendingUploads();
    }

    std::memcpy(static_cast<u8*>(State->StagingMapped) + alignedOffset, sourcePixels, requiredStagingSize);
    State->StagingUsed = alignedOffset + requiredStagingSize;
    State->PendingCopies.push_back({textureArray.Image, layer, width, height, alignedOffset});
}

void TexcacheVulkanLoader::FlushPendingUploads()
{
    if (State == nullptr || State->Device == VK_NULL_HANDLE)
        return;
    if (State->PendingInitialTransitions.empty() && State->PendingCopies.empty())
        return;

    WaitForPendingUploads();

    if (vkResetFences(State->Device, 1, &State->UploadFence) != VK_SUCCESS
        || vkResetCommandBuffer(State->CommandBuffer, 0) != VK_SUCCESS)
    {
        return;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(State->CommandBuffer, &beginInfo) != VK_SUCCESS)
        return;

    constexpr VkPipelineStageFlags kTextureShaderStages = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    if (!State->PendingInitialTransitions.empty())
    {
        std::vector<VkImageMemoryBarrier> initBarriers;
        initBarriers.reserve(State->PendingInitialTransitions.size());
        for (const auto& item : State->PendingInitialTransitions)
        {
            VkImageMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = item.Image;
            barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, item.Layers};
            initBarriers.push_back(barrier);
        }
        vkCmdPipelineBarrier(
            State->CommandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            kTextureShaderStages,
            0, 0, nullptr, 0, nullptr,
            static_cast<u32>(initBarriers.size()),
            initBarriers.data());
        State->PendingInitialTransitions.clear();
    }

    if (!State->PendingCopies.empty())
    {
        std::vector<VkImageMemoryBarrier> preBarriers;
        std::vector<VkImageMemoryBarrier> postBarriers;
        preBarriers.reserve(State->PendingCopies.size());
        postBarriers.reserve(State->PendingCopies.size());

        for (size_t i = 0; i < State->PendingCopies.size(); i++)
        {
            const auto& copy = State->PendingCopies[i];
            bool duplicate = false;
            for (size_t j = 0; j < i; j++)
            {
                if (State->PendingCopies[j].Image == copy.Image && State->PendingCopies[j].Layer == copy.Layer)
                {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate)
                continue;

            VkImageMemoryBarrier toTransfer{};
            toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toTransfer.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toTransfer.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toTransfer.image = copy.Image;
            toTransfer.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, copy.Layer, 1};
            preBarriers.push_back(toTransfer);

            VkImageMemoryBarrier toRead{};
            toRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            toRead.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toRead.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            toRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toRead.image = copy.Image;
            toRead.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, copy.Layer, 1};
            postBarriers.push_back(toRead);
        }

        vkCmdPipelineBarrier(
            State->CommandBuffer,
            kTextureShaderStages,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr,
            static_cast<u32>(preBarriers.size()),
            preBarriers.data());

        for (size_t i = 0; i < State->PendingCopies.size(); i++)
        {
            const auto& copy = State->PendingCopies[i];
            if (i > 0)
            {
                for (size_t j = 0; j < i; j++)
                {
                    if (State->PendingCopies[j].Image == copy.Image && State->PendingCopies[j].Layer == copy.Layer)
                    {
                        VkMemoryBarrier waw{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT};
                        vkCmdPipelineBarrier(State->CommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            0, 1, &waw, 0, nullptr, 0, nullptr);
                        break;
                    }
                }
            }
            VkBufferImageCopy region{};
            region.bufferOffset = copy.BufferOffset;
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, copy.Layer, 1};
            region.imageExtent = {copy.Width, copy.Height, 1};
            vkCmdCopyBufferToImage(
                State->CommandBuffer,
                State->StagingBuffer,
                copy.Image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1,
                &region);
        }

        vkCmdPipelineBarrier(
            State->CommandBuffer,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            kTextureShaderStages,
            0, 0, nullptr, 0, nullptr,
            static_cast<u32>(postBarriers.size()),
            postBarriers.data());

        State->PendingCopies.clear();
    }

    State->StagingUsed = 0;

    if (vkEndCommandBuffer(State->CommandBuffer) != VK_SUCCESS)
        return;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &State->CommandBuffer;
    {
        std::scoped_lock queueLock(VulkanContext::Get().GetQueueLock());
        if (vkQueueSubmit(State->Queue, 1, &submitInfo, State->UploadFence) != VK_SUCCESS)
            return;
    }
    State->UploadInFlight = true;
}

void TexcacheVulkanLoader::DeleteTexture(TextureHandle handle)
{
    if (State == nullptr)
        return;

    auto it = State->TextureArrays.find(handle);
    if (it == State->TextureArrays.end())
        return;

    FlushPendingUploads();
    WaitForPendingUploads();
    DestroyTextureArray(it->second);
    State->TextureArrays.erase(it);

    if (State->TextureArrays.empty())
        CleanupVulkanState();
}

bool TexcacheVulkanLoader::GetTextureDescriptor(TextureHandle handle, VkDescriptorImageInfo* outImageInfo) const
{
    if (State == nullptr || outImageInfo == nullptr || State->NearestSampler == VK_NULL_HANDLE)
        return false;

    auto it = State->TextureArrays.find(handle);
    if (it == State->TextureArrays.end())
        return false;

    const TextureArray& textureArray = it->second;
    if (textureArray.ArrayView == VK_NULL_HANDLE)
        return false;

    outImageInfo->sampler = State->NearestSampler;
    outImageInfo->imageView = textureArray.ArrayView;
    outImageInfo->imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    return true;
}

bool TexcacheVulkanLoader::GetTextureNormalizedDescriptor(TextureHandle handle, VkDescriptorImageInfo* outImageInfo) const
{
    if (State == nullptr || outImageInfo == nullptr || State->NearestSampler == VK_NULL_HANDLE)
        return false;

    auto it = State->TextureArrays.find(handle);
    if (it == State->TextureArrays.end())
        return false;

    const TextureArray& textureArray = it->second;
    if (textureArray.NormalizedArrayView == VK_NULL_HANDLE)
        return false;

    outImageInfo->sampler = State->NearestSampler;
    outImageInfo->imageView = textureArray.NormalizedArrayView;
    outImageInfo->imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    return true;
}

bool TexcacheVulkanLoader::IsTextureLayerOpaque(TextureHandle handle, u32 layer) const
{
    if (State == nullptr)
        return false;

    auto it = State->TextureArrays.find(handle);
    if (it == State->TextureArrays.end())
        return false;

    const TextureArray& textureArray = it->second;
    if (layer >= textureArray.LayerFlags.size())
        return false;

    return (textureArray.LayerFlags[layer] & TextureArray::Opaque) != 0u;
}

}
