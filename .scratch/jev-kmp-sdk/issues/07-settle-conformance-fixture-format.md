# Settle the conformance fixture format

Type: prototype
Status: open
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
