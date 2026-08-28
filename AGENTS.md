# Repository Guidelines

## Project Structure & Module Organization

This repository is a single Android/Xposed module. Kotlin sources live in
`app/src/main/java/com/mifan/kt`, grouped by responsibility: `hooks/` contains
host-process hooks, `rom/` contains ROM symbol resolution and compatibility
tables, `policy/` contains fan-policy helpers, and `ui/` contains the Compose
configuration screen. Android resources and the module manifest are under
`app/src/main/res` and `app/src/main/AndroidManifest.xml`. Build helpers are in
`scripts/`; generated APKs belong in `app/build/` and should not be committed.

## Build, Test, and Development Commands

Run these commands from the repository root:

- `bash scripts/build-debug.sh` builds and assembles the debug Xposed APK.
- `bash scripts/gradle.sh :app:compileDebugKotlin` performs a fast Kotlin
  compilation check.
- `bash scripts/gradle.sh :app:testDebugUnitTest` runs the local unit tests.
- `bash scripts/gradle.sh :app:lintDebug` checks Android/Kotlin lint rules.

The scripts select the project-local Gradle/toolchain setup, so prefer them to
invoking a system `gradle` binary directly.

## Coding Style & Naming Conventions

Use four-space indentation, Kotlin official formatting, and trailing commas in
multiline declarations. Classes and objects use `PascalCase`; functions,
properties, and test names use `camelCase`; constants use `UPPER_SNAKE_CASE`.
Keep host-specific class, field, and method names in `rom/RomTargets.kt` (or a
resolver) instead of scattering string literals through hook logic. Run lint
and compilation before submitting changes.

## Testing Guidelines

Unit tests use JUnit through the Android Gradle plugin. Name tests after the
behavior they verify (for example, `customModeRespectsMasterSwitch`). Add
regression coverage for policy decisions and reflection fallbacks whenever a
ROM version changes, then run `:app:testDebugUnitTest`.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects such as `Fix custom fan policy switch
handling`; keep unrelated changes in separate commits. Pull requests should
explain the affected host process/ROM version, describe manual verification,
link an issue when one exists, and include screenshots or log excerpts for UI
or hook-behavior changes. Do not commit OTA packages, extracted system images,
APK dumps, or build outputs.

## Security & Configuration Tips

Fan hooks run inside privileged system processes. Treat reflected values as
untrusted: guard lookups with `runCatching`, preserve original behavior when a
symbol is absent, and avoid logging user data or package lists unnecessarily.
