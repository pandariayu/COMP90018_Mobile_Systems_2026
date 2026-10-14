package com.comp90018.flashcards.debug

import com.comp90018.flashcards.data.p2p.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEBUG ONLY: set this to false to hide every emulator mode feature at once. The tickbox on the main
 * screen disappears so nothing can switch the emulator mode on, and the app behaves like the real thing.
 */
const val DEBUG_TOOLS_ENABLED = false

/**
 * DEBUG ONLY: switches for trying things out without a second phone. In memory, so they reset
 * when the app restarts.
 */
@Singleton
class DebugSettings
    @Inject
    constructor() {
        private val _emulatorMode = MutableStateFlow(false)

        // on an emulator nearby cannot connect, so the lobby gets a fake join request button
        val emulatorMode: StateFlow<Boolean> = _emulatorMode.asStateFlow()

        fun setEmulatorMode(enabled: Boolean) {
            _emulatorMode.value = enabled
        }

        private val _simulatedLink = MutableStateFlow<ConnectionState?>(null)

        // in emulator mode the screens show this link state instead of the real one, null means all fine
        val simulatedLink: StateFlow<ConnectionState?> = _simulatedLink.asStateFlow()

        fun clearSimulatedLink() {
            _simulatedLink.value = null
        }

        // retries for a few seconds like the real controller, then gives up and shows the lost screen
        suspend fun simulateDrop() {
            _simulatedLink.value = ConnectionState.Reconnecting(DEBUG_PEER)
            delay(DEBUG_GRACE_MS)
            if (_simulatedLink.value is ConnectionState.Reconnecting) {
                _simulatedLink.value = ConnectionState.Lost(DEBUG_PEER)
            }
        }

        // what the Reconnect button does: try for a moment, then the link is back
        suspend fun simulateReconnect() {
            _simulatedLink.value = ConnectionState.Reconnecting(DEBUG_PEER)
            delay(DEBUG_RECONNECT_MS)
            _simulatedLink.value = null
        }

        fun simulatePeerEnded() {
            _simulatedLink.value = ConnectionState.PeerEnded
        }
    }

private const val DEBUG_PEER = "Team Plum"
private const val DEBUG_GRACE_MS = 4_000L
private const val DEBUG_RECONNECT_MS = 1_500L
