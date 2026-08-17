package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuickActionPoliciesTest {

    private val protective = ChargePolicy.FixedLimit(80)

    // A policy-rich adapter (four modes) — only these devices offer a choice at all.
    private val rich = listOf(
        protective,
        ChargePolicy.FixedLimit(90),
        ChargePolicy.Adaptive,
        ChargePolicy.PauseAtFull,
        ChargePolicy.Unrestricted,
    )

    @Test
    fun `an unconfigured selection falls back to the protective default plus unrestricted`() {
        resolveQuickActionPolicies(null, rich, protective) shouldContainExactly
            listOf(protective, ChargePolicy.Unrestricted)
    }

    @Test
    fun `an empty, unreadable or unsupported selection falls back to the default pair`() {
        val fallback = listOf(protective, ChargePolicy.Unrestricted)
        resolveQuickActionPolicies(emptyList(), rich, protective) shouldContainExactly fallback
        resolveQuickActionPolicies(listOf("nonsense", "fixed:abc"), rich, protective) shouldContainExactly fallback
        // FixedLimit(70) parses but this adapter cannot apply it.
        resolveQuickActionPolicies(listOf("fixed:70"), rich, protective) shouldContainExactly fallback
    }

    @Test
    fun `unsupported entries are dropped while supported ones are kept`() {
        resolveQuickActionPolicies(
            listOf("fixed:70", "adaptive", "pause_at_full"),
            rich,
            protective,
        ) shouldContainExactly listOf(ChargePolicy.Adaptive, ChargePolicy.PauseAtFull)
    }

    @Test
    fun `duplicates collapse`() {
        resolveQuickActionPolicies(
            listOf("adaptive", "adaptive", "unrestricted"),
            rich,
            protective,
        ) shouldContainExactly listOf(ChargePolicy.Adaptive, ChargePolicy.Unrestricted)
    }

    @Test
    fun `the order follows the adapter, not the stored order`() {
        resolveQuickActionPolicies(
            listOf("unrestricted", "adaptive", "fixed:80"),
            rich,
            protective,
        ) shouldContainExactly listOf(protective, ChargePolicy.Adaptive, ChargePolicy.Unrestricted)
    }

    @Test
    fun `no more than three buttons are ever returned`() {
        val resolved = resolveQuickActionPolicies(
            listOf("fixed:80", "fixed:90", "adaptive", "pause_at_full", "unrestricted"),
            rich,
            protective,
        )
        resolved shouldContainExactly listOf(protective, ChargePolicy.FixedLimit(90), ChargePolicy.Adaptive)
    }

    @Test
    fun `a two-policy adapter ignores any stored selection`() {
        val binary = listOf(protective, ChargePolicy.Unrestricted)
        resolveQuickActionPolicies(listOf("unrestricted"), binary, protective) shouldContainExactly binary
        resolveQuickActionPolicies(listOf("adaptive"), binary, protective) shouldContainExactly binary
    }

    /**
     * The property the buttons rest on: an unsupported target would persist a recovery goal that can
     * never converge, so no branch — fallbacks included — may leak one.
     */
    @Test
    fun `no branch returns a policy outside the adapter's supported list`() {
        val cases = listOf(
            null,
            emptyList(),
            listOf("fixed:70"),
            listOf("adaptive", "pause_at_full"),
            listOf("unrestricted", "nonsense"),
        )
        val adapters = listOf(
            rich,
            listOf(protective, ChargePolicy.Unrestricted),
            // An adapter whose protective default isn't in its own list, and one without Unrestricted.
            listOf(ChargePolicy.Adaptive, ChargePolicy.PauseAtFull, ChargePolicy.Unrestricted),
            listOf(protective, ChargePolicy.FixedLimit(90), ChargePolicy.Adaptive),
            emptyList(),
        )
        adapters.forEach { supported ->
            cases.forEach { stored ->
                resolveQuickActionPolicies(stored, supported, protective).all { it in supported } shouldBe true
            }
        }
    }

    @Test
    fun `an adapter without a usable fallback returns nothing rather than an unusable button`() {
        resolveQuickActionPolicies(
            null,
            listOf(ChargePolicy.Adaptive, ChargePolicy.PauseAtFull, ChargePolicy.FixedLimit(90)),
            protective,
        ) shouldContainExactly emptyList()
    }

    @Test
    fun `toggling keeps the one-to-three bounds and the supported membership`() {
        val selection = listOf(protective, ChargePolicy.Unrestricted)

        // Adding a third is fine, a fourth is refused.
        toggleQuickActionPolicy(selection, ChargePolicy.Adaptive, true, rich) shouldContainExactly
            listOf(protective, ChargePolicy.Adaptive, ChargePolicy.Unrestricted)
        toggleQuickActionPolicy(
            listOf(protective, ChargePolicy.Adaptive, ChargePolicy.Unrestricted),
            ChargePolicy.PauseAtFull,
            true,
            rich,
        ) shouldContainExactly listOf(protective, ChargePolicy.Adaptive, ChargePolicy.Unrestricted)

        // The last remaining button cannot be removed.
        toggleQuickActionPolicy(selection, ChargePolicy.Unrestricted, false, rich) shouldContainExactly
            listOf(protective)
        toggleQuickActionPolicy(listOf(protective), protective, false, rich) shouldContainExactly listOf(protective)

        // An unsupported policy is never added.
        toggleQuickActionPolicy(selection, ChargePolicy.FixedLimit(70), true, rich) shouldContainExactly selection
    }
}
