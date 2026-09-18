# Give the on-demand mutation run a trigger, at release time

Type: task
Status: resolved
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

## Answer

**Shipped, unmerged**: PR <https://github.com/snevadalabs/jev-kmp/pull/14> (the parent session merges).

**Built.** `docs/releasing.md` — the pre-tag procedure in order (`check` green on `main`, then `pitestJvm`, then
the human prerequisites linked to [Publish 0.1.0](15-publish-0.1.0.md), not restated), the baseline, and the
comparison rule. `.github/workflows/publish.yml` gains one `pitestJvm` step between `checkVersion` and
`publishToMavenCentral`, `continue-on-error: true` with the reason in the comment (no threshold means anything at
0.8% flip; a release must not fail on a measurement). `README.md`'s "Mutation testing, on demand" subsection now
points at `docs/releasing.md`. `build.gradle.kts` is untouched: `pitestJvm` stays outside `check`, which is still
50 s from clean and 1 s warm.

**The numbers moved, and not because ticket 21 landed.** The ticket expected a move only from ticket 21's port,
but that port has not landed (its ticket is still `open`, and `issue-21-replicate-sibling-test-cases` is on the
remote and unmerged). `./gradlew pitestJvm --no-build-cache --rerun-tasks` printed `Generated 762 mutations Killed
546 (72%)` / `Mutations with no coverage 33. Test strength 75%` in 1 m 45 s; `mutations.xml` parses to 540 killed,
6 timed out, 183 survived, 33 no coverage — **546 detected of 729 covered = 74.9%, 183 survivors of 762**. The
whole 749→762 delta is `Questions.kt`, 36→49 generated mutants; every other source file's generated count matches
ticket 22's table exactly. `Questions.kt` gained `NoulCriteria` and the defaulted `criteria` on both `noul`
builders in ticket 20 (commit `05d31b0`), which merged after ticket 22 measured its baseline. So this is ticket 20's
13 mutants, and `docs/releasing.md` records the new baseline rather than the ticket's stale one.

**Verified rather than assumed.** `./gradlew check --rerun-tasks` → `BUILD SUCCESSFUL in 50s`, 69 of 69 tasks
executed (Kover, Dokka, `apiCheck`, both ktlint lanes, Android host tests). All eight
`generatePomFileFor*Publication` POMs contain zero references to `pitest` or `arcmutate` (recursive `grep -ril`
exits 1). `.github/workflows/publish.yml` parses (`ruby -ryaml -e YAML.load_file`).

**Honest limits.** The workflow step ships **reviewed, not run** — Actions is blocked by the org's billing, so no
CI can execute it. The first real tag run would prove the step is reachable on `macos-latest` (PIT launches on the
JVM test compilation's classpath) and that the strength/survivor line lands in the run log. Ticket 21 has not
landed, so the "did the survivor count drop?" comparison its rule is written for is still pending; a later
release-time run is where it gets answered.

**Deliberately left undone.** No `CHANGELOG.md` entry — docs and workflow only, nothing a user of the SDK
notices. `check` was not modified, ticket 15 was not edited, and `research/22` was not touched; its 749/534/74.6%
figures stay the historical measurement.

**Gate.** `./gradlew check --rerun-tasks` green (50 s), on the merged tree and after the doc's numbers were
corrected to the measured run.
