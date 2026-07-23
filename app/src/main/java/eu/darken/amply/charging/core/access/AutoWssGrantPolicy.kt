package eu.darken.amply.charging.core.access

/**
 * Pure decision for automatically self-granting WRITE_SECURE_SETTINGS via Shizuku once the user has
 * granted Shizuku access, so the separate "Grant permission with Shizuku" tap becomes unnecessary.
 *
 * "Episode" = one uninterrupted stretch of Shizuku being ready without WSS. We grant at most once per
 * episode; on failure the manual setup-card button remains as the fallback. The [attempted] latch is
 * threaded back by the caller and reset here when the episode ends (Shizuku drops or WSS is obtained),
 * so a later Shizuku re-grant re-arms a fresh attempt.
 */
object AutoWssGrantPolicy {

    data class Outcome(
        val grant: Boolean,
        /** The next value of the caller's per-episode attempt latch. */
        val attempted: Boolean,
    )

    fun evaluate(
        onboardingComplete: Boolean,
        controlEnabled: Boolean,
        shizukuReady: Boolean,
        wssReady: Boolean,
        writeRequiresShizuku: Boolean,
        attempted: Boolean,
    ): Outcome = when {
        // Episode over (WSS obtained) or not active (Shizuku not ready): clear the latch so the next
        // Shizuku-ready episode gets a fresh automatic attempt.
        wssReady || !shizukuReady -> Outcome(grant = false, attempted = false)
        // Not eligible to auto-grant yet: before onboarding is accepted we must not grant a system
        // permission, a capability-gated device could never use it, and a Shizuku-write adapter
        // (system/lineagesettings namespace) can never write via WSS — so granting it would be
        // wasted least-privilege surface. Hold the latch unchanged.
        !onboardingComplete || !controlEnabled || writeRequiresShizuku ->
            Outcome(grant = false, attempted = attempted)
        // Already tried this episode: leave it to the manual fallback button.
        attempted -> Outcome(grant = false, attempted = true)
        // First eligible observation of Shizuku-ready-without-WSS: grant once and latch.
        else -> Outcome(grant = true, attempted = true)
    }
}
