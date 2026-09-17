---
description: "Resolve one ticket from the local planning tracker end to end — claim it, do the work its Type demands, run the gate, record the Answer, push a branch. Argument: ticket number, optional (no argument → the first open, unblocked ticket in the map)."
---

Resolve ticket `$ARGUMENTS` from this repo's planning tracker. **There is no GitHub issue.** The tracker is committed markdown at `.scratch/jev-kmp-sdk/`, so it is present in your worktree.

No number given → take the first ticket in `.scratch/jev-kmp-sdk/map.md` whose `Status:` is `open` and whose `Blocked by:` are all resolved; none → say so and stop. Never guess a set.

## 1. Intake and claim

- `ls .scratch/jev-kmp-sdk/issues/ | grep "^$ARGUMENTS-"` then read that ticket in full.
- Read what it points at, in this order of authority:
  1. **The design brief**, `.scratch/jev-kmp-sdk/issues/01-lock-v0.1-design-brief.md` — the locked spec for v0.1.0. A section marked `[amended by …]` was overruled by later evidence, and the amendment is the current truth, not the original text.
  2. Any ticket it links, including that ticket's `## Answer`.
  3. The `.scratch/jev-kmp-sdk/research/*.md` files it cites. Research is recon, not truth: two of its claims about Ktor retry were falsified by direct checking, so verify anything load-bearing against the dependency's actual source or module metadata before you build on it.
  4. `docs/adr/` and `CONTEXT.md`, if they exist. Use their vocabulary in everything you write.
- Gates, all of which must hold before you touch code: the ticket's `Status:` is `open`, every id in `Blocked by:` reads `resolved` in its own file, and no branch named `issue-<n>-*` already exists on the remote (`git ls-remote --heads origin 'issue-<n>-*'`). A closed gate → report which one closed and stop.
- Claim it: set `Status: claimed` in the ticket file, and commit that change alone. The commit is the claim.

## 2. Read the `Type:`, then its protocol

| Type | What you owe |
| --- | --- |
| **task** | Verify the premise **before** building: is the ask already true of the current code, or obsolete? Premise false → record the evidence in the Answer, set `Status: resolved`, and ship that — no code. Otherwise build exactly what the ticket lists, nothing more. |
| **prototype** | Throwaway. Its job is to make an answer *checkable* by compiling and running it, and then to state a verdict. Nothing here is product code. Put it where the real build does not ship (its own module or source set) and keep that module's plugins minimal. The deliverable is the verdict in `## Answer`: the exact signatures or format to adopt, and every place the brief's sketch had to bend. |
| **research** | Facts from primary sources only — the dependency's source, its published module metadata, an official spec or docs page. Record the source for every claim. Never answer from memory, and never cite the recon as if it were the source. |

**Nothing to decide.** These tickets sit downstream of a locked brief. Where a ticket says nothing is to be decided and you find you need a decision that the brief, an ADR, or the ticket's own links do not already make, **stop and report the question**. Do not invent the decision. That is the one thing you may not unblock yourself, and a section in the brief that looks wrong is a finding to report, not a licence to overrule it.

## 3. Work

- **Tests are the spec.** The ticket's test list says what to write. `kotlin.test` only — no test framework, no assertion library, no fixtures library. Write the test first and watch it fail for the reason you expect.
- `commonTest` is where shared behavior is tested, against Ktor's `MockEngine`. A test belongs in a platform source set only when what it tests is platform-specific.
- `explicitApi` is `Strict`: every declaration is `public` or `internal`, and everything `public` carries KDoc. Implementation detail is `internal` — the transport, the retry mapping, the fixture loader, the `Retry-After` parser, and every seam injected for testing.
- Adding public API moves `api/jvm/jev-kmp.api`: run `./gradlew apiDump` and commit the result. Public API nobody asked for is a bug, not a bonus.
- Versions live in `gradle/libs.versions.toml`. Do not add a dependency the ticket does not name.
- **The gate is `./gradlew check`, and it must be green before you push.** GitHub Actions is disabled while the org's billing is blocked, so nothing runs on your pull request and there is no CI to fall back on — the full check is the only verification that happens. Several tickets running at once contend for the simulators; if an Apple task fails that way, say which one and why rather than retrying blindly.
- Leave `.scratch/jev-kmp-sdk/map.md` alone. The parent session owns it, and parallel branches editing one file conflict.
- **ADR ownership.** `0005` (the `conformance` fixture format) belongs to the fixture-format ticket. Every other number belongs to the foundational-ADR ticket. Write only your own numbers.
- Mark a deliberate simplification that cuts a real corner with a `ponytail:` comment naming the ceiling and the upgrade path.

## 4. Record the Answer

- Replace the ticket's `## Answer` with the record: what you built or found, the evidence (the command and the output you observed, the file and line), what you deliberately left undone, and what a later ticket must pick up. Short beats complete.
- Set `Status: resolved`. If the work is shipped but unmerged, say so in the Answer together with the PR link.
- Add a `CHANGELOG.md` line under `Unreleased` only for a change a user of the SDK would notice.

## 5. Ship

- Commit on the branch your worktree was created with (`issue-<n>-<slug>`).
- `git push -u origin HEAD`, then `gh pr create --fill --base main`. Actions is disabled, so the pull request will show no checks — that is expected, not a failure. The parent session runs the gate locally and merges.
- Do not merge, do not push to `main`, do not close anything. The parent session merges.

## 6. Report, then clean up

Report: ticket, type, branch, PR, the gate command you actually ran with its result, the files you touched, and any question you stopped on. If you stopped on a question, leave the ticket `claimed` and say so — do not resolve it.

Then, as the last thing you do, remove your own worktree so the panel reaper can close this panel:

```bash
main=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
git -C "$main" worktree remove "$(git rev-parse --show-toplevel)"
```

It refuses while the worktree is dirty, so commit and push first — nothing is lost once the branch is on the remote, and every later step reads the branch, not the directory.
