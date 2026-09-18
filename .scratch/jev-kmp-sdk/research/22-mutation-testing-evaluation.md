# 22 — Evaluate mutation testing as a way to find what coverage cannot see

Recon for [ticket 22](../issues/22-evaluate-mutation-testing.md). Every number below was produced by a run on
2026-09-17 in this worktree, on macOS arm64, JDK 21.0.8 (Temurin), Gradle 9.3.0.

Tooling: `info.solidsoft.pitest` 1.19.0 (the Gradle plugin) driving `org.pitest:pitest-command-line` 1.22.1.
Both versions came from the Plugin Portal's and Maven Central's `maven-metadata.xml`; the Gradle plugin and PIT
core version independently (1.19.0 is the newest plugin, 1.22.1 is the PIT it runs, and the plugin's own
`DEFAULT_PITEST_VERSION` is 1.22.1).

## 1. Getting a mutation tool running — the integration, and what it cost

**The plugin cannot attach itself to a KMP module.** `PitestPlugin.apply` registers its `pitest` task inside
`project.plugins.withType(JavaPlugin).configureEach { … }`. A Kotlin Multiplatform module never applies the
`java` plugin, so the task is never created. Measured: with the plugin on the classpath and nothing else,
`./gradlew tasks --all | grep -i pit` returns nothing (exit 1). This is not a Gradle-9 incompatibility — the
build configures clean under 9.3.0 and the plugin's own minimum is 8.4; it is the registration gate.

**Workaround used:** register the plugin's own `PitestTask` class against the JVM compilation's classpath
(`build.gradle.kts`, the `pitestJvm` task). The task is a `JavaExec` subclass whose `@TaskAction` builds the PIT
command line purely from its own properties, so it needs no `JavaPlugin`; the few properties the plugin normally
defaults (`launchClasspath`, `additionalClasspath`, `mutableCodePaths`, `additionalClasspathFile`,
`defaultFileForHistoryData`) are set explicitly. `sourceDirs` points at `src/commonMain/kotlin` and
`src/jvmMain/kotlin`; the `pitest` configuration holds `org.pitest:pitest-command-line` and nothing else.

Two traps found on the way, both recorded because they cost a run each:

- `PitestTask` writes its classpath file (`additionalClasspathFile`) itself and does not create the parent
  directory, so pointing it at `build/pitest/pitClasspath` fails with
  `java.io.FileNotFoundException: …/build/pitest/pitClasspath (No such file or directory)`. It is left at the
  build-directory root.
- `additionalClasspath` must carry the main classes directory as well as the test runtime classpath;
  `KotlinCompilation.runtimeDependencyFiles` is *dependencies only* and does not include the compilation's own
  output.

**PIT's line numbers are unreliable on Kotlin.** 44 of 749 mutants (6%) report a line number past the end of
their source file — `Headers.kt` is 53 lines and carries mutants reported at line 55 and 57; `RetryPolicy.kt` is
70 lines and carries mutants at 73. The cause is Kotlin's inline functions: the bytecode is copied into the
caller, and the source-map attribution lands outside the file. Mutant *method* names stay correct, so
attribution has to be read per method, not per line. This is a firm limit on using the HTML report to navigate
to the offending code. (Arcmutate's plugin exists partly to fix exactly this; see §6.)

Timings (wall clock, `./gradlew pitestJvm`):

| step | mutants | PIT time | Gradle wall clock |
|---|---|---|---|
| baseline, `STRONGER` | 749 | 103 s | 1 m 45 s |
| baseline, repeated (`--rerun-tasks`) | 749 | — | 1 m 43 s |
| baseline with the conformance package excluded | 749 | — | 1 m 46 s |
| `STRONGER` + `NON_VOID_METHOD_CALLS` | 1417 | — | 2 m 21 s |

## 2. Tool-fit table

| Question | Answer | Evidence |
|---|---|---|
| Does PIT see Kotlin bytecode mutants usefully? | **Yes.** It mutates the SDK's own compiled classes and reports per-mutant kill/survive with the killing test. | 749 mutants over 40 classes in 14 files; `<killingTest>` populated for 527 of them |
| How much of the output is mechanical noise? | **7%** — 49 of 749, of which 23 survive. Far lower than the ticket anticipated. | rules in §5 |
| Bridge methods / `$default` | `$default` bridges produced 0 mutants; no bridge-method mutants at all | census by mutated method name |
| `Intrinsics.checkNotNullParameter` | 0 mutants — PIT's `STRONGER` set does not mutate it | census by description |
| `Intrinsics.checkNotNullExpressionValue` | 5 mutants, 4 survive — always equivalent | §5 bucket M |
| `ResultKt.throwOnFailure` (coroutine bridges) | 17 mutants, 8 survive — mechanical | §5 bucket M |
| Coroutine state machines (`invokeSuspend`) | 15 mutants, 6 of them `NO_COVERAGE` | §5 |
| `when` over sealed types | handled; the `when` in `decodeAnswer` produces normal conditional mutants, not junk | `Answers.kt decodeAnswer` |
| `EmptyObjectReturnValsMutator` on Kotlin `Unit` lambdas | 12 mutants, 11 survive — equivalent by construction, all noise | §5 bucket M |
| Run-to-run stability | **6 of 749 mutants (0.8%) flipped status** between two runs of the identical config; test strength moved 534→533 detected of 716 | run1 vs run4 XML diff |
| Report navigability | 6% of mutants misattributed to impossible lines | §1 |

**Conclusion on the tool:** plain PIT on Kotlin bytecode is usable here. The noise ratio (7%) is low enough that
a human can read the survivor list directly, and the dominant noise form is a single well-understood class
(`EmptyObjectReturnValsMutator` on `Unit` lambdas, `ResultKt.throwOnFailure`, `checkNotNullExpressionValue`),
not a scattering of arbitrary junk.

## 3. Baseline

Config that produced it — the committed `pitestJvm` task: `targetClasses` and `targetTests` both
`com.sierranevadalabs.jev.sdk.*`, `sourceDirs` `src/commonMain/kotlin` + `src/jvmMain/kotlin`, `mutators
STRONGER`, `verbosity VERBOSE`, `timestampedReports false`, `outputFormats XML,HTML`, threads =
`availableProcessors`, `failWhenNoMutations true`, launched over the JVM test compilation's runtime classpath,
minions given `-Dtypesafe.live=false`.

```
./gradlew pitestJvm          # -> build/reports/pitest/mutations.xml
```

| | |
|---|---|
| Generated | 749 |
| Killed | 527 |
| Timed out (counted as detected) | 7 |
| Survived | 182 |
| No coverage | 33 |
| **Detected / covered (test strength)** | **534 / 716 = 74.6%** |
| PIT's printed headline "mutation score" | 71% — PIT divides by all 749, uncovered mutants included |
| Line coverage PIT measured over the mutated classes | 581/610 = 95% |
| Kover line coverage over the same compilation (the gate) | 24 missed / 490 covered = **95.33%**, floor 94 |
| Test executions | 4221 (5.64 per mutant) |

**This is the headline number the ticket was after.** The line-coverage gate sits at 95% and 25% of the mutants
it permits still live. Covered is emphatically not asserted.

### Per-class breakdown

The six files the ticket names, then every file:

| class | generated | killed | survived | no coverage | score (killed / covered) |
|---|---|---|---|---|---|
| `errors.ErrorMappingKt` | 129 | 88 | 39 | 2 | 69% |
| `TransportKt` | 54 | 29 | 17 | 4 | 58% |
| `Transport` | 42 | 27 | 10 | 4 | 71% |
| `RetryKt` | 83 | 70 | 12 | 0 | 84% |
| `RetryPolicy` | 56 | 47 | 9 | 0 | 84% |
| `ConfigKt` | 27 | 25 | 2 | 0 | 93% |
| `ResolvedConfig` | 19 | 16 | 3 | 0 | 84% |
| `TypeSafeConfig` | 9 | 5 | 3 | 0 | 56% |
| `HeadersKt` | 17 | 10 | 7 | 0 | 59% |
| `AnswersKt` | 66 | 46 | 20 | 0 | 70% |
| `ClientKt` (+ 5 lambdas) | 49 | 24 | 23 | 2 | 51% |
| `TypeSafeClientImpl` (+ lambda) | 37 | 29 | 8 | 0 | 80% |
| `ModelsKt` / `ModelsApi` / `ModelCard` / `Usage` | 78 | 28 | 17 | 13 | 62% / 74% |
| `QuestionsKt` | 31 | 28 | 3 | 0 | 90% |
| `LoggingKt` | 28 | 23 | 5 | 0 | 82% |
| `SystemOneResponse` | 15 | 12 | 2 | 1 | 86% |
| `errors.Errors` (5 classes) | 5 | 4 | 1 | 0 | 80% |
| `Platform_jvmKt` | 2 | 1 | 1 | 0 | 50% |

By source file:

| source | generated | killed | survived | no coverage | score |
|---|---|---|---|---|---|
| `ErrorMapping.kt` | 129 | 88 | 39 | 2 | 69% |
| `Transport.kt` | 109 | 61 | 27 | 16 | 66% |
| `Client.kt` | 86 | 53 | 31 | 2 | 63% |
| `Retry.kt` | 83 | 70 | 12 | 0 | 84% |
| `Answers.kt` | 76 | 51 | 20 | 5 | 72% |
| `RetryPolicy.kt` | 56 | 47 | 9 | 0 | 84% |
| `Config.kt` | 55 | 46 | 8 | 0 | 84% |
| `Models.kt` | 51 | 28 | 17 | 6 | 62% |
| `Questions.kt` | 36 | 33 | 3 | 0 | 92% |
| `Logging.kt` | 29 | 23 | 5 | 1 | 82% |
| `Headers.kt` | 17 | 10 | 7 | 0 | 59% |
| `SystemOneResponse.kt` | 15 | 12 | 2 | 1 | 86% |
| `Errors.kt` | 5 | 4 | 1 | 0 | 80% |
| `Platform.jvm.kt` | 2 | 1 | 1 | 0 | 50% |

The two files the ticket calls out for "where being wrong costs money or debugging time" are the *worst* two of
the six: `ErrorMapping.kt` at 69% and `Headers.kt` at 59%. `Retry.kt` — the file everyone assumes is the hard
one, and the one with the most test effort behind it — is the best covered at 84%. Mutation testing disagrees
with intuition about where the risk is.

## 4. The pay-off check: survivors, and the ticket-21 overlap

### 4a. Every surviving mutant is classified

Buckets, applied by rule (§5) and listed in full in the appendix:

| bucket | survivors | share |
|---|---|---|
| **U — unpinned behaviour** (some input distinguishes it; no test uses one) | 157 | 73% |
| **D — dead code** (`NO_COVERAGE`: no test executes the mutated line) | 33 | 15% |
| **M — mechanical / compiler-generated** | 23 | 11% |
| **E — equivalent** (no input distinguishes it) | 2 | 1% |

The 33 `NO_COVERAGE` mutants sit on lines the Kover floor does not see, because Kover measures what its own
instrumentation covers and PIT measures what its minions execute — and because a handful are on `getX()`
accessors of data classes and on `ModelsApi.decodeModelCard`'s error branch that no fixture reaches.

### 4b. The ticket-21 overlap, as a number

**0 of the 3 behaviours ticket 21 names produced a surviving mutant that corresponds to them.** Two of the
three methods do carry survivors, but none is the behaviour in question:

| # | ticket 21 item | survivors in the named method | survivors that match the item | why |
|---|---|---|---|---|
| 1 | `ErrorMapping.parseBody` parses JSON under a non-JSON `content-type` | **4** (all on the empty-body / null path) | **0** | the behaviour is an *omitted check*: there is nothing in the bytecode for `parseBody` to mutate. `content-type` is never read, so no mutant can remove reading it. |
| 2 | `Headers.assembleHeaders` lowercases names so a caller cannot smuggle a protected header; a caller's retry count is dropped | **4** behavioural (default headers dropped at L42; the `Content-Type` merge at L48) + 3 mechanical | **0** | `name.lowercase()` and `byLowercaseName.remove(…)` are *value-returning* method calls. `NON_VOID_METHOD_CALLS` is not in the `STRONGER` group (see §6), so PIT generates no mutant on either line at all. |
| 3 | `Transport.joinUrl` trims a trailing slash off the base URL | **0** | **0** | same: `trimEnd`/`trimStart` are value-returning, so `STRONGER` seeds exactly one placeholder mutant in `joinUrl` (`EmptyObjectReturnValsMutator`), which is killed trivially. |

So the direct answer to the ticket's question — *would mutation testing have caught what this project's suite
missed?* — is **no, not for the three holes that prompted the ticket.** Mutation testing finds implementations
that are wrong; it cannot find a check that was never written, and `STRONGER` cannot mutate a value-returning
call whose result is used.

### 4c. Where it *does* line up with a human finding

One genuine overlap, and it is a real one:

- **Ticket 21 item 6 — "an empty `200` body fails loudly rather than reading as an empty result."** The
  `decodeSystemOneResponse` guards on `Answers.kt`/`Client.kt` report 2 survivors each, and 11 more in
  `decodeSystemOneResponse` generally, all `RemoveConditionalMutator` on the `parseBody(...) as? JsonObject ?:`
  and `payload["answers"] as? JsonObject ?:` null/instance checks. A test sending `""` as the 200 body and
  asserting the failure and the field path would kill them. That is exactly item 6, found by a machine.

Related, and the more useful finding: the survivors in `ErrorMapping` cluster hard on **malformed-input paths
the fixtures never travel** — `asStringOrNull`, `objectAt`, `doubleAt`, `stringAt`, `detailField`,
`validationEntry`, `messageFrom`, `asPublicError`. The suite pins one malformed answer
(`aMalformedAnswerFailsWithTheFieldPath`) and the four error-body shapes, and the remaining combinatorics —
right key, wrong JSON type; array where object expected; negative/non-finite `Retry-After`; a
`CancellationException` reaching `asTransportFailure` — are all unpinned. That is the shape of hole mutation
testing is good at, and it is where a follow-up should spend its time.

### 4d. Two ticket-21 premises the measurements contradict

Reported, not acted on — ticket 21 is a different ticket and it is still open.

- **Item 3's premise ("nothing tests it") is false.** The shared transport helper in
  `TransportTest.kt:447` sets `baseUrl = "https://api.typesafe.ai/"` — with a trailing slash — and
  `TransportTest.kt:269` asserts `request.url.toString() == "https://api.typesafe.ai/v1/models"`. A source-level
  removal of `trimEnd('/')` yields `…ai//v1/models` and fails that assertion, so the behaviour is pinned. PIT
  did not see it only because `STRONGER` has no mutator for `trimEnd`.
- **Item 2's premise ("only exercises the exact-case spelling") is false.**
  `assemblesHeadersWithTheSiblingsPrecedenceRules` passes `"accept"` (lowercase) in `defaultHeaders` and
  `"Accept"` in the caller's headers, and asserts `getAll("Accept")?.size == 1` with the comment "names must be
  merged case-insensitively". It also asserts `assertNull(request.headers[RETRY_COUNT_HEADER])`. And with
  `NON_VOID_METHOD_CALLS` enabled, PIT *does* seed `removed call to java/lang/String::toLowerCase` and
  `removed call to java/util/LinkedHashMap::remove` on those two lines — **and both are killed by that existing
  test**. So both halves of item 2 are already covered; what is missing is only the sibling's exhaustive
  spelling matrix, not the behaviour.

### 4e. The conformance fixtures' contribution

Run with `excludedTestClasses = com.sierranevadalabs.jev.sdk.conformance.*`, the score moves from 534 detected
to 533 — net **one** mutant. Four mutants became survivors and three became kills, so most of that is run-to-run
noise, not a fixture effect: the measurable fixture contribution is about **1 kill of 534 (0.2%)**. The
conformance fixture suite is a cross-language wire contract, not a mutation-killing workhorse, and that is fine
— just do not expect it to hold a mutation gate up.

## 5. Classification rules

- **M — mechanical.** `mutator` description targets `kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue`,
  `kotlin/ResultKt::throwOnFailure`, or `CollectionsKt::throwIndexOverflow`; or the mutator is
  `NullReturnValsMutator`/`EmptyObjectReturnValsMutator` and the method's descriptor already returns
  `Lkotlin/Unit;` (a Kotlin `Unit` is JVM `null`, so the mutant is a no-op); or the mutated method is a
  `$default` bridge. Counting these across *all* mutants gives 49 of 749.
- **E — equivalent.** The mutated branch cannot change the observable result for any input. Only two qualify,
  both in `ErrorMapping.extractErrorMessage` L25: the early `if (trimmed.isEmpty()) return ""` is redundant,
  because with it removed the empty string falls through `runCatching` (null), the `?: trimmed` elvis, and the
  length check to the same `""`. Verified by reading the method, not assumed.
- **D — dead.** PIT status `NO_COVERAGE`.
- **U — unpinned.** Everything else: a distinguishing input exists and no test uses one. Dominant sub-shapes:
  Kotlin null/`as?` guards on malformed input (`RemoveConditionalMutator_EQUAL_*`), boundary values no test hits
  exactly (`ConditionalsBoundaryMutator`), default parameter values every test overrides (`PrimitiveReturnsMutator`
  on the default `random`, `EmptyObjectReturnValsMutator` on `platformEnv`, `getDefaultHeaders`, `toString`),
  and logging text.

Two deliberate honesty notes about this classification:

1. **U is a claim about the suite, not about the mutant.** For most of the 157 there is an obvious input that
   would kill it — feed a number where a string is expected, a `''` where a JSON object is expected, a
   `Retry-After` of exactly 200 characters. I did not write those tests, because the ticket is a measurement,
   not a test-porting ticket (that is ticket 21's job).
2. **E is deliberately small.** It would be easy to wave ~120 Kotlin null-check mutants into "equivalent", but
   they are not: they NPE where the source throws a typed error or returns null, and a test asserting the typed
   error kills them. Calling them equivalent would be exactly the "opinion is worth less than one number"
   failure the ticket warns about.

## 6. Costs and limits

**Scope — JVM only.** PIT mutates the JVM compilation of `commonMain`, so `jvmMain`, `appleMain` and
`linuxMain` `actual`s are outside its reach, as are all Apple and Linux test targets. `Platform.jvm.kt` is the
only platform file PIT sees (2 mutants, 1 survives: `platformEnv`'s default `System.getenv` call). The honest
scope of a mutation gate is "the shared logic as JVM bytecode", not the SDK.

**The `STRONGER` mutator group is the wrong group for this codebase, and it is the ticket's own suggestion.**
Per pitest.org's mutator table, `STRONGER` = `DEFAULTS` (Conditionals Boundary, Increments, Invert Negatives,
Math, Negate Conditionals) + Void Method Calls + Empty/False/True/Null/Primitive Returns + Remove Conditionals +
Experimental Switch. `NON_VOID_METHOD_CALLS` is in `ALL` but **not** in `STRONGER`. Measured cost of adding it:

| | `STRONGER` | `STRONGER` + `NON_VOID_METHOD_CALLS` |
|---|---|---|
| mutants | 749 | 1417 |
| detected | 534 | 1083 |
| no coverage | 33 | 56 |
| test strength | 74.6% | 79.6% |
| mechanical/plumbing share | 7% | **22%** |
| wall clock | 1 m 45 s | 2 m 21 s |
| `lowercase` / `trimEnd` / `trimStart` / `Map.remove` mutants | **none** | 5, **all killed** |

Adding it nearly doubles the mutant count and adds 279 mutants of collection/string plumbing noise
(`collectionSizeOrDefault`, `mapCapacity`, `coerceAtLeast`, `StringBuilder::append`, `Iterator::hasNext` — 42%
of the 668 it contributes). Note also *what* it adds on the interesting lines: `removed call to
String::toLowerCase` replaces the call's result — the mutator removes the call and substitutes the type's
default — so it proves the line is used, not that the argument is right. `joinUrl`'s trailing-slash semantics
still are not directly mutated. A follow-up that wants the wider net should budget for ~2x the run and ~3x the
triage.

**Noise, and where it concentrates.** The 7% mechanical share under `STRONGER` is dominated by three shapes:
`EmptyObjectReturnValsMutator`/`NullReturnValsMutator` on `Unit`-returning lambdas (11 survivors, always
equivalent), `ResultKt.throwOnFailure` in suspend bridges (8 survivors), and
`Intrinsics.checkNotNullExpressionValue` (4 survivors, always equivalent). A filter for those three is ~15 lines
of XML post-processing and removes 23 of 215 non-killing rows.

**Run-to-run noise.** 6 of 749 mutants (0.8%) changed status between two identical runs; detected count moved
534→533. Four of the six flips were `KILLED`↔`SURVIVED` and two were `TIMED_OUT`↔`KILLED` (score-neutral). On a
716-mutant covered set, one mutant is 0.14 percentage points, so a threshold set within ±1% of the observed
score is red on a coin flip.

**Arcmutate — what the commercial Kotlin plugin would add.** From the vendor's own documentation
(`docs.arcmutate.com/docs/kotlin.html`) and Maven Central: `com.arcmutate:pitest-kotlin-plugin` 1.5.1, requires
PIT ≥ 1.22.0, and requires a licence file (`arcmutate-licence.txt`) acquired from `subscribe.arcmutate.com` — it
is commercial, and the licence is a build input, which is a deployment consideration for a public repo's CI. It
adds:

- **Junk filtering for compiler-generated constructs** — Coroutines, Destructuring, Intrinsics, Safe casts,
  Autogenerated accessors, Lateinit, Unmatched `when` clauses in enums and sealed classes, Var accessors,
  Inlined code, Non-null types — plus filtering of `empty`-return mutants on methods that already return empty
  Kotlin collections, and empty-return support for `IntRange`/`LongRange`/`CharRange`/`Sequence`/`CoroutineContext`.
- **Kotlin-specific mutators** — `KOTLIN_RETURNS`, `KOTLIN_REMOVE_SORTED`, and friends, for constructs whose
  bytecode resembles Java but is not.
- **Inline-code correction** — combining inlined copies of a mutant and rewriting them to the original
  function, and rewriting line coverage too. That is precisely the 6% of misattributed lines measured in §1,
  and it is the strongest single argument for the licence *if* the tool is adopted as a gate rather than run
  on demand.

**Verdict on that:** plain PIT is good enough. Noise is 7%, not the 30%+ that would force the purchase, and the
misattribution is annoying but survivable because method names stay correct. The commercial mutators would add
no mutants we would act on: none of `KOTLIN_RETURNS`/`KOTLIN_REMOVE_SORTED` has a target in this source. Buy it
if the tool is promoted to a gate with a threshold, not before.

## 7. Verdict

**Adopt as an on-demand task, documented, and not in `check`.** The config is committed
(`build.gradle.kts`, `pitestJvm`), the README says how to run it, and the task is deliberately not reachable
from `check`.

Against a gate:

1. **A threshold cannot be set safely.** Observed test strength is 74.6% on 716 covered mutants, with a 0.8%
   per-mutant status flip rate. A gate at 73% tolerates ~11 spontaneous survivors; a gate at 74% is decided by
   the coin flip. A gate at 70% guards almost nothing. There is no stable line between "catches a regression"
   and "fails on a Tuesday".
2. **It would not have caught the gaps that motivated it.** §4b: 0 of 3.
3. **`check` must stay fast, and this is the slowest thing in the repo.** 103 s for the narrowest useful mutant
   set, 141 s for the set that actually reaches the interesting lines — against a `check` that currently
   completes in 41 s on this host with `--rerun-tasks`. Running it in `check` would roughly quadruple the gate.
4. **It only measures one fifth of the build.** Five of the fourteen source files have Apple/Linux `actual`s
   PIT cannot see, and no Apple or Linux test target is in scope. A gate would look like platform coverage and
   be JVM coverage.

For on-demand adoption:

1. It found a real hole a human found too (§4c) and quantifies the "covered ≠ asserted" claim in a way no human
   diff does: **95.33% Kover line coverage against 74.6% mutation test strength.**
2. The survivor list is actionable and short: 157 unpinned mutants, concentrated in error-decoding paths, with
   the top 3 files holding 84 of them.
3. It is cheap to run deliberately (under 2 minutes) and its noise is understood and bounded.
4. It is a decision input for ticket 21 and for any future "are we actually testing the error paths?" question.

**Evidence that would change the answer, in order of weight:**

1. A follow-up drives test strength into the mid-80s *and* the 0.8% flip rate proves to be timing noise rather
   than genuine non-determinism — then a gate at ~80% is stable and worth having.
2. Arcmutate's inline-code correction is adopted for report navigation, making triage cheap enough that the
   survivor backlog gets worked; that is an argument for a gate only if the backlog actually reaches zero.
3. The Apple/Linux `actual`s grow enough that JVM-only mutation coverage becomes misleading rather than
   partial — at which point the honest move is to say a mutation gate is out of scope for this SDK, not to
   narrow it silently.

## 8. What a later ticket must pick up

- Triage the 157 unpinned mutants into pinning tests, starting with `ErrorMapping.kt` (37),
  `Client.kt` (26), `Answers.kt` (20) and `Transport.kt` (18). The clusters worth naming: malformed-type
  decoding, absent-vs-empty body, the `Retry-After` boundaries, and the default-parameter values
  (`random`, `platformEnv`, `getDefaultHeaders`) that no test exercises.
- Decide whether `NON_VOID_METHOD_CALLS` should join `STRONGER` in the committed task. It is the only way PIT
  sees the value-returning-call class of bug at all, and it costs 2x runtime and 3x triage.
- Ticket 21's items 2, 3 and 6 should be re-read against §4b–§4d: two of the three premises in item 2 and 3
  are contradicted by measurements, and item 6 is confirmed.
- Nothing here should be wired into `check`, `apiCheck` or `dokkaGenerate`. `pitestJvm` is not reachable from
  `check`, and both generated POMs (`build/publications/jvm/pom-default.xml`,
  `build/publications/kotlinMultiplatform/pom-default.xml`) contain **zero** references to `pitest` or
  `arcmutate`.

---

## Appendix — every surviving mutant, classified


M = compiler-generated/mechanical. E = equivalent (no input distinguishes it). U = unpinned behaviour (an input distinguishes it; no test uses one). D = dead code (no test executes the line).

| file | method | line | mutator | description | bucket |
|---|---|---|---|---|---|
| Answers.kt | `asDouble` | 170 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `asDouble` | 171 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `decodeAnswer` | 106 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Answers.kt | `decodeAnswer` | 106 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `decodeAnswer` | 108 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Answers.kt | `decodeAnswer` | 108 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Answers.kt | `decodeAnswer` | 108 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Answers.kt | `doubleAt` | 138 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `doubleAt` | 139 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `doubleAt` | 139 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `getChoice` | 34 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/ChoiceAnswer::getChoice | D |
| Answers.kt | `getConfidence` | 36 | PrimitiveReturnsMutator | replaced double return with 0.0d for com/sierranevadalabs/jev/sdk/ChoiceAnswer::getConfidence | D |
| Answers.kt | `getConfidence` | 53 | PrimitiveReturnsMutator | replaced double return with 0.0d for com/sierranevadalabs/jev/sdk/ScoreAnswer::getConfidence | D |
| Answers.kt | `getNoul` | 23 | PrimitiveReturnsMutator | replaced double return with 0.0d for com/sierranevadalabs/jev/sdk/NoulAnswer::getNoul | D |
| Answers.kt | `getProbabilities` | 35 | EmptyObjectReturnValsMutator | replaced return value with Collections.emptyMap for com/sierranevadalabs/jev/sdk/ChoiceAnswer::getProbabilities | D |
| Answers.kt | `objectAt` | 164 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `stringAt` | 131 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Answers.kt | `stringAt` | 131 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `stringAt` | 131 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Answers.kt | `stringAt` | 131 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `createClient$lambda$0` | 80 | PrimitiveReturnsMutator | replaced double return with 0.0d for com/sierranevadalabs/jev/sdk/ClientKt::createClient$lambda$0 | U |
| Client.kt | `decodeSystemOneResponse` | 155 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Client.kt | `decodeSystemOneResponse` | 155 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 158 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Client.kt | `decodeSystemOneResponse` | 158 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 164 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Client.kt | `decodeSystemOneResponse` | 164 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 168 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 168 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 168 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 168 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `decodeSystemOneResponse` | 169 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `intOrNull` | 195 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `invoke` | 78 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/ClientKt$createClient$1::invoke | U |
| Client.kt | `invoke` | 79 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/ClientKt$createClient$2::invoke | D |
| Client.kt | `invoke` | 83 | VoidMethodCallMutator | removed call to java/io/PrintStream::println | D |
| Client.kt | `invokeSuspend` | 81 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `invokeSuspend` | 81 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Client.kt | `invokeSuspend` | 81 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Client.kt | `invokeSuspend` | 81 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/ClientKt$createClient$4::invokeSuspend | U |
| Client.kt | `invokeSuspend` | 109 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `invokeSuspend` | 109 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Client.kt | `request-FHKeTTw` | 132 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Client.kt | `request-FHKeTTw` | 141 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Client.kt | `request-FHKeTTw` | 145 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| Client.kt | `request-FHKeTTw` | 145 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| Client.kt | `systemOne-FHKeTTw` | 111 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Client.kt | `systemOne-FHKeTTw` | 125 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Config.kt | `<init>` | 70 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Config.kt | `<init>` | 70 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Config.kt | `<init>` | 70 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Config.kt | `_init_$lambda$0` | 55 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TypeSafeConfig::_init_$lambda$0 | M |
| Config.kt | `getDefaultHeaders` | 51 | EmptyObjectReturnValsMutator | replaced return value with Collections.emptyMap for com/sierranevadalabs/jev/sdk/TypeSafeConfig::getDefaultHeaders | U |
| Config.kt | `resolveSetting` | 88 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Config.kt | `resolveSetting` | 95 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Config.kt | `toString` | 58 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/TypeSafeConfig::toString | U |
| ErrorMapping.kt | `asPublicError` | 102 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asPublicError` | 103 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `asPublicError` | 103 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asPublicError` | 104 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asPublicError` | 105 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `asPublicError` | 105 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asStringOrNull` | 67 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asStringOrNull` | 67 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `asStringOrNull` | 67 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 47 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 47 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 49 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 49 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 49 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 49 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `detailField` | 118 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `errorField` | 40 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `errorField` | 40 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `errorFor` | 88 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| ErrorMapping.kt | `errorFor` | 88 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| ErrorMapping.kt | `extractErrorMessage` | 25 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | E |
| ErrorMapping.kt | `extractErrorMessage` | 25 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | E |
| ErrorMapping.kt | `extractErrorMessage` | 27 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `extractErrorMessage` | 28 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| ErrorMapping.kt | `messageFrom` | 34 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `messageFrom` | 34 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `parseBody` | 16 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| ErrorMapping.kt | `parseBody` | 16 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `parseBody` | 16 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `parseBody` | 16 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| ErrorMapping.kt | `validationEntry` | 55 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `validationEntry` | 55 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `validationEntry` | 55 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/errors/ErrorMappingKt::validationEntry | D |
| ErrorMapping.kt | `validationEntry` | 56 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `validationEntry` | 56 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/errors/ErrorMappingKt::validationEntry | D |
| ErrorMapping.kt | `validationEntry` | 57 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `validationEntry` | 57 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `validationEntry` | 59 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `validationEntry` | 59 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| ErrorMapping.kt | `validationEntry` | 132 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| ErrorMapping.kt | `validationEntry` | 140 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Errors.kt | `getBody` | 31 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/errors/APIError::getBody | U |
| Headers.kt | `assembleHeaders` | 42 | VoidMethodCallMutator | removed call to com/sierranevadalabs/jev/sdk/HeadersKt::assembleHeaders$merge | U |
| Headers.kt | `assembleHeaders` | 44 | VoidMethodCallMutator | removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue | M |
| Headers.kt | `assembleHeaders` | 48 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Headers.kt | `assembleHeaders` | 48 | VoidMethodCallMutator | removed call to com/sierranevadalabs/jev/sdk/HeadersKt::assembleHeaders$merge | U |
| Headers.kt | `assembleHeaders` | 52 | VoidMethodCallMutator | removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue | M |
| Headers.kt | `assembleHeaders` | 55 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Headers.kt | `assembleHeaders$merge` | 39 | VoidMethodCallMutator | removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue | M |
| Logging.kt | `invoke` | 77 | VoidMethodCallMutator | removed call to java/io/PrintStream::println | D |
| Logging.kt | `logSink$lambda$0` | 80 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/LoggingKt::logSink$lambda$0 | M |
| Logging.kt | `logSink$lambda$1` | 82 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/LoggingKt::logSink$lambda$1 | M |
| Logging.kt | `resolveLogLevel` | 62 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Logging.kt | `resolveLogLevel$lambda$1` | 66 | VoidMethodCallMutator | removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue | M |
| Logging.kt | `resolveLogLevel$lambda$1` | 66 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/LoggingKt::resolveLogLevel$lambda$1 | U |
| Models.kt | `decodeModelCard` | 81 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 82 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 82 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 82 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 82 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 82 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `decodeModelCard` | 83 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Models.kt | `decodeModelCard` | 83 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `getDescription` | 33 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/ModelCard::getDescription | D |
| Models.kt | `getInputTokens` | 45 | EmptyObjectReturnValsMutator | replaced Integer return value with 0 for com/sierranevadalabs/jev/sdk/Usage::getInputTokens | D |
| Models.kt | `getName` | 32 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/ModelCard::getName | D |
| Models.kt | `getOutputTokens` | 46 | EmptyObjectReturnValsMutator | replaced Integer return value with 0 for com/sierranevadalabs/jev/sdk/Usage::getOutputTokens | D |
| Models.kt | `getReleaseDate` | 34 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/ModelCard::getReleaseDate | D |
| Models.kt | `list` | 59 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Models.kt | `list` | 60 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `list` | 62 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `list` | 104 | IncrementsMutator | Changed increment from 1 to -1 | U |
| Models.kt | `list` | 104 | RemoveConditionalMutator_ORDER_ELSE | removed conditional - replaced comparison check with false | U |
| Models.kt | `list` | 104 | VoidMethodCallMutator | removed call to kotlin/collections/CollectionsKt::throwIndexOverflow | D |
| Models.kt | `stringFieldOrNull` | 99 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `stringFieldOrNull` | 99 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `stringFieldOrNull` | 99 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Models.kt | `stringFieldOrNull` | 99 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Platform.jvm.kt | `platformEnv` | 8 | EmptyObjectReturnValsMutator | replaced return value with "" for com/sierranevadalabs/jev/sdk/Platform_jvmKt::platformEnv | U |
| Questions.kt | `questionAccepts` | 132 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Questions.kt | `toWireJson` | 169 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Questions.kt | `toWireJson` | 172 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Retry.kt | `applyPolicy$lambda$2` | 113 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/RetryKt::applyPolicy$lambda$2 | M |
| Retry.kt | `parseRetryAfterMs` | 63 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Retry.kt | `parseRetryAfterMs` | 64 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| Retry.kt | `parseRetryAfterMs` | 64 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| Retry.kt | `parseRetryAfterMs` | 64 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Retry.kt | `parseRetryAfterMs` | 64 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| Retry.kt | `parseRetryAfterMs` | 64 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| Retry.kt | `parseRetryAfterMs` | 69 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| Retry.kt | `transportFailureKind` | 32 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Retry.kt | `transportFailureKind` | 36 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Retry.kt | `transportFailureKind` | 37 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Retry.kt | `transportFailureKind` | 40 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| RetryPolicy.kt | `<init>` | 61 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| RetryPolicy.kt | `<init>` | 64 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| RetryPolicy.kt | `<init>` | 65 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| RetryPolicy.kt | `<init>` | 66 | ConditionalsBoundaryMutator | changed conditional boundary | U |
| RetryPolicy.kt | `<init>` | 66 | RemoveConditionalMutator_ORDER_IF | removed conditional - replaced comparison check with true | U |
| RetryPolicy.kt | `<init>` | 73 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| RetryPolicy.kt | `<init>` | 73 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| RetryPolicy.kt | `<init>` | 73 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| RetryPolicy.kt | `getApiConnectionError` | 55 | BooleanTrueReturnValsMutator | replaced boolean return with true for com/sierranevadalabs/jev/sdk/RetryPolicy::getApiConnectionError | U |
| SystemOneResponse.kt | `answerOrNull` | 53 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| SystemOneResponse.kt | `answerOrNull` | 54 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| SystemOneResponse.kt | `answerOrNull` | 56 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/SystemOneResponse::answerOrNull | D |
| Transport.kt | `asTransportFailure` | 220 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `close` | 86 | VoidMethodCallMutator | removed call to io/ktor/client/HttpClient::close | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$0` | 131 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$0 | D |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$1` | 132 | PrimitiveReturnsMutator | replaced double return with 0.0d for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$1 | D |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$2` | 135 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$2 | M |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3` | 143 | VoidMethodCallMutator | removed call to io/ktor/client/HttpClientConfig::setExpectSuccess | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3` | 154 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$3 | M |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3$0` | 148 | VoidMethodCallMutator | removed call to com/sierranevadalabs/jev/sdk/RetryKt::applyPolicy | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3$0` | 149 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$3$0 | M |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3$1` | 152 | VoidMethodCallMutator | removed call to io/ktor/client/plugins/HttpTimeoutConfig::setSocketTimeoutMillis | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$3$1` | 153 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$3$1 | M |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$4` | 158 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$4` | 158 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$4` | 158 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Transport.kt | `createTransport_mVEZ1eQ$lambda$4` | 159 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::createTransport_mVEZ1eQ$lambda$4 | M |
| Transport.kt | `failureLine` | 196 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `failureLine` | 196 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Transport.kt | `invoke` | 136 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt$createTransport$5::invoke | D |
| Transport.kt | `invokeSuspend` | 133 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | D |
| Transport.kt | `invokeSuspend` | 133 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | D |
| Transport.kt | `invokeSuspend` | 133 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | D |
| Transport.kt | `invokeSuspend` | 133 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | D |
| Transport.kt | `invokeSuspend` | 133 | SwitchMutator | Changed switch default to be first case | D |
| Transport.kt | `invokeSuspend` | 133 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt$createTransport$3::invokeSuspend | D |
| Transport.kt | `invokeSuspend` | 133 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt$createTransport$3::invokeSuspend | D |
| Transport.kt | `request-Zzr-CC0` | 50 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Transport.kt | `request-Zzr-CC0` | 50 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | D |
| Transport.kt | `request-Zzr-CC0` | 50 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/Transport::request-Zzr-CC0 | D |
| Transport.kt | `request-Zzr-CC0` | 64 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `request-Zzr-CC0` | 73 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `request-Zzr-CC0` | 80 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Transport.kt | `request-Zzr-CC0` | 245 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `request-Zzr-CC0` | 247 | VoidMethodCallMutator | removed call to io/ktor/client/request/HttpRequestBuilder::setBodyType | U |
| Transport.kt | `request-Zzr-CC0` | 251 | VoidMethodCallMutator | removed call to io/ktor/client/request/HttpRequestBuilder::setBody | D |
| Transport.kt | `request-Zzr-CC0` | 252 | VoidMethodCallMutator | removed call to io/ktor/client/request/HttpRequestBuilder::setBodyType | D |
| Transport.kt | `request-Zzr-CC0` | 257 | RemoveConditionalMutator_EQUAL_IF | removed conditional - replaced equality check with true | U |
| Transport.kt | `request_Zzr_CC0$lambda$0$1` | 70 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/Transport::request_Zzr_CC0$lambda$0$1 | M |
| Transport.kt | `request_Zzr_CC0$lambda$0$2` | 71 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/Transport::request_Zzr_CC0$lambda$0$2 | M |
| Transport.kt | `responseLine` | 186 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |
| Transport.kt | `toTransportResponse` | 206 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | M |
| Transport.kt | `toTransportResponse` | 206 | VoidMethodCallMutator | removed call to kotlin/ResultKt::throwOnFailure | D |
| Transport.kt | `toTransportResponse` | 206 | NullReturnValsMutator | replaced return value with null for com/sierranevadalabs/jev/sdk/TransportKt::toTransportResponse | D |
| Transport.kt | `toTransportResponse` | 210 | RemoveConditionalMutator_EQUAL_ELSE | removed conditional - replaced equality check with false | U |

Bucket totals: U 157, D 33, M 23, E 2

## Amendment — the survivor filter, and the fact that the run does not reproduce

Added after `pitestJvm` gained a filter, and after two consecutive runs of the *same* configuration disagreed.

`pitestJvm` now sets `excludedMethods = ["*lambda*"]`. Kotlin inlines a lambda body into a synthetic method
named `enclosing$lambda$<n>` on the real class, so PIT reports targets that exist in no source file; nearly all
are `NullReturnVals` on a lambda returning Unit, which no test can observe. Measured effect:

| | before | after |
|---|---|---|
| generated | 767 | 727 |
| covered | 735 | 697 |
| killed | 589 | 573 |
| **survivors** | **139** | **122** |
| no coverage | 32 | 30 |
| test strength | 81.1 % | 82.5 % (83.0 % on the repeat run) |
| mutation score | 77.7 % | 79.1 % |

Two other filters were tried and **reverted, because Kotlin puts the suppressed call on the same source line as
real logic**:

* `avoidCallsTo = ["kotlin.ResultKt", "kotlin.jvm.internal"]`. PIT documents it as "any lines of code containing
  calls to these classes will not be mutated" — the scope is the *line*, not the call. `parseBody` is a
  one-expression function whose body contains `runCatching`, so **all four of its malformed-body conditionals
  vanished and the function had zero mutants left**. `failureLine`'s elvis died the same way, because
  `::class.simpleName` emits `Intrinsics.checkNotNullExpressionValue`. Losing `parseBody` is losing the entry
  point of the malformed-data contract, which is the cluster this tool was chosen for.
* `excludedClasses = ["*$*"]`. A suspend function's body compiles into `Enclosing$1.invokeSuspend`, so
  `TypeSafeClientImpl$models$1:144` is `models()`'s own body, not a lambda.

`excludedMethods` is method-scoped and cost 3 real-but-log-only survivors (`createTransport…$lambda$4:158`, the
retry line) and ~13 equivalent Unit returns. `assembleHeaders$merge` is a local function, not a lambda, so it
survives the glob. One caveat: the glob would also skip a hand-written function whose name contains `lambda`.

### Two identical runs disagree on 6 of 727 mutants (0.8 %)

Run 1 → run 2, same config, same tree, no edits:

* `Transport.request-Zzr-CC0:257` regressed SURVIVED.
* `ClientKt$createClient$4.invokeSuspend:102`, `ModelsApi.list:67`, `ResolvedConfig.<init>:70`,
  `Transport.request:245`, `TypeSafeClientImpl$models$1:143` became KILLED.
* The same `request:257` had moved the *other* way in the preceding pair of runs, so it flips in both directions.
* Test strength moved 82.5 % → 83.0 % between runs that differ in nothing.

The flipping mutants are the error/cancellation/timing paths plus three `ResultKt::throwOnFailure` ones. This
reproduces ticket 22's 0.8 % figure as *named* mutants, and it sets three constraints:

1. **No threshold is defensible**, restated with evidence.
2. **A committed survivor baseline cannot stay clean.** Every run would report ~6 spurious NEW/FIXED entries, so
   a "no new survivors on changed classes" gate would be flaky by construction unless those mutants are named
   and handled individually.
3. Only a difference well above ~6 mutants means anything. The 139 → 122 filter result does. The
   82.5 % → 83.0 % strength move does not.

One test-level flake was observed while adding the tests below, and is consistent with the mutant flips rather
than separate from them: `WireTransportTest.aConnectionDroppedMidBodyIsRetriedAndTheFinalCauseSurvives` — which
asserts `3 == server.recordedRequests.size` over a real socket — failed once, then passed 10 consecutive times
(4 with the new tests present, 6 with them stashed). A timing-sensitive test that asserts a retry *count* is a
plausible contributor to the retried-condition mutants flipping, though this run did not attribute them.

### Baseline ledger: mechanically sound, deliberately not adopted

A 30-line prototype was built and proven against four cases. Its key is PIT's own identity tuple —
`class, method, methodDescription, mutator, indexes` — and deliberately **not** the line number, which is what
makes it survive ordinary edits:

| case | result |
|---|---|
| run against its own baseline | 0 new, 0 fixed, 0 stale |
| every line number shifted by +10 (a 10-line insert above the classes) | **0 new, 0 stale** — the key is line-independent |
| one mutant removed from one method (a real edit) | 1 STALE, named correctly |
| one survivor becomes killed | 1 FIXED, named correctly |

Not adopted, for three reasons. The triage labour is 122 entries, and the bucket table above already carries that
reasoning. There is **no previous release** to baseline against — v0.1.0 has not shipped, so the first run
*creates* the baseline and it earns nothing until a second release exists. And constraint 2 above caps its
precision at ±6 mutants per run regardless.

If a delta is wanted at v0.2.0, use PIT's own history mechanism (`--historyInputLocation` /
`--historyOutputLocation`; the build already points `defaultFileForHistoryData` at `build/pitHistory.txt`, but no
file is written until history is enabled) rather than introducing a second format. It carries the same identity
key and additionally scopes a run to changed classes — at the cost of reporting cached statuses instead of
re-deriving them, which papers over the flakiness above rather than resolving it.

The arcmutate Kotlin plugin was considered and **declined**: it is the one tool that filters Kotlin's
compiler-generated null handling properly (default = compiler-generated subsets, `+KOTLIN_NO_NULLS` = all,
including hand-rolled), which is the largest remaining survivor family here, but it is a paid licence and would
put a third party's artefact on the path of a release-time check.

### The three normal-path survivors are now pinned

Survivor analysis separated 139 mutants into buckets by whether a user could hit them without the server doing
anything unusual. Three could, and each now has a test that fails when its mutant is applied:

| survivor | test |
|---|---|
| `Transport.close` losing `http.close()` | `TransportTest.aClosedTransportNeverReachesTheEngineAgain` |
| `RetryPolicy.getApiConnectionError` pinned to `true` | `TransportTest.classifiesConnectionFailuresSeparatelyAndHonoursApiConnectionError` |
| `toWireJson` encoding every choice description as `JsonNull` | `QuestionModelTest.choiceCriteriaReachTheWireWithTheDescriptionEachOptionCarries` |

Each was verified **by applying the mutant by hand and watching its test fail**, one at a time — not by a pitest
re-run, because a 3-mutant change sits well inside the ±6 noise floor above and a run could not have confirmed it.

The close guard is behavioural rather than structural: the leak that closing the Ktor client prevents (threads, a
connection pool) has no local observable when the engine is caller-owned, so the test pins the consequence — a
closed transport never reaches the engine again, while a new transport over the same engine still works.
