# Implement the logging surface

Type: task
Status: claimed
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
