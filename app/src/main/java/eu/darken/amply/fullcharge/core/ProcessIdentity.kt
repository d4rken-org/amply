package eu.darken.amply.fullcharge.core

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity of the current OS process. A persisted work record stamps [token] + [pid]; at pickup a
 * stored token that differs from the live [token] proves the work crossed a process death (crash,
 * force-stop, system kill). The [pid] only ever serves to match the corresponding
 * `ApplicationExitInfo` record — never as the survived-death signal itself (PIDs are reused).
 */
@Singleton
class ProcessIdentity @Inject constructor() {
    val token: String = UUID.randomUUID().toString()
    val pid: Int = android.os.Process.myPid()
}
