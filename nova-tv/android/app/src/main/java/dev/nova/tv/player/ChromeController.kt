package dev.nova.tv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the transient "hide the controls" deadline.
 *
 * One deadline, re-armed on activity — never a polling tick. Releasing the last
 * hold grants a *fresh full idle window* rather than hiding immediately, because
 * hiding on the same frame a menu closes loses the control the viewer just used.
 */
class ChromeController(
    private val scope: CoroutineScope,
    private val idleMs: Long = PlayerTimings.IDLE_MS
) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var idleJob: Job? = null
    private val holds = mutableSetOf<HoldReason>()

    fun update(transform: (PlayerUiState) -> PlayerUiState) {
        _state.update { current ->
            // Holds are authoritative here; the caller must not be able to drop them.
            transform(current).copy(holds = holds.toSet())
        }
    }

    /** Take a claim on the chrome. Returns a release that is safe to call twice. */
    fun hold(reason: HoldReason): () -> Unit {
        holds += reason
        idleJob?.cancel()
        publish()
        var released = false
        return {
            if (!released) {
                released = true
                holds -= reason
                publish()
                armIdle()
            }
        }
    }

    fun onUserActive() {
        _state.update { it.copy(userActive = true) }
        armIdle()
    }

    /**
     * Touch: a tap on the video hides the controls at once. Holds (an open
     * menu) still win, exactly like the idle timeout.
     */
    fun hideNow() {
        if (holds.isNotEmpty()) return
        idleJob?.cancel()
        _state.update { it.copy(userActive = false) }
    }

    private fun armIdle() {
        idleJob?.cancel()
        if (holds.isNotEmpty()) return
        idleJob = scope.launch {
            delay(idleMs)
            // Only go idle if nothing has claimed the chrome in the meantime.
            if (holds.isEmpty()) {
                _state.update { it.copy(userActive = false) }
            }
        }
    }

    private fun publish() {
        _state.update { it.copy(holds = holds.toSet()) }
    }

    fun clear() {
        holds.clear()
        idleJob?.cancel()
        idleJob = null
        publish()
    }
}
