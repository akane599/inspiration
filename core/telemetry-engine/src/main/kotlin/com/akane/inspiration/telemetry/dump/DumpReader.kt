package com.akane.inspiration.telemetry.dump

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object DumpReader {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun readAll(input: InputStream, maxBytes: Int = MAX_BYTES): ByteArray? = input.use {
        if (maxBytes < 0) {
            return@use null
        }

        try {
            val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            var overCap = false
            var reachedEnd = false
            while (!overCap && !reachedEnd) {
                val read = input.read(buffer)
                when {
                    read == -1 -> reachedEnd = true
                    read == 0 -> Unit
                    read > maxBytes - total -> overCap = true
                    else -> {
                        output.write(buffer, 0, read)
                        total += read
                    }
                }
            }
            if (overCap) null else output.toByteArray()
        } catch (_: IOException) {
            null
        }
    }

    /** Nonblocking reads make both this deadline and an enclosing capture cancellation effective. */
    suspend fun readAll(
        fd: ParcelFileDescriptor,
        maxBytes: Int = MAX_BYTES,
        timeoutMs: Long = 2_000L,
    ): ByteArray? = fd.use {
        if (maxBytes < 0) return@use null
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                try {
                    val descriptor = fd.fileDescriptor
                    val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
                    Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
                    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var reachedEnd = false
                    while (!reachedEnd) {
                        currentCoroutineContext().ensureActive()
                        val read = try {
                            Os.read(descriptor, buffer, 0, buffer.size)
                        } catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                            delay(10L)
                            continue
                        }
                        if (read == 0) {
                            reachedEnd = true
                        } else {
                            if (read > maxBytes - output.size()) return@withContext null
                            output.write(buffer, 0, read)
                        }
                    }
                    output.toByteArray()
                } catch (_: ErrnoException) {
                    null
                }
            }
        }
    }
}
