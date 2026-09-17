# Evaluate mutation testing as a way to find what coverage cannot see

Type: research
Status: open
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
