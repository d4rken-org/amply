package eu.darken.amply.charging.core.access

import eu.darken.amply.R
import eu.darken.amply.charging.core.access.shizuku.ManagerBackend
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.cache
import eu.darken.amply.common.ca.caString
import eu.darken.amply.common.ca.toCaString

/** "Porter" or "Shizuku" for an identified manager, "Shizuku or Porter" when none was identified. */
fun ManagerBackend?.displayName(): CaString = this?.label?.toCaString() ?: R.string.manager_name_any.toCaString()

fun BackendStatus?.managerName(): CaString = this?.manager.displayName()

/** Formats a string resource whose `%1$s` is the manager's [displayName]. */
fun Int.withManagerName(backend: ManagerBackend?): CaString =
    caString { getString(this@withManagerName, backend.displayName().get(it)) }.cache()
