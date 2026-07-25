package eu.darken.amply.stats.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.darken.amply.stats.core.StatsLiveSession
import kotlinx.coroutines.delay

/**
 * How long a live charge session has been running, as a value that keeps moving on its own.
 *
 * Fresh battery readouts normally drive recomposition, but identical consecutive readouts are
 * conflated upstream, so a session that sits at its limit would freeze the elapsed text. The tick is
 * the backstop — and because the same value also gates the curve's reveal threshold, it is aligned to
 * the next whole *session* minute rather than a minute from composition: a card entering composition
 * at 2m59s would otherwise reveal the curve at 3m59s.
 *
 * [nowElapsedRealtimeMillis] is injectable so callers stay testable without a real clock; `maxOf`
 * takes whichever read is fresher (both are monotonic elapsed-realtime). State is keyed by the
 * session, so a replaced session never inherits the previous one's timing.
 *
 * The clock is read **here, on composition** rather than seeded from [nowElapsedRealtimeMillis]: that
 * parameter is captured wherever the caller's own composition last ran, so a card that scrolls out of
 * a lazy list and back would otherwise re-enter holding a snapshot minutes or hours old, and sit on it
 * until the first tick. Reading it here makes re-entry current; the parameter still wins when it is
 * ahead (an injected test clock, or a caller with a fresher read).
 */
@Composable
fun rememberLiveElapsedMillis(
    session: StatsLiveSession,
    nowElapsedRealtimeMillis: Long,
): Long {
    val start = session.startedElapsedRealtimeMillis
    var tickedNow by remember(session.id, start) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(session.id, start) {
        while (true) {
            val sinceStart = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0)
            delay(LIVE_TICK_MILLIS - (sinceStart % LIVE_TICK_MILLIS))
            tickedNow = SystemClock.elapsedRealtime()
        }
    }
    return (maxOf(tickedNow, nowElapsedRealtimeMillis) - start).coerceAtLeast(0)
}

/** Backstop cadence for live elapsed text when battery readouts stop changing. */
private const val LIVE_TICK_MILLIS = 60_000L
