// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.lxsource.LxImportResult
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourceManager
import com.yunyin.music.data.lxsource.LxSourceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The import size cap.
 *
 * The cap exists to reject "the user picked the wrong file" with a sentence instead of an out-of-memory kill,
 * and it has to sit above real sources — the provider sources are 500–900 KB, so a 1 MiB cap was one large
 * update away from refusing a valid source. These tests pin both sides of the boundary: just over is refused
 * for size, just under is *not* refused for size (it fails later, at the header, which is the point).
 */
class LxSourceLimitsTest {

    private fun manager(): LxSourceManager {
        val dir = Files.createTempDirectory("lx-limits").toFile()
        val log = LxSourceLog(dir)
        return LxSourceManager(null, LxSourceStore(dir, log), log)
    }

    @Test
    fun `a script just over 4 MiB is refused with the cap named`() = runBlocking {
        val tooBig = "a".repeat(CAP_BYTES + 1)
        val result = manager().importScript(tooBig)
        assertTrue(result is LxImportResult.Failure)
        assertEquals("脚本文件过大（上限 4096 KB）", (result as LxImportResult.Failure).message)
    }

    @Test
    fun `a script just under the cap is not refused for its size`() = runBlocking {
        // The 800 KB provider sources are the case this protects: under the cap, the failure must be about the
        // missing header, not about size.
        val justUnder = "a".repeat(CAP_BYTES - 1)
        val result = manager().importScript(justUnder)
        assertTrue(result is LxImportResult.Failure)
        assertTrue(
            (result as LxImportResult.Failure).message,
            result.message.contains("缺少自定义源信息注释"),
        )
    }

    private companion object {
        const val CAP_BYTES = 4 * 1024 * 1024
    }
}
