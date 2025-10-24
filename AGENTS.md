# Repository Guidelines

## Project Structure & Module Organization
UUUpdater ships as a single Android module in `app/`. Production code sits in `app/src/main/kotlin/com/tripleu/updater`, grouped into `data/`, `repository/`, `service/`, `ui/`, `viewmodel/`, `worker/`, `util/`, and `di/` for Koin wiring. Resources stay in `app/src/main/res/` with localized strings under `values-*`. Unit tests live in `app/src/test/kotlin/`; Compose and Espresso suites in `app/src/androidTest/kotlin/`. Automation assets are tracked in `fastlane/`, while Gradle wrapper files are under `gradle/`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` (`gradlew.bat` on Windows) builds the debug APK with current assets.
- `./gradlew assembleRelease` creates the shrinked release APK, using signing data from `local.properties` or the debug fallback.
- `./gradlew test` runs JVM unit tests.
- `./gradlew connectedAndroidTest` executes instrumentation and Compose UI tests on a device or emulator.
- `./gradlew lint` launches Android Lint; fix new warnings before publishing.

## Coding Style & Naming Conventions
Adopt the default Kotlin/Compose style: four-space indentation, trailing commas where Android Studio suggests, PascalCase composables and view models, lowerCamelCase members, and UPPER_SNAKE_CASE constants. Add strings and drawables with the existing `update_*` and `ic_*` patterns, and keep DI registrations in the relevant module under `di/`. Document non-trivial behaviors with concise KDoc and prefer immutable data classes inside `data/` and `repository/`.

## Testing Guidelines
Use JUnit 4 for unit coverage and Espresso with `ui-test-junit4` for instrumentation. Name tests with `functionUnderTest_expectedResult`. Place screen-specific Compose tests next to the screen package in `androidTest`. Run `./gradlew test lint` before opening a PR, and add `connectedAndroidTest` when UI logic, installers, or background workers change.

## Commit & Pull Request Guidelines
Commit subjects stay short and imperative (e.g., `Refine install flow and improve progress feedback`). Squash noise before pushing and include context in the body when behavior shifts. Pull requests should explain intent, link issues, and attach UI captures when visuals change. Note any signing, translation, or migration considerations, and record the Gradle tasks you ran. Ping reviewers who maintain the affected package to speed feedback.

## Configuration & Signing Tips
Sign-off secrets are read from `local.properties`; never commit keystores or passwords. Provide sanitized examples when introducing new keys. If you add third-party endpoints, surface them via `BuildConfig` fields with safe defaults so debug builds remain functional.
