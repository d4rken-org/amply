# Commit & Pull Request Guidelines

## Workflow

All changes go through a **pull request** into `main` — the same flow CAPod uses. Direct pushes to `main` are
blocked by a branch ruleset (no force-push, no deletion) that requires an open PR with passing CI status checks and
resolved review threads before a change can land; version tags (`v*`) are protected the same way. Only the
`d4rken-org-releaser` app bypasses these rules, for the automated release/tag workflows. Work on a feature branch or
worktree, open a PR, let CI go green, then merge — see the PR sections below.

## Commit Message Format

```
<area>: <title>

<detailed technical description>

<optional context / issue references>
```

## Area Prefixes

Use the feature area the change lives in:

- **Charging** — policies, capability gate, OEM adapters, WSS/Shizuku access
- **FullCharge** — temporary session, boot recovery, reconnect gesture
- **Diagnostics** — privileged setting comparison workflow
- **Dashboard** / **Setup** / **Settings** / **Widget** / **Tile** — `main/ui` surfaces
- **Theming** — brand / Material You / mode / contrast
- **Debug** — logging + debug sessions
- **General** — cross-cutting concerns, architecture, build system, CI
- **Fix** — bug fixes that don't fit a specific area

Recent history uses plain descriptive titles (e.g. "Dim the reconnect-gesture card on unsupported devices"); a bare
descriptive title without a prefix is acceptable for small changes. Titles are for developers reading `git log` —
technical references (class/method names) are fine.

- Use action words: Fix, Add, Improve, Update, Remove, Refactor.
- Use `git mv` for moves/renames to preserve history.

### Example

```
FullCharge: Run boot restore inside the service with a convergence check

A boot-time settings write can race Settings Intelligence's observer
registration and never reach the charging HAL. Restore now re-writes
until the HAL confirms or a budget expires, persisting the pending
target so a killed service resumes.
```

## Pull Requests

Follow the project's global PR rules: one PR per coherent change, no known-flaw PRs, open as **draft** if more work is
still landing in the same PR.

### Description format

**What changed** — user-facing explanation (no internal class/method names). For non-user-facing work, write "No
user-facing behavior change" plus a brief internal note.

**Technical Context** — what the diff can't show: *why* this approach, root cause for fixes, non-obvious side effects,
and review guidance. Keep it scannable; don't restate the diff.

### Labels

PRs are labeled automatically by `.github/workflows/labeler.yml` (config `.github/labeler.yml`). It runs on
open/edit/synchronize, so labels appear a short time after opening and update when the title or diff changes:

- **Area labels** from the changed paths — `Translations`, `FOSS`, `Google Play`, `Build/Deploy`. These are synced
  to the current diff (a reverted path drops its label).
- **One exclusive type label** from the title + diff — a `Fix:` title prefix → `bug`; a docs-only (`*.md`) diff →
  `documentation`; anything else → `enhancement`. The workflow keeps exactly one of these three.

**After opening a PR, check the applied labels and adjust — don't assume they're complete or correct:**

- The workflow **cannot** infer device/OEM labels (`ROM: *`, `api: NN`, `device support`, `Shizuku`). Add those by
  hand when the change is device- or ROM-specific.
- The type heuristic keys on the `Fix:` prefix, so a bug fix written with a bare descriptive title (no prefix) lands
  as `enhancement` — correct it manually. Same for any other misclassification.
- The labeler runs from the **default branch**, so a PR that itself edits the labeler workflow/config isn't labeled
  by its own version — label such a PR by hand.
- It is **PR-only**; issues are still labeled manually (see the issue label taxonomy).

Because it only ever manages the labels above, manually-added labels (device/OEM/`triage`/etc.) are left untouched.
