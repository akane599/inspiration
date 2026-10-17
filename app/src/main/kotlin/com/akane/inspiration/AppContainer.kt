package com.akane.inspiration

import android.content.Context
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val shizukuManager: ShizukuManager = ShizukuManagerImpl(
        context = context.applicationContext,
        scope = applicationScope,
    )
}
