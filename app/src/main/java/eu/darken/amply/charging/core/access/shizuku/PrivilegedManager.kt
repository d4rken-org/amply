package eu.darken.amply.charging.core.access.shizuku

/** The manager app serving the privileged binder. [label] is the untranslated product name. */
enum class ManagerBackend(val label: String) {
    PORTER("Porter"),
    SHIZUKU("Shizuku"),
}

/** How far away the privileged manager is, from nothing installed to a binder in hand. */
sealed interface PrivilegedManager {

    /** A lookup succeeded and found no manager this app can use. */
    data object NotInstalled : PrivilegedManager

    /**
     * [packageName] declares [backend]'s permission, including a renamed fork. [connected] means this
     * process holds a binder from it that answers.
     */
    data class Installed(
        val backend: ManagerBackend,
        val packageName: String?,
        val connected: Boolean,
    ) : PrivilegedManager

    /** A binder answered, but the manager and this app share no protocol version. */
    data class Incompatible(
        val backend: ManagerBackend,
        val packageName: String?,
        val serverTooOld: Boolean,
        val clientTooOld: Boolean,
    ) : PrivilegedManager

    /** The lookup timed out or failed, so nothing is known either way. */
    data object Unreachable : PrivilegedManager
}
