package com.akane.inspiration.telemetry.toolkit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule X-Ray and the Toolkit screen use to decide whether a toolkit write may be asked for (B-06). */
class ToolkitAdmissionTest {
    private data class Listed(
        override val actionId: Long,
        override val restorable: Boolean = true,
        override val unverified: ToolkitRefusal? = null,
    ) : ListedOverride

    private fun admits(
        request: AdmissionRequest,
        controlsShown: Boolean,
        vararg listed: Listed,
    ) = ToolkitAdmission.admits(request, controlsShown, listed.toList())

    @Test
    fun `an app change runs only against controls that were read back and are shown`() {
        assertTrue(admits(AdmissionRequest.AppChange, controlsShown = true))
        assertFalse("loading, failed or refused controls", admits(AdmissionRequest.AppChange, controlsShown = false, Listed(1)))
    }

    @Test
    fun `a listed restorable change is restored whether or not the app's controls can be read`() {
        for (controlsShown in listOf(true, false)) {
            assertTrue(admits(AdmissionRequest.Restore(7), controlsShown, Listed(3), Listed(7)))
            assertTrue(
                "an unverified change may still be in effect, so its undo is offered",
                admits(AdmissionRequest.Restore(7), controlsShown, Listed(7, unverified = ToolkitRefusal.PROTECTED_APP)),
            )
        }
    }

    @Test
    fun `a restore of a change that is not listed, or not restorable, is refused`() {
        assertFalse(admits(AdmissionRequest.Restore(9), controlsShown = true, Listed(7)))
        assertFalse(admits(AdmissionRequest.Restore(7), controlsShown = true, Listed(7, restorable = false)))
        assertFalse(admits(AdmissionRequest.Restore(7), controlsShown = true))
    }

    @Test
    fun `reset all needs a listed change and no readable controls`() {
        assertTrue(admits(AdmissionRequest.ResetAll, controlsShown = false, Listed(7, restorable = false)))
        assertFalse(admits(AdmissionRequest.ResetAll, controlsShown = true))
    }
}
