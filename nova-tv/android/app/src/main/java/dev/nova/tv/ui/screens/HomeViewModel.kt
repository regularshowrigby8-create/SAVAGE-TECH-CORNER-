package dev.nova.tv.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nova.tv.NovaApplication
import dev.nova.tv.data.model.MetaPreview
import dev.nova.tv.data.repo.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One shelf of titles. */
data class HomeRow(val title: String, val items: List<MetaPreview>)

data class HomeState(
    val loading: Boolean = true,
    val rows: List<HomeRow> = emptyList(),
    val error: String? = null,
    val opened: MetaPreview? = null
)

/**
 * Drives the home screen.
 *
 * The repository already merges across addons and collects per-addon errors, so
 * this class only decides what the viewer sees: a shelf, a spinner, or an error
 * with a retry — never a blank screen.
 */
class HomeViewModel : ViewModel() {

    private val repository: CatalogRepository = NovaApplication.instance.let { app ->
        CatalogRepository(app.addonClient)
    }

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                repository.installDefaults()
                val movieRow = repository.loadRow("movie")
                val seriesRow = repository.loadRow("series")
                val rows = buildList {
                    if (movieRow.isNotEmpty()) add(HomeRow("Movies", movieRow))
                    if (seriesRow.isNotEmpty()) add(HomeRow("Series", seriesRow))
                }
                val error = if (rows.isEmpty()) {
                    "No catalogue answered. Check the network or add a source."
                } else {
                    null
                }
                _state.value = HomeState(loading = false, rows = rows, error = error)
            } catch (error: Exception) {
                _state.value = HomeState(
                    loading = false,
                    rows = emptyList(),
                    error = error.message ?: "Could not load the catalogue."
                )
            }
        }
    }

    fun openTitle(title: MetaPreview) {
        _state.value = _state.value.copy(opened = title)
    }

    fun closeTitle() {
        _state.value = _state.value.copy(opened = null)
    }
}
