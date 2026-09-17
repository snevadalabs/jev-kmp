# Build the conformance, wire, and live test tiers

Type: task
Status: resolved
Blocked by: 07, 12

## Question

Read [research/03-maven-central-publishing.md](../research/03-maven-central-publishing.md) §6 before wiring this — the live tier is a **separate workflow** (`integration.yml` on `schedule` + `workflow_dispatch` + push to `main`), not a job in `ci.yml`. `pull_request` from a fork gets no secrets by design, and `pull_request_target` grants fork authors the base repo's token and secrets; it is not to be used anywhere in this repo.

Read [research/04-ktor-transport-mechanics.md](../research/04-ktor-transport-mechanics.md) §7 for the exact `MockEngine` API, and its closing list for the transport-level cases a real socket must cover.

Nothing to decide — the fixture format and the SDK both exist; this wires the three test tiers the effort was justified by.

**[noted by the parent before dispatch]** `check.yml` is currently `workflow_dispatch`-only: GitHub Actions is billing-blocked on this private repo, so nothing runs on a push or a pull request. Wire the coverage gate into the file anyway — the wiring is the deliverable — and verify locally with the coverage task by name and `./gradlew check`. Do not try to prove it through GitHub and do not restore the triggers. `integration.yml` is new, so it has no such history.

**Tier 1 — conformance fixtures.** The `commonTest` loader from *Settle the conformance fixture format* runs every fixture case in `conformance/` against the real client over `MockEngine`. This is the suite that must be runnable *unchanged* by the Python and JS suites; if a case needed a Kotlin-specific tweak, the format is wrong and the format is what changes, not the fixture.

**Tier 2 — wire and socket tests.** A real engine against a real local socket, the way the JS SDK's `native-transport.test.ts` does. Pick Ktor's CIO engine bound to `127.0.0.1:0` for the JVM source set. Assertions that a `MockEngine` cannot make honestly:

- The exact bytes and headers on the wire, including `X-TypeSafe-SDK` and `X-TypeSafe-Runtime`.
- A server that sends a `200` and then stalls the body — it must time out, on every consumer path.
- A server that drops the connection mid-body — it must be retried, and the eventual error must be the last one with its cause intact.
- Caller cancellation after headers arrive but before the body completes.
- A `429` with `Retry-After` actually producing the retry delay.

**Tier 3 — live API, opt-in only.** Behind an explicit flag, never merely the presence of `TYPESAFE_API_KEY`. Gated by a Gradle property *and* the env var, so no default `./gradlew test` can ever spend money or depend on a network. Cases: `models.list()` returns cards; a mixed three-primitive call returns an in-range noul, a choice whose probabilities sum to one, and a score whose legend echoes the request; a bad key raises the authentication error; an unknown model raises a bad-request error naming the model. Loose numeric assertions only — a live model is not a deterministic function, and the Python suite's `abs=0.1` tolerance is the right level of ambition.

**Coverage floor.** Configure a coverage task over the SDK source with a floor, and — this is the part the JS SDK got wrong — make it a CI gate that runs, not a `prepublishOnly` hook. Set the floor at what the code actually achieves, and state in a comment that the floor is not to be lowered to make a build pass.

Deliverable: three green tiers, the coverage gate wired into `.github/workflows/check.yml`, and a `README` note on how to run the live tier.

## Answer

**Built, gated, green.** Branch `issue-13-conformance-wire-live-tiers`, shipped unmerged as PR #7.

### What landed

**Tier 1 — conformance against the real client.** `src/commonTest/.../conformance/Conformance.kt` no longer stubs
the SDK. `assertConformanceCase` parses the fixture's own `request.body` into typed questions, calls the real
client over `MockEngine`, and checks all of `expect`: method/path/body (parsed-JSON equality, so no key added,
dropped, or retyped), `error` → the class behind the canonical name, `message`, `field`, `answers` (including
the `unknown` variant carrying the server's own type string), `usage`, and `delayMs` recorded from the
transport's `sleeper` seam rather than slept. `createClient` grew two `internal` test seams (`random`,
`sleeper`); no public API changed and `apiCheck` is green.

**One bend, reported not papered over.** The format's "the response list is the attempt script" collided with
the real policy once: `error-502-html-body` is a one-response case whose `502` is in the default retry set, so
the client wanted a second attempt the script does not have. The loader now runs a one-response case with
`maxRetries = 0` and every case asserts `attempts == responses.size`. That is ADR 0005's own rule (0005 is
ticket 07's, so it is not edited here); any SDK adopting the format meets the same collision.

**Tier 2 — wire and socket tests** (`src/jvmTest/.../WireTransportTest.kt`, JVM-only). A hand-rolled HTTP/1.1
`ServerSocket` on `127.0.0.1:0` with one daemon thread per connection, so the bytes are ours to script: exact
request bytes and headers including `X-TypeSafe-SDK`/`X-TypeSafe-Runtime`; a `200` that stalls its body, timing
out on both `systemOne` and `models.list`; a connection aborted mid-body, retried three times with the final
`APIConnectionError` carrying the engine's own `IOException` as `cause`; caller cancellation after headers
staying a `CancellationException` and never a `JevError`; and `Retry-After: 1` actually waiting, with
`X-TypeSafe-Retry-Count: 1` on the retry and absent on attempt 0. CIO is bound as the ticket named —
hand-rolled rather than a Ktor server, because no server dependency was named for it.

**Tier 3 — live, opt-in twice.** `src/jvmTest/.../LiveApiTest.kt`: `models.list()`; a mixed three-primitive call
with loose tolerances (noul in range, choice probabilities summing to one, legend echoing the request); a bad key
→ `AuthenticationError`; an unknown model → `BadRequestError` naming it. Gated by `-Ptypesafe.live=true` **and**
`TYPESAFE_API_KEY`; the property without the key fails instead of skipping. New
`.github/workflows/integration.yml` (nightly + `workflow_dispatch` + push to `main`, `JEV_API_KEY` mapped to
`TYPESAFE_API_KEY`, no `pull_request_target` anywhere).

**Coverage floor.** Kover 0.9.9 in `libs.versions.toml`; total rule `minBound(94)` over the JVM compilation of
`commonMain` + `jvmMain`, wired into `check` *and* into `check.yml`'s Linux lane. Measured **94.572%** line
coverage; the comment states the floor is not to be lowered to pass.

### Questions this ticket graduates (the map's "Not yet specified")

- **`usage` needs no shim.** `response-usage-without-billing-units` now runs through the real client and passes:
  `Usage` reads the two fields the wire sends and never requires the schema's `billing_units`.
- **We validate responses and the error carries a field path.** `response-malformed-answer` now asserts
  `APIResponseValidationError.field == "answers.urgent.noul"`, and the loader maps the canonical name
  `APIResponseValidationError` to that class. `expect.field` is no longer presence-only.

### Evidence

- `./gradlew check` → **BUILD SUCCESSFUL** (59 tasks). Tests: `jvmTest` 92, `testAndroidHostTest` 83,
  `macosArm64Test` 83, `iosSimulatorArm64Test` 83, 0 failures each; `linuxX64Test` SKIPPED on this macOS host
  by design. `ktlintCheck`, `apiCheck`, `koverVerify` green; `api/jvm/jev-kmp.api` unchanged.
- Non-vacuity, both gates: floor raised to 99 → `Rule violated: lines covered percentage is 94.572000, but
  expected minimum is 99`; `./gradlew jvmTest -Ptypesafe.live=true` with no key → all four live tests FAIL with
  `typesafe.live=true requires TYPESAFE_API_KEY in the environment`.
- Tier 1 written first: `compileTestKotlinJvm` failed on `No parameter with name 'random'/'sleeper' found`, then
  the seams landed and the suite went green.

### Deliberately left undone

- **`integration.yml` is wired, not proven.** Actions is billing-blocked on this private repo, so nothing runs
  on GitHub; it is verified only by running the same command locally.
- **No CHANGELOG line** — no public API change, so nothing a user of the SDK notices.
- **Kover measures JVM only** (its own limit, not a choice); Apple and Linux source sets are outside the number.
- A local, gitignored `local.properties` was needed to point Gradle at the Android SDK for `check`; it is not
  committed, and CI's Linux lane already sets the SDK up.

### Later tickets

- *Write README and KDoc* owns the README beyond the live-tier note added here.
- *Publish 0.1.0* should keep `koverVerify` in the release gate, and finally answer whether upstream adopts the
  fixtures.
- *Settle the conformance fixture format* may want ADR 0005 to state the single-response/retryable-status rule
  explicitly, since the loader had to infer it.
