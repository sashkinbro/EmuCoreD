#include "../bridge/ds_frame_trace.h"

constexpr bool TraceWindowContract() {
    EmuCoreDS::DsFrameTrace trace;
    if (trace.shouldPoll(false) || trace.take(false)) return false;
    trace.observe(1);
    if (!trace.take(true) || trace.take(false)) return false;
    trace.observe(2);
    for (int frame = 0; frame < 90; ++frame) {
        if (!trace.shouldPoll(false) || !trace.take(false)) return false;
        trace.observe(2); // Re-reading an unchanged property must not refill the window.
    }
    if (trace.take(true) || trace.shouldPoll(false)) return false;
    trace.observe(0);
    trace.observe(2);
    if (!trace.take(false)) return false;
    trace.reset();
    if (trace.take(true)) return false;
    trace.observe(2);
    return trace.take(false);
}
static_assert(TraceWindowContract(), "Trace must be opt-in, bounded, rearmable and session-local");
