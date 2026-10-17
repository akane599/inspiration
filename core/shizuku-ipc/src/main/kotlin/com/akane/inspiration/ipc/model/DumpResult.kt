package com.akane.inspiration.ipc.model

import android.os.ParcelFileDescriptor
import kotlinx.parcelize.Parcelize

@Parcelize
data class DumpResult(
    val available: Boolean,
    val reason: String?,
    val fd: ParcelFileDescriptor?,
) : android.os.Parcelable {
    companion object {
        fun unavailable(reason: String): DumpResult = DumpResult(
            available = false,
            reason = reason,
            fd = null,
        )
    }
}
