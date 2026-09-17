# Write the README and KDoc

Type: task
Status: open
Blocked by: 12

## Question

Nothing to decide — the SDK is built and `explicitApi()` already forces KDoc onto every public declaration. This is the pass that makes the SDK usable by someone who has never seen the Python docs.

**KDoc.** Fill in what `explicitApi()` forced but nobody wrote. Two rules, taken from the recon of both siblings' best moments: every public declaration says what it does in one sentence, and anything with a non-obvious failure mode says when it throws and which type. No `@param` restating the parameter name. The retry policy, the `UnknownAnswer` variant, and the config-resolution precedence are the three places where a reader needs real prose rather than a signature.

**README.** Structured to mirror the Python and JS READMEs so a reader moving between SDKs sees the same shape:

- One-paragraph what-this-is, linking to `https://docs.typesafe.ai/`.
- Install, for Gradle Kotlin DSL, with the actual coordinate.
- Quickstart: set `TYPESAFE_API_KEY`, construct the client, ask one question of each primitive, read the typed answer. Show the typed-key accessor, because that is the thing this SDK does that the siblings cannot.
- A short "differences from the Python and JS SDKs" section: suspend-only, typed question keys, hand-rolled retry with the `maxRetryAfter` cap, score criteria requiring two levels, and that logging never records headers or bodies. Honest and specific — this section is what an evaluator reads first.
- Running the tests, including how to opt into the live tier.
- Error handling: the class names, and that they match the siblings by name.

**Executable docs.** Compile the README's Kotlin blocks as a test, the way the Python suite does with Sybil. Roughly 15 lines: extract fenced `kotlin` blocks, wrap, compile against the built artifact. Without this the README rots the moment a signature changes — which is exactly how the Python suite's doc tests earn their keep, and exactly what the JS repo does not do.

Deliverable: README, complete KDoc, and the doc-compilation test wired into CI.

## Answer
