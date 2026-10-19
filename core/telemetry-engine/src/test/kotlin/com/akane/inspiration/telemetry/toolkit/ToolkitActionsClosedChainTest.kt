package com.akane.inspiration.telemetry.toolkit

import com.akane.inspiration.ipc.toolkit.AppOp
import com.akane.inspiration.ipc.toolkit.AppOpMode
import com.akane.inspiration.ipc.toolkit.DozeProfile
import com.akane.inspiration.ipc.toolkit.StandbyBucket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chain the log proves closed (its newest `after` is its original again) can never be an override, so the
 * override recompute does not spend a privileged read on it; an open chain is still read back.
 */
class ToolkitActionsClosedChainTest {
    private val backend = FakeBackend()
    private val audit = FakeAudit()
    private val holds = FakeHolds()
    private var clock = 10_000L
    private val actions = ToolkitActions(backend, audit, holds::hold, nowMs = { clock++ })

    private val packages = (1..6).map { "com.example.toggled$it" }

    @Test
    fun closedChainsAreNotReadBackOnEveryOverrideRecompute() =
        runTest {
            for (pkg in packages) {
                actions.execute(ToolkitAction.SetBucket(pkg, StandbyBucket.RARE))
                actions.execute(ToolkitAction.SetBucket(pkg, StandbyBucket.ACTIVE)) // back to the original
                actions.execute(ToolkitAction.SetAppOp(pkg, AppOp.RUN_IN_BACKGROUND, AppOpMode.IGNORE))
                actions.execute(ToolkitAction.SetAppOp(pkg, AppOp.RUN_IN_BACKGROUND, AppOpMode.DEFAULT)) // back again
            }
            backend.reads.clear()

            val overrides = actions.activeOverrides().first()

            assertEquals("precondition: every chain is back at its original, nothing is overridden", emptyList<Override>(), overrides)
            assertTrue(
                "closed chains need no privileged read, but these were issued: ${backend.reads}",
                backend.reads.isEmpty(),
            )
        }

    @Test
    fun anOpenChainIsStillReadBackNextToClosedOnes() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(CLOSED, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(CLOSED, StandbyBucket.ACTIVE))
            actions.execute(ToolkitAction.SetBucket(OPEN, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(OPEN, StandbyBucket.FREQUENT))
            backend.reads.clear()

            val overrides = actions.activeOverrides().first()

            assertEquals(listOf("getBucket($OPEN)"), backend.reads)
            assertEquals(listOf(OPEN), overrides.map { it.pkg })
            assertEquals(
                "the chain original, not the intermediate value",
                "${StandbyBucket.ACTIVE.rawValue}",
                overrides.single().previousRaw,
            )
        }

    @Test
    fun aClosedChainChangedByHandIsNotReadEvenThoughTheDeviceMoved() =
        runTest {
            actions.execute(ToolkitAction.SetBucket(CLOSED, StandbyBucket.RARE))
            actions.execute(ToolkitAction.SetBucket(CLOSED, StandbyBucket.ACTIVE))
            // Changed outside the toolkit: not ours, so not an override whatever the read would say.
            backend.buckets[CLOSED] = StandbyBucket.RARE.rawValue
            backend.reads.clear()

            assertEquals(emptyList<Override>(), actions.activeOverrides().first())
            assertTrue(backend.reads.toString(), backend.reads.isEmpty())
        }

    @Test
    fun anUnsetDozeValueIsNotReadBack() =
        runTest {
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.BALANCED))
            actions.execute(ToolkitAction.ApplyDoze(DozeProfile.STANDARD))
            backend.reads.clear()

            assertEquals(emptyList<Override>(), actions.activeOverrides().first())
            assertTrue(backend.reads.toString(), backend.reads.isEmpty())
        }

    private companion object {
        const val CLOSED = "com.example.closed"
        const val OPEN = "com.example.open"
    }
}
