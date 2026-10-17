package com.akane.inspiration.telemetry.common

interface Clocks {
    fun wallMs(): Long

    fun elapsedRealtimeMs(): Long

    fun uptimeMs(): Long

    fun bootCount(): Int
}
