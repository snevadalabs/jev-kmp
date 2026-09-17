# 5. Conformance fixtures are declarative JSON in this repo

- **Status:** accepted
- **Date:** 2026-09-17
- **Spec version:** `formatVersion` 1
- **Brief:** §16 of `.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md`, settled by
  `.scratch/jev-kmp-sdk/issues/07-settle-conformance-fixture-format.md`

## Context

The SDK is justified partly by a wire-conformance suite that the Python and JS SDKs can adopt unchanged. Hand-
written JSON cases are the mechanism; this file is the format those three suites agree on. It has to be readable
by a foreign loader with no schema engine and no Kotlin, and it has to be strong enough that a case can state a
request body, a scripted response, and the outcome the SDK must reach.

The fixture set is a proof set, not a test suite: 16 cases is the cap. Fixture sets rot when they grow past what
anyone reads, so a case earns its place only by pinning a wire behaviour that a foreign SDK could plausibly get
wrong. **[amended by *Close the three parity gaps a sibling audit turned up*]** The sixteenth is noul criteria:
both siblings send `criteria: {"true"?, "false"?}` and this SDK could not express it at all, which is the exact
kind of wire behaviour the sentence above admits — the first fifteen were written from the same recon that missed
the field.

## Decision

### Location and discovery

```
conformance/manifest.json
conformance/cases/<id>.json
```

| `manifest.json` field | Meaning |
| --- | --- |
| `formatVersion` | Integer. `1` is this document. A loader refuses a version it does not know. |
| `description` | One sentence, for humans. |
| `spec` | Path to this file, relative to `manifest.json`. |
| `cases` | Directory, relative to `manifest.json`, holding the cases. |

Discovery is: read the manifest, list `*.json` in its `cases` directory, sort by file name. A case's `id` **must**
equal its file name without the extension. There is no index list to keep in sync.

### A case

```json
{
  "id": "error-401-error-object",
  "description": "{error: {message}} — the shape the live API actually returns.",
  "optional": false,
  "request": { "method": "POST", "path": "/v1/systemone", "body": { "state": "…", "model": "…", "questions": {…} } },
  "responses": [{ "status": 401, "headers": { "content-type": "application/json" },
                  "body": { "error": { "message": "invalid api key" } } }],
  "expect": { "error": "AuthenticationError", "message": "invalid api key" }
}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Equals the file name. Unique. |
| `description` | yes | One sentence: what the case pins. |
| `optional` | no | `true` marks a case only some suites can honour. See *Optional cases*. |
| `request.method` | yes | HTTP method. |
| `request.path` | yes | Path, relative to the SDK's `baseUrl`. |
| `request.body` | yes | The request body as JSON. The loader re-serializes it; byte-for-byte equality is **not** required, but no key may be added, dropped, or retyped. |
| `responses` | yes | The attempts, in order. |
| `responses[].status` | yes | Response status. |
| `responses[].headers` | no | Response headers, lower-case names as the wire sends them. |
| `responses[].body` | one of | A JSON body. |
| `responses[].bodyRaw` | one of | The body as an exact string, for bodies that are not JSON. |
| `expect` | yes | The outcome, below. |

`body` and `bodyRaw` are mutually exclusive, and `bodyRaw` means "these exact bytes, not JSON" — it is how the
non-JSON error-body case avoids a parser. Nothing else distinguishes a decode case from a transport case: the
**response list is the script**, so a case with one response is a decode case and a case with two is a retry.

### `expect`

| Field | Meaning |
| --- | --- |
| `answers` | Object: answer key → `noul`, `choice`, `score`, or `unknown`. Every listed key must be present with that type. |
| `unknownTypes` | Object: answer key → the server's own type string, for keys typed `unknown`. |
| `usage` | `present` or `absent`. Omitted means the case does not care. |
| `error` | The canonical error name the call must fail with. Omitted means the call must succeed. |
| `message` | The **extracted** explanation, exactly. See *Messages*. |
| `field` | Dotted path of the offending response field, for a response-validation failure. |
| `delayMs` | Whole milliseconds the SDK must wait before the second attempt. Only valid with two or more responses. |

A case declares either `error`, or `answers`/`usage` — never both. A case that declares `error` must also declare
`message` or `field`, so an error case can never be satisfied by "something went wrong".

### Canonical error names

The name in `expect.error` is the class name every SDK uses for that failure, with any SDK prefix and namespace
removed: `BadRequestError`, `AuthenticationError`, `PermissionDeniedError`, `NotFoundError`,
`UnprocessableEntityError`, `RateLimitError`, `InternalServerError`, `APIConnectionError`, `APITimeoutError`,
`APIResponseValidationError`. Python's `TypeSafeAuthenticationError` and Kotlin's `AuthenticationError` are the
same case. A language maps by name and asserts the case with its own class.

### Messages

`expect.message` is what the SDK extracts from the error body — never the string the SDK eventually formats. The
siblings agree on extraction and disagree on composition (`404 not found` vs `GET https://…: 404 not found`), so
the composition is each SDK's own test's business. The extraction rules the fixtures pin:

- `{error: string}` → the string; `{error: {message}}` → the message.
- `{message: string}` → the message.
- `{detail: string}` → the string; `{detail: {message}}` → the message.
- `{detail: [{loc, msg}]}` → `path: msg` per entry, joined with `"; "`, where `path` is `loc` with any leading
  `body` element dropped, dotted.
- A non-JSON body → the raw text.

### Optional cases

`optional: true` marks a case whose failure mode not every SDK implements. A suite whose SDK lacks the capability
may skip it, and must say so rather than pass it silently. Exactly one case is optional today:
`response-malformed-answer`, which requires response validation with a field path. JS 0.6.0 has no
`APIResponseValidationError` and cannot honour it; Python 0.6.0 and this SDK can.

### Deliberately not in the format

- **Request headers.** `X-TypeSafe-SDK` and `X-TypeSafe-Runtime` name the language and version, so there is no
  cross-language expectation. The wire tests in each SDK assert them.
- **Response header assertions beyond what a case needs.** Request-id surfacing is per-SDK.
- **Formatted error strings, the 200-character body truncation, and the empty-body message.** Each SDK's own
  tests, because the two siblings differ in composition.
- **Clock-dependent delays.** An HTTP-date `Retry-After` is absolute, so its delay depends on `now`. The
  `retry-after-http-date` case proves only that the form is accepted and the request retried; the parsed value is
  asserted by each SDK's own parser test with an injected clock.
- **Native representations JSON cannot state.** `response-score-stringified-keys` proves the SDK accepts
  stringified integer ordinals. That `legend` and `probabilities` decode to *integer*-keyed maps is asserted by
  each SDK's typed tests — a JSON comparison cannot tell `"0"` from `0` apart.

## Consequences

- The Python and JS suites adopt a case by reading one file and mapping the canonical error name to their class.
  No shared runtime, no schema engine, no code generation.
- The fixture set is versioned by `formatVersion`, so a v2 format can land beside v1 rather than mutating it.
- Fixtures live in this repo; if upstream adopts them, `git mv conformance/` and nothing breaks.
- A new case must not need a new field. If it does, that is a format change and a new `formatVersion`.

## Alternatives

- **A shared `typesafe-conformance` repo.** Rejected: it makes all three suites depend on a second repo before
  anyone agreed to maintain it, and we cannot create repos in the upstream org.
- **JSON Schema plus a validating loader.** Rejected: every loader would need a schema validator, which is the
  runtime we are trying not to write.
- **One file per language, or fixtures as code.** Rejected: the point is that a Go or Ruby SDK can read them.
- **Asserting response bodies by full equality.** Rejected: the expectation would be a copy of the payload, so a
  typo in the payload would be asserted as correct. `expect.answers` names only what the case is about.

## Cases not worth writing

- Empty-body and bare-scalar error bodies (`b""`, `null`, `42`) — the message is per-SDK composition.
- 200-character truncation of an unstructured body — same reason.
- `usage` with `input_tokens: null` — a sibling nuance, not a wire behaviour a client can get wrong.
- Redirect, gzip, and 204 handling — transport mechanics the SDKs delegate to their HTTP client.
- A retry that exhausts `maxRetries` — the delay curve and attempt count are policy, and each SDK's retry tests
  pin them with an injected clock and RNG.
- Anything the SDK is expected to *reject* before the network (`state = null`, an empty question map, a
  two-level-minimum score) — client-side validation, and a fixture implies a request that was never sent.

## How this repo consumes it

`src/commonTest/kotlin/com/sierranevadalabs/jev/sdk/conformance/Conformance.kt` is the loader. A KMP
`commonTest` cannot read files on Native, so the `generateConformanceFixtures` Gradle task bakes `conformance/**`
into a generated Kotlin constant in the `commonTest` source set, and the loader reads that. Every target's tests
therefore see the same bytes. The rest of the format is unchanged and stays language-neutral.

## What would change our mind

A second implementation writing fixtures by hand and needing a field this format lacks — that is a v2, not a
flag. Or upstream adopting the set: then the format moves with it, and the shared repo question comes back.
