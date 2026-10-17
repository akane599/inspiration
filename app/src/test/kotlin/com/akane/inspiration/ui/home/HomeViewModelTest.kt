package com.akane.inspiration.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun `addItem appends to immutable state`() {
        val vm = HomeViewModel()
        vm.addItem()
        vm.addItem()
        assertEquals(listOf("Item 1", "Item 2"), vm.state.value.items)
    }
}
