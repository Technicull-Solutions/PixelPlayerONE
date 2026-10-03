package com.theveloper.pixelplay.presentation.plex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.plex.PlexLibrary
import com.theveloper.pixelplay.data.plex.PlexRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlexUiState(
    val busy: Boolean = true, val connected: Boolean = false,
    val server: String = "", val libraries: List<PlexLibrary> = emptyList(),
    val message: String? = null, val error: Boolean = false
)

@HiltViewModel
class PlexViewModel @Inject constructor(private val repository: PlexRepository) : ViewModel() {
    private val _state = MutableStateFlow(PlexUiState())
    val state = _state.asStateFlow()

    init {
        runOperation {
            repository.restore()
            _state.update { it.copy(connected = repository.connected.value, server = repository.serverUrl.orEmpty()) }
            if (repository.connected.value) _state.update { it.copy(libraries = repository.libraries()) }
        }
    }

    fun connect(server: String, token: String) {
        if (_state.value.busy) return
        runOperation {
            val libraries = repository.connect(server, token)
            _state.update { it.copy(connected = true, server = repository.serverUrl.orEmpty(),
                libraries = libraries, message = "Connected. Import your music to start listening.") }
        }
    }

    fun sync() {
        if (_state.value.busy) return
        runOperation {
            val count = repository.sync { message -> _state.update { it.copy(message = message) } }
            _state.update { it.copy(message = "Imported $count tracks. Find them in Songs, Albums, and Artists.") }
        }
    }

    fun disconnect() {
        if (_state.value.busy) return
        runOperation {
            repository.disconnect()
            _state.value = PlexUiState(busy = true, message = "Disconnected and removed imported Plex music.")
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = false, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Don't display network exception URLs or request headers.
                val message = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message ?: "Plex operation failed"
                else "Could not reach Plex or unlock saved credentials. Check your server address and connection."
                _state.update { it.copy(message = message, error = true) }
            } finally { _state.update { it.copy(busy = false) } }
        }
    }
}
