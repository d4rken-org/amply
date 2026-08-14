package eu.darken.amply.common.flow

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A one-shot event channel for ViewModel → UI signals that must be *consumed*, not observed:
 * "show this snackbar", "open the upgrade screen". A [kotlinx.coroutines.flow.StateFlow] is the
 * wrong shape for those — it replays, so a rotation would re-fire the event.
 *
 * Buffered, so an event emitted while nothing is collecting (a screen mid-transition) is delivered
 * once the collector attaches instead of being dropped. Ported from SD Maid SE.
 */
class SingleEventFlow<T> : AbstractFlow<T>() {
    private val channel = Channel<T>(Channel.BUFFERED)

    override suspend fun collectSafely(collector: FlowCollector<T>) = channel.receiveAsFlow().collect(collector)

    suspend fun emit(value: T) = channel.send(value)

    fun tryEmit(value: T): ChannelResult<Unit> = channel.trySend(value)
}
