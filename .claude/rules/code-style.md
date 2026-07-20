# Code Style

## General Principles

- Package by feature, not by layer (see `architecture.md`).
- Prefer adding to existing files unless creating a genuinely new logical component.
- Minimalistic, concise code. Don't comment obvious code.
- Prefer Flow-based / reactive solutions. Long-running operations should be cancellable.
- Keep decision logic in pure, JVM-testable units (e.g. `SessionDecisionEngine`), not inside services/ViewModels.

## Kotlin Conventions

- Trailing commas on multi-line parameter lists and collections.
- Multi-line `if` always uses braces.
- Place `@Suppress` as close as possible to the affected code.
- The build sets `-Xannotation-default-target=param-property`; annotations on constructor params target both the
  parameter and property. Be intentional with `@field:` / `@get:` when it matters.

## ViewModels

Amply uses **plain `androidx.lifecycle.ViewModel`** with `@HiltViewModel` + constructor injection. There is **no
custom `ViewModel1/2/4` hierarchy** (that is a different project). Example:

```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    // deps…
) : ViewModel() { … }
```

## Compose

- Jetpack Compose + Material 3, single-Activity (`MainActivity`) with Navigation3.
- `modifier: Modifier = Modifier` must be the **first optional parameter** in a composable that takes a modifier.
- Extract a composable into its own file when it grows large (~200 lines). Reusable composables belong in a shared
  `common` compose location, not next to a single feature.

### Host/Page pattern (mandatory for all Compose screens)

Every screen splits into two composables:

**`<Feature>ScreenHost`** — ViewModel wiring + side effects. The only place that calls `hiltViewModel()`, collects
one-shot events, launches activity results, and starts intents. It reads the ViewModel's state and hands it down.

**`<Feature>Screen`** — Pure presentation. Accepts a `Flow<State>` (or plain state) plus callbacks, is marked
`internal`, and is previewable with mock `flowOf(...)` data.

```kotlin
@Composable
fun DashboardScreenHost(vm: DashboardViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onApply = vm::apply,
        onStartFull = vm::startFull,
    )
}

@Composable
internal fun DashboardScreen(
    modifier: Modifier = Modifier,
    state: DashboardUiState,
    onApply: (ChargePolicy) -> Unit = {},
    onStartFull: () -> Unit = {},
) { … }
```

Wire only the `Host` into `MainActivity`'s navigation, never the raw `Screen`.

### Previews (mandatory)

Every screen/component composable has a `@Preview` (light + dark) wrapped in the shared themed preview wrapper.
Preview functions are `private`, named `<Component>Preview()`, and placed directly below the composable they preview.
Drive them with mock state via `flowOf(...)` / a constructed `UiState`, never a real ViewModel.

## Widget & Tile

- The home-screen widget uses **Glance** (`androidx.glance:glance-appwidget` + `glance-material3`).
- The Quick Settings tile follows CAPod's short-lived tile pattern.

## Logging

Structured `Logging` fans out to backends (Logcat always in debug; opt-in file logger after consent). Tags are
prefixed `AMP:`.

```kotlin
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.debug.logging.Logging

private val TAG = logTag("Charging", "Repository")   // → "AMP:Charging:Repository"

log(TAG) { "Applying $policy" }                                // DEBUG (default)
log(TAG, Logging.Priority.INFO) { "Scan complete" }
log(TAG, Logging.Priority.ERROR) { "Write failed for ${policy.stableId}" }
```

Priority levels: `VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.

## Data & State

- Reactive with Kotlin Flow / StateFlow.
- **Preferences DataStore** via the single `AppDataStore` (`common/AppDataStore.kt`) — `preferencesDataStore(name = "amply")`.
  This is the raw AndroidX Preferences API (read/write `Preferences.Key`s through `store.data` / `store.edit`); there
  is no custom `createValue()` typed-setting DSL. Feature preference facades wrap `AppDataStore` but all share the one
  process-safe instance — do not create a second `DataStore`.
- Debug builds attach extra logging; the file logger requires explicit user consent before recording.
