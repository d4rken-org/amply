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

### State-hoisted screens (previewable)

Screens are **pure and state-hoisted**: a screen composable takes a state object plus callbacks, and never calls
`hiltViewModel()`, holds a ViewModel reference, or collects a `Flow` itself. All ViewModel wiring — state collection,
one-shot events, permission launchers, activity results, navigation — lives at the **composition root**
(`MainActivity`), which passes concrete state and callbacks down. That separation is what keeps every screen
renderable from mock state, which is what makes the mandatory previews below possible.

```kotlin
// Pure screen — no ViewModel/Hilt, fully previewable.
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onApply: (ChargePolicy) -> Unit,
    onStartFull: () -> Unit,
    // …
) { … }

// Wiring lives at the root (MainActivity), not inside the screen:
DashboardScreen(
    state = state,
    onApply = viewModel::applyPolicy,
    onStartFull = { runWithNotifications(START_FULL_CHARGE) },
)
```

A named `<Feature>ScreenHost` that pre-collects a ViewModel is **optional** — add one only if a screen's root wiring
grows large enough to be worth isolating. It isn't required here, because the screens are already pure. (ViewModels
own their side effects, including starting intents; that's the established convention — see the ViewModels section.)

### Previews (mandatory)

Every **screen and every reusable/public component** has previews via **`@AmplyPreview`** (the light + dark
multipreview annotation) wrapped in **`PreviewWrapper`** — both in `common/compose/Preview.kt`, which renders against
the branded `AmplyTheme`. Preview functions are `private`, named `<Component>Preview()`, placed at the end of the
file, and driven by a **constructed state fixture** — never a real ViewModel. Use a *meaningful* fixture that
exercises the real UI (e.g. a ready dashboard plus an unsupported-device variant), not bare `State()` defaults, which
mostly render loading/empty. Private one-off leaf helpers don't need their own preview — they're covered by their
screen's preview.

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

Debug logs stay **local**: the file logger records only after explicit consent, nothing is transmitted automatically
(Android's share sheet opens only on a separate user action), and completed recordings are user-deletable in-app.

## Data & State

- Reactive with Kotlin Flow / StateFlow.
- **Preferences DataStore** via the single `AppDataStore` (`common/AppDataStore.kt`) — `preferencesDataStore(name = "amply")`.
  Feature preference facades wrap `AppDataStore` but all share the one process-safe instance — do not create a second
  `DataStore`.
- **Never read `store.data` directly in a facade.** Declare settings with the `createValue()` DSL in
  `common/datastore/` (ported from SD Maid SE). Because there is exactly one shared store, Preferences DataStore hands
  the *entire* snapshot to *every* collector on *any* write — an unrelated key's write would otherwise re-emit every
  setting in the app, restarting downstream `flatMapLatest` chains and rebuilding whole UI states. `DataStoreValue`
  dedupes on the **raw stored value, before the reader runs**, so the guard cannot be forgotten per-facade and never
  depends on a domain type's `equals`. (This is not hypothetical: an undeduplicated `captureEnabled` made the
  dashboard's charging card flash its loading state on every stats-recorder tick.)

  ```kotlin
  val captureEnabled = dataStore.createValue("stats.capture_enabled", false)   // scalar
  val session = dataStore.createValue<ChargeSessionRecord?>("session.v2", null, json, fallbackToDefault = true)
  ```

  Read with `.value()` / collect `.flow`; write with `.value(x)` or `.update { }` (read-modify-write in one
  transaction). Settings consumed **as a unit** — a session and its provenance, the alarm config — are one
  `@Serializable` record under one key, so a partially-written state cannot exist. Independent scalars stay separate,
  so a hot-path write doesn't wake unrelated collectors.
- **Persisted records are a stored wire format.** Give every persisted property and enum constant an explicit
  `@SerialName` and a default, and never reuse a key name for a different type (Preferences keys compare by name).
  `fallbackToDefault` is chosen **per record**: fine where the decode is already all-or-nothing (an unreadable session
  is no session) or cosmetic, but a record whose fields degrade *independently* — `ChargingPreferences` — must
  validate field by field, or one bad field silently resets a user's real protective baseline to the default.
  `StoredRecordFormatTest` pins the JSON.
- Debug builds attach extra logging; the file logger requires explicit user consent before recording.
