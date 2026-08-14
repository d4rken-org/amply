package eu.darken.amply.upgrade.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun UpgradeScreen(
    view: FossUpgradeView? = FossUpgradeView.PITCH,
    supporterSince: Instant? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onGithubSponsors: () -> Unit = {},
    onOpenSponsors: () -> Unit = {},
    onShowUpgradeOptions: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
) {
    UpgradeScreenScaffold(
        // Status views describe the existing install, not a support ask — they get the composed
        // flavor title, with the postfix highlighted for supporters.
        title = if (view == FossUpgradeView.PITCH) {
            AnnotatedString(stringResource(R.string.upgrade_screen_title))
        } else {
            upgradeScreenTitle(upgraded = view == FossUpgradeView.STATUS_UPGRADED)
        },
        onNavigateUp = onNavigateUp,
        snackbarHostState = snackbarHostState,
    ) { paddingValues ->
        when (view) {
            null -> Unit // Visit not started yet (single frame); content lands with the next state.
            FossUpgradeView.PITCH -> UpgradePitchContent(
                paddingValues = paddingValues,
                onGithubSponsors = onGithubSponsors,
            )

            FossUpgradeView.STATUS_FREE -> UpgradeStatusFreeContent(
                paddingValues = paddingValues,
                onShowUpgradeOptions = onShowUpgradeOptions,
            )

            FossUpgradeView.STATUS_UPGRADED -> UpgradeStatusUpgradedContent(
                paddingValues = paddingValues,
                supporterSince = supporterSince,
                onOpenSponsors = onOpenSponsors,
            )
        }
    }
}

@Composable
private fun UpgradePitchContent(
    paddingValues: PaddingValues,
    onGithubSponsors: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeroCard(
            text = stringResource(R.string.upgrade_screen_preamble),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_why_title),
            icon = Icons.TwoTone.AutoAwesome,
        ) {
            UpgradeFeatureList(text = stringResource(R.string.upgrade_screen_why_body))
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_how_title),
            icon = Icons.TwoTone.Favorite,
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_how_body))
        }

        UpgradeActionCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Button(
                onClick = onGithubSponsors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_SPONSOR),
            ) {
                Text(stringResource(R.string.upgrade_screen_sponsor_action))
            }

            UpgradeHintText(text = stringResource(R.string.upgrade_screen_sponsor_action_hint))
        }
    }
}

@Composable
private fun UpgradeStatusFreeContent(
    paddingValues: PaddingValues,
    onShowUpgradeOptions: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeader(markSize = 104.dp)

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_status_free_title),
            icon = Icons.TwoTone.Info,
            modifier = Modifier.testTag(UpgradeScreenTags.FOSS_STATUS_FREE),
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_status_free_body))
            Button(
                onClick = onShowUpgradeOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_SHOW_OPTIONS),
            ) {
                Text(stringResource(R.string.upgrade_screen_status_free_action))
            }
        }
    }
}

@Composable
private fun UpgradeStatusUpgradedContent(
    paddingValues: PaddingValues,
    supporterSince: Instant?,
    onOpenSponsors: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeader(markSize = 104.dp)

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_status_upgraded_title),
            icon = Icons.TwoTone.Verified,
            modifier = Modifier.testTag(UpgradeScreenTags.FOSS_STATUS_UPGRADED),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_status_upgraded_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            supporterSince?.let { since ->
                val formatter = remember {
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
                }
                Text(
                    text = stringResource(R.string.upgrade_screen_supporter_since, formatter.format(since)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_recurring_title),
            icon = Icons.TwoTone.Favorite,
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_recurring_body))
            OutlinedButton(
                onClick = onOpenSponsors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_DONATE),
            ) {
                Text(stringResource(R.string.upgrade_screen_recurring_action))
            }
        }
    }
}

/**
 * Tracks whether the screen was backgrounded since the last resume, which is how the sponsor return
 * is detected. Kept out of the composable so its (deliberately non-Compose) latch semantics are
 * unit-testable: exactly one resume after a stop counts as a return.
 */
internal class SponsorReturnTracker(
    private var wentToBackground: Boolean = false,
) {

    fun onStop() {
        wentToBackground = true
    }

    fun consumeResumeReturn(): Boolean {
        return if (wentToBackground) {
            wentToBackground = false
            true
        } else {
            false
        }
    }
}

@AmplyPreview
@Composable
private fun UpgradeScreenPreview() = PreviewWrapper {
    UpgradeScreen()
}

@AmplyPreview
@Composable
private fun UpgradeScreenStatusFreePreview() = PreviewWrapper {
    UpgradeScreen(view = FossUpgradeView.STATUS_FREE)
}

@AmplyPreview
@Composable
private fun UpgradeScreenStatusUpgradedPreview() = PreviewWrapper {
    UpgradeScreen(
        view = FossUpgradeView.STATUS_UPGRADED,
        supporterSince = Instant.ofEpochMilli(1_700_000_000_000L),
    )
}
