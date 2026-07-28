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

## Reading before changing control code

Before editing charge-control, Shizuku/WSS access, the AIDL, or the capability gate, read `privileged-access.md` and
the relevant part of `architecture.md`. These paths have real safety constraints (allowlist, no shell strings,
capability gate) that must not be relaxed casually. For adapter internals (keys, value domains, write ordering), use
the `oem-adapters` skill.

## Device testing

Follow the global Test-Target rules: never adopt an Android device/emulator you didn't start unless interference is
positively ruled out, and never re-point a named target without confirmation. Amply's control paths are
capability-gated to specific Pixels — record device results in the qualification ledger (`device-qualification` skill)
rather than loosening the gate to run on an unqualified device.
