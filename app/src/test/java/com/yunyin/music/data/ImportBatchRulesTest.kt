package com.yunyin.music.data

import com.yunyin.music.data.import.ImportBatchRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the import state machine's pure rules: enablement, concurrency, timeout classification, progress, ids. */
class ImportBatchRulesTest {

    @Test
    fun `button enablement depends only on non-empty input`() {
        assertFalse(ImportBatchRules.canStart(""))
        assertFalse(ImportBatchRules.canStart("   \n  "))
        assertTrue(ImportBatchRules.canStart("夜曲 - 周杰伦"))
        assertTrue(ImportBatchRules.canStart("https://music.163.com/playlist?id=1"))
        // A malformed link is still tappable; validation happens after the tap.
        assertTrue(ImportBatchRules.canStart("not a playlist yet"))
    }

    @Test
    fun `concurrency is capped and never exceeds the row count`() {
        assertEquals(0, ImportBatchRules.workers(0))
        assertEquals(1, ImportBatchRules.workers(1))
        assertEquals(4, ImportBatchRules.workers(4))
        assertEquals(ImportBatchRules.MAX_CONCURRENCY, ImportBatchRules.workers(999))
    }

    @Test
    fun `progress fraction is monotonic and total zero is safe`() {
        assertEquals(0f, ImportBatchRules.progress(0, 10), 0.0001f)
        assertEquals(0.5f, ImportBatchRules.progress(5, 10), 0.0001f)
        assertEquals(1f, ImportBatchRules.progress(10, 10), 0.0001f)
        assertEquals(1f, ImportBatchRules.progress(11, 10), 0.0001f)
        assertEquals(0f, ImportBatchRules.progress(0, 0), 0.0001f)
    }

    @Test
    fun `timeout and cancellation classify separately from ordinary errors`() {
        assertEquals(ImportBatchRules.FailureKind.TIMEOUT, ImportBatchRules.classifyFailure(isTimeout = true))
        assertEquals(ImportBatchRules.FailureKind.ERROR, ImportBatchRules.classifyFailure(isTimeout = false))
        assertEquals(
            ImportBatchRules.FailureKind.CANCELLED,
            ImportBatchRules.classifyFailure(isTimeout = true, isCancellation = true),
        )
    }

    @Test
    fun `direct playlist ids drop invalid and duplicate ids`() {
        assertEquals(listOf(1L, 2L, 3L), ImportBatchRules.directIds(listOf(0L, 1L, 2L, 2L, -1L, 3L)))
        assertTrue(ImportBatchRules.directIds(emptyList()).isEmpty())
    }
}
