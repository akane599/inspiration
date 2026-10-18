package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.model.BgDexoptReason
import com.akane.inspiration.ipc.model.BgDexoptState
import com.akane.inspiration.ipc.model.BgDexoptStatus as ServiceBgDexoptStatus
import com.akane.inspiration.ipc.model.ToolkitCode
import com.akane.inspiration.ipc.model.ToolkitResult
import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ToolkitActionStatus
import kotlinx.coroutines.yield

const val PKG = "com.example.chatty"
const val SYSTEM_PKG = "com.example.updatedsys"

fun serviceStatus(state: Int, exit: Int = 0, reason: Int = BgDexoptReason.NONE) =
    ServiceBgDexoptStatus(state, startedAtMs = 1_000L, endedAtMs = if (state >= BgDexoptState.FINISHED) 2_000L else 0L, exitCode = exit, reason = reason)

/** In-memory audit log with the real store's ordering contract (recent: newest first, pending: oldest first). */
class FakeAudit : ToolkitAuditStore {
    val rows = mutableListOf<ToolkitActionRecord>()
    private var nextId = 1L

    override suspend fun insert(record: ToolkitActionRecord): Long {
        val id = nextId++
        rows += record.copy(id = id)
        return id
    }

    override suspend fun update(record: ToolkitActionRecord) {
        // A real cancellation point, like a Room call: an update the caller's cancel can abort.
        yield()
        val index = rows.indexOfFirst { it.id == record.id }
        if (index >= 0) rows[index] = record
    }

    override suspend fun recent(limit: Int): List<ToolkitActionRecord> = rows.sortedByDescending { it.id }.take(limit)

    override suspend fun pending(): List<ToolkitActionRecord> =
        rows.filter { it.status == ToolkitActionStatus.PENDING }.sortedBy { it.id }

    fun byId(id: Long): ToolkitActionRecord = rows.first { it.id == id }
}

/**
 * A fake of the privileged service as the use case sees it: it holds the "device" state, answers reads
 * from it and applies writes to it, with hooks to script denials, failures and a stale API.
 */
class FakeBackend : ToolkitBackend {
    val appOps = mutableMapOf<String, MutableMap<AppOp, String>>()
    val buckets = mutableMapOf<String, Int>()
    var dozeRaw: String? = null
    val updatedSystem = mutableSetOf<String>()

    /** pkg to the refusal code the service answers for any call about it. */
    val denied = mutableMapOf<String, Int>()
    var unavailable: GatewayUnavailable? = null

    /** The next this many reads answer NO_SERVICE, then reads answer normally again (a bind wait that expired once). */
    var unavailableReads = 0

    /** Like [unavailable] but only for writes: reads still answer (a service that goes stale mid-flow). */
    var writeUnavailable: GatewayUnavailable? = null
    var unavailableAfterWrite: GatewayUnavailable? = null

    /** When set, the next write returns this instead of changing the device. */
    var scriptedWrite: ToolkitResult? = null
    var nullBeforeOnAppOpWrite = false
    var nullBeforeOnBucketWrite = false

    /** When set, every app-op/bucket/doze read answers this instead of the device state (a read-back that fails). */
    var scriptedRead: ToolkitResult? = null

    /** Runs at the start of every read, before the answer is built (a device that changes between two reads). */
    var onRead: (String) -> Unit = {}

    /** Names of the writes issued, in order. Reads are not listed. */
    val writes = mutableListOf<String>()
    val reads = mutableListOf<String>()

    /** Runs at the start of every write, before the device changes (used to look at the audit log). */
    var onWrite: suspend (String) -> Unit = {}

    /** Runs after the device changed, before the reply is returned (the write has happened by now). */
    var afterWrite: suspend (String) -> Unit = {}

    /** When set, every write throws this once the call has started (a backend that blows up). */
    var writeFailure: Throwable? = null

    var startResult: ToolkitResult = ToolkitResult.done(before = "${BgDexoptState.IDLE}", after = "${BgDexoptState.RUNNING}")
    var cancelResult: ToolkitResult = ToolkitResult.done()

    /** Consumed one per status read; the last entry repeats. */
    val dexoptStatuses = ArrayDeque<ServiceBgDexoptStatus>().apply { add(serviceStatus(BgDexoptState.IDLE)) }
    var dexoptReads = 0

    fun device(pkg: String): MutableMap<AppOp, String> =
        appOps.getOrPut(pkg) { AppOp.entries.associateWith { "allow" }.toMutableMap() }

    private fun warnCode(pkg: String) = if (pkg in updatedSystem) ToolkitCode.WARN_UPDATED_SYSTEM else ToolkitCode.OK

    private fun <T : Any> guard(pkg: String?, block: () -> GatewayReply<T>): GatewayReply<T> {
        unavailable?.let { return GatewayReply.Unavailable(it) }
        if (unavailableReads > 0) {
            unavailableReads -= 1
            return GatewayReply.Unavailable(GatewayUnavailable.NO_SERVICE)
        }
        return block()
    }

    private fun refusal(pkg: String): ToolkitResult? = denied[pkg]?.let { ToolkitResult.refused(it) }

    private suspend fun write(name: String, block: () -> ToolkitResult): GatewayReply<ToolkitResult> {
        unavailable?.let { return GatewayReply.Unavailable(it) }
        writes += name
        onWrite(name)
        writeUnavailable?.let { return GatewayReply.Unavailable(it) }
        writeFailure?.let { throw it }
        val reply = GatewayReply.Reply(scriptedWrite?.also { scriptedWrite = null } ?: block())
        afterWrite(name)
        unavailableAfterWrite?.let { return GatewayReply.Unavailable(it) }
        return reply
    }

    override suspend fun setAppOp(pkg: String, op: AppOp, mode: AppOpMode) = write("setAppOp($pkg,${op.argv},${mode.argv})") {
        refusal(pkg) ?: run {
            val before = device(pkg)[op]
            device(pkg)[op] = mode.argv
            ToolkitResult.done(warnCode(pkg), before.takeUnless { nullBeforeOnAppOpWrite }, mode.argv)
        }
    }

    override suspend fun getAppOps(pkg: String): GatewayReply<ToolkitResult> = guard(pkg) {
        reads += "getAppOps($pkg)"
        onRead("getAppOps($pkg)")
        GatewayReply.Reply(
            scriptedRead ?: refusal(pkg) ?: ToolkitResult.done(
                warnCode(pkg),
                after = AppOp.entries.joinToString(",") { "${it.argv}=${device(pkg)[it]}" },
            ),
        )
    }

    override suspend fun setStandbyBucket(pkg: String, bucket: StandbyBucket) = write("setBucket($pkg,${bucket.argv})") {
        refusal(pkg) ?: run {
            val before = buckets[pkg] ?: StandbyBucket.ACTIVE.rawValue
            buckets[pkg] = bucket.rawValue
            ToolkitResult.done(warnCode(pkg), before.toString().takeUnless { nullBeforeOnBucketWrite }, bucket.rawValue.toString())
        }
    }

    override suspend fun getStandbyBucket(pkg: String): GatewayReply<ToolkitResult> = guard(pkg) {
        reads += "getBucket($pkg)"
        onRead("getBucket($pkg)")
        GatewayReply.Reply(
            scriptedRead ?: refusal(pkg) ?: ToolkitResult.done(warnCode(pkg), after = (buckets[pkg] ?: StandbyBucket.ACTIVE.rawValue).toString()),
        )
    }

    override suspend fun applyDozeProfile(profile: DozeProfile, expectedBeforeRaw: String?) =
        write("applyDoze(${profile.code},$expectedBeforeRaw)") {
            if (dozeRaw != expectedBeforeRaw) {
                ToolkitResult.refused(ToolkitCode.STALE, before = dozeRaw)
            } else {
                val before = dozeRaw
                dozeRaw = profile.constants
                ToolkitResult.done(before = before, after = dozeRaw)
            }
        }

    override suspend fun getDozeState(): GatewayReply<ToolkitResult> = guard(null) {
        reads += "getDozeState"
        onRead("getDozeState")
        val matched = DozeProfile.entries.firstOrNull { it.constants == dozeRaw }
        GatewayReply.Reply(
            scriptedRead ?: ToolkitResult.done(
                code = matched?.code ?: ToolkitCode.DOZE_CUSTOM,
                after = dozeRaw,
                message = matched?.effectiveMs?.entries?.joinToString(",") { "${it.key}=${it.value}" },
            ),
        )
    }

    override suspend fun restoreDozeRaw(rawOrNull: String?, expectedCurrentRaw: String?) =
        write("restoreDoze($rawOrNull,$expectedCurrentRaw)") {
            if (dozeRaw != expectedCurrentRaw?.takeIf { it.isNotEmpty() }) {
                ToolkitResult.refused(ToolkitCode.STALE, before = dozeRaw)
            } else {
                val before = dozeRaw
                dozeRaw = rawOrNull?.takeIf { it.isNotEmpty() }
                ToolkitResult.done(before = before, after = dozeRaw)
            }
        }

    override suspend fun forceStop(pkg: String) = write("forceStop($pkg)") {
        refusal(pkg) ?: ToolkitResult.done(warnCode(pkg), "stopped=false", "stopped=true")
    }

    override suspend fun startBgDexopt() = write("startBgDexopt") { startResult }

    override suspend fun bgDexoptStatus(): GatewayReply<ServiceBgDexoptStatus> = guard(null) {
        dexoptReads += 1
        val next = if (dexoptStatuses.size > 1) dexoptStatuses.removeFirst() else dexoptStatuses.first()
        GatewayReply.Reply(next)
    }

    override suspend fun cancelBgDexopt() = write("cancelBgDexopt") { cancelResult }
}

/** Counts binding holds like `ShizukuManager.holdBinding()`: [opened] total, [open] currently alive. */
class FakeHolds {
    var opened = 0
    var open = 0

    fun hold(): AutoCloseable {
        opened += 1
        open += 1
        var closed = false
        return AutoCloseable {
            if (!closed) {
                closed = true
                open -= 1
            }
        }
    }
}
