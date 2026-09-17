# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version declared in `gradle.properties` must match the top heading below. The `check-version` Gradle task
enforces this, and the publish workflow runs it before anything is uploaded.

## [Unreleased]

- The SDK is usable end to end: `TypeSafeClient`, its environment-aware configuration, `client.models.list()`,
  and the twelve-class error tree under `com.sierranevadalabs.jev.sdk.errors`.
