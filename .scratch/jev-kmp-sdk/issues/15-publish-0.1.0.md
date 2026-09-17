# Publish 0.1.0 to Maven Central

Type: task
Status: open
Blocked by: 13, 14, 18

## Question

Nothing to decide — everything is built and gated. This ticket is the human-in-the-loop release, and it is the only ticket on the map that cannot be finished by an agent alone.

**[noted by the parent before dispatch]** Two prerequisites are missing and one is stale. The repo's GitHub Actions minutes are exhausted — every job fails in 9s without starting, and `check.yml` is disabled — and the Portal credentials and signing key are not repository secrets. **So do not tag or push `v0.1.0`:** `publish.yml` still triggers on `v*`, the run cannot start, and the tag would be an orphan pointing at a commit no CI ever verified. A tag is cheap to make and socially expensive to walk back. Do the parts that are safe — cut the `0.1.0` CHANGELOG section, commit the API dump, run the gate locally with `./gradlew check` — and report exactly what the human must do first. Steps 2 and 4 lose their CI half until billing is restored. Also stale: prerequisite 4 ("create the remote repository") is done, `snevadalabs/jev-kmp` exists and is pushed.

**Human prerequisites** (from *Establish Maven Central publishing* — fill in the exact steps there, do not re-derive them here):

1. Register and verify the `com.sierranevadalabs` namespace on the Sonatype Central Portal.
2. Generate a PGP signing key and publish the public half to a keyserver.
3. Add the portal credentials and signing key as repository secrets on the GitHub repo.
4. Create the remote repository and push `main`.

**Agent work, once the above exist:**

1. Cut the `0.1.0` section in `CHANGELOG.md` with the real release date, replacing `Unreleased`. The version-consistency check in CI must pass.
2. Verify the full gate green on `main`: `ktlintCheck`, `apiCheck`, `allTests`, coverage floor, doc-compilation test. Nothing ships on a red gate.
3. Commit the generated `api/jev-kmp.api` dump — it is the 0.1.0 compatibility baseline that every future release is checked against.
4. Tag `v0.1.0` and let the publish workflow run.
5. Verify the artifact actually resolves from a clean, unrelated project — not just that the upload succeeded. `implementation("com.sierranevadalabs:jev-kmp:0.1.0")`, one call, one answer. Both siblings have a distribution smoke test for exactly this reason; Python's is shallow (it checks the version and that the client constructs) and JS's is not (it installs the tarball and round-trips). Do the JS version.
6. Create the GitHub Release with the changelog section as its body.

**Then, the point of the effort:**

7. Open a pull request upstream against `typesafe-ai/typesafe-sdk-python` and `typesafe-sdk-js` offering the `conformance/` fixtures, with the ADR as the spec. This is the only deliverable on the map that outlives the SDK itself — three SDKs agreeing on the wire is worth more than a fourth one existing.

Deliverable: `0.1.0` resolving from Maven Central, verified from a clean project, with the GitHub Release published and the fixtures offered upstream.

## Answer
