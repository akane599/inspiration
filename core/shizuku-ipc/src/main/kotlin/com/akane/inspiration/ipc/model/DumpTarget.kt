package com.akane.inspiration.ipc.model

object DumpTarget {
    const val BATTERYSTATS_PROTO = 1
    const val POWER_PROTO = 2
    const val ALARM_PROTO = 3
    const val DEVICEIDLE_TEXT = 4

    fun isValid(target: Int): Boolean = target in BATTERYSTATS_PROTO..DEVICEIDLE_TEXT
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
