package com.akane.inspiration.telemetry.dump

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.FileDescriptor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DumpReaderTest.PipeOs::class])
class DumpReaderTest {
    @Before
    fun setUp() {
        PipeOs.data = null
    }

    @Test(timeout = 15_000)
    fun `an open stalled pipe times out and closes the descriptor`() =
        runBlocking {
            descriptor().use { fd ->
                val rawFd = fd.fileDescriptor
                assertEquals(DumpParse.Unavailable("timeout"), DumpReader.read(fd, timeoutMs = 500))
                assertTrue(PipeOs.nonblocking)
                assertTrue(PipeOs.reads > 0)
                assertFalse(rawFd.valid())
            }
        }

    @Test(timeout = 15_000)
    fun `an enclosing capture deadline cancels an open pipe read`() =
        runBlocking {
            descriptor().use { fd ->
                val rawFd = fd.fileDescriptor
                var cancelled = false
                try {
                    withTimeout(500) { DumpReader.read(fd, timeoutMs = 10_000) }
                } catch (_: TimeoutCancellationException) {
                    cancelled = true
                }
                assertTrue(cancelled)
                assertTrue(PipeOs.nonblocking)
                assertFalse(rawFd.valid())
            }
        }

    @Test(timeout = 15_000)
    fun `a failed read says whether it timed out or overflowed`() =
        runBlocking {
            descriptor().use { assertEquals(DumpParse.Unavailable("timeout"), DumpReader.read(it, timeoutMs = 200)) }
            PipeOs.data = byteArrayOf(1, 2, 3)
            descriptor().use { assertEquals(DumpParse.Unavailable("overflow"), DumpReader.read(it, maxBytes = 2)) }
            descriptor().use { assertArrayEquals(PipeOs.data, (DumpReader.read(it) as DumpParse.Ok).value) }
        }

    private fun descriptor(): ParcelFileDescriptor {
        PipeOs.nonblocking = false
        PipeOs.reads = 0
        return ParcelFileDescriptor.open(
            File.createTempFile("dump-pipe", ".tmp").apply { deleteOnExit() },
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
    }

    /** Models a writer keeping its pipe open without writing; a blocking read would never return. */
    @Implements(Os::class)
    class PipeOs {
        companion object {
            var nonblocking = false
            var reads = 0
            var data: ByteArray? = null

            @JvmStatic
            @Implementation
            fun fcntlInt(
                fd: FileDescriptor,
                command: Int,
                flags: Int,
            ): Int {
                if (command == OsConstants.F_SETFL) nonblocking = flags and OsConstants.O_NONBLOCK != 0
                return 0
            }

            @JvmStatic
            @Implementation
            fun read(
                fd: FileDescriptor,
                buffer: ByteArray,
                offset: Int,
                count: Int,
            ): Int {
                check(nonblocking) { "Pipe reads must be nonblocking" }
                reads++
                val bytes = data ?: throw ErrnoException("read", OsConstants.EAGAIN)
                if (reads > 1) return 0
                bytes.copyInto(buffer, offset)
                return bytes.size
            }
        }
    }
}
