package com.fakeglucosense.companion.domain

import com.fakeglucosense.companion.data.source.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process status the service publishes and the UI shows. It is not persisted: after process
 * death the service rebuilds it when it restarts.
 */
class LiveStatus {
    private val linkFlow = MutableStateFlow<LinkState?>(null)
    val link: StateFlow<LinkState?> = linkFlow.asStateFlow()

    private val runningFlow = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningFlow.asStateFlow()

    fun setLink(state: LinkState?) {
        linkFlow.value = state
    }

    fun setRunning(value: Boolean) {
        runningFlow.value = value
    }
}
