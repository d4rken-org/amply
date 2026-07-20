# Localization

## Current state (honest)

Localization is **partially established**. System-surfaced text — notification channels/titles/bodies, the Quick
Settings tile, and the widget description — **is** extracted to `app/src/main/res/values/strings.xml`. In-app
**Compose UI text is currently hardcoded** as string literals (~30+ `Text("…")` call sites in `main/ui`). There are no
translated `values-*` string files yet.

Treat `strings.xml` as the target convention: prefer extracting new user-facing strings, and when you touch a
Compose screen that hardcodes text, extracting those literals is a reasonable, in-scope cleanup.

## Convention

- All user-facing text should be extractable to `strings.xml` to be localizable.
- Access from Compose/UI with `stringResource(R.string.…)` (or `context.getString(...)` off the UI thread).
- Use ordered placeholders for multi-argument strings: `%1$s is %2$d`.
- Use the ellipsis character `…`, not three dots `...`.

## String ID naming

Existing IDs are grouped by the surface they belong to (`session_notification_*`, `gesture_notification_*`,
`recovery_notification_*`, `tile_*`, `widget_*`). Follow that pattern:

- Prefix with the feature/surface (`dashboard_`, `setup_`, `settings_`, `diagnostics_`).
- Name by intent, not widget type — postfix actions with `_action` rather than prefixing `button_`.
- Set `formatted="false"` on strings that contain a literal `%` but no format args (as existing entries do).

## Resource location

- `app/src/main/res/values/strings.xml` — base English strings (shared across both flavors).
- There are currently no flavor-specific (`src/foss` / `src/gplay`) or translated (`values-*`) string resources; add
  them under the matching source set / qualifier if the need arises.
