# Junk the server could send: pin the decode paths

A plan, **not a ticket**. Dispatched by the parent session; there is no entry for this work in the tracker, and
`map.md`, `docs/adr/`, `research/22-*` and every `issues/*.md` file are out of bounds. This file is both the spec
and the record — append your outcomes to it.

## Why this exists

Mutation testing's post-filter run (`build/reports/pitest/mutations.xml`, `39eaccb`: 727 mutants, 697 covered,
118–122 survivors) was classified by whether a caller could hit a survivor without the server misbehaving.
Three were pinned at `34c9369`. About **61** sit in the decode seams: the paths that read a server body the SDK
did not expect. They are not a random sample — they cluster on exactly the guards that make a malformed response
survivable, which is the property a user is paying this SDK for.

## What is already true, and therefore what is owed

The decode seams are already written defensively. The guards, the field-path helpers (`fieldPath`, `Answers.kt:87`)
and `ResponseValidationException` (`Answers.kt:81`) exist. The contract is written down:

- `README.md:156` — "`APIResponseValidationError` is a 200 whose body did not match the wire contract."
- `Answers.kt:96` — "a malformed **known** answer fails with a `ResponseValidationException` naming the exact
  field path", while an unknown primitive becomes `UnknownAnswer` rather than an error.
- `Models.kt:20` — `list()` throws `APIResponseValidationError` "if a 200 body is not" the wire contract.
- The brief (`01-lock-v0.1-design-brief.md:117`) fixes the error class names, and `:121` fixes client-side
  validation at two things, everything else forwarded.

**So the work is to pin behaviour that is already defined, not to invent validation.** Expect tests only.
Production code changes only where the probe below finds a throwable escaping the `JevError` tree, and then only
as one guard at the funnel (see *If a leak is real*). Adding per-field validation, new error classes, or new
public API is out of scope and is a stop-and-report.

## The six seams, with their survivor counts

| seam | survivors | what the server would have to get wrong |
| --- | --- | --- |
| `ModelsKt.decodeModelCard` / `.stringFieldOrNull` / `ModelsApi.list` | 8 + 4 + 4 | `{models: [...]}` where an entry or a field is not what the contract says |
| `ErrorMappingKt`: `asPublicError` / `extractErrorMessage` / `parseBody` / `validationEntry` / `detailField` / `errorFor` / `messageFrom` | 5+4+4+4+2+2+1 | an error body that is not JSON, or is JSON in a shape we do not know |
| `RetryKt.parseRetryAfterMs` | 7 | a junk `Retry-After` / `retry-after-ms` header — `0`, negative, non-numeric, HTTP-date, huge |
| `ClientKt.decodeSystemOneResponse` / `.intOrNull` | 4 + 1 | `answers` missing or not an object, `usage` malformed |
| `AnswersKt.decodeAnswer` / `.doubleAt` / `.stringAt` / `.asDouble` | 3 + 2 + 2 + 1 | a *known* answer with a wrong-typed field |
| `SystemOneResponse.answerOrNull` + `QuestionsKt.questionAccepts` | 2 + 1 | a well-formed answer of the wrong primitive, or a key the request never asked for |

Out of scope, and **say so in your report rather than touching them**: `RetryPolicy.<init>`'s 9 (caller input, a
programmer error, not junk from the wire), `HeadersKt.assembleHeaders` 7 and `ConfigKt`/`ResolvedConfig` 4 (caller
and environment input), `Transport.request` 7 / `TypeSafeClientImpl` 10 / `TransportKt` 6 / logging 3 (transport
and logging, not decoding), the synthetic frames.

## Method

1. **Characterise before you assert.** One probe per seam, feeding the matrix below through a `MockEngine` and
   *recording* what comes back: the returned value, or the throwable's class, `field` and message. Do this first
   and write the table into the outcomes section — it is the evidence the classification rests on, and it is
   what makes this a check rather than a guess.
2. **Classify each cell** into exactly one of three:
   - **P — pin it.** Deterministic *and* defensible for a caller *and* consistent with the quoted contract:
     write the test.
   - **L — leak.** A throwable that is not a `JevError` (NPE, `ClassCastException`, `IllegalStateException`)
     reaches the caller, or a lookup returns a wrong-typed value that blows up at the caller's assignment.
     That contradicts `README.md:156`, so it is a bug: fix it, then pin the fixed behaviour.
   - **U — undefined.** Neither defensible nor clearly wrong (sibling divergence, an ambiguous field path, a
     behaviour the brief does not fix). **Do not decide it.** List it in the outcome with the evidence and stop
     there; the parent owns that call.
3. **Pin (P), tests first.** `kotlin.test` in `commonTest`, `MockEngine` for the body, no new fixtures. Each
   test asserts the *exact* outcome — the thrown class **and** the `field` path **and** a message fragment, or the
   exact decoded value. A test that asserts only "it throws" kills nothing.
4. **Fix (L) at the funnel, once.** The body of a funnel already funnels through one entry point
   (`decodeModelCard`, `decodeAnswers`, `decodeSystemOneResponse`, `extractErrorMessage`), so one guard per
   funnel covers its whole field matrix. Map only unexpected throwables; never wrap `JevError`, never wrap
   `CancellationException`. Reuse `APIResponseValidationError` — it exists, and no `apiDump` move should be
   needed. A `ponytail:` comment names the ceiling if you take a shortcut.
5. **Prove non-vacuity by perturbation, per seam.** For each seam, perturb the production code the way that
   seam's mutants do — invert the guard, coerce the field, skip the validation — and watch the new test(s) fail
   for the expected reason. Record the perturbation and the failing test names. Do **not** try to prove this with
   a `pitestJvm` run: repeat runs flip ~6 of 727 mutants, so a change this size is inside the noise floor. The
   perturbation is the proof. Restore the code afterwards and confirm the tree is byte-clean.

### The input matrix

Applied per seam, with the columns relevant to that seam: an entry that is not an object (`5`, `"x"`, `[]`, `null`),
a field of the wrong JSON type (string where number, number where string, object where array), a missing key, an
explicit `null`, an empty body, a body that is not JSON at all, a top-level array where an object is expected,
and for numbers: `0`, negative, huge, fractional-where-integer. For the retry headers: `0`, `-1`, `abc`, `3.5`,
an HTTP-date in the past and in the future, and a value above the cap. For answers: an unknown `type`, and a
known type with a wrong primitive.

## Constraints

- `kotlin.test` only; `commonTest` + `MockEngine`. No new source set, no platform-specific test — nothing here is
  platform-specific.
- No new dependency; versions live in `gradle/libs.versions.toml` and stay untouched.
- No new conformance fixture. ADR `0005` caps that set at 16 and it is deliberately small; these are robustness
  paths of ours, not the API's wire contract. If you believe a fixture is warranted, report it instead.
- `explicitApi` is `Strict`: nothing new is public. Public API nobody asked for is a bug.
- Leave `map.md`, `docs/adr/**`, `research/22-*` and every `issues/*.md` alone — parallel work and the parent own
  them. You are editing this file and test/source files only.
- `CHANGELOG.md` gets a line only if a user would notice the change. Pin-only tests: no line.
- The gate is `./gradlew check` and it must be green before you push. CI is disabled (org billing), so this local
  run is the only verification that happens.
- If a fix would change a *user-visible* outcome that the siblings (JS/Python) do differently, or would need new
  public API, **stop and report** — do not decide it. Leave the worktree in place if you stop.

## Deliverable and record

- Tests (and, only if step 3 found a real leak, the funnel guard) on branch `junk-input-hardening`, pushed with a
  PR into `main`. Do not merge.
- An `## Outcomes` section appended to this file, carrying: the characterisation table, the P/L/U classification
  per seam, the pinned list with the test names, the perturbation run for each seam, the survivors that remain
  alive on purpose with the reason (equivalent, undefined, or out of scope), and the `check` result.
- Report back: branch, PR, gate, files, and anything you stopped on.
