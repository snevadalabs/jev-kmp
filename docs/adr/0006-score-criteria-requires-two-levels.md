# 0006. Score criteria validation enforces at least two levels

- **Status:** Accepted
- **Date:** 2026-09-17
- **Source:** [Lock the v0.1 design brief](../../.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md) §10 and §11; [reference recon of the JS SDK](../../.scratch/jev-kmp-sdk/research/reference-typesafe-sdk-js-recon.md) §2; [reference recon of the Python SDK](../../.scratch/jev-kmp-sdk/research/reference-typesafe-sdk-python-recon.md) §2

## Context

A `score` question rates a state along ordered levels the caller defines. The API requires **at least
two** levels; a one-level score is not a judgment. The two reference SDKs disagree on where that is
caught:

- JS enforces `criteria.length >= 2` in `validateQuestions` before any request is sent.
- Python enforces only that the criteria list is non-empty. A one-level score is forwarded, and the
  caller receives a `422` from the server instead of a local error.

Client-side validation is otherwise deliberately minimal: everything that is not one of the two known
local checks is forwarded, matching both siblings.

## Decision

The client validates exactly two things locally: a non-empty question map, and a `score` question with
at least two criteria levels. Everything else is forwarded to the API. This follows JS.

## Alternatives

- **Forward everything and let the server's `422` decide**, as Python does. Rejected: the constraint is
  documented and knowable before the call. A round trip and an opaque `422` body are a worse error than
  a local `IllegalArgumentException` that names the question.
- **Enforce non-empty criteria only.** Rejected for the same reason; it accepts a request the API is
  documented to reject.
- **Validate more** — option counts, level uniqueness, criteria types. Rejected: the parity rule is
  exactly two local checks. Growing the list makes the client a partial re-implementation of the
  server's schema.

## Consequences

A one-level score fails locally, before the network, with an error naming the question. The validation
lives at the request boundary, next to the non-empty-question check, so both rejections are raised from
one place.

This is a real behavioural divergence from Python, recorded here so that a porting caller does not
mistake it for a bug.

## What would change our mind

The API relaxes the two-level minimum, or evidence appears that a one-level score is accepted in
practice — either would make the local check reject a valid request, and it would be removed.
