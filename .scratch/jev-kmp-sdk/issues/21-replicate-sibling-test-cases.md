# Replicate the sibling test cases, and expose the resolved settings

Type: task
Status: claimed
Blocked by: 20

## Question

Nothing to decide — the parent mined every test case name out of both sibling suites, diffed them against ours, and
checked each candidate against our source. Sections A to C are what to port, and all of it is **pinning tests, not
new behaviour: every case describes what the code already does.** If a mandated test fails, **stop and report** —
that would mean our behaviour differs from the sibling, and that divergence is a decision, not something to fix
quietly. **Section D is the one exception**: it adds public API, and the parent has already made that call.

**Two of the parent's candidates were wrong, and the corrections are in place below.** Ticket 22's mutation run
measured the same lines, so items 2 and 3 now say what is actually covered.

**Method.** JS: all nine files in `typesafe-sdk-js/test/`, which is the siblings' whole regression surface and part
of their `check`. Python: `typesafe-sdk-python/tests/test_*.py`. Both v0.6.0 clones at `/tmp/ts-study/`. Ours:
thirteen files, 2,236 lines. The overlap is large — retry defaults and delay math, the error-body shapes, config
resolution, answer decoding, the typed accessors are covered on both sides — so this is a list of what is *absent*,
not a rewrite.

### A. Behaviour we have that nothing asserts

Each is a case the sibling suite carries as a regression, and the parent verified the code path exists; nothing
fails today if it breaks.

1. **A JSON error body under a missing or non-JSON `content-type` is still parsed.** JS
   `test/errors.test.ts` — "parses JSON even when content-type is missing". Ours ignores content-type entirely:
   `errors/ErrorMapping.kt:13-15`, `parseBody` tries `Json.parseToJsonElement` and falls back to the raw text. Same
   lenient rule, but all four JSON error fixtures carry `content-type: application/json` and the fifth is genuinely
   HTML, so nothing pins it. Assert the extracted message for a JSON body sent as `text/plain`.
2. **A caller cannot smuggle a protected header by spelling it differently** — **narrowed: the parent's claim that
   only exact case is tested was false.** JS `test/release-regressions.test.ts` — "replaces mixed-case defaults and
   protects every SDK header on every attempt", and "does not send a caller-supplied content type or retry count on
   GET". `Headers.kt:33-51` lowercases every name into one map, then overwrites `Authorization`, `Accept`,
   `Content-Type` (when there is a body), `X-TypeSafe-SDK`, `X-TypeSafe-Runtime`, and *removes* a caller's
   `X-TypeSafe-Retry-Count` — and `TransportTest.kt:310-312` already pins the `"accept"`/`"Accept"` pair with the
   retry-count `assertNull` right after it. So port only what remains: a differently-spelled `authorization`,
   `content-type`, `x-typesafe-sdk` or `x-typesafe-runtime` through `defaultHeaders` (our only caller-header
   channel; per-call headers are §5-deferred).
3. **Already covered — do not write this one.** The parent claimed the trailing-slash base URL was untested; ticket
   22's measurement falsified that too: `TransportTest.kt:447` hands the shared helper a trailing-slash `baseUrl`
   and `:269` asserts the exact URL. The only variant left is the env-sourced base URL, which reaches the same
   resolution — add it only if `ConfigTest` has a natural home for it.
4. **`maxRetries = 0` disables retrying.** JS `test/reliability.test.ts` — "per-call maxRetries overrides the
   client, and 0 disables retries". The retry paths are well covered; the zero case is not, and it is the one a user
   reaches for when they specifically do not want the SDK retrying.
5. **A cancelled scope never reaches the engine.** JS `test/retry.test.ts` — "rejects immediately if the signal is
   already aborted". Our abort signal is the coroutine, so a call on an already-cancelled scope must produce no
   request at all. Ticket 13 pins cancellation *during* a delay and *after* headers; the pre-cancelled case is
   unpinned.
6. **An empty `200` body fails loudly rather than reading as an empty result.** JS
   `test/release-regressions.test.ts` — "returns a null-body response without trying to read a stream". Ours must
   neither hang nor invent an empty answer map: assert the failure and the path it names, for `systemOne` and for
   `models.list()` (whose shape error already names the endpoint —
   `modelsListNamesTheEndpointWhenTheShapeIsWrong`). Ticket 22's measurement points straight at this one:
   `decodeSystemOneResponse` carries 11 survivors on its `parseBody(...) as? JsonObject` guards, and a test sending
   `""` as a 200 body kills them.
7. **`Retry-After` is absent, not zero, when the server sends none.** JS `test/reliability.test.ts` — "is undefined
   when the server sent no Retry-After". Only the parsed values are asserted today (`ErrorMappingTest.kt:80`,
   `ClientTest.kt:161`); a sentinel `0` would make an inherited policy look like a server instruction.
8. **Each attempt gets its own timeout budget.** JS `test/reliability.test.ts` — "retries after a timeout, each
   attempt getting its own timeout". `aBodyThatStallsAfterA200TimesOutOnEveryConsumerPath` stalls every attempt but
   asserts only the final error, so the count is unpinned: add the attempt assertion to a stalled-body retry
   (`assertEquals(3, server.recordedRequests.size)` in the shape
   `aConnectionDroppedMidBodyIsRetriedAndTheFinalCauseSurvives` already uses).
9. **A `state` of `JsonNull`.** Python `tests/test_types.py` — "test_json_value_and_state_exclude_top_level_none".
   Python's model drops a `None` state; JS's request type requires `state` and sends it; ours always sends it.
   Check the fixtures and record which rule the wire wants. `record: N/A` unless they say otherwise.

### B. The unpinned mutants ticket 22 measured

`./gradlew pitestJvm` exists on `main` now and is this ticket's feedback loop. Its baseline: **test strength 74.6%
(534 of 716 covered mutants) against 95.33% Kover line coverage** on the same compilation — that gap is the point of
this section. Its non-equivalent survivors cluster like this, and the per-mutant classification is in
`research/22-mutation-testing-evaluation.md`:

| where | survivors | the shape |
| --- | --- | --- |
| `ErrorMapping.kt` | 37 | malformed-input decoding: the right key with the wrong JSON type, an array where an object is expected, the `Retry-After` boundaries |
| `Client.kt` | 26 | the same, plus the `decodeSystemOneResponse` guards behind item 6 |
| `Answers.kt` | 20 | the `as? JsonObject` / `as? JsonPrimitive` guards on answer fields |

**Read the research file before writing any of these.** An unpinned mutant is not automatically a behaviour worth
pinning; the equivalent ones are named there. Port the clusters that describe a contract a user can actually hit — a
response field arriving with the wrong JSON type is the clearest — and say in the Answer which clusters were left
and why.

**Then report the delta.** Run `./gradlew pitestJvm` before and after the ported tests and put both strengths in the
Answer. That is this ticket's non-vacuity proof: the number has to move, and a test that kills nothing it was written
for documents rather than checks.

### C. Two sibling cases that are not gaps — record the reasoning, do not port

- **Mutable caller collections.** JS copies `httpStatuses` into a fresh `Set` and tests it ("copies caller-owned
  status sets when constructing a client", "isolates the policy and status set between default clients"). Kotlin's
  `Set<Int>` and `Map` are read-only *by type*, so the copy JS needs is not needed here; a caller mutating a
  collection they handed us is a hostile cast, not a supported path. Same for the map behind
  `SystemOneResponse.answers` (Python `tests/test_responses.py` — "test_cached_groups_cannot_be_reassigned"). Do not
  add a defensive copy and do not add a test that pins the wart.
- **`ModelCard` dropping unknown fields.** JS's plain interface passes an extra field through; our decoder drops it,
  and `modelsListReturnsTheCardsAndIgnoresExtraFields` pins the drop. The siblings disagree with each other on the
  shape anyway (`ListModelsResponse` vs the bare array). Deliberate; no test.

### D. A gate gap, not a test gap

**One KDoc example is compiled nowhere.** Python runs its docstring examples as tests (`tests/test_docs.py` —
`test_python_doctests`), and ticket 14's Gradle task extracts the README's fenced `kotlin` blocks into a generated
`commonTest` source file — but it reads `README.md` only. `Questions.kt:19` carries a fenced `kotlin` block in KDoc,
so an example a reader copies out of the IDE can rot silently while the identical README example is gated. Extend
the extraction task to read fenced `kotlin` blocks out of `src/**/*.kt` KDoc too — same regex, one more input — and
keep the non-vacuity check it already has passing. If that turns out to be more than a few lines, report why
instead of building machinery.

### E. The client cannot report its resolved settings (build it)

**JS exposes the resolved settings; ours exposes none.** JS has `readonly baseURL, defaultModel, logLevel, retry,
timeout, defaultHeaders`, pinned by "defaults to the SDK policy and exposes the resolved policy on the client". Our
`TypeSafeClient` has `models` and `systemOne` only, so after `explicit → env → default` a caller cannot find out
what actually took effect — which is the exact question our three-tier resolution invites ("did
`TYPESAFE_BASE_URL` land?"). §5's parity list does not name introspection, so this was the parent's call to make:
**add them.**

- Six read-only properties on `TypeSafeClient` returning the **resolved** values — after env and defaults — for
  `baseUrl`, `defaultModel`, `timeout`, `retry`, `logLevel`, `defaultHeaders`. Match JS's set: those six, no more.
- **Never the apiKey.** JS keeps it in a `#private` field for exactly this reason. Do not expose it, and do not
  expose the input `TypeSafeConfig` to get these six: that object carries the key *and* the unresolved input (the
  `null` the env then filled), which is the opposite of the question being asked. `TypeSafeConfig.apiKey` is public
  today as pre-existing surface — leave that alone, but do not widen it.
- KDoc each property as the *effective* value with the resolution order stated once, and keep `explicitApi`
  satisfied.
- **Tests.** For the two values env can move (`baseUrl`, `defaultModel`) plus `logLevel`, assert all three sources:
  the default with nothing set, the env value with only the env set, and the explicit value beating the env. For
  `retry`, assert it reports the default policy when the caller passed none — the sibling's own case. `timeout` and
  `defaultHeaders` have no env var, so one assertion each is enough.
- `./gradlew apiDump`, and a README line in the parity/differences section: JS exposes the same six, and the apiKey
  is exposed nowhere.

**Tests.** The cases above, in the file each behaviour already lives in (`ClientTest`, `TransportTest`,
`ErrorMappingTest`, `QuestionModelTest`, `WireTransportTest` as appropriate) — `kotlin.test` only, no new
dependency, no new framework. Each ported case carries a one-line comment naming the sibling suite it came from,
because the reason it exists is not visible from the code.

**Deliverable:** the ported tests, the six resolved-settings properties on `TypeSafeClient` with their tests and
`./gradlew apiDump`, the KDoc-snippet extension of the existing Gradle task, and an Answer saying which sibling
files were mined, what was ported, what was checked and already covered, and what was deliberately not ported and
why. `./gradlew check` green.
