package com.akane.inspiration.telemetry.source

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import com.akane.inspiration.telemetry.snapshot.UID_USER_RANGE
import com.akane.inspiration.telemetry.snapshot.UidIdentityProof
import com.akane.inspiration.telemetry.snapshot.UidIdentityScope
import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.snapshot.isApplicationUid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Fresh framework reads, deliberately separate from cached cosmetic app labels. */
interface UidIdentityBackend {
    val userId: Int

    fun userSerial(): Long?

    fun packageChangeSequence(): Int

    fun packagesForUid(uid: Int): List<String>?

    fun packageInfo(packageName: String): UidOwnerInstall?
}

class AndroidUidIdentityBackend(
    context: Context,
) : UidIdentityBackend {
    private val packageManager = context.packageManager
    private val userManager = context.getSystemService(UserManager::class.java)
    private val user = Process.myUserHandle()

    // UserHandle.getIdentifier is hidden from the public SDK; derive only this process's namespace.
    override val userId: Int = Process.myUid() / UID_USER_RANGE

    override fun userSerial(): Long? = userManager?.getSerialNumberForUser(user)?.takeIf { it >= 0 }

    // This executes INSIDE the blocking bridge. Successful API null means sequence zero, not failure.
    override fun packageChangeSequence(): Int = packageManager.getChangedPackages(0)?.sequenceNumber ?: 0

    override fun packagesForUid(uid: Int): List<String>? =
        try {
            packageManager.getPackagesForUid(uid)?.toList()
        } catch (_: SecurityException) {
            null
        }

    override fun packageInfo(packageName: String): UidOwnerInstall? =
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.applicationInfo?.let { app -> UidOwnerInstall(info.packageName, app.uid, info.firstInstallTime) }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
}

/** A token is local to the full acquisition bracket, never persisted or reused by another reader. */
class UidIdentityOpening internal constructor(
    internal val scope: UidIdentityScope,
    internal var calls: Int,
) {
    internal val consumed = AtomicBoolean(false)
}

/**
 * <=500ms extra identity caller time (50 opening +400 owners +50 closing), <=1024 attempted calls.
 * Overlapping captures take turns on the physical gate within their phase budgets instead of failing
 * each other's proof. A timed-out native call retains the gate until it returns and new calls fail fast
 * meanwhile: repeated captures cannot pile up abandoned PM transactions. Local deadlines omit proof;
 * outer cancellation still propagates.
 */
class UidIdentityReader(
    private val backend: UidIdentityBackend,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openingBudgetMs: Long = 50,
    private val ownerBudgetMs: Long = 400,
    private val closingBudgetMs: Long = 50,
    private val maxCalls: Int = 1_024,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val epoch = AtomicReference(UUID.randomUUID().toString())

    /** One physical identity call at a time; released only when that call returns, even if its caller gave up. */
    private val gate = Mutex()

    /** Token of a call whose caller timed out or was cancelled while it was in flight, until it returns. */
    private val abandonedCall = AtomicReference<Any?>(null)

    suspend fun open(): UidIdentityOpening? {
        if (maxCalls < 4) return null
        val beforeEpoch = epoch.get()
        return withTimeoutOrNull(openingBudgetMs) {
            val serial = blocking { Value(backend.userSerial()) }?.value ?: return@withTimeoutOrNull null
            val sequence = blocking { SequenceSuccess(backend.packageChangeSequence()) }?.sequence ?: return@withTimeoutOrNull null
            val scope = UidIdentityScope(backend.userId, serial, beforeEpoch, sequence)
            if (scope.isKnown() && epoch.get() == beforeEpoch) UidIdentityOpening(scope, 2) else null
        }
    }

    suspend fun close(
        opening: UidIdentityOpening?,
        uids: Set<Int>,
    ): UidIdentityProof? {
        if (opening == null || epoch.get() != opening.scope.readerEpoch || !opening.consumed.compareAndSet(false, true)) return null
        val fingerprints = mutableMapOf<Int, String>()
        val ownerDeadline = elapsedMs() + ownerBudgetMs
        withTimeoutOrNull(ownerBudgetMs) {
            for (uid in uids.sorted()) {
                currentCoroutineContext().ensureActive()
                if (epoch.get() != opening.scope.readerEpoch || callAbandoned()) break
                if (!isApplicationUid(uid) || !opening.scope.covers(uid)) continue
                if (opening.calls >= maxCalls - 2 || elapsedMs() >= ownerDeadline) break
                opening.calls++
                val names = blocking { Value(backend.packagesForUid(uid)) }?.value ?: continue
                if (names.isEmpty() || names.size > UidOwnerFingerprint.MAX_OWNERS || names.distinct().size != names.size) continue
                if (names.any { !UidOwnerFingerprint.validPackageName(it) }) continue
                // A complete owner group is atomic: reserve all of its lookups, plus the closing calls.
                if (opening.calls + names.size > maxCalls - 2) break
                val owners = mutableListOf<UidOwnerInstall>()
                for (name in names.sorted()) {
                    currentCoroutineContext().ensureActive()
                    if (elapsedMs() >= ownerDeadline) break
                    opening.calls++
                    val owner = blocking { Value(backend.packageInfo(name)) }?.value
                    if (owner == null || owner.packageName != name || owner.uid != uid || owner.firstInstallTimeMs <= 0) break
                    owners += owner
                }
                if (owners.size == names.size) {
                    UidOwnerFingerprint.create(uid, opening.scope.userSerial, owners)?.let { fingerprints[uid] = it }
                }
                if (epoch.get() != opening.scope.readerEpoch || callAbandoned()) break
            }
        }
        if (epoch.get() != opening.scope.readerEpoch || callAbandoned()) return null
        return withTimeoutOrNull(closingBudgetMs) {
            opening.calls++
            val serial = blocking { Value(backend.userSerial()) }?.value ?: return@withTimeoutOrNull null
            opening.calls++
            val sequence = blocking { SequenceSuccess(backend.packageChangeSequence()) }?.sequence ?: return@withTimeoutOrNull null
            if (serial != opening.scope.userSerial || sequence != opening.scope.packageChangeSequence ||
                epoch.get() != opening.scope.readerEpoch
            ) {
                return@withTimeoutOrNull null
            }
            UidIdentityProof(opening.scope, fingerprints.toMap())
        }
    }

    private data class Value<T>(
        val value: T,
    )

    private data class SequenceSuccess(
        val sequence: Int,
    )

    private fun callAbandoned(): Boolean = abandonedCall.get() != null

    private suspend fun <T : Any> blocking(call: () -> T): T? {
        currentCoroutineContext().ensureActive()
        // Never queue behind an abandoned native call: it keeps the gate until it actually returns.
        if (callAbandoned()) return null
        // An overlapping capture's ordinary call is short: wait for it within the caller's phase budget
        // (the enclosing withTimeoutOrNull). A wait that times out abandons no call, so the epoch is kept.
        gate.lock()
        val token = Any()
        val state = AtomicInteger(CALL_RUNNING)

        // Runs exactly once per acquired gate, on the thread that ends the physical call.
        fun finish() {
            if (!state.compareAndSet(CALL_RUNNING, CALL_RETURNED)) abandonedCall.compareAndSet(token, null)
            gate.unlock()
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                epoch.set(UUID.randomUUID().toString())
                abandonedCall.set(token)
                if (!state.compareAndSet(CALL_RUNNING, CALL_ABANDONED)) abandonedCall.compareAndSet(token, null)
            }
            try {
                dispatcher.asExecutor().execute {
                    if (!continuation.isActive) {
                        finish()
                        return@execute
                    }
                    var cancelled: CancellationException? = null
                    val result =
                        try {
                            call()
                        } catch (failure: CancellationException) {
                            cancelled = failure
                            null
                        } catch (_: Exception) {
                            // A failed service may have restarted and reset its sequence: rotate the reader epoch.
                            epoch.set(UUID.randomUUID().toString())
                            null
                        }
                    finish()
                    if (cancelled != null) continuation.cancel(cancelled) else continuation.resume(result) { _, _, _ -> }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                epoch.set(UUID.randomUUID().toString())
                finish()
                continuation.resume(null) { _, _, _ -> }
            }
        }
    }

    private companion object {
        const val CALL_RUNNING = 0
        const val CALL_RETURNED = 1
        const val CALL_ABANDONED = 2
    }
}
