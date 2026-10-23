#include "../bridge/renderer/FaithfulLiveRoutePolicy.h"

using MelonDSAndroid::FaithfulLiveRouteHeaderValid;
using MelonDSAndroid::FaithfulLiveProjectionValid;

// Route-only publication has exact generation/key metadata but no capture table.
static_assert(FaithfulLiveRouteHeaderValid(1u, 1921u, 0u, 2u, 0u, true),
              "exact live routes must not require capture-product lineage");
static_assert(FaithfulLiveRouteHeaderValid(3u, 1u, 0u, 2u, 0u, true));
static_assert(FaithfulLiveRouteHeaderValid(5u, 1u, 0u, 2u, 0u, true));
static_assert(!FaithfulLiveRouteHeaderValid(9u, 1u, 0u, 2u, 0u, true),
              "unknown flags cannot authorize a live source");
static_assert(!FaithfulLiveRouteHeaderValid(0u, 1u, 0u, 2u, 0u, true));
static_assert(!FaithfulLiveRouteHeaderValid(1u, 0u, 0u, 2u, 0u, true));
static_assert(!FaithfulLiveRouteHeaderValid(1u, 1u, 0u, 0u, 0u, true));
static_assert(!FaithfulLiveRouteHeaderValid(1u, 1u, 0u, 2u, 0u, false),
              "malformed ABI envelope cannot authorize a live source");

static_assert(FaithfulLiveProjectionValid(1u, 256u, 192u),
              "native resolution must bind the exact live render too");
static_assert(FaithfulLiveProjectionValid(2u, 512u, 384u));
static_assert(FaithfulLiveProjectionValid(8u, 2048u, 1536u));
static_assert(FaithfulLiveProjectionValid(2u, 1024u, 768u));
static_assert(!FaithfulLiveProjectionValid(0u, 256u, 192u));
static_assert(!FaithfulLiveProjectionValid(9u, 2304u, 1728u));
static_assert(!FaithfulLiveProjectionValid(2u, 256u, 192u));
static_assert(!FaithfulLiveProjectionValid(2u, 768u, 576u));
static_assert(!FaithfulLiveProjectionValid(1u, 256u, 193u));
static_assert(!FaithfulLiveProjectionValid(1u, 65536u, 49152u));
