package com.akane.inspiration.telemetry.dump

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

object DumpReader {
    const val MAX_BYTES = 16 * 1024 * 1024

    /**
     * Reads [fd] to EOF and closes it, or says why it got no bytes: "timeout" after [timeoutMs], "overflow" past
     * [maxBytes], else "io". Nonblocking reads make both this deadline and an enclosing capture cancellation
     * effective.
     */
    suspend fun read(
        fd: ParcelFileDescriptor,
        maxBytes: Int = MAX_BYTES,
        timeoutMs: Long = 2_000L,
    ): DumpParse<ByteArray> =
        fd.use {
            if (maxBytes < 0) return@use DumpParse.Unavailable("overflow")
            withTimeoutOrNull(timeoutMs) {
                withContext<DumpParse<ByteArray>>(Dispatchers.IO) {
                    try {
                        val descriptor = fd.fileDescriptor
                        val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
                        Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var reachedEnd = false
                        while (!reachedEnd) {
                            currentCoroutineContext().ensureActive()
                            val read =
                                try {
                                    Os.read(descriptor, buffer, 0, buffer.size)
                                } catch (error: ErrnoException) {
                                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                                    delay(10L)
                                    continue
                                }
                            if (read == 0) {
                                reachedEnd = true
                            } else {
                                if (read > maxBytes - output.size()) return@withContext DumpParse.Unavailable("overflow")
                                output.write(buffer, 0, read)
                            }
                        }
                        DumpParse.Ok(output.toByteArray())
                    } catch (_: ErrnoException) {
                        DumpParse.Unavailable("io")
                    }
                }
            } ?: DumpParse.Unavailable("timeout")
        }
}
