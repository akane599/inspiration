package com.akane.inspiration.ipc

import android.content.ComponentName
import android.content.ServiceConnection
import rikka.shizuku.Shizuku

interface ShizukuFacade {
    fun pingBinder(): Boolean

    fun getUid(): Int

    fun getVersion(): Int

    fun isPreV11(): Boolean

    fun checkSelfPermission(): Int

    fun shouldShowRequestPermissionRationale(): Boolean

    fun requestPermission(requestCode: Int)

    fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener)

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener)

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener)

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener)

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)

    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)

    fun bindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
    )

    fun unbindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
        remove: Boolean,
    )
}

object RealShizukuFacade : ShizukuFacade {
    override fun pingBinder(): Boolean = Shizuku.pingBinder()

    override fun getUid(): Int = Shizuku.getUid()

    override fun getVersion(): Int = Shizuku.getVersion()

    override fun isPreV11(): Boolean = Shizuku.isPreV11()

    override fun checkSelfPermission(): Int = Shizuku.checkSelfPermission()

    override fun shouldShowRequestPermissionRationale(): Boolean = Shizuku.shouldShowRequestPermissionRationale()

    override fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    override fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    override fun bindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
    ) {
        Shizuku.bindUserService(args, connection)
    }

    override fun unbindUserService(
        args: Shizuku.UserServiceArgs,
        connection: ServiceConnection,
        remove: Boolean,
    ) {
        Shizuku.unbindUserService(args, connection, remove)
    }
}

internal data class ShizukuApplicationInfo(
    val versionCode: Long,
    val debuggable: Boolean,
)

/** This app's own package details, which the user-service args carry. */
internal fun interface ShizukuApplicationInfoProvider {
    fun load(): ShizukuApplicationInfo
}

/**
 * Whether the Shizuku manager app is installed. Only asked when no Shizuku binder is available: Sui (root)
 * hands ShizukuProvider a binder without any manager app, so a live binder never means "not installed" (N-08).
 */
internal fun interface ShizukuManagerAppProbe {
    fun isInstalled(): Boolean
}

internal fun shizukuUserServiceArgs(
    componentName: ComponentName,
    applicationInfo: ShizukuApplicationInfo,
): Shizuku.UserServiceArgs =
    Shizuku
        .UserServiceArgs(componentName)
        .processNameSuffix("telemetry")
        .daemon(false)
        .version(applicationInfo.versionCode.toInt())
        .tag("telemetry")
        .debuggable(applicationInfo.debuggable)
