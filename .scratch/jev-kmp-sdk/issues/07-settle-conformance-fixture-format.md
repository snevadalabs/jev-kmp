# Settle the conformance fixture format

Type: prototype
Status: resolved
Blocked by:

## Question

The brief locks the *idea* — hand-written JSON wire fixtures that all SDKs can consume — but not the format, and the format is what decides whether the Python and JS suites can actually adopt them. Get this wrong and the fixtures become this repo's private test data, which is the status quo with extra steps.

Design the format against real cases, then prove it with a loader.

**Requirements the format must satisfy:**

- Declarative JSON only. No code, no language-specific escaping, no templating — a Go or Ruby SDK must be able to adopt it without us writing a runtime for them.
- Each case must be able to express: a request body to assert on, a response status + headers + body, an expected outcome, and the *error type name* expected (mapped per-language by each SDK, since the classes differ by namespace but not by name).
- Cases that are transport-level, not payload-level, must fit too: a `Retry-After` header case and a non-JSON error body. Decide how those are distinguished from pure decode cases without adding a schema engine.
- A case must be able to say "the response body is malformed in a way the SDK should reject" and name where.

**Cases to write as the proof set (10–15):**

- Happy-path request bodies for all three primitives, with `String` state and with structured state.
- Happy-path response for a mixed three-primitive call.
- An `Unknown` answer `type` alongside known ones.
- A response with `usage` absent.
- A response where `usage` lacks `billing_units` (the OpenAPI schema requires it; the API omits it).
- All four error body shapes: `{error:{message}}`, `{error:string}`, `{detail:string}`, and FastAPI `{detail:[{loc,msg}]}`.
- A non-JSON (HTML) error body.
- A `Retry-After` case, in both the seconds and HTTP-date forms.
- A `score` answer whose `legend` and `probabilities` keys are stringified integers.

**Then build it:**

1. Write a tiny `commonTest` loader (~30 lines, no framework) that reads the fixtures and hands each case to a test. If the loader is more than ~30 lines the format is wrong — say so and simplify.
2. Write one passing case end-to-end against a hand-stubbed transport to prove the seam works.
3. Draft the ADR that defines the format, in `docs/adr/`, since a foreign-language consumer must be able to read the spec without reading our test code.

Deliverable: `conformance/` populated with the proof set, `manifest.json`, the loader, and the ADR draft. Also state explicitly **which cases are not worth writing** — fixture sets rot when they grow past what anyone reads.

## Answer

**Format:** `conformance/manifest.json` + `conformance/cases/<id>.json`, `formatVersion` 1, spec in
[docs/adr/0005-conformance-fixture-format.md](../../../docs/adr/0005-conformance-fixture-format.md). A case is
`request {method, path, body}`, `responses[] {status, headers, body|bodyRaw}`, and `expect`. The **response array
is the attempt script** — one response is a decode case, two are a retry — which is the whole transport-level vs
decode distinction: no type field, no schema engine. `expect` names only what the case is *about*: `answers`
(key → `noul`/`choice`/`score`/`unknown`), `unknownTypes`, `usage` (`present`/`absent`), `error` plus `message`
or `field`, `delayMs`. The id is the file name, so there is no index to rot.

**Where the sketch bent** (all in the ADR):

- **`expect.message` is the extracted explanation, never the SDK's formatted string.** Python and JS extract
  identically and compose differently (`GET https://…: 401 invalid api key` vs `401 invalid api key`), so the
  composed form cannot live in a cross-language file.
- **`optional: true` is a field the ticket did not ask for.** `response-malformed-answer` needs response
  validation with a field path; JS 0.6.0 has no `APIResponseValidationError`, so that one case cannot be
  honoured by all three suites. Everything else is mandatory.
- **The HTTP-date `Retry-After` case asserts only that the retry happens.** A date is absolute, so its delay
  depends on `now`; asserting a number would bake a clock seam into the shared format. The seconds form does
  assert `delayMs: 2000`.
- **`body` vs `bodyRaw`** is the only body distinction — JSON to re-serialize, or exact bytes for the HTML error.
- **Not in the format:** request headers (they name the language and version), formatted error strings,
  truncation, and native integer-keying of `legend`/`probabilities` — a JSON comparison cannot tell `"0"` from
  `0`, so that stays in each SDK's typed tests while the fixture pins that the wire form is accepted.

**Cases not worth writing** are listed in the ADR: empty and bare-scalar bodies, message truncation, `null`
tokens, redirect/gzip/204, retry exhaustion, and anything client-side validation rejects before the wire.

**Proof:** 15 cases (the cap) — three request bodies, mixed/unknown/usage/stringified-keys/malformed responses,
four error shapes, HTML body, both `Retry-After` forms. `src/commonTest/.../Conformance.kt` is a ~35-line loader
plus a `MockEngine` replay harness; `ConformanceFixturesTest` runs all 15, and a second test corrupts one
expectation to show the check is not vacuous. `generateConformanceFixtures` bakes `conformance/**` into a
generated `commonTest` constant, because a KMP `commonTest` cannot read files on Native.

Gate: `./gradlew checkVersion ktlintCheck jvmTest apiCheck` → BUILD SUCCESSFUL, `tests="2" failures="0"`.
`compileTestKotlinLinuxX64` and `compileTestKotlinIosSimulatorArm64` also pass, so the loader is portable;
`linuxX64Test` is SKIPPED on this host by design and runs in CI's Linux lane. No public API, so
`api/jvm/jev-kmp.api` is untouched and there is no CHANGELOG line.

**For later tickets**

- *Build the conformance, wire, and live test tiers* swaps the `MockEngine` stub for the real client; the
  fixtures do not change, only the last assertion.
- That ticket must settle whether our response-validation error carries a field path, and whether we validate
  at all — `expect.field` is declared and currently only checked for presence.
- The canonical error names (`APIResponseValidationError`, `APIConnectionError`, …) are the sibling class names
  minus their prefix; ticket 12 should adopt them or the ADR's mapping changes.
- ADR `0005` is written, so *Write the foundational ADRs* must use other numbers.

