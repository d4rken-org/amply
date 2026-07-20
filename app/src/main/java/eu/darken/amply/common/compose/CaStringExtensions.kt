package eu.darken.amply.common.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import eu.darken.amply.common.ca.CaString

@Composable
@ReadOnlyComposable
fun CaString.asComposable(): String = get(LocalContext.current)
