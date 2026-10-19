package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitApi
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Why a call produced no service answer. A post-dispatch transport failure may hide a committed write. */
enum class GatewayUnavailable {
    /** Shizuku is not ready, the service did not bind in time, or the binder call failed. */
    NO_SERVICE,

    /**
     * The bound service predates API 2, rejected this caller, or threw instead of answering, so it must not be
     * written to.
     */
    STALE_SERVICE,
}

sealed interface GatewayReply<out T> {
    data class Reply<T>(
        val value: T,
    ) : GatewayReply<T>

    /**
     * [sent] is false only when the typed transaction is known never to have been dispatched: no service could be
     * acquired (Shizuku not ready, the bind timed out, the binder died before the call), or the API check in the
     * same session refused it. Nothing can have changed then. Anything after dispatch (a lost reply, a dead binder
     * mid-call, a rebind, a throw, a null return) keeps [sent] true: that write may have committed.
     */
    data class Unavailable(
        val reason: GatewayUnavailable,
        val sent: Boolean = true,
    ) : GatewayReply<Nothing>
}

/** The service marks updated system apps with a DONE code the UI must warn about. */
val ToolkitResult.requiresWarning: Boolean get() = code == ToolkitCode.WARN_UPDATED_SYSTEM

/**
 * Raw client of the typed privileged transactions 20-31. It is internal to the container: features
 * only ever see `ToolkitActions`, which re-validates, confirms and audits before calling this.
 *
 * Every call re-checks the service API version in the same binder session, so a service left over
 * from an older build (whose unknown transactions would otherwise look like successful no-ops or
 * return null parcelables) is reported as [GatewayUnavailable.STALE_SERVICE] and nothing is sent.
 *
 * While a bg-dexopt job is RUNNING the service must stay bound, or the idle unbind (remove=true)
 * would destroy it and the process supervising the job. The long-lived hold belongs to
 * `ToolkitActions` (it has a supervisor that releases it when the job ends or the cap passes). The
 * gateway only *bridges* the gap: it takes a hold inside the binder session, before the manager drops
 * its per-call use of the service, so the service cannot be unbound between the reply and that hold.
 * The bridge lapses on its own [HOLD_BRIDGE_MS] after the last RUNNING observation (or at once when a
 * status read shows the job is no longer running), so nothing here can leak when nobody collects.
 *
 * The constructor is public so the container can build it; every member that can write is internal to
 * this module, so features can only reach the privileged service through `ToolkitActions`.
 */
class ToolkitGateway(
    private val shizuku: ShizukuManager,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    private val holdBridgeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val holdLock = Any()
    private var dexoptHold: AutoCloseable? = null
    private var bridgeTimer: Job? = null

    internal suspend fun setAppOp(
        pkg: String,
        op: AppOp,
        mode: AppOpMode,
    ): GatewayReply<ToolkitResult> = call { it.setAppOp(pkg, op.code, mode.code) }

    internal suspend fun getAppOps(pkg: String): GatewayReply<ToolkitResult> = call { it.getAppOps(pkg) }

    internal suspend fun setStandbyBucket(
        pkg: String,
        bucket: StandbyBucket,
    ): GatewayReply<ToolkitResult> = call { it.setStandbyBucket(pkg, bucket.code) }

    internal suspend fun getStandbyBucket(pkg: String): GatewayReply<ToolkitResult> = call { it.getStandbyBucket(pkg) }

    /** [expectedBeforeRaw] null means "expected unset". A different current value is refused as stale. */
    internal suspend fun applyDozeProfile(
        profile: DozeProfile,
        expectedBeforeRaw: String?,
    ): GatewayReply<ToolkitResult> = call { it.applyDozeProfile(profile.code, expectedBeforeRaw) }

    internal suspend fun getDozeState(): GatewayReply<ToolkitResult> = call { it.getDozeState() }

    internal suspend fun restoreDozeRaw(
        rawOrNull: String?,
        expectedCurrentRaw: String?,
    ): GatewayReply<ToolkitResult> = call { it.restoreDozeRaw(rawOrNull, expectedCurrentRaw) }

    internal suspend fun forceStop(pkg: String): GatewayReply<ToolkitResult> = call { it.forceStop(pkg) }

    // The hold is taken inside the binder session, before the manager drops its per-call use of
    // the service: with no holder at that moment the idle unbind (remove=true) would destroy the
    // service, and the process supervising the job with it.
    internal suspend fun startBgDexopt(): GatewayReply<ToolkitResult> =
        call { service ->
            service.startBgDexopt()?.also { result -> if (result.isDone) holdBindingWhileRunning() }
        }

    internal suspend fun bgDexoptStatus(): GatewayReply<BgDexoptStatus> =
        call { service ->
            service.getBgDexoptStatus()?.also { status ->
                if (status.state == BgDexoptState.RUNNING) holdBindingWhileRunning() else releaseHold()
            }
        }

    /** Idempotent and system-side: valid whether or not this process started the job. */
    internal suspend fun cancelBgDexopt(): GatewayReply<ToolkitResult> = call { it.cancelBgDexopt() }

    /** For container teardown; normally the bridge lapses by itself. */
    fun release() = releaseHold()

    private suspend fun <T : Any> call(block: (ITelemetryUserService) -> T?): GatewayReply<T> {
        // Set right before the typed transaction. While it is false nothing but the API check reached the service,
        // so a failure then (no service, a bind timeout, a dead binder on the API check, a stale API) left the
        // device untouched; the manager's null alone cannot tell that apart from a failure after dispatch.
        val dispatched = AtomicBoolean(false)
        val reply =
            shizuku.withTelemetryService(bindTimeoutMs) { service ->
                try {
                    if (service.apiVersion < ToolkitApi.VERSION) {
                        GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = false)
                    } else {
                        dispatched.set(true)
                        block(service)?.let { GatewayReply.Reply(it) }
                            ?: GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE)
                    }
                } catch (_: RuntimeException) {
                    // The service was reached but threw (e.g. a SecurityException from its caller check, or an
                    // exception it propagated): it answered, so this is not "can't be reached". A dead binder is a
                    // RemoteException, which the manager already turns into null (NO_SERVICE below).
                    GatewayReply.Unavailable(GatewayUnavailable.STALE_SERVICE, sent = dispatched.get())
                }
            }
        return reply ?: GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE, sent = dispatched.get())
    }

    private fun holdBindingWhileRunning() {
        synchronized(holdLock) {
            if (dexoptHold == null) dexoptHold = shizuku.holdBinding()
            bridgeTimer?.cancel()
            bridgeTimer =
                holdBridgeScope.launch {
                    delay(HOLD_BRIDGE_MS)
                    releaseHold()
                }
        }
    }

    private fun releaseHold() {
        synchronized(holdLock) {
            bridgeTimer?.cancel()
            bridgeTimer = null
            dexoptHold?.close()
            dexoptHold = null
        }
    }

    private companion object {
        const val DEFAULT_BIND_TIMEOUT_MS = 5_000L

        /** Long enough for the caller to take its own hold right after the reply; short enough never to matter. */
        const val HOLD_BRIDGE_MS = 30_000L
    }
}
