#pragma once

namespace MelonDSAndroid {

// A direct live render is identified by the physical route, not by capture
// product lineage. The caller separately validates ABI dimensions and offsets.
constexpr bool FaithfulLiveRouteHeaderValid(
    unsigned flags, unsigned generationLo, unsigned generationHi,
    unsigned epochLo, unsigned epochHi, bool envelopeValid)
{
    return envelopeValid && (flags & 1u) != 0u && (flags & ~7u) == 0u
        && (generationLo | generationHi) != 0u && (epochLo | epochHi) != 0u;
}

constexpr bool FaithfulLiveProjectionValid(unsigned outputScale, unsigned width, unsigned height)
{
    return outputScale >= 1u && outputScale <= 8u
        && width <= 0xFFFFu && height <= 0xFFFFu
        && width >= 256u * outputScale && width % (256u * outputScale) == 0u
        && height * 4u == width * 3u;
}

} // namespace MelonDSAndroid
