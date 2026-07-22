package eu.darken.amply.common.flow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Typed [combine] for six flows. Kotlin's stdlib provides typed overloads only up to five, which
 * forces view-model state assembled from more sources into nested pre-combines. This mirrors the
 * helper other darken projects (CAPod, SD Maid) ship so the combine can stay flat. Add higher
 * arities here as they are needed.
 */
fun <T1, T2, T3, T4, T5, T6, R> combine(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    transform: suspend (T1, T2, T3, T4, T5, T6) -> R,
): Flow<R> = combine(
    combine(flow1, flow2, flow3) { a, b, c -> Triple(a, b, c) },
    combine(flow4, flow5, flow6) { d, e, f -> Triple(d, e, f) },
) { (a, b, c), (d, e, f) ->
    transform(a, b, c, d, e, f)
}
