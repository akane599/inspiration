package com.akane.inspiration.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class HomeUiState(
    val items: ImmutableList<String> = persistentListOf(),
)

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun addItem() {
        _state.update { s -> s.copy(items = (s.items + "Item ${s.items.size + 1}").toImmutableList()) }
    }
}
