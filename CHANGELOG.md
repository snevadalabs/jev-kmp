# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version declared in `gradle.properties` must match the top heading below. The `check-version` Gradle task
enforces this, and the publish workflow runs it before anything is uploaded.

## [0.1.0] - 2026-09-17

Initial release.

- The SDK is usable end to end: `TypeSafeClient`, its environment-aware configuration, `client.models.list()`,
  and the twelve-class error tree under `com.sierranevadalabs.jev.sdk.errors`.
- Typed questions and answers for all three System One primitives — `noul`, `choice`, and `score` — where the
  question object is itself the statically typed key for reading its answer.
- Retry with the JS delay policy on Ktor's `HttpRequestRetry`, plus per-call `RetryPolicy`, `model`, and
  `timeout` overrides.
- Targets: JVM, Android, `iosArm64`, `iosSimulatorArm64`, `iosX64` (compile-only), `macosArm64`, `linuxX64`.
- A cross-language wire-conformance fixture suite in `conformance/`, and the committed
  binary-compatibility baseline at `api/jvm/jev-kmp.api`.
