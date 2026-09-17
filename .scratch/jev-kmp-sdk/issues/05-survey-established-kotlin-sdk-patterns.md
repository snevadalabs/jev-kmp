# Survey established Kotlin SDK patterns

Type: research
Status: resolved
Blocked by:

## Question

We are told to follow well-established patterns without overcomplicating. What do the established Kotlin/KMP *client SDKs* actually do — not blog posts, but the source of libraries people depend on?

Read the source of at least four of: `supabase-kt`, `openai-kotlin` (a Kotlin port of a sibling SDK — closest analog to our task), `firebase-kotlin-sdk`, `stripe-android`, `kubernetes-client-kotlin`, `aws-sdk-kotlin`, `tgbotapi`, `kotlin-telegram-bot`, plus `ktor-client` itself.

For each, report concretely (file paths, short excerpts):

1. **Client construction.** One class or several? Constructor config object vs individual parameters? Does it implement `Closeable`/`AutoCloseable`, and how does it treat an injected dependency it does not own?
2. **Typed accessors.** How do these libraries give callers a statically typed result keyed by a name — is there an established equivalent of Ktor's `AttributeKey` / our typed question key? Look for `*Key` patterns, sealed result hierarchies, and how they handle an unknown payload variant from the server.
3. **Retry.** Hand-rolled or library? Where does the delay function live and is it injectable? How do their tests make it deterministic?
4. **Errors.** Sealed class vs exception hierarchy vs `Result`-returning. Which do they pick for a network client and why? Do they translate transport exceptions into their own types at a single boundary?
5. **Public surface discipline.** Do they use `explicitApi()`, binary-compatibility-validator, `@InternalTypeSafeApi`-style opt-in markers, or `internal`-by-default with a small public facade? What does the `api/` dump actually look like in practice?
6. **Module layout.** Single module or split (`core` / `api` / each transport)? Where do tests for a multiplatform library live, and do they use `commonTest` for the bulk?
7. **Anything they do that we should copy, and anything they do that is over-engineering for a 15-file SDK.** Be opinionated — the brief is "well-established, not complicated".

Deliverable: a comparative table plus a short "patterns we adopt / patterns we reject" list tied to specific libraries and files.

## Answer

Full comparative survey with file:line citations across `openai-kotlin`, `supabase-kt`, `firebase-kotlin-sdk`, `aws-sdk-kotlin`, and `ktor-client`: [research/05-established-kotlin-sdk-patterns.md](../research/05-established-kotlin-sdk-patterns.md).

The mature Kotlin SDKs converge on a small, boring shape — and two parts of it **contradict decisions we locked in the design brief.**

**Conflict 1 — the client is an interface, not a class.** Brief §9 says `TypeSafeClient` is a regular class. The survey found that **zero** of the four product SDKs expose a public concrete client class; all use `interface X` + `internal class XImpl` + a top-level `fun X(config)` factory. A concrete client class appears in none of their api dumps. → *Reconcile the client shape and answer access with the Kotlin SDK survey*.

**Conflict 2 — retry should use the HTTP library's plugin.** Brief §10 hand-rolls it. The survey's verdict is openai-kotlin's 12 lines: use Ktor's plugin, expose only `maxRetries`/`base`/`maxDelay`, and add `delay: suspend (Long) -> Unit` as a config field *because* that one lambda is the whole determinism seam. This corroborates *Recon Ktor transport mechanics* from an independent direction. → *Reconcile retry against Ktor's built-in HttpRequestRetry*.

**What the survey settles in our favour**, worth knowing we're not wrong: suspend-only is the norm; a config class beats a builder DSL (supabase's `@SupabaseDsl` builder exists only because it composes six independently published plugins); one module is right for ~15 files, and the api/client split openai-kotlin uses costs a second coordinate, a BOM and a second api dump; and never closing a dependency instance we were handed is exactly Ktor's own `manageEngine = true|false` rule.

**Refinements to adopt wholesale:**

- **Every typed accessor gets an `OrNull` twin**, and an unrecognised server discriminator returns `null` and logs — it never throws. Only *malformed* data throws, never *unrecognised*. Our `UnknownAnswer` posture is right; the accessor shape gets the twin.
- **Never `enum class` on a string the server owns.** Use a `@JvmInline value class` plus companion constants; unknown values then pass through deserialization instead of throwing. The one violation found in the entire survey throws `NoSuchElementException` on an unrecognised event type.
- **Sealed base exception in exactly two branches** — SDK/API errors and IO/transport errors — with one `when`-based translation function at the transport boundary and `CancellationException` rethrown first. Not `Result<T>`: nobody in the survey does that for a network client.
- **`explicitApi()` + one `@InternalJev` marker + BCV with one committed dump + `apiCheck` in CI** is the exact configuration of the closest analog. Two real gotchas from reading actual dumps: a `@JvmInline value class` in a signature produces mangled names with a type hash (and wrapping an *existing* parameter in a value class is binary-incompatible while looking source-compatible), and a dump records drift, not intent — so read the diff before committing it. Do **not** add per-target dump dirs or `.klib.api` until we publish more than one target that matters.
- **Tolerate unknown fields globally** (`ignoreUnknownKeys = true`), matching both siblings' posture.

The survey also ranks ten over-engineering traps for a ~15-file SDK — a plugin registry, an `Attributes` bag, a retry *framework*, a `core`/`client` split, a BOM module, a `test-common` module, four opt-in markers, and `apiValidation { ignoredPackages }` escape hatches. All rejected with reasons.
