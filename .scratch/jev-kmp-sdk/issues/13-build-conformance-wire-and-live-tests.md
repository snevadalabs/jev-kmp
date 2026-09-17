# Build the conformance, wire, and live test tiers

Type: task
Status: claimed
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
