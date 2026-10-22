#include "renderer/VulkanRetroArchFilterChain.h"

namespace MelonDSAndroid
{

VulkanRetroArchFilterChain::~VulkanRetroArchFilterChain()
{
    shutdown();
}

VulkanRetroArchFilterChain::VulkanRetroArchFilterChain(VulkanRetroArchFilterChain&& other) noexcept
{
    chain = other.chain;
    currentPresetPath = std::move(other.currentPresetPath);
    currentSourceWidth = other.currentSourceWidth;
    currentSourceHeight = other.currentSourceHeight;
    currentOutputWidth = other.currentOutputWidth;
    currentOutputHeight = other.currentOutputHeight;
    currentParameterOverrides = std::move(other.currentParameterOverrides);

    other.chain = nullptr;
    other.currentSourceWidth = 0;
    other.currentSourceHeight = 0;
    other.currentOutputWidth = 0;
    other.currentOutputHeight = 0;
}

VulkanRetroArchFilterChain& VulkanRetroArchFilterChain::operator=(VulkanRetroArchFilterChain&& other) noexcept
{
    if (this == &other)
        return *this;

    shutdown();
    chain = other.chain;
    currentPresetPath = std::move(other.currentPresetPath);
    currentSourceWidth = other.currentSourceWidth;
    currentSourceHeight = other.currentSourceHeight;
    currentOutputWidth = other.currentOutputWidth;
    currentOutputHeight = other.currentOutputHeight;
    currentParameterOverrides = std::move(other.currentParameterOverrides);

    other.chain = nullptr;
    other.currentSourceWidth = 0;
    other.currentSourceHeight = 0;
    other.currentOutputWidth = 0;
    other.currentOutputHeight = 0;
    return *this;
}

void VulkanRetroArchFilterChain::shutdown()
{
    chain = nullptr;
    currentPresetPath.clear();
    currentSourceWidth = 0;
    currentSourceHeight = 0;
    currentOutputWidth = 0;
    currentOutputHeight = 0;
    currentParameterOverrides.clear();
}

bool VulkanRetroArchFilterChain::configure(
    const std::string& presetPath,
    melonDS::u32 sourceWidth,
    melonDS::u32 sourceHeight,
    melonDS::u32 outputWidth,
    melonDS::u32 outputHeight,
    const std::vector<std::pair<std::string, float>>& parameterOverrides)
{
    (void)presetPath;
    (void)sourceWidth;
    (void)sourceHeight;
    (void)outputWidth;
    (void)outputHeight;
    (void)parameterOverrides;
    return false;
}

bool VulkanRetroArchFilterChain::recordFrame(
    VkCommandBuffer commandBuffer,
    VkImage sourceImage,
    VkImage outputImage,
    melonDS::u64 frameCount,
    bool clearHistory,
    melonDS::u32 frametimeDeltaMs)
{
    (void)commandBuffer;
    (void)sourceImage;
    (void)outputImage;
    (void)frameCount;
    (void)clearHistory;
    (void)frametimeDeltaMs;
    return false;
}

void VulkanRetroArchFilterChain::logError(const char*, libra_error_t error)
{
    (void)error;
}

std::string VulkanRetroArchFilterChain::describeError(const char* context, libra_error_t error)
{
    (void)error;
    return std::string(context) + " failed";
}

}
