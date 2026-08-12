package eu.darken.amply.upgrade.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

// The acquisition pitch inserts the SAME composed brand the status title uses, postfix colored — one
// brand rendering for both. Word-order-proof: the brand is spliced into the TRANSLATED pattern, so
// Android's formatter owns placeholder semantics (numbering, reordering, escaping).
@Composable
private fun upgradeAcquisitionTitle(): AnnotatedString = spliceBrandTitle(
    formatted = stringResource(R.string.upgrade_screen_title_template, BRAND_TITLE_MARKER),
    brand = upgradeScreenTitle(upgraded = true),
)

@Composable
internal fun UpgradeScreen(
    uiState: GplayUpgradeUiState = GplayUpgradeUiState.Loading,
    onIap: () -> Unit = {},
    onSubscription: () -> Unit = {},
    onSubscriptionTrial: () -> Unit = {},
    onRestore: () -> Unit = {},
    onManageSubscription: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
) {
    // Owners get the ownership presentation: no acquisition upsell (pitch, benefits, offers box)
    // anywhere — the one-time purchase appears only as the ownership view's own switch offer, locked
    // while the subscription still renews.
    val loaded = uiState as? GplayUpgradeUiState.Loaded
    val ownedState = loaded?.takeIf { it.ownership.ownsAnything }

    UpgradeScreenScaffold(
        // Grace users still have the upgrade: they get the bare status title — "Get Amply Pro" on the
        // status screen would contradict the rest of the app, which behaves upgraded. Acquisition
        // wraps that same brand in the pitch sentence.
        title = if (ownedState != null || loaded?.grace != null) {
            upgradeScreenTitle(upgraded = true)
        } else {
            upgradeAcquisitionTitle()
        },
        onNavigateUp = onNavigateUp,
    ) { paddingValues ->
        UpgradeScreenContent(
            paddingValues = paddingValues,
            contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 32.dp),
        ) {
            if (ownedState == null) {
                if (loaded?.grace != null) {
                    // Grace users never see the preamble (sales copy contradicts "still active"), so
                    // the brand mark stays a standalone header above the grace card.
                    UpgradeHeader(markSize = 88.dp)
                } else {
                    UpgradeHeroCard(
                        text = stringResource(R.string.upgrade_screen_preamble),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }

            if (ownedState != null) {
                UpgradeOwnershipContent(
                    uiState = ownedState,
                    onIap = onIap,
                    onManageSubscription = onManageSubscription,
                    onRestore = onRestore,
                )
            } else {
                UpgradeAcquisitionContent(
                    uiState = uiState,
                    onIap = onIap,
                    onSubscription = onSubscription,
                    onSubscriptionTrial = onSubscriptionTrial,
                    onRestore = onRestore,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun UpgradeAcquisitionContent(
    uiState: GplayUpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRestore: () -> Unit,
    onRetry: () -> Unit,
) {
    val loadedState = uiState as? GplayUpgradeUiState.Loaded
    val inGrace = loadedState?.grace != null
    loadedState?.grace?.let { grace ->
        UpgradeGraceCard(
            showDiagnostics = grace.showDiagnostics,
            onRestore = onRestore,
            busy = loadedState.busy,
        )
    }

    // Grace users never see the pitch (they still have the upgrade, sales copy next to a "still
    // active" card reads as a contradiction), and the OFFERS follow the episode age — the client
    // can't tell a blip from a lapsed purchase, so time is the arbiter: a young episode (likely
    // self-healing) shows calm status only, an aged one (likely really gone) adds restore AND the
    // offers, so an expired subscriber can switch without waiting out the full grace window.
    if (!inGrace) {
        if (loadedState != null && loadedState.wasPreviouslyPro) {
            // The targeted returning-buyer nudge: prominent placement and emphasis, and the ONLY
            // restore affordance on the screen — a second one below would make the screen feel
            // uncertain about its own advice.
            UpgradeRestoreSection(
                title = stringResource(R.string.upgrade_screen_restore_banner_title),
                body = stringResource(R.string.upgrade_screen_restore_banner_body),
                onRestore = onRestore,
                modifier = Modifier.testTag(UpgradeScreenTags.GPLAY_RESTORE_BANNER),
                busy = loadedState.busy,
                emphasized = true,
                restoreTag = UpgradeScreenTags.GPLAY_RESTORE_BANNER_ACTION,
            )
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_benefits_title),
            icon = Icons.TwoTone.AutoAwesome,
        ) {
            UpgradeFeatureList(text = stringResource(R.string.upgrade_screen_benefits_body))
        }
    }

    // During a YOUNG grace episode the offers box is hidden: likely a blip, and offers next to "still
    // active" would contradict it. An aged episode brings them back.
    if (!inGrace || loadedState.grace?.showDiagnostics == true) {
        UpgradeOffersBox(
            uiState = uiState,
            onIap = onIap,
            onSubscription = onSubscription,
            onSubscriptionTrial = onSubscriptionTrial,
            onRetry = onRetry,
        )
    }

    // Restore is account reconciliation, not an offer — its own described section, after the offers.
    // Only for plain acquisition: returning buyers get the emphasized section up top instead, and
    // grace users' restore is owned by the grace card's two-stage disclosure.
    if (loadedState != null && !loadedState.wasPreviouslyPro && loadedState.grace == null) {
        UpgradeRestoreSection(
            title = stringResource(R.string.upgrade_screen_restore_banner_title),
            body = stringResource(R.string.upgrade_screen_restore_body),
            onRestore = onRestore,
            busy = loadedState.busy,
        )
    }
}

// All purchase framing lives inside the offers box (LoadedOffers) — no separate explainer card. Each
// state brings its OWN container: the error state is a full card itself, and wrapping it in the
// action card would produce a card-in-card.
@Composable
private fun UpgradeOffersBox(
    uiState: GplayUpgradeUiState,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
    onRetry: () -> Unit,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "upgrade-offers",
    ) { state ->
        when (state) {
            GplayUpgradeUiState.Loading -> UpgradeActionCard { UpgradeLoadingBlock() }
            is GplayUpgradeUiState.Unavailable -> UpgradeInlineStateCard(
                title = stringResource(R.string.upgrade_screen_offers_unavailable_title),
                body = stringResource(R.string.upgrade_screen_offers_unavailable_message),
                icon = Icons.TwoTone.WarningAmber,
            ) {
                // Play can be slow rather than broken (cold store, first sign-in): let the user
                // re-run the offer queries instead of leaving a dead screen. No reset needed: this
                // composable unmounts the moment the state leaves Unavailable.
                var retryTapped by remember { mutableStateOf(false) }
                val retryEnabled = !retryTapped
                OutlinedButton(
                    // Guard inside the callback, not just via `enabled`: `enabled` only takes effect
                    // after recomposition, so two taps in the same frame would both fire.
                    onClick = {
                        if (!retryTapped) {
                            retryTapped = true
                            onRetry()
                        }
                    },
                    enabled = retryEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UpgradeScreenTags.GPLAY_RETRY),
                    // The button sits on the errorContainer card, so the default primary-on-surface
                    // outlined colors read as a foreign element with poor contrast.
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        disabledContentColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.38f),
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = if (retryEnabled) 1f else 0.1f),
                    ),
                ) {
                    Text(stringResource(R.string.upgrade_retry_action))
                }
            }

            is GplayUpgradeUiState.Loaded -> UpgradeActionCard {
                LoadedOffers(
                    uiState = state,
                    onIap = onIap,
                    onSubscription = onSubscription,
                    onSubscriptionTrial = onSubscriptionTrial,
                )
            }
        }
    }
}

@AmplyPreview
@Composable
private fun UpgradeScreenLoadingPreview() = PreviewWrapper {
    UpgradeScreen(uiState = GplayUpgradeUiState.Loading)
}

@AmplyPreview
@Composable
private fun UpgradeScreenLoadedPreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.TRIAL,
            subscriptionEnabled = true,
            subscriptionPrice = "$4.99",
            iapEnabled = true,
            iapPrice = "$9.99",
        ),
    )
}

@AmplyPreview
@Composable
private fun UpgradeScreenReturningBuyerPreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.STANDARD,
            subscriptionEnabled = true,
            subscriptionPrice = "$4.99",
            iapEnabled = true,
            iapPrice = "$9.99",
            wasPreviouslyPro = true,
        ),
    )
}

@AmplyPreview
@Composable
private fun UpgradeScreenGraceQuietPreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.STANDARD,
            subscriptionEnabled = true,
            subscriptionPrice = "$4.99",
            iapEnabled = true,
            iapPrice = "$9.99",
            grace = GraceHint(showDiagnostics = false),
        ),
    )
}

@AmplyPreview
@Composable
private fun UpgradeScreenGraceDiagnosticsPreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.STANDARD,
            subscriptionEnabled = true,
            subscriptionPrice = "$4.99",
            iapEnabled = true,
            iapPrice = "$9.99",
            grace = GraceHint(showDiagnostics = true),
        ),
    )
}

@AmplyPreview
@Composable
private fun UpgradeScreenOwnedPreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.UNAVAILABLE,
            subscriptionEnabled = false,
            subscriptionPrice = "$4.99",
            iapEnabled = false,
            iapPrice = "$9.99",
            ownership = Ownership(hasIap = true),
        ),
    )
}

@AmplyPreview
@Composable
private fun UpgradeScreenUnavailablePreview() = PreviewWrapper {
    UpgradeScreen(
        uiState = GplayUpgradeUiState.Unavailable(
            error = RuntimeException("Google Play unavailable"),
        ),
    )
}
