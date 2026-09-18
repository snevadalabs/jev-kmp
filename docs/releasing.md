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

The baseline from the 2026-09-18 run on the `0.1.0` tree: 762 mutants generated, 546 detected (540 killed, 6 timed out)
of 729 covered — **test strength 74.9%** — with **183 survivors** and 33 mutants on lines no test
executes. That sits against 95.33% Kover line coverage measured on the same compilation when the comparison was
first made (the floor is 94): a quarter of the covered mutants still live. The gap is the point of taking the
measurement at all.

The rule is a comparison, not a threshold. **A survivor count that has not dropped after ticket 21's port is a
signal to investigate a decode path, not a number to lower.** There is no useful floor at this size: one mutant
is about 0.14pp and repeat runs flip roughly 0.8% of mutants, so a score within a point of the baseline is
noise. When the count moves the wrong way, classify the survivors first — equivalent mutant, genuinely unpinned
behaviour, or dead code — using the per-mutant classification in
[research/22](../.scratch/jev-kmp-sdk/research/22-mutation-testing-evaluation.md). Add a case for a genuinely
unpinned one; do not chase the score.
