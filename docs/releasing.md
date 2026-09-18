# Releasing

The pre-tag procedure for this SDK. It is short on purpose: the gate is `./gradlew check`, the mutation run is a
measurement rather than a gate, and the parts only a human can do stay where they live — the ordered list is in
[Publish 0.1.0 § Human prerequisites](../.scratch/jev-kmp-sdk/issues/15-publish-0.1.0.md), and it is linked
rather than restated here.

## Pre-tag, in order

1. **`./gradlew check` green on `main`.** This is the whole gate: ktlint, `apiCheck`, the Dokka KDoc gate, the
   version/CHANGELOG check, the Java 8 bytecode assertion, the Kover floor, and every test target the host can
   run. GitHub Actions is disabled while the org's billing is blocked, so nothing runs on a pull request — this
   local run is the only verification a release gets.
2. **`./gradlew pitestJvm`**, and write down the test strength and the survivor count it prints. Read
   `build/reports/pitest/mutations.xml`; the HTML report mis-attributes line numbers for inlined Kotlin, so
   attribute a mutant by method, not by line. `publish.yml` runs the same task on the tag push, explicitly
   non-gating, so the number also lands in the release run's log whether or not anyone works this checklist.
3. **The human prerequisites** — the Portal namespace TXT record, the PGP key, the repository secrets and the POM
   developer email. They are ordered, with secret names, in
   [Publish 0.1.0 § Human prerequisites](../.scratch/jev-kmp-sdk/issues/15-publish-0.1.0.md). Then tag.

## Reading the number

The baseline from the runs at `39eaccb`, on the `0.1.0` tree as it stands (ticket 21's test port merged, and the
PIT filter narrowed to `excludedMethods = *lambda*` after two wider filters were measured and rejected): **727
mutants generated**, 697 covered, **118–122 survivors**, 30 of them on lines no test executes, **test strength
82.5–83.0%** — the range is two consecutive runs of an unchanged tree. That sits against 95.33% Kover line
coverage measured on the same compilation (the floor is 94): about a fifth of the covered mutants still live. The
gap is the point of taking the measurement at all.

An earlier pass recorded 762 mutants and 183 survivors here. That worktree was cut from `ece128a`, before ticket
21's test port merged, so its numbers describe the pre-port tree (185 survivors is what ticket 21 started from) and
should not be compared against the run above.

The rule is a comparison, not a threshold. **A survivor count that has not dropped is a signal to investigate a
decode path, not a number to lower.** Ticket 21's port is the worked example: nine malformed-body and edge-value
tests took survivors 185 → 139 and test strength 74.6% → 81.1%. There is no useful floor at this size: one mutant
is about 0.14pp and repeat runs flip roughly six mutants, so a score within a point of the baseline is noise. When
the count moves the wrong way, classify the survivors first — equivalent mutant, genuinely unpinned behaviour, or
dead code — using the per-mutant classification in
[research/22](../.scratch/jev-kmp-sdk/research/22-mutation-testing-evaluation.md). Add a case for a genuinely
unpinned one; do not chase the score.
