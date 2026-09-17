# Implement the logging surface

Type: task
Status: resolved
Blocked by: 12

## Question

Nothing to decide — brief §5's carve-out already fixes the contract and the parent settled the three mechanics the
tickets below it left open. This is the last unimplemented promise in §5.

**The gap.** *Implement transport and retry* built the retry seam (`Transport` takes `log: (String) -> Unit`) and
subscribes to `HttpRequestRetryEvent`, but nothing ever passes a sink, so the default no-op swallows every line —
logging cannot be turned on. And §5's other half was never built: the per-call line carrying method, path, status,
duration and request id. §5 says "logging is off by default; **when enabled** it logs method, path, status, duration
and request id and **never headers or bodies**", and its carve-out says logging is **not deferrable**. Both sentences
are currently unmet.

**Decided by the parent, because the tickets below this one each deferred it:**

1. **`LogLevel`, public, with the siblings' exact vocabulary** — `debug`, `info`, `warn`, `error`, `off` — because
   `TYPESAFE_LOG_LEVEL` is a sibling-named surface and a value that works against Python must not throw here. A
   public `LogLevel` enum is not the deferred "pluggable `Logger` abstraction"; that stays deferred, so **there is no
   public `logger` config field**.
2. **`logLevel` joins the config with the same resolution as every other field**: `explicit → TYPESAFE_LOG_LEVEL →
   default`, blank/whitespace ignored, an unparseable value rejected with a message naming the variable. **The default
   is `off`** — §5 says off by default, and that is a deliberate divergence from JS's `warn` default (goes in the
   README parity list).
3. **Level semantics, stated honestly rather than invented:** `info` emits the per-call line and the per-retry line;
   `debug` emits the same lines today and is reserved for the deferred abstraction's byte-level logging; `warn` and
   `error` emit nothing and are accepted only for sibling parity — our failures are thrown, not logged. Say this in
   the KDoc rather than leaving a reader to infer it from silence.
4. **The sink is `internal` and defaults to `println`**, prefixed `[typesafe-sdk]` like JS's `consoleLogger`. On
   Android stdout is redirected to logcat, so one sink covers all four platforms without an `expect`/`actual` pair.
   Tests inject their own sink, the same way they already inject `sleeper` and `random`.
5. **Duration comes from an injected `kotlin.time.TimeSource`**, defaulting to `TimeSource.Monotonic`, so
   `kotlin.time.TestTimeSource` pins the number in a test with no new dependency and no clock seam.

**Then build:**

- The per-call line: **method, path, status, duration and request id**, one line, after the response is read. Nothing
  else — no headers, no bodies, no query string beyond the path, no `Authorization`, no `apiKey`. The `apiKey` must
  not be able to reach it by construction, and a test must assert that against the same leak triple the client's
  `toString()` test uses.
- The per-retry line already emitted from `HttpRequestRetryEvent` becomes reachable through the same level gate and
  the same sink.
- The line must be emitted for failures too, at the level that is on, since a failed call is when a reader wants it
  most — with the status it failed on.
- `TYPESAFE_LOG_LEVEL` documented in the README's config section, and the "off by default" divergence added to the
  differences list.

**Tests.** Off by default asserts *silence*, not just absence of output — an empty sink after a real call. Level
gating at each of the five values. The exact line for a pinned duration and request id. The leak assertions. And one
test that an unparseable `TYPESAFE_LOG_LEVEL` fails loudly rather than silently disabling logging.

Deliverable: `LogLevel`, the config field and its resolution, the sink seam, both lines, the tests, the api dump, and
the README notes.

## Answer

Built the whole deliverable. **Shipped, unmerged:** [PR #10](https://github.com/snevadalabs/jev-kmp/pull/10).

**Code.** `LogLevel` (`Debug`/`Info`/`Warn`/`Error`/`Off`, public, KDoc states honestly what each emits);
`TypeSafeConfig.logLevel` resolved `explicit → TYPESAFE_LOG_LEVEL → Off`, blank ignored, an unrecognised value
rejected with a `JevError` naming the variable (siblings' five names, read case-insensitively, so `INFO` — which
Python accepts — does not throw); `internal fun logSink(level, sink = ::println)` as the one gate and the one
place `[typesafe-sdk]` is added. The transport takes `log` and `timeSource: TimeSource = TimeSource.Monotonic`;
the retry subscription keeps its line through the same sink.

**Lines.** `[typesafe-sdk] POST /v1/systemone <- 200 in 42ms (request req_123)`, request id omitted when the
server sent none; a call that never got a response writes `… <- failed in 42ms (IOException)` — the class where a
response would put its status, since the ticket's "failures" rule only names status-bearing ones and silence on an
`APIConnectionError` would be the wrong kind of quiet; the retry line is unchanged (`retry 1: 503`). Nothing else
is formatted: no headers, no bodies, no query string (`logPath` strips one), so the `apiKey` cannot reach a line
by construction.

**Evidence.** `./gradlew check` → `BUILD SUCCESSFUL` (ktlint, `apiCheck`, `checkVersion`, `checkJvmBytecode`,
`koverVerify` at the 94 floor, `dokkaGenerate`, `allTests`): 95/95 tests on `macosArm64Test`,
`iosSimulatorArm64Test` and `testAndroidHostTest`, 104/104 on `jvmTest`, 0 failures. `./gradlew apiDump` added
`LogLevel` and `TypeSafeConfig.getLogLevel` to `api/jvm/jev-kmp.api` and nothing else public. The suite is
non-vacuous, checked rather than assumed: flipping the gate to `if (true)` failed exactly
`offByDefaultIsSilentForARealCall`, `theFiveLevelsGateEveryLine` and `theSinkPrefixesTheLineAndOnlyWhenTheLevelIsOn`;
the mutation was reverted.

**Left undone, deliberately.** No public `logger` field and no pluggable `Logger` abstraction — deferred, per the
parent; `debug` emits the same two lines as `info` until that abstraction owns byte-level output; `warn`/`error` emit
nothing for parity, because our failures are thrown. The retry line is still formatted when the level is off (the
gate is in the sink), which costs one string per retry.

**One process note, not a question.** `CHANGELOG.md` has no `Unreleased` heading — `gradle.properties` is `0.1.0`
and `checkVersion` requires the top heading to name that version — so the user-noticeable line went under
`## [0.1.0]`, which is itself unreleased. `local.properties` (the Android SDK path) is required for
`testAndroidHostTest` and stays gitignored.
