# Implement the question and answer model

Type: task
Status: resolved
Blocked by: 06, 09

## Question

Nothing to decide beyond what *Prototype the typed question API* settles — build the public typed surface.

**Questions.** `Noul`, `Choice`, `Score` as `Question<T : Answer>` values, produced by the builder functions the prototype lands on, with the `JsonElement` core and the `String`/`Map<String, String?>` convenience overloads from the brief. Keys are typed so that the answer type is known statically. The raw map form must keep working, since it is what a Python or JS user reaches for first.

**Answers.** The sealed hierarchy — `NoulAnswer`, `ChoiceAnswer`, `ScoreAnswer`, `UnknownAnswer(type, raw)` — decoding from the wire. Decoding rules that must be explicit and tested:

- Unknown answer `type` decodes to `UnknownAnswer` rather than throwing, and the raw payload is preserved.
- Unknown *extra fields* on a known answer are tolerated and ignored, matching both siblings' forward-compatibility posture.
- `score` `legend` and `probabilities` keys arrive as stringified integers and must surface as integers.
- A *malformed known* answer (missing `noul`, `choice` without `probabilities`) fails with a validation error naming the exact field path, the way Python's `field_path` does. Write the path-building helper once, not per-field.
- An answer key present in the response but absent from the request, and vice versa: decide and test the behaviour rather than letting it fall out.

**Validation, client-side, exactly two checks:** the question map is non-empty, and score criteria has at least two levels. Everything else is forwarded to the server. Test that an invalid-but-well-formed question reaches the transport rather than being rejected locally — the sibling suites both assert this and it is easy to over-validate by accident.

**Tests.** Type-level guarantees are half the deliverable here, so the tests must fail to compile when the guarantee is broken: a caller reading a `ChoiceAnswer` off a noul key must not compile, and neither must reading an answer off a key that was never sent. Whatever mechanism the prototype lands on, leave behind the smallest thing that turns a regression in the typing into a build failure.

Deliverable: question and answer sources, the field-path validation helper, and the tests.

## Answer

**Built, gated, green.** Branch `issue-11-implement-question-answer-model`. No question to report: every open
point was already settled by the brief, an ADR, or ticket 06's `## Answer`.

### What landed

`src/commonMain/kotlin/com/sierranevadalabs/jev/sdk/`

- **`Questions.kt`** — `sealed interface Question<T : Answer>` and the `NoulQuestion`/`ChoiceQuestion`/
  `ScoreQuestion` `data class`es; the six `noul`/`choice`/`score` builder overloads (`String` and `JsonElement`,
  plus the `Map<String, String?>` options form); `internal questionAccepts`, the exhaustive `when` over the
  sealed hierarchy ticket 06 demanded in place of the silent `as? T`; `internal validateQuestions`, the two local
  checks of ADR 0006; `internal Question<*>.toWireJson()`, since the question types are the only place that knows
  their own wire shape.
- **`Answers.kt`** — `sealed interface Answer` plus `NoulAnswer`, `ChoiceAnswer`, `ScoreAnswer`,
  `UnknownAnswer(type, raw)`; `internal decodeAnswers(JsonObject)`, the `fieldPath` helper, and the `internal
  ResponseValidationException(fieldPath, …)` a malformed known answer throws.
- **`SystemOneResponse.kt`** — `answers`, `operator fun <T : Answer> get(Question<T>): T`, `answerOrNull`, and
  `AnswerTypeMismatchException : ClassCastException`.

`src/commonTest` — 24 new tests (10 / 7 / 7). Every decode case the fixtures cover is read from
`conformance/cases/` rather than restated; the malformed fixture's own `expect.field`
(`answers.urgent.noul`) is asserted against `ResponseValidationException.fieldPath`.

### The three bends the wire forced on the prototype's sketch

The prototype never decoded JSON, so its `ScoreAnswer` field types were a guess. Its `## Answer` defers the
real decode to this ticket, and the wire settles all three:

| Field | Prototype | Here | Evidence |
|---|---|---|---|
| `score` | `String` | `Double` | `response-mixed-primitives` carries `"score": 1.7`; Python `ScoreAnswer.score: float` ("may fall between the integer rubric levels"), JS `score: number` |
| `legend` | `List<String>` | `Map<Int, JsonElement>` | the wire sends `{"0": "can wait", …}`; ADR 0005: "`legend` and `probabilities` decode to *integer*-keyed maps" |
| `probabilities` | `Map<String, Double>` | `Map<Int, Double>` | same |

A stringified `score` *value* (as opposed to a key) is rejected with a field path rather than coerced, matching
Python's `float` decode.

### Decisions this ticket was told to make

- **An answer key in the response but not the request** is kept in `answers`; the request is never consulted, so
  nothing errors.
- **A key the request asked for but the response omitted** is simply absent: `get` throws
  `NoSuchElementException` (not `AnswerTypeMismatchException` — the network *can* produce this), and
  `answerOrNull` returns `null`. That keeps `get` non-null and the `Map`-flavoured pair intact.
- **`AnswerTypeMismatchException` is a `ClassCastException`**, applying ticket 06's recorded call. One line to
  change if the owner wants another base.
- **"The raw map form must keep working"** is read as the `Map<String, String?>` / `Map<String, JsonElement>`
  options overloads, not the sibling container literal `mapOf("x" to choice(…))`. Ticket 06 measured that form
  as non-compiling and accepted the cost; adding a `Map<String, Question<*>>` overload would reintroduce the id
  in two places, which is the exact thing ticket 16 deleted.
- **`SystemOneResponse` carries `answers` only.** `model`, `usage`, `requestId`, `status` and `headers` belong to
  ticket 12's delivered surface; `Usage` is still unspecified, so nothing here invents it.

### Type-level guarantee

The two typed reads at `SystemOneResponseTest.typedAccessReturnsTheConcreteAnswer` assign `get`'s result to
concrete answer types, so a widened accessor or a re-pointed `Question<T>` stops the test source set compiling.
The **JVM dump cannot carry generics** (`Question<T>` and `get<T>` erase to `Question`/`Answer`), so `apiCheck`
is not the type pin — it catches an erased-shape change such as a new `get(String)` overload. A literal
negative-compilation test needs its own compile-and-expect-failure task; the `ponytail:` comment on that class
names the ceiling and why it was not paid for.

### Left undone, by design

- **`prototype/` is deleted** (module, `settings.gradle.kts` include, `apiValidation` block, and the api dump it
  never had). Ticket 06's `## Answer` scheduled exactly this: *"must be deleted … when the real model lands"*.
  The stale `String`-typed `ScoreAnswer` it contained is now contradicted by the real one.
- **No CHANGELOG line.** `0.1.0` is unpublished and nothing is reachable yet; matches tickets 06 and 10.
- **No end-to-end "invalid-but-well-formed question reaches the transport" test.** There is no `systemOne` to
  reach the transport with until ticket 12. `QuestionModelTest.forwardsEveryWellFormedQuestionThatIsNotOneOf…`
  pins the validator's pass side (no options, duplicate levels, blank id all pass) so over-validation cannot
  creep in.

### Gate

`./gradlew check` (`ANDROID_HOME` pointed at the local SDK) → **BUILD SUCCESSFUL**, 51 tasks, and the same 24
new tests green on all four runnable targets: `jvmTest`, `testAndroidHostTest`, `macosArm64Test`,
`iosSimulatorArm64Test` — 57 tests each, 0 failures. `linuxX64Test` compiles and links and is SKIPPED on a macOS
host, as CI's Linux lane expects. Both workflow lanes run explicitly and green:
`ktlintCheck checkVersion checkJvmBytecode jvmTest linuxX64Test` and
`apiCheck iosSimulatorArm64Test macosArm64Test dokkaGenerate`. `api/jvm/jev-kmp.api` reviewed and committed as a
deliberate diff. No simulator contention.

### Picked up by ticket 12

- **`internal ResponseValidationException.fieldPath`** is the field-path carrier; map it into the public
  `APIResponseValidationError` when the error tree lands. `conformance/cases/response-malformed-answer.json`
  expects `field: answers.urgent.noul` and already gets it.
- **`internal validateQuestions(questions)`** is the two-check request-boundary validation; call it before the
  first request and keep its `IllegalArgumentException` (ADR 0006).
- **`internal Question<*>.toWireJson()`** is the per-question wire object, id excluded — the id becomes the key of
  the `questions` map. `QuestionModelTest.questionIdsBecomeTheWireQuestionKeysWithTheFixturesRequestShape` pins the
  whole shape against the request fixture, so the envelope only needs the `associate`.
