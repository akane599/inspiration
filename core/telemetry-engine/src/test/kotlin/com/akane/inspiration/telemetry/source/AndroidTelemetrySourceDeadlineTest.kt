package com.akane.inspiration.telemetry.source

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.akane.inspiration.ipc.ITelemetryUserService
import com.akane.inspiration.ipc.ShizukuFacade
import com.akane.inspiration.ipc.ShizukuManager
import com.akane.inspiration.ipc.ShizukuManagerImpl
import com.akane.inspiration.ipc.ShizukuState
import com.akane.inspiration.ipc.model.BatteryHistorySnapshot
import com.akane.inspiration.ipc.model.BatteryUsageSnapshot
import com.akane.inspiration.ipc.model.DumpResult
import com.akane.inspiration.ipc.model.DumpTarget
import com.akane.inspiration.ipc.model.HistoryEvent
import com.akane.inspiration.ipc.model.HistoryKind
import com.akane.inspiration.ipc.model.KernelBatterySnapshot
import com.akane.inspiration.ipc.model.UidUsage
import com.akane.inspiration.telemetry.capacity.ChargeSegment
import com.akane.inspiration.telemetry.capacity.SegmentVerdict
import com.akane.inspiration.telemetry.capture.CaptureResult
import com.akane.inspiration.telemetry.capture.SnapshotCapturer
import com.akane.inspiration.telemetry.common.Clocks
import com.akane.inspiration.telemetry.diff.SnapshotDiffer
import com.akane.inspiration.telemetry.dump.DumpReaderTest.PipeOs
import com.akane.inspiration.telemetry.proto.AlarmManagerServiceDumpProto
import com.akane.inspiration.telemetry.proto.BroadcastStatsProto
import com.akane.inspiration.telemetry.proto.FilterStatsProto
import com.akane.inspiration.telemetry.snapshot.BatterySnapshot
import com.akane.inspiration.telemetry.snapshot.CaptureTrigger
import com.akane.inspiration.telemetry.snapshot.LiveReading
import com.akane.inspiration.telemetry.snapshot.SnapshotKind
import com.akane.inspiration.telemetry.snapshot.SourceAvailability
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import com.akane.inspiration.telemetry.store.ChargeSessionRecord
import com.akane.inspiration.telemetry.store.HISTORY_PAGE_CAP_REASON
import com.akane.inspiration.telemetry.store.HISTORY_SOURCE
import com.akane.inspiration.telemetry.store.HealthSample
import com.akane.inspiration.telemetry.store.Marker
import com.akane.inspiration.telemetry.store.SnapshotStore
import com.akane.inspiration.telemetry.store.TelemetrySample
import com.akane.inspiration.telemetry.store.ToolkitActionRecord
import com.akane.inspiration.telemetry.store.ingestedHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real AndroidTelemetrySource + SnapshotCapturer against a service whose calls block like Binder
 * transactions: the calling thread is held and an interrupt does not end the call. A capture deadline must still
 * release its caller on time, and history pages read before it must be kept so a large backlog makes progress.
 * Blocking calls hold real IO threads on latches until each test's cleanup; deadlines run on virtual time
 * ([TestCoroutineScheduler]), so no assertion depends on how fast a loaded host dispatches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTelemetrySourceDeadlineTest {
    @Test
    fun `a standalone kernel deadline includes acquisition and releases service use while transact is blocked`() {
        val fixture = KernelFixture(connectImmediately = false)
        val telemetry = source(fixture.manager)
        val call = fixture.scope.async { telemetry.kernelBatterySnapshot(KERNEL_DEADLINE_MS) }
        try {
            assertTrue("the manager must acquire a service use", fixture.scheduler.runCurrentUntil { fixture.facade.bound.count == 0L })
            fixture.scheduler.advanceTimeBy(KERNEL_ACQUISITION_MS)
            fixture.facade.connect()
            assertTrue("the Binder transaction must start", fixture.scheduler.runCurrentUntil { fixture.transaction.entered.count == 0L })

            // Acquisition took KERNEL_ACQUISITION_MS of the budget; the read may only have what is left of it.
            fixture.scheduler.advanceTimeBy(KERNEL_DEADLINE_MS - KERNEL_ACQUISITION_MS - 1)
            fixture.scheduler.runCurrent()
            assertTrue("the shared deadline has not passed yet", call.isActive)
            fixture.scheduler.advanceTimeBy(1)
            assertTrue(
                "acquisition and read must share $KERNEL_DEADLINE_MS ms, not get separate budgets, and the caller " +
                    "must not wait for the blocked transaction",
                fixture.scheduler.runCurrentUntil { call.isCompleted },
            )
            assertNull(call.getCompleted())
            assertEquals("native work remains blocked", 1L, fixture.transaction.finished.count)
            fixture.scheduler.advanceUntilIdle()
            assertEquals("the real manager released its last active service use", 1, fixture.facade.unbinds.get())
        } finally {
            fixture.transaction.release.countDown()
            call.cancel()
            fixture.close()
        }
    }

    @Test
    fun `cancelling a live kernel reading promptly releases the real manager refcount`() {
        val fixture = KernelFixture()
        val holder = fixture.manager.holdBinding()
        val call = fixture.scope.async { source(fixture.manager).kernelReading() }
        try {
            assertTrue(
                "the Binder transaction must be in flight",
                fixture.scheduler.runCurrentUntil { fixture.transaction.entered.count == 0L },
            )
            call.cancel()
            // The transaction blocks until the finally below, so any bound tells a prompt release from waiting.
            assertTrue(
                "cancellation must complete without waiting for native Binder work",
                fixture.scheduler.runCurrentUntil { call.isCompleted },
            )
            assertTrue(call.isCancelled)
            assertEquals(1L, fixture.transaction.finished.count)

            holder.close()
            fixture.scheduler.advanceUntilIdle()
            assertEquals("no active service user may keep the binding after the holder closes", 1, fixture.facade.unbinds.get())
            assertNull(fixture.manager.telemetryService.value)
            assertEquals("unbind did not need the transaction to finish", 1L, fixture.transaction.finished.count)
        } finally {
            fixture.transaction.release.countDown()
            call.cancel()
            holder.close()
            fixture.close()
        }
    }

    @Test
    fun `an outer kernel deadline is not swallowed as an unavailable result`() {
        val fixture = KernelFixture()
        val call =
            fixture.scope.async {
                runCatching {
                    withTimeout(KERNEL_DEADLINE_MS) {
                        source(fixture.manager).kernelBatterySnapshot(KERNEL_DEADLINE_MS * 10)
                    }
                }
            }
        try {
            assertTrue(fixture.scheduler.runCurrentUntil { fixture.transaction.entered.count == 0L })
            fixture.scheduler.advanceTimeBy(KERNEL_DEADLINE_MS)
            assertTrue(
                "the outer deadline must end the call while the transaction blocks",
                fixture.scheduler.runCurrentUntil { call.isCompleted },
            )
            assertTrue("only the helper's own timeout becomes null", call.getCompleted().exceptionOrNull() is CancellationException)
            assertEquals(1L, fixture.transaction.finished.count)
        } finally {
            fixture.transaction.release.countDown()
            call.cancel()
            fixture.close()
        }
    }

    @Test
    fun `a cancellation raised by the kernel boundary is propagated rather than swallowed`() =
        runBlocking {
            val cancellation = CancellationException("kernel read cancelled")
            val service =
                object : ITelemetryUserService.Default() {
                    override fun readKernelBattery(): KernelBatterySnapshot = throw cancellation
                }

            val result = runCatching { source(service).kernelBatterySnapshot() }

            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(cancellation.message, result.exceptionOrNull()?.message)
            assertFalse(result.isSuccess)
        }

    @Test
    fun `a capture deadline releases the caller while a binder call still blocks`() {
        val store = MemoryStore()
        val service = BlockingService(blockUsage = true)
        val capturer = SnapshotCapturer(source(service), store, FixedClocks)
        val caller = VirtualCaller()
        val call = caller.scope.async { capturer.captureFull(CaptureTrigger.MARKER, deadlineMs = DEADLINE_MS, ingestHistory = false) }
        try {
            assertTrue("the usage read must start", caller.scheduler.runCurrentUntil { service.entered.count == 0L })
            caller.scheduler.advanceTimeBy(DEADLINE_MS)
            assertTrue(
                "captureFull(deadlineMs = $DEADLINE_MS) must return at its deadline, not wait for the blocking usage read",
                caller.scheduler.runCurrentUntil { call.isCompleted },
            )
            assertTrue(call.getCompleted() is CaptureResult.Missed)
            assertEquals(listOf(CaptureTrigger.MARKER), store.misses.toList())
            assertEquals("the usage read still blocks", 1L, service.finished.count)
        } finally {
            service.release.countDown()
            caller.close()
        }
    }

    @Test
    fun `the ingest deadline bounds the read even while history pages are in flight`() {
        val service = BlockingService(blockHistoryFrom = 1)
        val capturer = SnapshotCapturer(source(service), MemoryStore(), FixedClocks)
        val caller = VirtualCaller()
        val call = caller.scope.async { capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS) }
        try {
            assertTrue("a history page must be in flight", caller.scheduler.runCurrentUntil { service.entered.count == 0L })
            caller.scheduler.advanceTimeBy(DEADLINE_MS)
            assertTrue(
                "ingestHistory(deadlineMs = $DEADLINE_MS) must return at its deadline, not wait for the blocking history page",
                caller.scheduler.runCurrentUntil { call.isCompleted },
            )
            assertNull("a read that missed its deadline is not kept", call.getCompleted().read)
            assertEquals("the history page still blocks", 1L, service.finished.count)
        } finally {
            service.release.countDown()
            caller.close()
        }
    }

    @Test
    fun `history pages read before the deadline are persisted and the next ingest resumes from them`() {
        val service = BlockingService(blockHistoryFrom = 1)
        val store = MemoryStore()
        val capturer = SnapshotCapturer(source(service), store, FixedClocks)
        val caller = VirtualCaller()
        try {
            val first = caller.scope.async { capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS) }
            assertTrue("the second history page must be in flight", caller.scheduler.runCurrentUntil { service.entered.count == 0L })
            caller.scheduler.advanceTimeBy(DEADLINE_MS)
            assertTrue(caller.scheduler.runCurrentUntil { first.isCompleted })

            assertNotNull(
                "${service.historyRequests.size} history pages were requested inside the $DEADLINE_MS ms deadline, " +
                    "but none was persisted and the cursor did not move",
                store.cursor,
            )
            val cursor = store.cursor!!
            assertEquals(STATS_START_MS, cursor.first)
            assertEquals("the cursor is the last persisted event", store.history.maxOf { it.historyTimeMs }, cursor.second)

            service.historyRequests.clear()
            caller.scope.async { capturer.ingestHistory(CaptureTrigger.CHARGE_JOB, deadlineMs = DEADLINE_MS) }
            assertTrue("the next ingest must request a page", caller.scheduler.runCurrentUntil { service.historyRequests.isNotEmpty() })
            assertEquals("the next ingest pages on from the cursor", cursor.second, service.historyRequests.first())
        } finally {
            service.release.countDown()
            caller.close()
        }
    }

    @Test
    fun `a read that stops at the page cap keeps its pages but marks the history horizon only once drained`() =
        runBlocking {
            // A backlog of ten full pages: the first read stops at its cap of eight with newer history still unread.
            val service = BlockingService(lastHistoryPage = 9)
            val store = MemoryStore()
            val capturer = SnapshotCapturer(source(service), store, FixedClocks)

            val capped = capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = HANG_GUARD_MS, ingestHistory = true)

            assertTrue("capture saved: $capped", capped is CaptureResult.Saved)
            assertEquals("the read stops at its page cap", 8, service.historyRequests.size)
            assertEquals("every page read is stored", 8, store.history.size)
            assertEquals(STATS_START_MS to store.history.maxOf { it.historyTimeMs }, store.cursor)
            val cappedSnapshot = store.snapshots.single()
            assertFalse(
                "history still pending after the page cap was recorded as complete to the snapshot's wall time: " +
                    "${cappedSnapshot.availability["history"]}",
                cappedSnapshot.ingestedHistory(),
            )
            assertEquals(SourceAvailability(true, HISTORY_PAGE_CAP_REASON), cappedSnapshot.availability[HISTORY_SOURCE])

            val drained = capturer.captureFull(CaptureTrigger.APP_OPEN, deadlineMs = HANG_GUARD_MS, ingestHistory = true)

            assertTrue("capture saved: $drained", drained is CaptureResult.Saved)
            assertEquals("the next read pages on from the cursor to the newest event", 10, service.historyRequests.size)
            assertEquals(10, store.history.size)
            assertTrue("a read that reached the newest event marks the horizon", store.snapshots.last().ingestedHistory())
        }

    @Test
    @Config(shadows = [PipeOs::class])
    fun `closed alarm writer is unavailable at either endpoint without losing usage energy`() =
        runBlocking {
            val alarmBytes =
                AlarmManagerServiceDumpProto
                    .newBuilder()
                    .addAlarmStats(
                        AlarmManagerServiceDumpProto.AlarmStat
                            .newBuilder()
                            .setBroadcast(BroadcastStatsProto.newBuilder().setUid(12345).setPackageName("pkg"))
                            .addFilters(
                                FilterStatsProto
                                    .newBuilder()
                                    .setTag("sync")
                                    .setCount(42)
                                    .setWakeupCount(42),
                            ),
                    ).build()
                    .toByteArray()

            for (failedReference in listOf(true, false)) {
                var elapsed = 1_000L
                val clocks =
                    object : Clocks {
                        override fun wallMs(): Long = elapsed

                        override fun elapsedRealtimeMs(): Long = elapsed

                        override fun uptimeMs(): Long = elapsed

                        override fun bootCount(): Int = 4
                    }
                val descriptors = mutableListOf<java.io.FileDescriptor>()
                val service =
                    object : ITelemetryUserService.Default() {
                        override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
                            val energy = elapsed / 1_000.0
                            return BatteryUsageSnapshot(
                                available = true,
                                reason = null,
                                statsStartMs = STATS_START_MS,
                                statsEndMs = STATS_START_MS + elapsed,
                                dischargedPowerMah = energy,
                                batteryTimeRemainingMs = 0,
                                chargeTimeRemainingMs = 0,
                                uids = listOf(UidUsage(12345, "pkg", energy, mapOf("cpu" to energy), mapOf("FOREGROUND" to energy))),
                                deviceComponents = mapOf("cpu" to energy),
                            )
                        }

                        override fun openDump(target: Int): DumpResult {
                            if (target != DumpTarget.ALARM_PROTO) return DumpResult.unavailable("not_under_test")
                            // Reuse the reader's Os shadow to expose EOF or a complete payload deterministically.
                            // The failed writer closes before writing, not by returning permission-denial text.
                            val failed = (elapsed == 1_000L) == failedReference
                            PipeOs.data = if (failed) byteArrayOf() else alarmBytes
                            PipeOs.reads = 0
                            PipeOs.nonblocking = false
                            val pipe = ParcelFileDescriptor.createPipe()
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                                if (!failed) it.write(alarmBytes)
                            }
                            descriptors += pipe[0].fileDescriptor
                            return DumpResult(available = true, reason = null, fd = pipe[0])
                        }
                    }
                val store = MemoryStore()
                val capturer =
                    SnapshotCapturer(
                        source(
                            service,
                            clocks,
                            UidIdentityReader(KnownIdentityBackend(), openingBudgetMs = 1_000, closingBudgetMs = 1_000),
                        ),
                        store,
                        clocks,
                    )

                capturer.captureFull(CaptureTrigger.MARKER, deadlineMs = 5_000, ingestHistory = false)
                elapsed = 2_000L
                capturer.captureFull(CaptureTrigger.MARKER, deadlineMs = 5_000, ingestHistory = false)

                val ref = store.snapshots[0]
                val now = store.snapshots[1]
                val failed = if (failedReference) ref else now
                val successful = if (failedReference) now else ref
                assertEquals(SourceAvailability(false, "malformed alarm dump"), failed.availability.getValue("alarm"))
                assertEquals(SourceAvailability(true, null), successful.availability.getValue("alarm"))
                assertTrue(
                    failed.uids
                        .single()
                        .alarmDumpWakeups
                        .isEmpty(),
                )
                assertEquals(
                    42L,
                    successful.uids
                        .single()
                        .alarmDumpWakeups
                        .single()
                        .count,
                )
                assertTrue("both dump descriptors are closed", descriptors.size == 2 && descriptors.none { it.valid() })

                val diff = SnapshotDiffer.diff(ref, now)
                assertTrue(diff.validity.batteryStats)
                assertTrue(diff.validity.kernelCounters)
                assertFalse(diff.validity.alarmDumpCounters)
                assertTrue(diff.validity.problems.isEmpty())
                val uid = diff.uids.single()
                assertTrue(uid.valid)
                assertTrue("no fabricated or negative alarm delta", uid.alarmDumpWakeups.isEmpty())
                assertEquals(1.0, uid.totalMah, 0.0)
                assertEquals(1.0, checkNotNull(uid.fgMah), 0.0)
                assertEquals(mapOf("cpu" to 1.0), uid.componentsMah)
                assertEquals(mapOf("cpu" to 1.0), diff.deviceComponentsMah)
                assertEquals(1.0, checkNotNull(diff.dischargedMah), 0.0)
            }
        }

    @Test
    fun `full identity guards surround telemetry while LIGHT and kernel reads never query owners`() =
        runBlocking {
            val backend = KnownIdentityBackend()
            var changeDuringTelemetry = false
            val service =
                object : ITelemetryUserService.Default() {
                    override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
                        if (changeDuringTelemetry) backend.sequence++
                        return BatteryUsageSnapshot(
                            true,
                            null,
                            10,
                            20,
                            40.0,
                            0,
                            0,
                            listOf(UidUsage(12345, "hint", 40.0, emptyMap(), emptyMap())),
                        )
                    }

                    override fun openDump(target: Int): DumpResult = DumpResult.unavailable("test")

                    override fun readKernelBattery() = KernelBatterySnapshot.unavailable("test")
                }
            val telemetry =
                source(service, uidIdentityReader = UidIdentityReader(backend, openingBudgetMs = 1_000, closingBudgetMs = 1_000))
            telemetry.light()
            telemetry.kernelReading()
            assertEquals(0, backend.calls.get())
            assertNotNull(telemetry.full(CaptureTrigger.APP_OPEN, null)!!.uidIdentity)
            changeDuringTelemetry = true
            val changed = requireNotNull(telemetry.full(CaptureTrigger.APP_OPEN, null))
            assertNull(changed.uidIdentity)
            assertEquals(
                40.0,
                changed.usage!!
                    .uids
                    .single()
                    .totalMah,
                0.0,
            )
        }

    @Test
    fun `identity owner timeout preserves acquired FULL telemetry before the outer deadline`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val backend =
                object : KnownIdentityBackend() {
                    override fun packagesForUid(uid: Int): List<String> {
                        started.countDown()
                        try {
                            release.await()
                        } finally {
                            finished.countDown()
                        }
                        return super.packagesForUid(uid)
                    }
                }
            val service =
                object : ITelemetryUserService.Default() {
                    override fun readBatteryUsage(maxAgeMs: Long) =
                        BatteryUsageSnapshot(
                            true,
                            null,
                            10,
                            20,
                            40.0,
                            0,
                            0,
                            listOf(UidUsage(12345, "hint", 40.0, emptyMap(), emptyMap())),
                            deviceComponents = mapOf("CPU" to 10.0),
                        )

                    override fun openDump(target: Int) = DumpResult.unavailable("test")
                }
            val store = MemoryStore()
            val telemetry =
                source(
                    service,
                    uidIdentityReader = UidIdentityReader(backend, openingBudgetMs = 1_000, ownerBudgetMs = 50, closingBudgetMs = 1_000),
                )
            val call =
                async(
                    Dispatchers.Default,
                ) { SnapshotCapturer(telemetry, store, FixedClocks).captureFull(CaptureTrigger.APP_OPEN, 3_000, false) }
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(withTimeout(2_000) { call.await() } is CaptureResult.Saved)
                val saved = store.snapshots.single()
                assertNull(saved.uidIdentityScope)
                assertNull(saved.uids.single().ownerInstallFingerprint)
                assertEquals(40.0, saved.uids.single().totalMah, 0.0)
                assertEquals(10.0, saved.deviceComponentsMah.getValue("CPU"), 0.0)
                assertEquals(1L, finished.count)
            } finally {
                release.countDown()
                call.cancelAndJoin()
                assertTrue(finished.await(2, TimeUnit.SECONDS))
            }
        }

    private open class KnownIdentityBackend : UidIdentityBackend {
        override val userId = 0
        var sequence = 0
        val calls = AtomicInteger()

        override fun userSerial(): Long {
            calls.incrementAndGet()
            return 7
        }

        override fun packageChangeSequence(): Int {
            calls.incrementAndGet()
            return sequence
        }

        override fun packagesForUid(uid: Int): List<String> {
            calls.incrementAndGet()
            return listOf("test.owner")
        }

        override fun packageInfo(packageName: String): UidOwnerInstall {
            calls.incrementAndGet()
            return UidOwnerInstall(packageName, 12345, 123)
        }
    }

    /**
     * Runs the tasks due at the current virtual time until [done]. Blocking service calls run on real IO threads and
     * resume their callers onto this scheduler from there, so the check repeats; real time only bounds a hang.
     */
    private fun TestCoroutineScheduler.runCurrentUntil(done: () -> Boolean): Boolean {
        val endNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANG_GUARD_MS)
        while (true) {
            runCurrent()
            if (done()) return true
            if (System.nanoTime() - endNs >= 0L) return false
            Thread.sleep(1L)
        }
    }

    /** A caller on virtual time: its capture deadline expires only when the test advances [scheduler]. */
    private class VirtualCaller : AutoCloseable {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        override fun close() = scope.cancel()
    }

    private fun source(
        service: ITelemetryUserService,
        clocks: Clocks = FixedClocks,
        uidIdentityReader: UidIdentityReader = UidIdentityReader(AndroidUidIdentityBackend(RuntimeEnvironment.getApplication())),
    ) = source(DirectShizuku(service), clocks, uidIdentityReader)

    private fun source(
        shizuku: ShizukuManager,
        clocks: Clocks = FixedClocks,
        uidIdentityReader: UidIdentityReader = UidIdentityReader(AndroidUidIdentityBackend(RuntimeEnvironment.getApplication())),
    ) = AndroidTelemetrySource(
        uidIdentityReader = uidIdentityReader,
        context = RuntimeEnvironment.getApplication(),
        shizuku = shizuku,
        clocks = clocks,
    )

    /**
     * Real manager + AIDL proxy on virtual time: its 3 s idle-unbind cleanup, the service-use block it runs on its IO
     * dispatcher, and the deadlines of calls the test starts in [scope] all run on [scheduler], so any deadline in
     * the chain expires only when the test advances it. The transaction itself still blocks a real IO thread, via
     * the source's own dispatcher.
     */
    private class KernelFixture(
        connectImmediately: Boolean = true,
    ) : AutoCloseable {
        val transaction = LatchKernelTransaction()
        val facade = KernelFacade(transaction, connectImmediately)
        val scheduler = TestCoroutineScheduler()
        private val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val manager: ShizukuManagerImpl

        init {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).installPackage(
                PackageInfo().apply {
                    packageName = "moe.shizuku.privileged.api"
                    applicationInfo = ApplicationInfo().apply { packageName = "moe.shizuku.privileged.api" }
                },
            )
            manager = ShizukuManagerImpl(context, facade.api, scope, ioDispatcher = dispatcher)
            manager.start()
            scheduler.runCurrent() // start()'s state read runs on the manager's IO dispatcher (B-10)
            check(manager.state.value is ShizukuState.Ready)
        }

        override fun close() {
            transaction.release.countDown()
            try {
                if (transaction.entered.count == 0L) {
                    assertTrue("test must not leave a Binder worker blocked", transaction.finished.await(2L, TimeUnit.SECONDS))
                }
            } finally {
                manager.stop()
                scope.cancel()
            }
        }
    }

    /** No local interface: Stub.asInterface creates a proxy which calls this real, latch-blocked transact. */
    private class LatchKernelTransaction : Binder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            // readKernelBattery = 1 in the AIDL; explicit method IDs are relative to FIRST_CALL_TRANSACTION.
            if (code != IBinder.FIRST_CALL_TRANSACTION + 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface("com.akane.inspiration.ipc.ITelemetryUserService")
            entered.countDown()
            var interrupted = false
            try {
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true // Binder/driver work cannot be ended by interrupting its caller thread.
                    }
                }
                checkNotNull(reply).writeNoException()
                reply.writeTypedObject(KernelBatterySnapshot.unavailable("test"), 0)
                return true
            } finally {
                finished.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private class KernelFacade(
        private val transaction: IBinder,
        private val connectImmediately: Boolean,
    ) {
        val bound = CountDownLatch(1)
        val unbinds = AtomicInteger()

        @Volatile private var connection: ServiceConnection? = null

        // The IPC module does not export rikka.shizuku API classes on this module's compile classpath.
        // A proxy scripts the existing facade without adding a test dependency or changing its production API.
        val api: ShizukuFacade =
            Proxy.newProxyInstance(
                ShizukuFacade::class.java.classLoader,
                arrayOf(ShizukuFacade::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "pingBinder" -> {
                        true
                    }

                    "getUid" -> {
                        2000
                    }

                    "getVersion" -> {
                        13
                    }

                    "isSui", "isPreV11", "shouldShowRequestPermissionRationale" -> {
                        false
                    }

                    "checkSelfPermission" -> {
                        PackageManager.PERMISSION_GRANTED
                    }

                    "bindUserService" -> {
                        connection = checkNotNull(args)[1] as ServiceConnection
                        bound.countDown()
                        if (connectImmediately) connect()
                        null
                    }

                    "unbindUserService" -> {
                        unbinds.incrementAndGet()
                        null
                    }

                    "requestPermission", "addBinderReceivedListenerSticky", "removeBinderReceivedListener",
                    "addBinderDeadListener", "removeBinderDeadListener", "addRequestPermissionResultListener",
                    "removeRequestPermissionResultListener",
                    -> {
                        null
                    }

                    else -> {
                        error("Unexpected facade method: ${method.name}")
                    }
                }
            } as ShizukuFacade

        fun connect() {
            checkNotNull(connection).onServiceConnected(ComponentName("test", "service"), transaction)
        }
    }

    /**
     * Every history page up to index [lastHistoryPage] is full (truncated), as on a large first-ingest backlog, so
     * paging continues until its page cap; that page reaches the newest event. The usage read with [blockUsage], and
     * history pages from index [blockHistoryFrom] on, block until [release], ignoring interrupts the way a Binder
     * transaction does.
     */
    private class BlockingService(
        private val blockUsage: Boolean = false,
        private val blockHistoryFrom: Int = Int.MAX_VALUE,
        private val lastHistoryPage: Int = Int.MAX_VALUE,
    ) : ITelemetryUserService.Default() {
        val historyRequests: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        private val historyCalls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)

        override fun readBatteryUsage(maxAgeMs: Long): BatteryUsageSnapshot {
            if (blockUsage) blockUninterruptibly()
            return BatteryUsageSnapshot.unavailable("not_under_test")
        }

        override fun openDump(target: Int): DumpResult = DumpResult.unavailable("not_under_test")

        override fun readBatteryHistory(
            statsStartMs: Long,
            afterHistoryTimeMs: Long,
            maxEvents: Int,
        ): BatteryHistorySnapshot {
            historyRequests += afterHistoryTimeMs
            val index = historyCalls.getAndIncrement()
            if (index >= blockHistoryFrom) blockUninterruptibly()
            val time = afterHistoryTimeMs + 10L
            return BatteryHistorySnapshot(
                available = true,
                reason = null,
                statsStartMs = STATS_START_MS,
                sessionChanged = false,
                events = listOf(HistoryEvent(time, 1_000_000L + time, HistoryKind.LEVEL, 50, -1L, 250, 4_000, 0)),
                truncated = index < lastHistoryPage,
            )
        }

        private fun blockUninterruptibly() {
            entered.countDown()
            var interrupted = false
            try {
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                finished.countDown()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    /** A ready, already-bound service: the block runs directly, as ShizukuManagerImpl does once connected. */
    private class DirectShizuku(
        private val service: ITelemetryUserService,
    ) : ShizukuManager {
        override val state: StateFlow<ShizukuState> = MutableStateFlow(ShizukuState.Ready(2000, 13))
        override val telemetryService: StateFlow<ITelemetryUserService?> = MutableStateFlow(service)

        override fun requestPermission() = Unit

        override fun start() = Unit

        override suspend fun <T> withTelemetryService(
            timeoutMs: Long,
            block: suspend (ITelemetryUserService) -> T,
        ): T? = block(service)

        override fun holdBinding(): AutoCloseable = AutoCloseable {}

        override fun stop() = Unit
    }

    private object FixedClocks : Clocks {
        override fun wallMs(): Long = 1_000L

        override fun elapsedRealtimeMs(): Long = 2_000L

        override fun uptimeMs(): Long = 3_000L

        override fun bootCount(): Int = 4
    }

    private class MemoryStore : SnapshotStore {
        @Volatile var cursor: Pair<Long, Long>? = null
        val history: MutableList<HistoryEvent> = Collections.synchronizedList(mutableListOf())
        val misses: MutableList<CaptureTrigger> = Collections.synchronizedList(mutableListOf())
        val snapshots = mutableListOf<BatterySnapshot>()

        override suspend fun insertHistory(
            events: List<HistoryEvent>,
            statsStartMs: Long,
        ) {
            history += events
        }

        override suspend fun historyCursor(): Pair<Long, Long>? = cursor

        override suspend fun setHistoryCursor(
            statsStartMs: Long,
            lastHistoryTimeMs: Long,
        ) {
            cursor = statsStartMs to lastHistoryTimeMs
        }

        override suspend fun historyBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<HistoryEvent> = history.toList().filter { it.wallMs in fromWallMs..toWallMs }

        override suspend fun recordMiss(
            wallMs: Long,
            trigger: CaptureTrigger,
            reason: String,
        ) {
            misses += trigger
        }

        override suspend fun insert(snapshot: BatterySnapshot): Long {
            snapshots += snapshot
            return snapshots.size.toLong()
        }

        override suspend fun byId(id: Long): BatterySnapshot? = null

        override suspend fun latest(kind: SnapshotKind?): BatterySnapshot? = null

        override suspend fun firstAtOrAfter(
            wallMs: Long,
            kind: SnapshotKind?,
            statsStartMs: Long?,
            bootCount: Int?,
        ): BatterySnapshot? = null

        override suspend fun lastAtOrBefore(
            wallMs: Long,
            kind: SnapshotKind?,
        ): BatterySnapshot? = null

        override suspend fun latestByTrigger(trigger: CaptureTrigger): BatterySnapshot? = null

        override suspend fun snapshotsBetween(
            fromWallMs: Long,
            toWallMs: Long,
        ): List<BatterySnapshot> = emptyList()

        override suspend fun addMarker(
            snapshotId: Long,
            label: String,
            wallMs: Long,
        ): Long = 0

        override suspend fun marker(id: Long): Pair<Long, BatterySnapshot>? = null

        override suspend fun markers(): List<Marker> = emptyList()

        override suspend fun insertSamples(samples: List<LiveReading>) = Unit

        override suspend fun samplesBetween(
            from: Long,
            to: Long,
        ): List<TelemetrySample> = emptyList()

        override suspend fun upsertChargeSession(
            statsStartMs: Long,
            segment: ChargeSegment,
            verdict: SegmentVerdict,
        ) = Unit

        override suspend fun chargeSessions(): List<ChargeSessionRecord> = emptyList()

        override suspend fun acceptedEstimatesChronological(limit: Int): List<Double> = emptyList()

        override suspend fun healthSamples(): List<HealthSample> = emptyList()

        override suspend fun insertHealthSample(sample: HealthSample) = Unit

        override suspend fun insertToolkitAction(record: ToolkitActionRecord): Long = 0

        override suspend fun updateToolkitAction(record: ToolkitActionRecord) = Unit

        override suspend fun toolkitActions(limit: Int): List<ToolkitActionRecord> = emptyList()

        override suspend fun pendingToolkitActions(): List<ToolkitActionRecord> = emptyList()

        override suspend fun prune(nowWallMs: Long) = Unit
    }

    private companion object {
        const val KERNEL_DEADLINE_MS = 800L
        const val KERNEL_ACQUISITION_MS = 600L
        const val DEADLINE_MS = 400L

        /**
         * Real time allowed for work that is not waiting on a deadline, such as a dispatch to an IO thread.
         * Blocked calls stay blocked until a test's finally, so this bounds only a hang and needs no tuning to the
         * host; it stays below the 5 s default bind timeout, which would otherwise end a wait of its own.
         */
        const val HANG_GUARD_MS = 4_000L
        const val STATS_START_MS = 77L
    }
}
