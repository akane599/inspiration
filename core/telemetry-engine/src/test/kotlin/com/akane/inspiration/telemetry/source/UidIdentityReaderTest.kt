package com.akane.inspiration.telemetry.source

import com.akane.inspiration.telemetry.snapshot.UidOwnerInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
                listOf("serial", "sequence", "telemetry", "packages:10001", "info:test.owner", "serial", "sequence"),
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
    fun `default 1024 call ceiling includes four guards for single and shared owners`() =
        runTest {
            for (ownerCount in listOf(1, 2)) {
                val backend = FakeBackend()
                val count = if (ownerCount == 1) 510 else 340
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
                assertEquals(1_024, backend.callCount.get())
                assertNull(reader.close(opening, uids))
                assertEquals(1_024, backend.callCount.get())
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

        private fun record(value: String) {
            calls += value
            callCount.incrementAndGet()
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
