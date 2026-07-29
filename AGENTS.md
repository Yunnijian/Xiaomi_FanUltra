# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android LSPosed project. `app/` contains the application; Kotlin sources live in `app/src/main/java/com/mifan/kt/`. `HookEntry.kt` routes host packages, `hooks/` installs Settings, PowerKeeper, and SystemUI hooks, and `rom/` owns ROM-specific reflection and DexKit resolution. Keep policy helpers in `policy/`, shared utilities in `util/`, data types in `data/`, and Compose/MIUIX screens in `ui/`. Android resources are under `app/src/main/res/`; LSPosed metadata is under `app/src/main/resources/META-INF/xposed/`. Build helpers are in `scripts/`; release shrinker rules are `app/proguard-rules.pro`.

## Build, Test, and Development Commands

- `bash scripts/build-debug.sh` builds the debug APK using the workspace-local JDK 17, Android SDK, and Gradle cache.
- `bash scripts/gradle.sh :app:lintDebug` runs Android Lint for the debug variant.
- `bash scripts/gradle.sh :app:testDebugUnitTest` runs debug unit tests when tests are added.
- `bash scripts/gradle.sh :app:assembleRelease` builds a release APK. It requires the ignored `app/release-key.jks` and `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, and optionally `RELEASE_KEY_ALIAS`.

For a standalone checkout, copy `local.properties.example` to `local.properties` and configure the local SDK before using `./gradlew`. Do not commit local SDK paths, signing material, APKs, logs, or build output.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, `PascalCase` for types and files, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Prefer narrow `internal` visibility and preserve defensive reflection and logging around host APIs. Centralize host class, field, and method names in `rom/RomTargets.kt`; keep module constants and Settings keys in `FanModeConstants.kt`. No formatter or static-style tool is configured, so avoid unrelated reformatting and add KDoc for compatibility-sensitive behavior.

## Testing Guidelines

No unit or instrumentation tests are currently checked in. Every change must at least pass the debug build and `lintDebug`. For hook, policy, or ROM-compatibility changes, validate on a scoped HyperOS device with LSPosed enabled for Settings, PowerKeeper, and SystemUI. Exercise the affected fan-mode flow and include relevant logcat evidence; attach screenshots for Compose/UI changes.

## Commit & Pull Request Guidelines

Use concise, imperative English subjects, matching history: `Fix PowerKeeper hook regression`, `Add per-app rule storage`, or `Update release notes`. Keep commits focused. Pull requests should state the affected host packages and ROM/device, list build and device-validation results, link the issue when applicable, and call out metadata, scope, ProGuard, permission, or signing changes. Treat commands in `IMICHARGE_COMMANDS.md` as device-mutating operations and run them only on a disposable test device.
