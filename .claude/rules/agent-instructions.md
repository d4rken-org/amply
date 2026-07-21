# Agent Instructions

## Core Principles

- Keep the orchestrator (main agent) and each sub-agent context focused.
- Delegate suitable work to sub-agents; optimize for task efficiency and token usage.
- Be critical of all suggestions, including your own. Verify assumptions against the actual code. Don't over-engineer.

## Project-specific agent selection

Per the global rules, prefer these for this Kotlin/Android project:

- **`devtools:build-runner`** for all gradle build/test/lint runs — keeps verbose output out of the main context.
- **`jvm-tools:jvm-dev`** for Kotlin/JVM tasks that may need to inspect a Gradle dependency's API.
- **`jvm-tools:jar-explorer`** for deep exploration of a library (20+ classes).
- **`debugbadger`** tools/agent for on-device use-case runs and logcat capture (Android device automation).
- **`Explore`** / **`general-purpose`** for broad codebase searches when you only need the conclusion.

## When to use sub-agents

**Use for:** exploring unfamiliar parts of the codebase, cross-file pattern searches, multi-file research, parallel
work. **Handle directly:** known-path reads, single-file edits with clear requirements, quick grep/glob.

## Reading before changing control code

Before editing charge-control, Shizuku/WSS access, the AIDL, or the capability gate, read `privileged-access.md` and
the relevant part of `architecture.md`. These paths have real safety constraints (allowlist, no shell strings,
capability gate) that must not be relaxed casually.

## Multi-step work

1. Break complex tasks into discrete steps (use TaskCreate/TaskUpdate to track).
2. Complete and verify one step before the next.
3. Separate exploring (read-only) from implementing (minimal, focused edits) — when uncertain, explore first.

## Error handling

Understand a tool failure before retrying; don't repeat a failing approach. Report blockers early rather than working
around them silently. Ask for clarification on ambiguous requirements via the AskUserQuestion tool.

## Device testing

Follow the global Test-Target rules: never adopt an Android device/emulator you didn't start unless interference is
positively ruled out, and never re-point a named target without confirmation. Amply's control paths are
capability-gated to specific Pixels — record device results in the qualification ledger in `privileged-access.md`
rather than loosening the gate to run on an unqualified device.
