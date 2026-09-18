# Give the on-demand mutation run a trigger, at release time

Type: task
Status: open
Blocked by:

## Question

Nothing to decide — [Evaluate mutation testing](22-evaluate-mutation-testing.md) settled that PIT stays an
on-demand task, and this ticket lands the one hole it left: **an on-demand task with no trigger rots.** Today
`./gradlew pitestJvm` is documented in the README and nothing in the repo says *when* to run it, so its value is
front-loaded into the one run that produced the 157 surviving mutants and ends there.

The data that decided it, from `research/22-mutation-testing-evaluation.md`: **74.6% test strength (534 of 716
covered mutants) against 95.33% Kover line coverage** on the same compilation, so line coverage was overstating
safety by a fifth — and **no safe threshold exists** at this size, because one mutant is 0.14pp and repeat runs
flip 0.8%. A gate would be noise; a measurement nobody takes is nothing. So: one trigger, at the moment that
already exists — a release — and the rule for reading the number.

**Build:**

1. **`docs/releasing.md`** — the pre-tag procedure, short, in order: `./gradlew check` green on `main`; then
   `./gradlew pitestJvm`, recording the strength and the survivor count; then the human prerequisites, which stay
   where they are in [Publish 0.1.0](15-publish-0.1.0.md) — link that ticket for them, do not restate its list.
   The comparison rule is the point of the file: the baseline is 534 killed / 716 covered / 74.6%, 182 survivors
   of 749 generated; **a survivor count that has not dropped after ticket 21's port is a signal to investigate a
   decode path, not a number to lower**, and the per-mutant classification in `research/22-…` is how to tell the
   difference. Keep it to a page.
2. **`publish.yml`** — one `pitestJvm` step on the tag push, **explicitly non-gating** (`continue-on-error: true`)
   with the reason in a comment: there is no threshold that means anything (0.8% flip rate), and a release must
   not fail on a measurement. A tag push is the only moment anyone releases, so the number lands in the run's log
   whether or not a human remembers the checklist.
3. **README** — one line from the existing "Mutation testing, on demand" subsection to `docs/releasing.md`, so the
   doc is discoverable from the place the command is documented.

**Constraints.**

- No threshold, no gate, and `check` must stay exactly as it is — 41 s locally, with `pitestJvm` still outside it.
- **Do not edit** `.scratch/jev-kmp-sdk/issues/15-publish-0.1.0.md` (the parent adds the pointer there) and do not
  edit `research/22-mutation-testing-evaluation.md` — that baseline is a historical measurement, and ticket 21's
  delta belongs in ticket 21's Answer.
- Adding a published-artifact change is not in scope: the `pitest` configuration must keep producing POMs with no
  reference to it, which ticket 22 already verified. Re-verify rather than assume.

**Verification.** `./gradlew check` green. `./gradlew pitestJvm` runs on the merged tree and prints the baseline
above — if the numbers have moved, ticket 21 has landed in between and that is the new baseline to write down.
The YAML parses. Then say so honestly in the Answer: **the workflow step itself cannot be exercised while Actions
is blocked by billing**, so it ships reviewed-not-run, and name what the first real tag run would prove.

**Deliverable:** `docs/releasing.md`, the non-gating publish step with its comment, the README pointer, and an
Answer carrying the numbers the run produced.
