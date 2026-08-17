package eu.darken.amply.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.fullcharge.core.MAX_QUICK_ACTION_POLICIES

/**
 * Picks which charge modes a surface offers as persistent-policy buttons (the reconnect notification,
 * a widget instance). One to [MAX_QUICK_ACTION_POLICIES] may be selected, enforced by disabling the
 * rows that would break the bound: the last selected row cannot be unchecked, and unchecked rows go
 * inert once the maximum is reached. That is presentation only — the writing side re-checks both
 * bounds and adapter membership.
 *
 * [rowsEnabled] switches the whole picker off (e.g. while the feature owning the buttons is disabled)
 * without hiding it, so the user can see what they would be configuring.
 */
@Composable
fun QuickActionPolicyPicker(
    availablePolicies: List<ChargePolicy>,
    selectedPolicyIds: List<String>,
    onToggle: (ChargePolicy, Boolean) -> Unit,
    rowsEnabled: Boolean = true,
) {
    Column {
        availablePolicies.forEach { policy ->
            val checked = policy.stableId in selectedPolicyIds
            val boundAllows = if (checked) {
                selectedPolicyIds.size > 1
            } else {
                selectedPolicyIds.size < MAX_QUICK_ACTION_POLICIES
            }
            SettingsSwitchItem(
                title = policy.label.asComposable(),
                subtitle = null,
                checked = checked,
                onCheckedChange = { onToggle(policy, it) },
                enabled = rowsEnabled && boundAllows,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun QuickActionPolicyPickerPreview() = PreviewWrapper {
    QuickActionPolicyPicker(
        availablePolicies = listOf(
            ChargePolicy.FixedLimit(80),
            ChargePolicy.FixedLimit(90),
            ChargePolicy.Adaptive,
            ChargePolicy.Unrestricted,
        ),
        selectedPolicyIds = listOf("fixed:80", "adaptive", "unrestricted"),
        onToggle = { _, _ -> },
    )
}
