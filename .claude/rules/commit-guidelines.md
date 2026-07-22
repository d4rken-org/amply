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
