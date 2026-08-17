package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.compose.AmplyCardActionLabel
import eu.darken.amply.common.compose.AmplyNavigationCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.RulePhase
import eu.darken.amply.rules.core.RuleRuntimeState
import eu.darken.amply.rules.core.policy
import eu.darken.amply.rules.ui.conditionSummary
import eu.darken.amply.rules.ui.displayTitle
import eu.darken.amply.rules.ui.kindIcon
import eu.darken.amply.upgrade.ui.ProBadge

/**
 * The dashboard's view of the conditions: the same ordered list the rules screen shows, compressed,
 * with the one that is actually in effect marked. Order is meaning here — the topmost matching rule
 * wins — so the card never re-sorts by state.
 */
@Composable
fun ConditionsCard(
    state: ConditionsState,
    showProBadge: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.enabledRules
    AmplyNavigationCard(
        onClick = onOpen,
        onClickLabel = stringResource(R.string.dashboard_conditions_open),
        title = stringResource(R.string.dashboard_conditions_title),
        modifier = modifier,
        icon = Icons.TwoTone.Bolt,
        headerStatus = when {
            enabled.isEmpty() -> null
            state.activeRule != null -> stringResource(R.string.dashboard_conditions_active)
            else -> stringResource(R.string.dashboard_conditions_waiting)
        },
    ) {
        if (enabled.isEmpty()) {
            Text(
                stringResource(R.string.dashboard_conditions_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showProBadge) ProBadge()
                AmplyCardActionLabel(
                    text = stringResource(R.string.dashboard_conditions_empty_action),
                    modifier = Modifier.weight(1f),
                )
            }
            return@AmplyNavigationCard
        }
        enabled.forEach { rule ->
            val active = rule.id == state.activeRule?.id
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    rule.kindIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        rule.displayTitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        rule.conditionSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rule.policy?.label?.asComposable()?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (state.applyFailed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.rules_failure_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun previewRule(
    id: String,
    label: String,
    condition: RuleCondition,
    policy: ChargePolicy,
) = ChargeRule(id = id, label = label, condition = condition, policyId = policy.stableId)

@AmplyPreview
@Composable
private fun ConditionsCardPreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val car = previewRule(
            id = "car",
            label = "Car",
            condition = RuleCondition.BluetoothDevice("AA:BB:CC:DD:EE:FF", "Car audio"),
            policy = ChargePolicy.Unrestricted,
        )
        val desk = previewRule(
            id = "desk",
            label = "Desk",
            condition = RuleCondition.ChargerType(setOf(PlugKind.AC)),
            policy = ChargePolicy.FixedLimit(80),
        )
        ConditionsCard(
            state = ConditionsState(
                rules = listOf(car, desk),
                runtime = RuleRuntimeState(phase = RulePhase.ACTIVE, activeRuleId = "car"),
            ),
            showProBadge = false,
            onOpen = {},
        )
        ConditionsCard(
            state = ConditionsState(
                rules = listOf(car, desk),
                runtime = RuleRuntimeState(lastApplyFailed = true),
            ),
            showProBadge = false,
            onOpen = {},
        )
    }
}

@AmplyPreview
@Composable
private fun ConditionsCardEmptyPreview() = PreviewWrapper {
    Column(modifier = Modifier.padding(16.dp)) {
        ConditionsCard(state = ConditionsState(), showProBadge = true, onOpen = {})
    }
}
