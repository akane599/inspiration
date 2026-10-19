package com.akane.inspiration.toolkit

import com.akane.inspiration.toolkit.ObscuredTouchGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ObscuredTouchGateTest {
    private val gate = ObscuredTouchGate()

    private fun down(obscured: Boolean) = gate.onEvent(obscured = obscured, pressed = true, anyDown = true)
    private fun move(obscured: Boolean) = gate.onEvent(obscured = obscured, pressed = false, anyDown = true)
    private fun up(obscured: Boolean) = gate.onEvent(obscured = obscured, pressed = false, anyDown = false)

    @Test
    fun `a tap on an uncovered window passes untouched`() {
        assertEquals(listOf(Verdict.PASS, Verdict.PASS, Verdict.PASS), listOf(down(false), move(false), up(false)))
    }

    @Test
    fun `an obscured tap is blocked from press to release and notifies only on the press`() {
        assertEquals(listOf(Verdict.BLOCK_AND_NOTIFY, Verdict.BLOCK, Verdict.BLOCK), listOf(down(true), move(true), up(true)))
    }

    @Test
    fun `a second finger in the same gesture is blocked without a second notice`() {
        down(true)
        assertEquals(Verdict.BLOCK, down(true))
        assertEquals(Verdict.BLOCK, up(true))
    }

    @Test
    fun `every new gesture notifies again`() {
        down(true)
        up(true)
        assertEquals(Verdict.BLOCK_AND_NOTIFY, down(true))
    }

    @Test
    fun `an overlay that appears mid-gesture blocks the rest and notifies on the next press`() {
        assertEquals(Verdict.PASS, down(false))
        assertEquals(Verdict.BLOCK, move(true))
        assertEquals(Verdict.BLOCK, up(true))
        assertEquals(Verdict.BLOCK_AND_NOTIFY, down(true))
    }

    @Test
    fun `a gesture that ends uncovered resets the notice`() {
        down(true)
        assertEquals(Verdict.PASS, up(false))
        assertEquals(Verdict.BLOCK_AND_NOTIFY, down(true))
    }
}
