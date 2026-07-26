package eu.darken.amply.common.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One persisted setting, backed by a single [Preferences.Key].
 *
 * Amply keeps **one** process-wide [AppDataStore], and Preferences DataStore hands the *entire*
 * snapshot to every collector whenever any key changes. Without a guard, an unrelated write — the
 * stats recorder stamping its last-capture timestamp every ~20s, say — re-emits every setting in the
 * app, restarting downstream `flatMapLatest` chains and rebuilding whole UI states. That is a real
 * user-visible defect, not just waste: it made the dashboard's charging card flash its loading state
 * on every recorder tick.
 *
 * So the dedupe lives *here*, in the primitive, rather than at each call site — a facade cannot
 * forget it. It runs on the **raw stored value, before [reader]**, which matters twice over: the
 * comparison never depends on a domain type's `equals` (a type with identity equality would silently
 * never dedupe), and no decoding work happens for a duplicate emission.
 *
 * Ported from SD Maid SE's `DataStoreValue`.
 */
class DataStoreValue<T : Any?>(
    private val dataStore: DataStore<Preferences>,
    private val key: Preferences.Key<*>,
    val reader: (Any?) -> T,
    val writer: (T) -> Any?,
) {
    val keyName: String
        get() = key.name

    val flow: Flow<T> = dataStore.data
        .map { prefs -> prefs[this.key] }
        .distinctUntilChanged()
        .map { raw -> reader(raw) }

    data class Updated<T>(
        val old: T,
        val new: T,
    )

    /**
     * Read-modify-write in a single transaction, returning both sides. Returning `null` from [update]
     * clears the key.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun update(update: (T) -> T?): Updated<T> {
        val values = arrayOfNulls<Any?>(2)

        dataStore.updateData { prefs ->
            val before = reader(prefs[this.key]).also { values[0] = it }
            val after: T? = update(before).also { values[1] = it ?: reader(null) }

            prefs.toMutablePreferences().apply {
                set(this@DataStoreValue.key as Preferences.Key<Any?>, after?.let { writer(it) })
            }.toPreferences()
        }

        return Updated(old = values[0] as T, new = values[1] as T)
    }
}

fun <T : Any?> AppDataStore.createValue(
    key: Preferences.Key<*>,
    reader: (rawValue: Any?) -> T,
    writer: (value: T) -> Any?,
) = DataStoreValue(
    dataStore = store,
    key = key,
    reader = reader,
    writer = writer,
)

@Suppress("UNCHECKED_CAST")
inline fun <reified T> basicKey(key: String): Preferences.Key<T> = when (T::class) {
    Boolean::class -> booleanPreferencesKey(key) as Preferences.Key<T>
    String::class -> stringPreferencesKey(key) as Preferences.Key<T>
    Int::class -> intPreferencesKey(key) as Preferences.Key<T>
    Long::class -> longPreferencesKey(key) as Preferences.Key<T>
    Float::class -> floatPreferencesKey(key) as Preferences.Key<T>
    else -> throw NotImplementedError("Unsupported type: ${T::class}")
}

/**
 * Reads the raw value, falling back to [defaultValue] when the key is absent **or holds the wrong
 * type**. A wrong-typed value can only come from a key name that was reused for a different type;
 * decoding to the default beats throwing, which would kill the collector's flow for good.
 */
inline fun <reified T> basicReader(defaultValue: T): (rawValue: Any?) -> T = { rawValue ->
    (rawValue as? T) ?: defaultValue
}

inline fun <reified T> basicWriter(): (T) -> Any? = { value ->
    when (value) {
        is Boolean, is String, is Int, is Long, is Float -> value
        null -> null
        else -> throw NotImplementedError("Unsupported type: ${value::class}")
    }
}

inline fun <reified T : Any?> AppDataStore.createValue(
    key: String,
    defaultValue: T = null as T,
) = createValue(
    key = basicKey<T>(key),
    reader = basicReader(defaultValue),
    writer = basicWriter(),
)

suspend fun <T : Any?> DataStoreValue<T>.value(): T = flow.first()

suspend fun <T : Any?> DataStoreValue<T>.value(value: T) = update { value }
