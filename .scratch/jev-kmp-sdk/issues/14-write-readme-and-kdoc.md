# Write the README and KDoc

Type: task
Status: resolved
Blocked by: 12

## Question

**[amended by *Prototype the typed question API* and *Reconcile retry against Ktor's built-in HttpRequestRetry*]** `explicitApi()` does **not** force KDoc: the prototype measured that deleting a public declaration's KDoc compiles clean, so this pass is not "filling in what the compiler forced" — nothing forces it, and the deliverable needs a KDoc gate of its own. Pick the cheapest thing that fails the build when a public declaration loses its doc (Dokka's `failOnWarning` is the obvious candidate), say why, and do not lower it later to make a build pass. Nothing else to decide: this is the pass that makes the SDK usable by someone who has never seen the Python docs.

**KDoc.** Fill in what `explicitApi()` forced but nobody wrote. Two rules, taken from the recon of both siblings' best moments: every public declaration says what it does in one sentence, and anything with a non-obvious failure mode says when it throws and which type. No `@param` restating the parameter name. The retry policy, the `UnknownAnswer` variant, and the config-resolution precedence are the three places where a reader needs real prose rather than a signature.

**README.** Structured to mirror the Python and JS READMEs so a reader moving between SDKs sees the same shape:

- One-paragraph what-this-is, linking to `https://docs.typesafe.ai/`.
- Install, for Gradle Kotlin DSL, with the actual coordinate.
- Quickstart: set `TYPESAFE_API_KEY`, construct the client, ask one question of each primitive, read the typed answer. Show the typed-key accessor, because that is the thing this SDK does that the siblings cannot.
- A short "differences from the Python and JS SDKs" section: suspend-only, typed question keys, Ktor's built-in `HttpRequestRetry` driven by our `RetryPolicy` and the `maxRetryAfter` cap, score criteria requiring two levels, and that logging never records headers or bodies. Honest and specific — this section is what an evaluator reads first.
- Running the tests, including how to opt into the live tier.
- Error handling: the class names, and that they match the siblings by name.

**Executable docs.** Compile the README's Kotlin blocks as a test, the way the Python suite does with Sybil. Roughly 15 lines: extract fenced `kotlin` blocks, wrap, compile against the built artifact. Without this the README rots the moment a signature changes — which is exactly how the Python suite's doc tests earn their keep, and exactly what the JS repo does not do.

Deliverable: README, complete KDoc, and the doc-compilation test wired into CI.

## Answer

README, the KDoc gaps, and both gates. Branch `issue-14-readme-and-kdoc`, unmerged when this was written.

**Premise: both halves were real.** `explicitApi(Strict)` forces nothing about KDoc, and `README.md` was the
scaffold's placeholder. The KDoc pass found only **two** undocumented public declarations — `NoulAnswer.noul`
(no `@property noul`) and the explicit `TypeSafeConfig.toString()` override — and gave the three places the
ticket names real prose: `RetryPolicy` (what is retried, the delay formula, the `maxRetryAfter` cap as a
deliberate divergence from Python, what OkHttp re-sends behind our back), `UnknownAnswer` (why a new variant is
source-breaking, never patch-released), `TypeSafeConfig` (explicit → env → default, blank env unset, read once).

**KDoc gate** — `build.gradle.kts:225`, reusing the Dokka run the Apple CI lane already performs, so no new
dependency and no new task:

```kotlin
dokka {
    dokkaSourceSets.configureEach { reportUndocumented.set(true) }
    dokkaPublications.configureEach { failOnWarning.set(true) }
}
```

Two facts found by falsifying, not reading. `reportUndocumented` defaults to **false** in the Dokka 2.x Gradle
plugin — a bare `dokkaGenerate` printed nothing for the two declarations above — and `failOnWarning` is **not**
on the `dokkaGenerate*` tasks' `AbstractDokkaTask`, so the obvious
`tasks.withType<AbstractDokkaTask>() { failOnWarning.set(true) }` compiles and gates nothing; it lives on
`DokkaPublication`. Falsified end to end: deleting `Models.list`'s KDoc gave `w: Undocumented: …/Models/list/#/`
and `FAILED … Failed with warningCount=3 and errorCount=0`, exit 1. `check` now depends on `dokkaGenerate`
(line 308), which is host-neutral: `--dry-run` shows the task graph compiles Kotlin metadata only, never an
Apple klib.

**README** mirrors the siblings (`## Quickstart` install-then-client, `## Documentation` linking
`docs.typesafe.ai`) plus every section the ticket lists: what-this-is, install with the real coordinate, a
quickstart asking one question of each primitive and reading typed answers, the differences section
(suspend-only, typed question keys, Ktor `HttpRequestRetry` under our `RetryPolicy` with the `maxRetryAfter`
cap, `score` needing two levels, no log line that can carry a header or a body), error handling with the class
names, and the tests including the twice-gated live tier.

**Executable docs** — `generateReadmeSnippets` (`build.gradle.kts:64`), wired into `commonTest` at line 167:
extract every fenced `kotlin` block, hoist the block's imports to file level (Kotlin allows `import` only at
file top, so a wrapped block cannot keep them), wrap it in an `internal suspend fun`, and let the ordinary test
compilation compile it against the module. Hoisting imports is deliberate — a stale package in a README import
line then fails the build too — and it refuses a README with no blocks, so it cannot go vacuous.

It caught three real defects on the way, which is the point of it. `systemOne(String, …)` is a top-level
**extension**, so every block needed `import com.sierranevadalabs.jev.sdk.systemOne` or a copying reader hits
`actual type is 'String', but 'JsonElement' was expected`. The retry block's Markdown list indentation needed
`trimIndent()` before its imports were recognised. And renaming `maxRetries` to `maxAttempts` in the README
gives `e: …/ReadmeSnippets.kt:38:68 No parameter with name 'maxAttempts' found.`

**Gate.** `ANDROID_HOME=~/Library/Android/sdk ./gradlew check` → `BUILD SUCCESSFUL in 35s`, 69 tasks, running
`jvmTest`, `iosSimulatorArm64Test`, `macosArm64Test`, `testAndroidHostTest`, `apiCheck`, `checkVersion`,
`checkJvmBytecode`, `koverVerify` and `dokkaGenerate`. No Apple test failed on simulator contention.

**Not done.** No CHANGELOG line: docs and build gates only, nothing a consumer of the artifact notices. No
`apiDump`: KDoc is not public API. No logging switch: the README states 0.1.0 has none rather than documenting
an option that does not exist — `createTransport` has a `log` seam `createClient` never fills and
`TYPESAFE_LOG_LEVEL` (brief §9) is unread, which is still open fog on the map.

**Handoff.** If a per-call log line and the `TYPESAFE_LOG_LEVEL` gate are built, the README's logging bullet
and `TypeSafeConfig`'s KDoc must change with them.
