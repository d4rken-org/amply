package eu.darken.amply.rules.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.rules.core.ChargeRule
import eu.darken.amply.rules.core.PlugKind
import eu.darken.amply.rules.core.RuleCondition
import eu.darken.amply.rules.core.RuleKind
import eu.darken.amply.rules.core.kind

/**
 * How a rule reads. Shared by the rules screen and the dashboard card so the two can never describe
 * the same rule differently.
 */
@Composable
fun ChargeRule.conditionSummary(): String = when (val condition = this.condition) {
    is RuleCondition.BluetoothDevice -> {
        val name = condition.name?.takeIf { it.isNotBlank() } ?: condition.address.takeIf { it.isNotBlank() }
        if (name != null) {
            stringResource(R.string.rules_condition_bluetooth, name)
        } else {
            stringResource(R.string.rules_condition_bluetooth_unnamed)
        }
    }
    is RuleCondition.ChargerType -> if (condition.types.isEmpty()) {
        stringResource(R.string.rules_condition_charger_none)
    } else {
        stringResource(R.string.rules_condition_charger, condition.types.joinLabels())
    }
}

/** The user's name for a rule, falling back to what it actually does. */
@Composable
fun ChargeRule.displayTitle(): String = label.takeIf { it.isNotBlank() } ?: conditionSummary()

@Composable
fun Set<PlugKind>.joinLabels(): String {
    val separator = stringResource(R.string.rules_condition_separator)
    // Ordered by the enum, not by insertion, so the same selection always reads the same way. The
    // labels are resolved before joining — joinToString is not inline, so its transform cannot make
    // composable calls.
    val labels = PlugKind.entries.filter { it in this }.map { it.label() }
    return labels.joinToString(separator)
}

@Composable
fun PlugKind.label(): String = stringResource(
    when (this) {
        PlugKind.AC -> R.string.rules_plug_ac
        PlugKind.USB -> R.string.rules_plug_usb
        PlugKind.WIRELESS -> R.string.rules_plug_wireless
        PlugKind.DOCK -> R.string.rules_plug_dock
    },
)

/** The kind is derived from the policy, so the icon and the effect can never disagree. */
val ChargeRule.kindIcon: ImageVector
    get() = when (kind) {
        RuleKind.CHARGE -> Icons.Default.Bolt
        RuleKind.PROTECTION -> Icons.Default.Security
        // Undecodable policy: the row is marked unsupported and does nothing.
        null -> Icons.Default.PowerSettingsNew
    }

val ChargeRule.conditionIcon: ImageVector
    get() = when (condition) {
        is RuleCondition.BluetoothDevice -> Icons.Default.Bluetooth
        is RuleCondition.ChargerType -> Icons.Default.Bolt
    }

@Composable
fun ChargeRule.kindLabel(): String? = when (kind) {
    RuleKind.CHARGE -> stringResource(R.string.rules_kind_charge)
    RuleKind.PROTECTION -> stringResource(R.string.rules_kind_protection)
    null -> null
}
