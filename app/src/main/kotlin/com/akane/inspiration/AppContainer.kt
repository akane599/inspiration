package com.akane.inspiration

import android.content.Context
import com.akane.inspiration.database.InspirationDatabase
import com.akane.inspiration.database.store.RoomSnapshotStore
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuManagerImpl
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.source.AndroidClocks
import com.akane.inspiration.telemetry.source.AndroidTelemetrySource
import com.akane.inspiration.telemetry.store.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clocks: Clocks = AndroidClocks(appContext)
    val shizukuManager: ShizukuManager = ShizukuManagerImpl(
        context = appContext,
        scope = applicationScope,
    )
    val database: InspirationDatabase = InspirationDatabase.create(appContext)
    val store: SnapshotStore = RoomSnapshotStore(database)
    val capturer = SnapshotCapturer(
        source = AndroidTelemetrySource(appContext, shizukuManager, clocks),
        store = store,
        clocks = clocks,
    )
}
