package com.sbro.emucored.data.covers

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sbro.emucored.data.GamePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run this class alone in a fresh process with -e coverIndexBenchmark true for cold timings. */
@RunWith(AndroidJUnit4::class)
class CoverIndexDeviceBenchmarkInstrumentedTest {
    @Test
    fun bundledIndexFirstLookupAndRepeatedHitsAndMisses() {
        assumeTrue("Opt in with coverIndexBenchmark=true in a fresh instrumentation process",
            InstrumentationRegistry.getArguments().getString("coverIndexBenchmark") == "true")
        val repository = CoverIndexRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val queries = listOf(
            Query("DS serial", "NTR-AMCE", null, GamePlatform.NDS, "2345"),
            Query("DS title", null, "Mario Kart DS", GamePlatform.NDS, "2345"),
            Query("3DS serial", "CTR-P-AMKE", null, GamePlatform.N3DS, "2349"),
            Query("3DS title", null, "Mario Kart 7", GamePlatform.N3DS, "2349"),
            Query("DS miss", "NTR-ZZZZ", "qzxvplkj absent sentinel", GamePlatform.NDS, null),
            Query("3DS miss", "CTR-P-ZZZZ", "qzxvplkj absent sentinel", GamePlatform.N3DS, null)
        )
        val coldStart = SystemClock.elapsedRealtimeNanos()
        val first = queries.map { query ->
            val started = SystemClock.elapsedRealtimeNanos()
            val cover = repository.find(query.serial, query.title, query.platform)
            val nanos = SystemClock.elapsedRealtimeNanos() - started
            verify(query, cover)
            report("first ${query.label}: ${nanos / 1_000_000.0} ms, id=${query.expectedId ?: "miss"}")
            cover
        }
        val firstBatchNanos = SystemClock.elapsedRealtimeNanos() - coldStart
        val warmStart = SystemClock.elapsedRealtimeNanos()
        repeat(100) {
            queries.forEachIndexed { index, query ->
                val result = repository.find(query.serial, query.title, query.platform)
                assertEquals("Repeated ${query.label} changed identity", first[index], result)
            }
        }
        val warmNanos = SystemClock.elapsedRealtimeNanos() - warmStart
        report("first batch=${firstBatchNanos / 1_000_000.0} ms; warm600 lookups=${warmNanos / 1_000_000.0} ms; mean=${warmNanos / 600_000.0} us/lookup")
    }

    private fun verify(query: Query, cover: CoverIndexRepository.Cover?) {
        if (query.expectedId == null) assertNull(query.label, cover)
        else assertEquals(query.label, "https://raw.githubusercontent.com/sashkinbro/EmuCoreD-Covers/main/covers/${query.expectedId}.jpg", cover?.url)
    }

    private fun report(message: String) {
        Log.i("CoverIndexDeviceBenchmark", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nCoverIndex benchmark: $message\n")
        })
    }

    private data class Query(val label: String, val serial: String?, val title: String?, val platform: GamePlatform, val expectedId: String?)
}
