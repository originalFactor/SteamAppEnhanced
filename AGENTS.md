# Repository Guidelines

## Project Structure & Module Organization

Steam Enhanced is a single-module Android app written in Kotlin with Jetpack Compose and Miuix. Source code lives under `app/src/main/java/cc/ptoe/steam/enhanced/`:

- `core/`: Android-independent cryptography, models, protobuf, and Steam TOTP logic.
- `net/` and `protocol/`: HTTP transport and Steam service clients.
- `store/`, `app/`, and `sync/`: persistence, Android integration, and encrypted WebDAV synchronization.
- `ui/`: Compose screens, controls, and themes.

Resources and translations live in `app/src/main/res/`. JVM tests mirror production packages in `app/src/test/java/`. See `docs/ava-port.md` for architecture, compatibility constraints, and verification notes; preserve upstream attribution in `docs/AVA-LICENSE`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper from the repository root. Configure Android SDK 37; the daemon toolchain specifies JDK 21, while app bytecode targets Java 17.

- `./gradlew :app:assembleDebug`: build the debug APK.
- `./gradlew :app:installDebug`: install on a connected device or emulator; launch from its app launcher.
- `./gradlew :app:testDebugUnitTest`: run JVM unit tests.
- `./gradlew :app:lintDebug`: run Android Lint.
- `./gradlew :app:testDebugUnitTest --tests '*SteamTotpTest'`: run a focused test class.

Dependency versions are centralized in `gradle/libs.versions.toml`.

## Coding Style & Naming Conventions

Follow surrounding Kotlin style: four-space indentation, `UpperCamelCase` types and composables, and `lowerCamelCase` functions and properties. Match filenames to their primary type or feature. Keep `core/` free of Android dependencies. Use resource strings for UI text and maintain relevant translations. No dedicated formatter or Kotlin linter is configured; use Android Studio formatting and Android Lint.

## Testing Guidelines

Tests use JUnit 4, Kotlin test assertions, coroutine test utilities, and OkHttp MockWebServer. Name classes `*Test` and use descriptive test methods, including backtick names. Add regression coverage for changed behavior, especially crypto vectors, malformed protocol input, storage failures, and synchronization conflicts. Prefer synthetic credentials and local HTTP fixtures. No numerical coverage threshold is configured.

## Commit & Pull Request Guidelines

History currently contains only `first commit`; no established message convention exists. Use concise, imperative subjects describing one focused change. PRs should explain behavior and rationale, link relevant issues, report validation commands and results, and include screenshots for UI changes. Keep generated build outputs and local IDE changes out of PRs. Never commit real Steam secrets, tokens, vault exports, or WebDAV credentials.
