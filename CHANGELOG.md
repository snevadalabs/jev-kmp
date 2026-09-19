# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version declared in `gradle.properties` must match the top heading below: a `-SNAPSHOT` version requires an
`Unreleased` heading, and a release version requires the heading to name it. The `check-version` Gradle task
enforces this, and the publish workflow runs it before anything is uploaded.

## [Unreleased]

Initial release, not yet tagged: no artifact has been published to Maven Central.

- The SDK is usable end to end: `TypeSafeClient`, its environment-aware configuration, `client.models.list()`,
  and the twelve-class error tree under `com.sierranevadalabs.jev.sdk.errors`.
- The client reports the settings that actually took effect — `baseUrl`, `defaultModel`, `timeout`, `retry`,
  `logLevel` and `defaultHeaders` — after `explicit → environment → default`. The API key is exposed nowhere.
- Typed questions and answers for all three System One primitives — `noul`, `choice`, and `score` — where the
  question object is itself the statically typed key for reading its answer. A `noul` question describes its yes
  and no outcomes with `NoulCriteria`.
- Retry with the JS delay policy on Ktor's `HttpRequestRetry`, plus per-call `RetryPolicy`, `model`, and
  `timeout` overrides; `client.models.list()` takes the same per-call `timeout` and `retry`.
- Opt-in logging: `LogLevel` and `TypeSafeConfig.logLevel` (or `TYPESAFE_LOG_LEVEL`), off by default, writing one
  line per call and one per retry and never a header, a body or the API key.
- Targets: JVM, Android, `iosArm64`, `iosSimulatorArm64`, `iosX64` (compile-only), `macosArm64`, `linuxX64`.
- A cross-language wire-conformance fixture suite in `conformance/`, and the committed
  binary-compatibility baseline at `api/jvm/jev-kmp.api`.
