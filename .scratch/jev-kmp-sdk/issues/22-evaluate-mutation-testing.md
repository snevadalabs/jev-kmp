# Evaluate mutation testing as a way to find what coverage cannot see

Type: research
Status: resolved
Blocked by:

## Question

**Would mutation testing have caught what this project's suite missed, and is it worth adopting?**

The premise to test, not assume: our coverage gate is Kover at a 94% line floor over the JVM compilation of
`commonMain`, and *covered is not the same as asserted*. A line can be executed by a test that asserts nothing
about it, and three of the last four tickets found exactly that shape of hole by hand:

- a JSON error body under a non-JSON `content-type` is parsed leniently (`ErrorMapping.parseBody` ignores
  content-type) and nothing pins it, because all four JSON error fixtures happen to be labelled `application/json`;
- `Headers.assembleHeaders` lowercases names precisely so a caller cannot smuggle a protected header — and no test
  uses anything but the exact-case spelling;
- `joinUrl` trims a trailing slash off the base URL and nothing tests it.

Ticket 21 ports the sibling suites' cases for those. This ticket asks the different question: **should a machine
find them instead of a human diffing two other SDKs' test names?** Answer it with measurements, not a literature
review — the repo already contains both the code and the suite, so an opinion is worth less than one number.

**Facts already established, so they are not re-derived.** The JVM test engine is JUnit 4, resolved transitively by
`kotlin("test")` (`build.gradle.kts:165-183`; the Kover binary report carries
`org/junit/runner/notification/RunNotifier`), so PIT needs no JUnit 5 plugin. Tests live in `commonTest`/`jvmTest`
and are compiled into the JVM target, Kover is already wired over the JVM compilation of `commonMain` + `jvmMain`,
`jvmTest` carries a `-Ptypesafe.live` system property and so must not be run with the live tier on, and GitHub
Actions is disabled — every mutation run is local, and nothing may slow `./gradlew check`.

## What to do

1. **Get a mutation tool actually running against the JVM target.** PIT (`info.solidsoft.pitest`) is the candidate:
   `targetClasses` over `com.sierranevadalabs.jev.sdk.*`, `sourceDirs` pointed at `src/commonMain/kotlin`,
   `testSourceSets`/`testPlugin` matching how `jvmTest` is configured, `mutators` at `STRONGER`, `verbosity` on,
   `timestampedReports = false`, and exclusions for generated code and for whatever turns out to be noise. Report
   **whether PIT sees Kotlin bytecode mutants usefully at all** and how much of its output is mechanical noise —
   bridge methods, `Intrinsics.checkNotNullParameter`, `when` over sealed types, coroutine state machines,
   `$default` methods — because that ratio, not the headline score, decides whether the tool is usable here.
   Time-box this: if it cannot be made to run at all in a reasonable effort, **stop and write up the exact failure**
   with the errors verbatim; that is a legitimate and useful answer.
2. **Baseline the run**: mutation score with killed/total, a per-class breakdown for the files where being wrong
   costs money or debugging time (`Retry.kt`, `Headers.kt`, `ErrorMapping.kt`, `Answers.kt`, `Config.kt`,
   `Transport.kt`), wall-clock, and the exact config that produced it.
3. **The pay-off check, which is the point of the ticket.** For every surviving mutant, classify it — equivalent
   mutant, **genuinely unpinned behaviour**, or dead code — and then compare the survivors against ticket 21's list
   specifically. Report the overlap as a number, and call out any survivor in `ErrorMapping.parseBody` or
   `Headers.assembleHeaders`, since those two are ticket-21 items that a human found by reading another SDK's tests.
   A mutation score with no such mapping does not answer the question; the survivors are the finding.
4. **Costs and limits, stated rather than implied.** Only the JVM compilation is measurable, so the Apple, Linux and
   `jvmMain`/`appleMain` `actual` paths are outside PIT's reach — the honest scope of what a mutation gate would
   guard. The conformance fixtures run inside `commonTest`, so they count as killers. Mutation scores on a codebase
   this size are noisy, and Arcmutate's Kotlin-specific mutators are commercial: say whether plain PIT mutants on
   Kotlin bytecode are good enough without them, and what the commercial ones would add if they are not.
5. **Verdict, with numbers attached**: adopt as a gate (and then justify a threshold on ~2k lines of source, where a
   one-mutant swing is a percentage point), adopt as an on-demand task documented but **not** in `check`, or reject.
   A rejection must name the evidence that would change the answer.

## Constraints

- **Not in `check`.** The gate stays fast. If the verdict is on-demand, land the config as a task an operator runs
  deliberately, plus a short note where a reader will find it.
- Nothing may reach the published artifact: plugin classpath or `testImplementation` only, no new runtime
  dependency. If the plugin breaks `check`, `apiCheck`, the api dump or `dokkaGenerate`, revert and report rather
  than working around it.
- Versions belong in `gradle/libs.versions.toml`. Do not add a second mutation tool to compare against the first —
  one measured tool beats three configured ones.

## Deliverable

`research/22-mutation-testing-evaluation.md` with the tables (tool fit, score, per-class breakdown, survivors by
class with their classification, ticket-21 overlap), the committed config if the verdict is to adopt it, and an
`## Answer` carrying the numbers, the verdict and what a later ticket would have to pick up. Research is a decision
input, so the Answer must be readable without opening the research file.

## Answer

**Verdict: adopt as an on-demand task, documented, not in `check`.** Shipped in PR
<https://github.com/snevadalabs/jev-kmp/pull/12> (unmerged; the parent session merges). Full tables and the
per-mutant classification are in
[`research/22-mutation-testing-evaluation.md`](../research/22-mutation-testing-evaluation.md); this is readable
without it.

**Verdict on the tool.** PIT works. `info.solidsoft.pitest` 1.19.0 running `org.pitest:pitest-command-line`
1.22.1 mutates this SDK's Kotlin bytecode usefully: 749 mutants over 40 classes, **103 s**, 7% mechanical noise
(the ticket guessed high; `EmptyObjectReturnValsMutator` on `Unit` lambdas, `ResultKt.throwOnFailure` and
`checkNotNullExpressionValue` are the whole of it). Plain PIT is good enough here — no Arcmutate.
`NON_VOID_METHOD_CALLS` is in PIT's `ALL` group but **not** in `STRONGER`, which is why the ticket's suggested
mutator set misses every value-returning call. Adding it: 1417 mutants, 79.6% strength, 22% noise, 2 m 21 s.

**The integration, which is the one thing a later reader needs.** The plugin registers its `pitest` task inside
`withType(JavaPlugin)`, which a KMP module never applies, so no task ever appears. The committed `pitestJvm`
task registers the plugin's own `PitestTask` against the JVM test compilation instead. Two traps cost a run
each: `additionalClasspathFile`'s parent directory is not created by PIT, and `runtimeDependencyFiles` is
dependencies only, so the main classes directory must be added explicitly.

**Baseline** (`./gradlew pitestJvm` → `build/reports/pitest/mutations.xml`): 749 generated, 527 killed, 7 timed
out, **182 survived**, 33 with no coverage. **Test strength 534/716 = 74.6%**, against **Kover line coverage of
95.33%** (24 missed / 490 covered, floor 94) on the same compilation. That gap is the number the ticket wanted:
at a 95% line floor, a quarter of the covered mutants still live. Repeat runs flip 6 of 749 mutants (0.8%) and
move the score by one mutant. 44 mutants (6%) report impossible line numbers — Kotlin inline functions defeat
PIT's source mapping — so read the XML per method, not per line.

**The pay-off check: 0 of the 3 ticket-21 behaviours produced a matching surviving mutant.**
`Record.parseBody`'s missing content-type check is an *omitted* check — `content-type` is never read, so there
is no bytecode to mutate. `Headers.assembleHeaders`' `name.lowercase()` and `byLowercaseName.remove(…)`, and
`Transport.joinUrl`'s `trimEnd`/`trimStart`, are value-returning calls, so `STRONGER` seeds nothing on them
(`joinUrl` gets one placeholder mutant, killed trivially). Under `NON_VOID_METHOD_CALLS` those lines do get
mutants — and every one is already killed. So the direct answer is **no, mutation testing would not have caught
what the humans caught.** It finds wrong implementations, not missing checks.

**It found one thing a human found too:** ticket-21 item 6, the empty `200` body. `decodeSystemOneResponse`
carries 11 survivors on its `parseBody(...) as? JsonObject` / `answers as? JsonObject` guards. A test sending
`""` as a 200 body and asserting the failure kills them. The bigger cluster is malformed-input decoding in
`ErrorMapping.kt` (37 unpinned), `Client.kt` (26), `Answers.kt` (20) — right key with the wrong JSON type,
array where object expected, the `Retry-After` boundaries — none of which a fixture travels.

**Two ticket-21 premises the measurements contradict** (reported, not acted on): item 3's "nothing tests it" is
false — `TransportTest.kt:447` gives the shared helper a trailing-slash `baseUrl` and `:269` asserts the exact
URL; and item 2's "exact-case spelling only" is false — the `"accept"`/`"Accept"` pair is asserted at
`TransportTest.kt:310-312` with the retry-count `assertNull` right after, and PIT kills both mutants on those
lines when it can see them.

**Costs, stated.** Only the JVM compilation is measurable: 5 of 14 source files have Apple/Linux `actual`s PIT
never sees. The conformance fixtures are worth about **1 kill of 534**, so they are not a mutation-gate
workhorse. A gate has no safe threshold — at 716 covered mutants one mutant is 0.14pp and the measured flip
rate is 0.8%, so any line within a point of 74.6% is a coin flip; it would also quadruple a `check` that
currently finishes in 41 s here.

**Left undone, deliberately.** The 157 unpinned mutants are classified, not fixed — pinning them is ticket 21's
job, not a measurement ticket's. Whether `NON_VOID_METHOD_CALLS` joins `STRONGER` is recorded as an open choice
for a follow-up rather than decided here. Arcmutate's licence was not purchased: its inline-code correction
would fix the 6% misattribution, but no commercial Kotlin mutator has a target in this source.

**Gate.** `./gradlew check --rerun-tasks` green (Android SDK needed `ANDROID_HOME`; unset in this shell had
failed `:testAndroidHostTest` before any of this branch's changes). `apiCheck`, `koverVerify`, `dokkaGenerate`
and both ktlint lanes all pass; both generated POMs contain zero references to `pitest` or `arcmutate`.
