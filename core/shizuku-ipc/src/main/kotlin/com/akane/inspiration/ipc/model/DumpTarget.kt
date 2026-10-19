package com.akane.inspiration.ipc.model

object DumpTarget {
    const val BATTERYSTATS_PROTO = 1

    // 2 (`power --proto`) and 4 (`deviceidle` text) are reserved: removed with no client; never reuse them.
    const val ALARM_PROTO = 3

    /** An explicit allowlist, not a range: removed codes stay rejected. */
    fun isValid(target: Int): Boolean = target == BATTERYSTATS_PROTO || target == ALARM_PROTO
}

object Reasons {
    const val API = "api"
    const val PERMISSION = "permission"
    const val REMOTE = "remote"
    const val NO_SUCH_METHOD = "no_such_method"
    const val TIMEOUT = "timeout"
    const val IO = "io"
    const val TARGET = "target"
    const val CALLER = "caller"
}
