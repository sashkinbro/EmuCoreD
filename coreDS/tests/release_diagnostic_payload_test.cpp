#include "../bridge/renderer/FaithfulDiagnosticPayload.h"

using MelonDSAndroid::FaithfulDiagnosticPayload;
static_assert(sizeof(FaithfulDiagnosticPayload) == 1u,
              "release must not retain diagnostic headers or frame identities");

int main()
{
    FaithfulDiagnosticPayload payload;
    FaithfulDiagnosticPayload::Header header{};
    header[0] = FaithfulDiagnosticPayload::Magic;
    header[1] = FaithfulDiagnosticPayload::Version;
    header[2] = FaithfulDiagnosticPayload::HeaderWords;
    FaithfulDiagnosticPayload::write64(header, 4u, 1u);
    payload.publish(1u, header);
    payload.invalidateSlot(0u);
    if (payload.matches(1u) || !payload.copy(1u, nullptr, 0u, nullptr, 0u).empty()) return 1;
    payload.invalidate();
    return 0;
}
