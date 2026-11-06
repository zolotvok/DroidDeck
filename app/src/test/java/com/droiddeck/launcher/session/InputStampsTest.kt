package com.droiddeck.launcher.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The focus guard's input stamps (droiddeck-agent FocusGuard reads .padapply/user and .padapply/agent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class InputStampsTest {
    private val tmp: File = Files.createTempDirectory("stamps").toFile()
    private val dir = File(tmp, ".padapply")

    @After fun tearDown() {
        InputStamps.reset(null)
        tmp.deleteRecursively()
    }

    @Test fun userInputIsStampedAndThrottled() {
        InputStamps.reset(dir)
        InputStamps.user(1_000)
        InputStamps.user(1_100)  // within USER_EVERY_MS: same stamp
        InputStamps.flush()
        assertEquals("1000", File(dir, "user").readText().trim())
        InputStamps.user(1_000 + InputStamps.USER_EVERY_MS)
        InputStamps.flush()
        assertEquals("${1_000 + InputStamps.USER_EVERY_MS}", File(dir, "user").readText().trim())
    }

    @Test fun agentInputIsAWindowNotAUserStamp() {
        InputStamps.reset(dir)
        val result = InputStamps.agent { InputStamps.flush(); File(dir, "agent").readText() }
        // while the input runs, its window is open (provisional end)
        val (start, provisional) = result.trim().split(" ").map { it.toLong() }
        assertTrue(provisional > start)
        InputStamps.flush()
        val (start2, end) = File(dir, "agent").readText().trim().split(" ").map { it.toLong() }
        assertEquals(start, start2)
        assertTrue(end in start..provisional)
        assertFalse(File(dir, "user").exists())
    }

    @Test fun onlyTheLastWindowsAreKept() {
        InputStamps.reset(dir)
        repeat(InputStamps.AGENT_WINDOWS + 5) { InputStamps.agent { } }
        InputStamps.flush()
        assertEquals(InputStamps.AGENT_WINDOWS, File(dir, "agent").readLines().size)
    }

    @Test fun noDirectoryNoFiles() {
        InputStamps.reset(null)
        InputStamps.user(5_000)
        InputStamps.agent { }
        InputStamps.flush()
        assertFalse(dir.exists())
    }
}
