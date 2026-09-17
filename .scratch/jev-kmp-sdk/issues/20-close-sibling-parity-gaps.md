# Close the three parity gaps a sibling audit turned up

Type: task
Status: open
Blocked by: 11, 12

## Question

Nothing to decide — the parent's parity audit against both sibling SDKs (v0.6.0 clones at `/tmp/ts-study/`) found
brief §5's list complete and left exactly the three surfaces below missing. Two of them are capability, not
cosmetics, and both survived nine tickets for the same reason: **the yardstick never looked there.**

**Method, so the reasoning can be checked.** The audit diffed our `api/jvm/jev-kmp.api` against
`typesafe-sdk-js/src/index.ts` plus the client at `typesafe-sdk-js/src/client.ts:234-470`, and Python's
`typesafe_sdk/__init__.py` `__all__`, then re-derived every field list from the wire (`src/types.ts`,
`_schemas/models.py`) rather than from prose. Everything §5 names matches — client, all three primitives,
`models.list()`, the retry policy's nine fields with the siblings' defaults, the twelve error classes, request-id
surfacing, `Usage(inputTokens, outputTokens)`, `ModelCard(name, description, releaseDate)`. The three below do not.

**Why nothing caught them.** All fifteen conformance cases came out of the same recon, and the two request cases
(`conformance/cases/request-primitives-string-state.json:10-31`, `error-422-detail-list.json:12-31`) carry a noul
question with `instructions` alone. The fixture set is the cross-language contract, which makes it an excellent
yardstick and a poor mirror: **whatever it does not name is invisible to it.** Adding the missing case is part of
this ticket; the lesson goes in the Answer, because it is the one that generalises.

### 1. A noul question cannot describe its yes/no outcomes (capability gap)

Both siblings can, and document it on both sides:

- **JS** — `src/types.ts:21-29`: `criteria?: {true?: EntryType; false?: EntryType}`, "Optional descriptions of the
  yes and no outcomes"; built by `noul(instructions = null, criteria?)` (`src/questions.ts:22-29`). `EntryType` is
  `string | object | array | null`, so a description can be structured.
- **Python** — `_schemas/models.py:34-36`: `class NoulCriteria(Struct): true: … = UNSET; false: … = UNSET`,
  exported publicly (it is in `typesafe_sdk/__init__.py`'s `__all__`), with
  `tests/test_questions.py::test_optional_noul_criteria` pinning one side, both, or neither.

Ours: `NoulQuestion(id, prompt)` (`Questions.kt:39-44`), the two builders (`Questions.kt:61-70`), and `toWireJson`
(`Questions.kt:154-172`) emit `{type, instructions}` and nothing else. A user porting from either sibling loses the
feature outright.

**Build:**

- A **public `NoulCriteria`** holding two optional outcome descriptions, each accepting what a prompt accepts
  (`String` or `JsonElement`; `JsonElement` already covers the object and array forms). Name it `NoulCriteria`
  because Python exports exactly that name. Kotlin has no `true`/`false` property names — they are keywords — so
  pick readable ones and map them explicitly in `toWireJson`, with the KDoc naming the wire keys.
- Add `criteria` to the two `noul` builders **as a parameter with a default**, so `noul("id", "prompt")` compiles
  unchanged. Nothing is published yet, so a signature change costs nothing; do not add a third and fourth overload
  if one defaulted parameter does it. A secondary constructor taking the two descriptions as `String?` is fine if
  it keeps the common case to one line; a family of overloads is not.
- Wire shape: `{"type": "noul", "instructions": …, "criteria": {"true": …, "false": …}}` with **an absent side
  omitted, never sent as `null`**. Both siblings' encoders do that (JS drops `undefined` keys, Python omits
  `UNSET`). This is the one place noul deliberately differs from `choice`, whose `null` option values *are* sent
  because there the key is the option — `toWireJson` already does that and must keep doing it.
- The criteria must not leak anywhere else: the id stays the enclosing map's key and never a wire field.

**Tests.** One side, both sides, neither (matching Python's `test_optional_noul_criteria`), each asserting the exact
wire object, plus a structured (object or array) description. Then **a new conformance fixture** — one case with
both sides present, declared in `conformance/manifest.json` exactly like the existing request cases — so the
fixture set finally names this surface. Follow ADR 0005 for the file's shape; ADR 0005 itself belongs to another
ticket, do not edit it.

### 2. `models.list()` cannot be given per-call overrides

- **JS** `src/resources/models.ts:15`: `list(options: RequestOptions = {})`, where `RequestOptions` is
  `{signal?, timeout?, retry?: Partial<RetryPolicy>, headers?}` (`src/types.ts:197-206`).
- **Python** `_core/client/sync/models.py:23-29`: `list(*, retry=None, timeout=None, extra_headers=None)`.
- **Ours**: `Models.list()` takes nothing — `api/jvm/jev-kmp.api` has
  `public abstract fun list (Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` — so a models call cannot
  override the timeout or the retry policy, even though `systemOne` can.

**Build:** `list(timeout: Duration? = null, retry: RetryPolicy? = null)`, meaning exactly what it means on
`systemOne`: `null` inherits the client's value, and the effective policy reaches the same `Transport.request`
parameter. **`extraHeaders` stays out** — §5 defers per-call extra headers — and JS's `signal` has no Kotlin
equivalent, because cancellation is the coroutine's. Say both in the Answer so the next audit does not re-open
them. The return type stays `List<ModelCard>`: JS returns a bare array, Python wraps it in `ListModelsResponse`,
and §12 already chose JS's shape.

### 3. The release version is not readable from code

JS exports `VERSION` (`src/version.ts`, guarded by its `check:version` script) and Python exports `__version__`
(`typesafe_sdk/_version.py`, listed in `__all__`). Ours is `internal const val SDK_VERSION = "0.1.0"`
(`Headers.kt:10-11`), visible only inside the `X-TypeSafe-SDK` header.

**Build:** make `SDK_VERSION` public, keep its name, keep it where it is, keep the KDoc. The existing
`checkVersion` task already asserts that constant equals `gradle.properties`'s version by regex over
`src/**/*.kt` (`build.gradle.kts:236-275`), so going public needs no new machinery and cannot drift — but verify
that the gate still fires (flip the constant, watch it fail, revert) rather than assuming it.

**Judgement recorded rather than acted on:** JS also exports `ENV`, its four environment-variable names. **Do not
add it.** The names are documented in the README's config section, and four public constants that must stay in step
with prose are API surface nobody asked for. Note the skip in the Answer.

**Tests.** The version constant needs no test of its own — `checkVersion` is stronger than one. Noul criteria and
`models.list` get wire tests: the criteria's exact shape, and for `models.list` the same timeout/per-call-policy
assertions `ClientTest` already makes for `systemOne`, including that `null` inherits the client's policy.

**Deliverable:** `NoulCriteria` and the `noul` parameter, the wire shape and its fixture, `models.list(timeout,
retry)`, the public `SDK_VERSION`, the tests, `./gradlew apiDump`, and the README's parity/differences section
brought back into line. `./gradlew check` green — Actions is off, so that is the only verification that runs.
