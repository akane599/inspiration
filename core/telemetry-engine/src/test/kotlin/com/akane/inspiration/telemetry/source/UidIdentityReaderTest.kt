package com.akane.inspiration.telemetry.source

import com.akane.inspiration.telemetry.snapshot.UidOwnerFingerprint
import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UidIdentityReaderTest {
    @Test
    fun `serial sequence bracket surrounds complete owners and ignores other users and system uids`() =
        runTest {
            val backend = FakeBackend()
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
            val opening = reader.open()
            backend.calls += "telemetry"
            val proof = requireNotNull(reader.close(opening, setOf(1_000, 10_001, 110_001)))
            assertEquals(setOf(10_001), proof.fingerprintsByUid.keys)
            assertEquals(
                listOf("serial", "sequence", "telemetry", "installed", "packages:10001", "info:test.owner", "serial", "sequence"),
                backend.calls,
            )
            assertEquals(0, proof.scope.packageChangeSequence)
            val second = requireNotNull(reader.close(reader.open(), setOf(10_001)))
            assertEquals(proof.scope, second.scope)
            assertEquals(proof.fingerprintsByUid, second.fingerprintsByUid)
        }

    @Test
    fun `all scope changes during telemetry or owner lookup discard global evidence`() =
        runTest {
            for (duringOwnerLookup in listOf(false, true)) {
                for (serialChange in listOf(false, true)) {
                    val backend = FakeBackend()
                    val reader =
                        UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
                    val opening = reader.open()
                    val mutate = { if (serialChange) backend.serial++ else backend.sequence++ }
                    if (duringOwnerLookup) backend.onInfo = { mutate() } else mutate()
                    assertNull(reader.close(opening, setOf(10_001)))
                }
            }
        }

    @Test
    fun `call ceiling keeps only complete uid prefix after a good close`() =
        runTest {
            for (goodClose in listOf(true, false)) {
                val backend =
                    FakeBackend().apply {
                        packages[10_002] = listOf("test.second", "test.third")
                        if (!goodClose) onInfo = { sequence++ }
                    }
                val reader =
                    UidIdentityReader(
                        backend,
                        StandardTestDispatcher(testScheduler),
                        maxCalls = 7,
                        elapsedMs = { testScheduler.currentTime },
                    )
                val proof = reader.close(reader.open(), setOf(10_001, 10_002, 10_003))
                if (goodClose) assertEquals(setOf(10_001), requireNotNull(proof).fingerprintsByUid.keys) else assertNull(proof)
                assertEquals(7, backend.calls.size)
                assertEquals(listOf("serial", "sequence"), backend.calls.takeLast(2))
                assertFalse(backend.calls.any { it == "info:test.second" || it == "packages:10003" })
            }
        }

    @Test
    fun `default 1024 call ceiling includes four guards and the failed bulk read for single and shared owners`() =
        runTest {
            for (ownerCount in listOf(1, 2)) {
                val backend = FakeBackend()
                val (count, expectedCalls) = if (ownerCount == 1) 509 to 1_024 else 339 to 1_023
                val uids = (10_000..10_000 + count).toSet()
                for (uid in uids) {
                    val names = (1..ownerCount).map { "owner.p${uid}n$it" }
                    backend.packages[uid] = names
                    names.forEach { backend.infos[it] = UidOwnerInstall(it, uid, 123) }
                }
                val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
                val opening = reader.open()
                val proof = requireNotNull(reader.close(opening, uids))
                assertEquals(count, proof.fingerprintsByUid.size)
                assertEquals(expectedCalls, backend.callCount.get())
                assertNull(reader.close(opening, uids))
                assertEquals(expectedCalls, backend.callCount.get())
            }
        }

    @Test
    fun `wall budget keeps a complete prefix with good close but never a partial owner set`() =
        runTest {
            val backend =
                FakeBackend().apply {
                    packages[10_002] = listOf("test.second", "test.third")
                    infos["test.second"] = UidOwnerInstall("test.second", 10_002, 123)
                }
            var elapsed = 0L
            backend.onInfo = { elapsed += 10 }
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), ownerBudgetMs = 20, elapsedMs = { elapsed })
            val proof = requireNotNull(reader.close(reader.open(), setOf(10_001, 10_002)))
            assertEquals(setOf(10_001), proof.fingerprintsByUid.keys)
            assertFalse(backend.calls.contains("info:test.third"))
            assertEquals(listOf("serial", "sequence"), backend.calls.takeLast(2))
        }

    @Test
    fun `ordinary per uid absence conflict and excessive owners do not taint completed peers`() =
        runTest {
            val backend =
                FakeBackend().apply {
                    packages[10_002] = null
                    packages[10_003] = listOf("missing.owner")
                    packages[10_004] = listOf("test.owner", "test.owner")
                    packages[10_005] = (0..16).map { "test.owner$it" }
                    packages[10_006] = listOf("wrong.uid")
                    infos["wrong.uid"] = UidOwnerInstall("wrong.uid", 10_007, 123)
                }
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
            val proof = requireNotNull(reader.close(reader.open(), (10_001..10_006).toSet()))
            assertEquals(setOf(10_001), proof.fingerprintsByUid.keys)
        }

    @Test
    fun `sequence service failure is not successful zero and rotates retained epoch`() =
        runTest {
            val backend = FakeBackend()
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
            val old = requireNotNull(reader.close(reader.open(), setOf(10_001)))
            backend.failSequence = true
            assertNull(reader.open())
            backend.failSequence = false
            val fresh = requireNotNull(reader.close(reader.open(), setOf(10_001)))
            assertFalse(old.scope.readerEpoch == fresh.scope.readerEpoch)
            assertEquals(0, fresh.scope.packageChangeSequence)
        }

    @Test
    fun `blocked native owner times out caller and retains physical gate until actual return`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    onPackages = {
                        started.countDown()
                        try {
                            release.await()
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(backend, dispatcher, openingBudgetMs = 1_000, ownerBudgetMs = 50, closingBudgetMs = 1_000, elapsedMs = {
                        System.nanoTime() /
                            1_000_000
                    })
                try {
                    val opening = reader.open()
                    val result = async(Dispatchers.Default) { reader.close(opening, setOf(10_001, 10_002)) }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    assertNull(result.await())
                    val calls = backend.callCount.get()
                    assertNull(reader.open())
                    assertEquals(calls, backend.callCount.get())
                    assertFalse(backend.calls.contains("info:test.owner"))
                    release.countDown()
                    assertTrue(finished.await(2, TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        }

    @Test
    fun `overlapping captures wait their turn and both keep identity proof`() =
        runBlocking {
            val ownerStarted = CountDownLatch(1)
            val firstLookup =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val backend =
                FakeBackend().apply {
                    onPackages = {
                        if (firstLookup.getAndSet(false)) {
                            ownerStarted.countDown()
                            Thread.sleep(150)
                        }
                    }
                }
            val executor = Executors.newFixedThreadPool(2)
            executor.asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 1_000,
                        ownerBudgetMs = 1_000,
                        closingBudgetMs = 1_000,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                val firstOpening = requireNotNull(reader.open())
                val first = async(Dispatchers.Default) { reader.close(firstOpening, setOf(10_001)) }
                assertTrue(ownerStarted.await(2, TimeUnit.SECONDS))
                // The second capture starts while the first one's owner lookup holds the physical gate.
                val second = async(Dispatchers.Default) { reader.close(reader.open(), setOf(10_001)) }
                val firstProof = requireNotNull(first.await())
                val secondProof = requireNotNull(second.await())
                assertEquals(setOf(10_001), firstProof.fingerprintsByUid.keys)
                assertEquals(firstProof.fingerprintsByUid, secondProof.fingerprintsByUid)
                assertEquals(firstProof.scope, secondProof.scope)
            }
        }

    @Test
    fun `blocked closing guard discards completed fingerprints and retains physical gate`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    onSerial = {
                        if (calls.count { it == "serial" } == 2) {
                            started.countDown()
                            try {
                                release.await()
                            } finally {
                                finished.countDown()
                            }
                        }
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 1_000,
                        ownerBudgetMs = 1_000,
                        closingBudgetMs = 50,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                val result = async(Dispatchers.Default) { reader.close(reader.open(), setOf(10_001)) }
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    assertNull(result.await())
                    assertTrue(backend.calls.contains("info:test.owner"))
                    val calls = backend.callCount.get()
                    assertNull(reader.open())
                    assertEquals(calls, backend.callCount.get())
                    assertEquals(1, backend.calls.count { it == "sequence" })
                } finally {
                    release.countDown()
                    result.cancelAndJoin()
                    assertTrue(finished.await(2, TimeUnit.SECONDS))
                }
                assertNull(result.await()) // The late guard cannot publish its already-completed UID map.
            }
        }

    @Test
    fun `external cancellation propagates and cancelled phase never launches another query`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    onPackages = {
                        started.countDown()
                        release.await()
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(backend, dispatcher, openingBudgetMs = 1_000, ownerBudgetMs = 5_000, elapsedMs = {
                        System.nanoTime() /
                            1_000_000
                    })
                try {
                    val opening = reader.open()
                    val result = async(Dispatchers.Default) { reader.close(opening, setOf(10_001, 10_002)) }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    result.cancelAndJoin()
                    assertTrue(result.isCancelled)
                    val calls = backend.callCount.get()
                    assertNull(reader.open())
                    assertEquals(calls, backend.callCount.get())
                } finally {
                    release.countDown()
                }
            }
        }

    @Test
    fun `one bulk read proves 300 app uids within the owner budget that the per uid loop cannot finish`() =
        runTest {
            val owners = (10_001..10_300).map { UidOwnerInstall("app.p$it", it, 123) }
            for (bulk in listOf(true, false)) {
                val backend =
                    FakeBackend().apply {
                        costPerCallMs = 5
                        owners.forEach {
                            packages[it.uid] = listOf(it.packageName)
                            infos[it.packageName] = it
                        }
                        installed = if (bulk) owners else null
                    }
                val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { backend.elapsed })
                val proof = requireNotNull(reader.close(reader.open(), owners.map { it.uid }.toSet()))
                if (bulk) {
                    assertEquals(owners.map { it.uid }.toSet(), proof.fingerprintsByUid.keys)
                    assertEquals(listOf("serial", "sequence", "installed", "serial", "sequence"), backend.calls)
                } else {
                    // The same 5 ms per call leaves the per-UID loop roughly 40 UIDs inside the 400 ms budget.
                    assertTrue(proof.fingerprintsByUid.size in 1 until 300)
                }
            }
        }

    @Test
    fun `failed bulk read falls back to per uid lookups in caller priority order`() =
        runTest {
            val backend =
                FakeBackend().apply {
                    for (uid in 10_002..10_004) {
                        packages[uid] = listOf("app.p$uid")
                        infos["app.p$uid"] = UidOwnerInstall("app.p$uid", uid, 123)
                    }
                }
            val uids = (10_001..10_004).toSet()
            val priority = mapOf(10_003 to 9.0, 10_001 to 1.0, 10_004 to Double.NaN)
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { backend.elapsed })
            val proof = requireNotNull(reader.close(reader.open(), uids, priority))
            assertEquals(uids, proof.fingerprintsByUid.keys)
            assertEquals("installed", backend.calls[2])
            assertEquals(
                listOf("packages:10003", "packages:10001", "packages:10002", "packages:10004"),
                backend.calls.filter { it.startsWith("packages:") },
            )

            // A budget cut keeps the highest-energy rows: 25 ms covers the bulk attempt and two UIDs.
            backend.costPerCallMs = 5
            val cut = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), ownerBudgetMs = 25, elapsedMs = { backend.elapsed })
            val cutProof = requireNotNull(cut.close(cut.open(), uids, priority))
            assertEquals(setOf(10_003, 10_001), cutProof.fingerprintsByUid.keys)
        }

    @Test
    fun `bulk and per uid fingerprints are equal for the same single and shared owner groups`() =
        runTest {
            val owners =
                listOf(
                    UidOwnerInstall("test.owner", 10_001, 123),
                    UidOwnerInstall("shared.b", 10_002, 456),
                    UidOwnerInstall("shared.a", 10_002, 789),
                )
            val viaBulk = FakeBackend().apply { installed = owners }
            val viaUid =
                FakeBackend().apply {
                    packages[10_002] = listOf("shared.b", "shared.a")
                    owners.forEach { infos[it.packageName] = it }
                }
            val proofs =
                listOf(viaBulk, viaUid).map { backend ->
                    val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { backend.elapsed })
                    requireNotNull(reader.close(reader.open(), setOf(10_001, 10_002)))
                }
            assertEquals(setOf(10_001, 10_002), proofs[0].fingerprintsByUid.keys)
            assertEquals(proofs[0].fingerprintsByUid, proofs[1].fingerprintsByUid)
            assertEquals(UidOwnerFingerprint.create(10_002, 7, owners.drop(1)), proofs[0].fingerprintsByUid[10_002])
            assertFalse(viaBulk.calls.any { it.startsWith("packages:") || it.startsWith("info:") })
            assertTrue(viaUid.calls.contains("packages:10002"))
        }

    @Test
    fun `bulk read skips invalid groups uncovered users system uids and unrequested owners`() =
        runTest {
            val backend =
                FakeBackend().apply {
                    installed =
                        listOf(
                            UidOwnerInstall("test.owner", 10_001, 123),
                            UidOwnerInstall("android", 1_000, 123),
                            UidOwnerInstall("other.user", 110_001, 123),
                            UidOwnerInstall("not.requested", 10_050, 123),
                            // One unproven owner withholds its whole shared group, never a partial set.
                            UidOwnerInstall("shared.ok", 10_002, 123),
                            UidOwnerInstall("shared.unknown", 10_002, 0),
                            UidOwnerInstall("1invalid", 10_003, 123),
                            UidOwnerInstall("dup.name", 10_004, 123),
                            UidOwnerInstall("dup.name", 10_005, 123),
                        ) + (0..UidOwnerFingerprint.MAX_OWNERS).map { UidOwnerInstall("big.p$it", 10_006, 123) }
                }
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { backend.elapsed })
            val requested = setOf(1_000, 110_001) + (10_001..10_007)
            val proof = requireNotNull(reader.close(reader.open(), requested))
            assertEquals(setOf(10_001), proof.fingerprintsByUid.keys)
            // A successful bulk read never falls back, not even for requested UIDs it did not list (10_007).
            assertEquals(listOf("serial", "sequence", "installed", "serial", "sequence"), backend.calls)
        }

    @Test
    fun `blocked bulk read is abandoned like any owner call and never falls back while holding the gate`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    installed = listOf(UidOwnerInstall("test.owner", 10_001, 123))
                    onInstalled = {
                        started.countDown()
                        try {
                            release.await()
                        } finally {
                            finished.countDown()
                        }
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 1_000,
                        ownerBudgetMs = 50,
                        closingBudgetMs = 1_000,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                try {
                    val result = async(Dispatchers.Default) { reader.close(reader.open(), setOf(10_001)) }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    assertNull(result.await())
                    assertFalse(backend.calls.any { it.startsWith("packages:") })
                    val calls = backend.callCount.get()
                    assertNull(reader.open())
                    assertEquals(calls, backend.callCount.get())
                } finally {
                    release.countDown()
                    assertTrue(finished.await(2, TimeUnit.SECONDS))
                }
            }
        }

    @Test
    fun `abandoned call clears its token on return and a later bracket proves owners again`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    onPackages = {
                        started.countDown()
                        release.await()
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 1_000,
                        ownerBudgetMs = 50,
                        closingBudgetMs = 1_000,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                try {
                    val abandoned = async(Dispatchers.Default) { reader.close(reader.open(), setOf(10_001)) }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    assertNull(abandoned.await())
                    assertNull(reader.open())
                } finally {
                    release.countDown()
                }
                // The native call returns: its token clears and the gate opens without any new caller's help.
                val opening =
                    withTimeout(2_000) {
                        var next = reader.open()
                        while (next == null) {
                            delay(10)
                            next = reader.open()
                        }
                        next
                    }
                val proof = requireNotNull(reader.close(opening, setOf(10_001)))
                assertEquals(setOf(10_001), proof.fingerprintsByUid.keys)
            }
        }

    @Test
    fun `wait for the gate that times out abandons no call and keeps the epoch`() =
        runBlocking {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val backend =
                FakeBackend().apply {
                    onPackages = {
                        started.countDown()
                        release.await()
                    }
                }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 100,
                        ownerBudgetMs = 5_000,
                        closingBudgetMs = 1_000,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                val firstOpening = requireNotNull(reader.open())
                val first = async(Dispatchers.Default) { reader.close(firstOpening, setOf(10_001)) }
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    val calls = backend.callCount.get()
                    // The overlapping opening only waits on the gate: its timeout reaches no native call.
                    assertNull(reader.open())
                    assertEquals(calls, backend.callCount.get())
                } finally {
                    release.countDown()
                }
                val proof = requireNotNull(first.await())
                assertEquals(firstOpening.scope.readerEpoch, proof.scope.readerEpoch)
                assertEquals(firstOpening.scope.readerEpoch, requireNotNull(reader.open()).scope.readerEpoch)
            }
        }

    @Test
    fun `backend error releases the physical gate and still reaches the caller`() =
        runBlocking {
            val backend = FakeBackend().apply { onPackages = { throw BackendError() } }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val reader =
                    UidIdentityReader(
                        backend,
                        dispatcher,
                        openingBudgetMs = 1_000,
                        ownerBudgetMs = 1_000,
                        closingBudgetMs = 1_000,
                        elapsedMs = { System.nanoTime() / 1_000_000 },
                    )
                val opening = requireNotNull(reader.open())
                var thrown: Throwable? = null
                try {
                    withTimeout(500) { reader.close(opening, setOf(10_001)) }
                } catch (failure: BackendError) {
                    thrown = failure
                }
                assertNotNull(thrown)
                backend.onPackages = {}
                val fresh = requireNotNull(reader.close(reader.open(), setOf(10_001)))
                assertEquals(setOf(10_001), fresh.fingerprintsByUid.keys)
                assertFalse(opening.scope.readerEpoch == fresh.scope.readerEpoch)
            }
        }

    private class BackendError : Error("test backend error")

    @Test
    fun `backend cancellation is not swallowed as missing ownership`() =
        runTest {
            val backend = FakeBackend().apply { onPackages = { throw CancellationException("outer") } }
            val reader = UidIdentityReader(backend, StandardTestDispatcher(testScheduler), elapsedMs = { testScheduler.currentTime })
            var cancelled = false
            try {
                reader.close(reader.open(), setOf(10_001))
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
        }

    private class FakeBackend : UidIdentityBackend {
        override val userId = 0
        var serial = 7L
        var sequence = 0
        var failSequence = false
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val callCount = AtomicInteger()
        val packages = mutableMapOf<Int, List<String>?>(10_001 to listOf("test.owner"))
        val infos = mutableMapOf("test.owner" to UidOwnerInstall("test.owner", 10_001, 123))
        var onInfo: () -> Unit = {}
        var onSerial: () -> Unit = {}
        var onPackages: () -> Unit = {}

        /** Null makes the bulk read fail, so the reader proves owners one UID at a time. */
        var installed: List<UidOwnerInstall>? = null

        /** Simulated binder cost charged to [elapsed] by every call. */
        var costPerCallMs = 0L
        var elapsed = 0L

        private fun record(value: String) {
            calls += value
            callCount.incrementAndGet()
            elapsed += costPerCallMs
        }

        var onInstalled: () -> Unit = {}

        override fun installedOwners(): List<UidOwnerInstall>? {
            record("installed")
            onInstalled()
            return installed
        }

        override fun userSerial(): Long {
            record("serial")
            onSerial()
            return serial
        }

        override fun packageChangeSequence(): Int {
            record("sequence")
            check(!failSequence)
            return sequence
        }

        override fun packagesForUid(uid: Int): List<String>? {
            record("packages:$uid")
            onPackages()
            return packages[uid]
        }

        override fun packageInfo(packageName: String): UidOwnerInstall? {
            record("info:$packageName")
            onInfo()
            return infos[packageName]
        }
    }
}
