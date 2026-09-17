# Implement the question and answer model

Type: task
Status: open
Blocked by: 06, 09

## Question

Nothing to decide beyond what *Prototype the typed question API* settles — build the public typed surface.

**Questions.** `Noul`, `Choice`, `Score` as `Question<T : Answer>` values, produced by the builder functions the prototype lands on, with the `JsonElement` core and the `String`/`Map<String, String?>` convenience overloads from the brief. Keys are typed so that the answer type is known statically. The raw map form must keep working, since it is what a Python or JS user reaches for first.

**Answers.** The sealed hierarchy — `NoulAnswer`, `ChoiceAnswer`, `ScoreAnswer`, `UnknownAnswer(type, raw)` — decoding from the wire. Decoding rules that must be explicit and tested:

- Unknown answer `type` decodes to `UnknownAnswer` rather than throwing, and the raw payload is preserved.
- Unknown *extra fields* on a known answer are tolerated and ignored, matching both siblings' forward-compatibility posture.
- `score` `legend` and `probabilities` keys arrive as stringified integers and must surface as integers.
- A *malformed known* answer (missing `noul`, `choice` without `probabilities`) fails with a validation error naming the exact field path, the way Python's `field_path` does. Write the path-building helper once, not per-field.
- An answer key present in the response but absent from the request, and vice versa: decide and test the behaviour rather than letting it fall out.

**Validation, client-side, exactly two checks:** the question map is non-empty, and score criteria has at least two levels. Everything else is forwarded to the server. Test that an invalid-but-well-formed question reaches the transport rather than being rejected locally — the sibling suites both assert this and it is easy to over-validate by accident.

**Tests.** Type-level guarantees are half the deliverable here, so the tests must fail to compile when the guarantee is broken: a caller reading a `ChoiceAnswer` off a noul key must not compile, and neither must reading an answer off a key that was never sent. Whatever mechanism the prototype lands on, leave behind the smallest thing that turns a regression in the typing into a build failure.

Deliverable: question and answer sources, the field-path validation helper, and the tests.

## Answer
