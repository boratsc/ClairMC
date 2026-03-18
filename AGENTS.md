# Repository Guidelines

## Project Structure & Module Organization
This repository contains a Paper/Purpur Minecraft plugin built with Gradle and Java 21. Main code lives under `src/main/java/app/clair/mcbridge`, split by responsibility: `bridge/` for WebSocket and signing logic, `commands/` for `/link` and `/unlink`, `listeners/` for Bukkit event hooks, and `tools/` for local verification helpers such as `SignatureProbe`. Runtime descriptors and defaults are in `src/main/resources`, especially `plugin.yml` and `config.yml`. Design notes and API references live in `DOC/`. Build outputs land in `build/` and should not be edited manually.

## Build, Test, and Development Commands
Use the Gradle wrapper so contributors stay on the same toolchain.

- `./gradlew clean build`: compile, package, and produce the shaded plugin JAR.
- `./gradlew shadowJar`: build only the distributable JAR in `build/libs/clair-mc-bridge-<version>.jar`.
- `./gradlew probeSig -PprobeSecret=...`: run the signature probe against the Java signing implementation.
- `./gradlew tasks`: inspect available Gradle tasks when adding new workflow steps.

## Coding Style & Naming Conventions
Follow existing Java style: 4-space indentation, braces on the same line, and `final` classes where appropriate. Keep packages under `app.clair.mcbridge`. Use `PascalCase` for classes, `camelCase` for methods and fields, and clear Bukkit-facing names such as `LinkCommand` or `PlayerEvents`. Prefer small helpers over inline protocol logic, and keep config keys aligned with `config.yml` naming.

## Testing Guidelines
There is no `src/test` suite yet and no test framework is configured in `build.gradle.kts`. For now, treat `./gradlew build` as the minimum verification step and manually validate plugin startup, command registration, and Bridge connectivity on a Paper 1.21.1 server. When adding tests, place them in `src/test/java` and name them after the target class, for example `BridgeClientTest`.

## Commit & Pull Request Guidelines
Git history is not available in this workspace snapshot, so use short imperative commit subjects such as `Add reconnect guard for invalid bridge config`. Keep commits focused on one change. Pull requests should describe behavior changes, list verification steps, reference related issues, and include config or log snippets when touching bridge protocol, commands, or connection handling.

## Security & Configuration Tips
Do not commit real `bridge.secret` values or environment-specific server identifiers. Treat `src/main/resources/config.yml` as a template only, and scrub sensitive URLs, tokens, and server names from docs, logs, and screenshots.
