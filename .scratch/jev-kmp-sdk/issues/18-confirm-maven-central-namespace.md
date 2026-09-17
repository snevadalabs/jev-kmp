# Confirm the Maven Central namespace

Type: task
Status: resolved
Blocked by:

## Question

Nothing to decide — find out one fact that only the owner can supply, because the destination's coordinates depend on it.

*Establish Maven Central publishing* found that `com.sierranevadalabs` can be registered on the Sonatype Central Portal by **exactly one** route: proving DNS control of `sierranevadalabs.com` with a TXT record. The code-hosting verification path is limited to *personal* `io.github.<username>` namespaces, so a GitHub **org**-based claim is not offered.

So: **does Sierra Nevada Labs control `sierranevadalabs.com` and its DNS?**

- **If yes** — the coordinates locked in the design brief (`com.sierranevadalabs:jev-kmp`, package `com.sierranevadalabs.jev.sdk`) stand. Add the TXT record when the namespace is registered, per the checklist in [research/03-maven-central-publishing.md](../research/03-maven-central-publishing.md).
- **If no** — the group id must change, and the package name with it. The realistic fallback is `io.github.<username>` (personal namespace, verified by a temporary public repo named after the Verification Key), which means coordinates like `io.github.franfernandez:jev-kmp` and a package under the same root. That is a visible downgrade for a library we intend to offer upstream, so it is worth checking before committing.
- **If there's a third option** — an alternative domain the org does control (the group id does not have to match the company name, only a domain we can prove control of) — that resolves it too.

Record the answer, and if the coordinates change, say so loudly, because *Lock the v0.1 design brief* and *Scaffold the repo and its CI gate* both encode them.

The remaining human steps from the publishing checklist are not this ticket's job — they belong to *Publish 0.1.0*, which is where the owner does the one-time Portal, PGP, and secrets work.

## Answer

**Confirmed: Sierra Nevada Labs controls `sierranevadalabs.com` and its DNS.** The owner supplied the fact directly; no fallback is needed.

**Consequence: the locked coordinates stand unchanged.**

- Group id `com.sierranevadalabs`, artifact `jev-kmp` → `com.sierranevadalabs:jev-kmp`.
- Package `com.sierranevadalabs.jev.sdk`.
- Brief §2 is untouched, and *Scaffold the repo and its CI gate* can encode the group id exactly as written. The `io.github.<username>` fallback — which would have meant `io.github.franfernandez:jev-kmp` and a matching package root, and a visible downgrade for a library intended for handover upstream — is off the table entirely.

Registering `com.sierranevadalabs` grants publishing rights for every sub-group, so no further namespace work is implied.

**The remaining work is one-time and belongs to *Publish 0.1.0*** (which now has one fewer unknown): Portal account → Portal issues a Verification Key → TXT record added on `sierranevadalabs.com` → verify → delete the record. Per [research/03-maven-central-publishing.md](../research/03-maven-central-publishing.md) §1 the automated check inspects the exact reversed domain (`com.sierranevadalabs` → `sierranevadalabs.com`, no subdomain), and **"Verify Namespace" must not be clicked before the TXT record resolves** — a failed check caches NXDOMAIN and delays verification. That is a ~15-minute human task, not a blocker on any code path.
