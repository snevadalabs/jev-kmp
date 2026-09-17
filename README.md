# jev-kmp

Kotlin Multiplatform SDK for the TypeSafe / Jev System One API.

> **Status: unreleased.** `0.1.0` is under construction. The public API is not yet stable and no artifact has
> been published. The build skeleton exists; no SDK behaviour has been implemented yet.

| | |
|---|---|
| Coordinates | `com.sierranevadalabs:jev-kmp` |
| Package | `com.sierranevadalabs.jev.sdk` |
| Targets | JVM, Android, Apple (iOS `arm64`/`simulatorArm64`/`x64`, macOS `arm64`), Linux `x64` |
| License | MIT |

## Quickstart

Arrives with `0.1.0`. The HTTP client is built on [Ktor](https://ktor.io), but Ktor types appear in exactly two
configuration parameters and nowhere else in the public surface.

## Building

```bash
./gradlew check
```

`check` runs ktlint, the public-API dump comparison, the version/CHANGELOG consistency check, the coverage
floor, and every test target the current host can execute.

## Running the live API tests

The live tier hits the real API, so it is opt-in twice over — a Gradle property **and** an environment
variable — and a default `./gradlew check` can never reach the network or spend money:

```bash
TYPESAFE_API_KEY=… ./gradlew jvmTest -Ptypesafe.live=true
```

`integration.yml` runs the same command nightly and on every push to `main`, using the `JEV_API_KEY`
repository secret.

## Compatibility

The public API dump under `api/` is committed and compared on every build. Until `0.1.0` is tagged it is a
review gate rather than a promise: any public-surface change must show up as a deliberate, reviewed diff rather
than a silent `apiDump`. From the first tag onward it is the published baseline.
