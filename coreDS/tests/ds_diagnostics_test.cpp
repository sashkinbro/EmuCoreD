#include "../bridge/ds_diagnostics.h"
#include "../melonDS/src/VulkanPerfStats.h"

#include <cassert>
#include <cstdio>

static int logCalls = 0;
static int propertyCalls = 0;
static int nameEvaluations = 0;

static const char* diagnosticName()
{
    ++nameEvaluations;
    return "EMUCORED_DIAGNOSTIC_TEST_SENTINEL";
}

static int __system_property_get(const char*, char* value)
{
    ++propertyCalls;
    value[0] = '2';
    return 1;
}

int main()
{
    MelonDSAndroid::PerfSampleWindow<3> samples;
    samples.Add(10);
    samples.Add(20);
    samples.Add(30);
    // The real clock remains available for presentation deadlines in all variants.
    assert(MelonDSAndroid::PerfNowNs() > 0);
    EMUCORED_DIAGNOSTIC_LOG(++logCalls);
    const char* environment = EMUCORED_DIAGNOSTIC_ENV(diagnosticName());
    char property[2]{};
    const int length = EMUCORED_DIAGNOSTIC_PROPERTY(diagnosticName(), property);
#if EMUCORED_DIAGNOSTICS
    assert(MelonDSAndroid::DiagnosticNowNs() > 0);
    assert(samples.Ready());
    const auto summary = samples.SummarizeAndReset();
    assert(summary.Count == 3 && summary.MeanNs == 20 && summary.MaxNs == 30);
    assert(samples.Empty());
    assert(logCalls == 1);
    assert(nameEvaluations == 2);
    assert(propertyCalls == 1 && length == 1 && property[0] == '2');
#else
    static_assert(sizeof(samples) == 1, "Release must not allocate sample storage");
    assert(MelonDSAndroid::DiagnosticNowNs() == 0);
    assert(!samples.Ready() && samples.Empty());
    assert(samples.SummarizeAndReset().Count == 0);
    assert(logCalls == 0 && nameEvaluations == 0 && propertyCalls == 0);
    assert(environment == nullptr && length == 0 && property[0] == 0);
    // Release must discard arguments before type checking or side effects.
    EMUCORED_DIAGNOSTIC_LOG(nonexistentDiagnosticDump("release-dump-sentinel.bin"));
#endif
}
