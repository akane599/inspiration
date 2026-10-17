package com.akane.inspiration.telemetry.dump

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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

    fun readAll(fd: ParcelFileDescriptor, maxBytes: Int = MAX_BYTES): ByteArray? =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            readAll(input, maxBytes)
        }
}
