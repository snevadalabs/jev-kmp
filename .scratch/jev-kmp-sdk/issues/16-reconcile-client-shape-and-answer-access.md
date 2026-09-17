# Reconcile the client shape and answer access with the Kotlin SDK survey

Type: grilling
Status: resolved
Blocked by:

## Question

*Survey established Kotlin SDK patterns* contradicts two parts of the locked design brief. Both need the owner's call before the public surface is built on them.

Read [research/05-established-kotlin-sdk-patterns.md](../research/05-established-kotlin-sdk-patterns.md) first — specifically §1, §2, and the adopt/reject lists.

### Decision A — is `TypeSafeClient` an interface or a class?

The brief (§9) says: a regular class, **not a `data class`**, so a generated `toString()` can't print `apiKey`. That reasoning still holds either way.

What the survey found: of `openai-kotlin`, `supabase-kt`, `firebase-kotlin-sdk`, and `aws-sdk-kotlin`, **none** exposes a public concrete client class. All four ship `interface X` + `internal class XImpl` + a top-level `fun X(config)` factory, and the concrete class appears in no api dump. openai-kotlin's entire public entry point is 62 lines.

Options:

1. **Keep the class.** Fewest concepts; a user fakes it by... they can't. Our own tests construct the real thing over `MockEngine`, which is what the brief's test plan does anyway.
2. **Adopt the survey shape** — `interface TypeSafeClient : AutoCloseable` + `internal class TypeSafeClientImpl` + `fun TypeSafeClient(config: TypeSafeConfig): TypeSafeClient`. Costs one interface with one implementation; buys consumer-side fake-ability and hides the constructor entirely from the api dump.
3. Interface only for the client, classes for everything else.

Worth weighing: the factory function keeps the name `TypeSafeClient` at the call site, so a Python or JS user's code still reads the same. The cost is an interface and an impl class where there is exactly one of each — the kind of thing the map's "out of scope" list already rejects as unrequested abstraction, except that four established libraries disagree.

### Decision B — how does a typed answer read, and what happens when the type is wrong?

The brief (§7) says `r[category]` returns `ChoiceAnswer` statically typed, and the prototype ticket (*Prototype the typed question API*) is already charged with settling whether an unexpected answer under a valid key throws, returns nullable, or returns the sealed supertype.

The survey supplies a strong precedent the prototype didn't have: supabase-kt's `PostgrestResult` keeps the raw payload and gives **every** typed accessor an `OrNull` twin (`decodeAs`/`decodeAsOrNull`, `decodeSingle`/`decodeSingleOrNull`). The rule the whole survey converges on is: **an unrecognised server variant returns `null` and logs; only malformed data throws.**

So the question is whether that pattern resolves Decision B outright: `r[category]` throws or returns the sealed type for a mismatch, `r.answerOrNull(category)` returns `null`, and the `UnknownAnswer` variant exists for the raw-payload path. Confirm, or pick differently.

### Decision C — what does the api dump baseline freeze?

The survey says adopt `explicitApi()` + a single `@InternalJev` opt-in marker + BCV with one committed dump, matching openai-kotlin exactly, and to skip per-target dumps until we publish more than one target that matters.

Confirm that, and decide whether the 0.1.0 dump is allowed to be regenerated freely before the first tag (it is, if the first publish is the baseline) or whether it should be treated as binding from the first commit.

### Also confirm

The survey's "patterns we reject" list — a plugin registry, an `Attributes`/`AttributeKey` bag, a retry framework, a `core`/`client` module split, a `-bom` module, a `test-common` module, four opt-in markers, and `apiValidation { ignoredPackages }` escape hatches. All are out of scope by default. Flag any you disagree with.

## Answer

Resolved across five grilling rounds. Evidence: [research/05-established-kotlin-sdk-patterns.md](../research/05-established-kotlin-sdk-patterns.md) (§1, §2f, §5, the adopt/reject lists) and openai-kotlin's committed `api/openai-client.api`.

### The finding that reshaped the ticket

I grepped all six reference codebases for an alternate implementation of the client. **There is none.** openai-kotlin's `OpenAI` interface is implemented only by its own `internal OpenAIApi`; nothing in its tests or samples fakes it, and its `commonTest` contains no `MockEngine` at all. Nobody implements `SupabaseClient` either — it's a parameter type for third-party plugins. Python has no fake client class and monkeypatches `tenacity.time` and `random.random` instead.

So the shape's headline benefit — consumer-side semantic fakes — is **zero-demonstrated across all six**, including the four libraries that adopt it. That did not overturn the decision, but it removes the strongest argument for it, and the brief should not claim a benefit nobody exercises.

### Client shape — an interface, and *why*

`public interface TypeSafeClient : AutoCloseable` + `internal class TypeSafeClientImpl` + a top-level `fun TypeSafeClient(config: TypeSafeConfig): TypeSafeClient`. The factory keeps `TypeSafeClient(config)` reading identically to a constructor, so nothing about a Python or JS user's code changes shape.

The honest reason it wins is **not** fakeability: it's that the committed api dump then contains an interface and a function, we can restructure the implementation freely, and we match every mature KMP SDK's public surface. Cost: one interface, one internal class, and an interface whose members cannot be added later without a default body. The reviewer-facing difference between "a client" and "an SDK".

### Decision A — client and resource composition

**Shape B: one client interface with a nested resource object.**

```kotlin
public interface TypeSafeClient : AutoCloseable {
    public val models: Models
    public suspend fun systemOne(...): SystemOneResponse
}
public interface Models { public suspend fun list(): List<ModelCard> }
```

Q2 in round 1 mislabelled this: I called `TypeSafeClient : Models by ModelsApi(...)` "openai-kotlin's per-resource shape" with call site `client.models.list()`, which is incoherent — inheriting `Models` puts `list()` flat on the client, with no `models` object. openai-kotlin is in fact **flat** (`openAI.chatCompletion(...)`, never `openAI.chat.completions(...)`), and its 19 feature interfaces exist purely to group endpoints for delegation. Nested resource objects are supabase-kt's shape, obtained via extension properties — which is also the real reason `SupabaseClient` is public there.

Rejected: the openai grouping shape (`TypeSafeClient : SystemOne, Models, AutoCloseable`) because it makes the call site `client.list()` — *list what?* — and openai only gets away with it because its resource names are pluralized per resource. Shape B costs one extra public type for a call site that names the resource. It makes the impl explicit rather than delegated: `override val models: Models = ModelsApi(requester)`, since a property cannot be `by`-delegated.

### Decision B — typed answer access

**The typed key *is* the wire question.** The brief's `"category" to choice(...)` infix is dropped:

```kotlin
val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
val urgent   = noul("urgent", "Does this convey urgency?")

val r = client.systemOne("Help! My payouts have been failing for 3 days.", category, urgent)
val c: ChoiceAnswer = r[category]        // throws on mismatch
val maybe = r.answerOrNull(category)     // null on mismatch
val raw = r.answers["category"]          // sealed Answer, incl. UnknownAnswer
```

`ChoiceQuestion : Question<ChoiceAnswer>`; `operator fun <T : Answer> get(q: Question<T>): T` throws, `answerOrNull` returns `null` for **both** mismatch kinds. This is supabase's `decodeAs`/`decodeAsOrNull` twin exactly.

Two failure modes, deliberately answered differently:

- **Caller/contract error** — key built as `choice(...)` but read as `ScoreAnswer`. Throws `AnswerTypeMismatchException`, a public type in `com.sierranevadalabs.jev.sdk` — **not** in `.errors`, because it is a programming error and nothing from the network can produce it.
- **Server/forward-compat** — a valid key, an unknown primitive. `UnknownAnswer(type, raw)` stays in the sealed hierarchy so `when (r.answers[id])` remains exhaustive and forces the graceful branch. `answerOrNull` also returns `null` here.

**Consequence accepted:** the day the server adds a primitive and we add a variant, every consumer's exhaustive `when` stops compiling. That is the correct failure — a compile error beats silently dropping data — but it means **adding a primitive is a source-breaking change** and must be documented as one, never shipped in a patch.

**Divergence from both siblings, and why it is forced.** Both put the question's name in the *container key* (`{"billing": noul("Is this about billing?")}`). We put it inside the question. The reason is a language gap, not taste: JS types its response off its request with TypeScript mapped types (`SystemOneRequest<Q>` → `SystemOneResult<Q>`, giving `answers.billing.noul`). **Kotlin has no equivalent**, which is precisely why the brief reached for a typed key object — and a key object can only work if the question knows its own name. Sibling-literal request syntax and typed access are mutually exclusive here; we chose typed access.

### Decision C — the api dump

`explicitApi()` + BCV with one committed dump + `apiCheck` in CI, as the survey recommended. **Amended from the survey: no `@InternalJev` marker at 0.1.0.** The marker only earns its place when something must be `public` in bytecode yet isn't consumer API — `@PublishedApi internal` members reached from `public inline` functions. supabase-kt needs that because `decodeAs` is `inline` for reified serialization. Nothing here is inline or reified, so the marker would ship as a public annotation nothing uses. Add it the day the first `@PublishedApi internal` appears.

The dump's *meaning* before `0.1.0` exists: **a stop-and-look gate, not a compatibility promise.** Any public-surface change must appear as a deliberate reviewed diff — never a silent `apiDump` — with no semver promise until the first tag, at which point the dump becomes the published baseline.

### Other settled points

- **One abstract method per endpoint; convenience overloads are extension functions.** So the interface's `systemOne` is a single `JsonElement` member and the `String` overload is an extension forwarding to it. This keeps the implementor contract minimal and lets the convenience surface grow without breaking external implementors.
- **`systemOne(state, vararg questions, model = null, timeout = null)`.** `state` is non-null `JsonElement` core with a `String` extension; JS's `state: null` is covered by `JsonNull` rather than a third overload. Kotlin permits `vararg` with **zero** arguments, so the non-empty-questions runtime check is still required despite the signature implying otherwise.
- **Per-call `model` and `timeout` are in scope** — the brief's §5 carve-out list (`extra_body`, pluggable `Logger`, per-call extra headers) is explicit, and these are not on it, so parity puts them in. **Per-call `retry` is deferred to [Reconcile retry against Ktor's built-in HttpRequestRetry](17-reconcile-retry-vs-ktor-plugin.md)**, because if we adopt Ktor's plugin a per-call policy is `HttpRequestRetry.request { }` — a different public shape than a `RetryPolicy?` parameter, and not safe to guess.
- **`sealed interface Answer`** with `data class` subtypes, `UnknownAnswer(type: String, raw: JsonElement)` retained.
- **`close()` is not `suspend`**; we always construct the `HttpClient` ourselves so we always close it, with `manageEngine = false` for an injected engine instance and the factory overload for our own default engine; `close()` does **not** cancel in-flight caller coroutines, and the KDoc says so instead of implying otherwise.
- **Reject all ten over-engineering call-outs** — plugin registry, `Attributes`/`AttributeKey` bag, retry framework, `core`/`client` split, BOM module, `test-common` module, four opt-in markers, `apiValidation { ignoredPackages }` escape hatches.

### The frozen surface — what BCV will record

```
com.sierranevadalabs.jev.sdk
  interface TypeSafeClient : AutoCloseable   { val models: Models; suspend fun systemOne(JsonElement, vararg Question<*>, String?, Duration?): SystemOneResponse }
  fun TypeSafeClient(config: TypeSafeConfig): TypeSafeClient
  suspend fun TypeSafeClient.systemOne(String, vararg Question<*>, String?, Duration?)   // extension
  interface Models                           { suspend fun list(): List<ModelCard> }
  class TypeSafeConfig                       // plain class, defaulted params, NOT data
  sealed interface Question<T : Answer>      // + ChoiceQuestion, NoulQuestion, ScoreQuestion
  fun choice(id, prompt, options): ChoiceQuestion ; fun noul(id, prompt): NoulQuestion ; fun score(id, prompt, levels): ScoreQuestion
  sealed interface Answer                    // + NoulAnswer, ChoiceAnswer, ScoreAnswer, UnknownAnswer
  class SystemOneResponse                    { answers; operator get; answerOrNull; model; usage; requestId; status; headers }
  class ModelCard ; class Usage ; class RetryPolicy
  class AnswerTypeMismatchException          // root package, NOT .errors
  ...12 error classes in .errors
```

Two BCV gotchas confirmed in openai-kotlin's real dump rather than assumed: an interface method with default parameters emits a `DefaultImpls` class with `$default` synthetics, and a `@JvmInline value class` in a signature mangles the JVM name with a type hash (`assistant-7pl7fn0`). **No value classes in our public surface**, so the second does not apply to us.

### Brief amendments

The brief was locked before this research and is now stale in five places. Amended in place, each marked with what overruled it:

| § | Was | Now |
|---|---|---|
| 4 | "Ktor types are internal" (contradicted by its own `HttpClientEngine` seam) | Ktor appears in exactly two opt-in config parameters — `engine: HttpClientEngine?` and `httpClientConfig: HttpClientConfig<*>.() -> Unit` — and nowhere else |
| 5 | (silent) | per-call `model`/`timeout` in scope; per-call `retry` deferred to the retry ticket |
| 7 | `"id" to choice(...)`; regular sealed class | id inside the question; `sealed interface`; mismatch exception; exhaustive-`when` consequence |
| 9 | "`TypeSafeClient` is a regular class" | interface + internal impl + factory; the anti-`data class` reasoning still governs |
| 12 | `models.list()` (call site only) | nested `Models` interface, composed as Shape B |
| 14 | `explicitApi()` + BCV | also: the dump is a stop-and-look gate; no `@InternalJev` at 0.1.0 |

### Unblocks

[Implement the question and answer model](11-implement-question-and-answer-model.md) and [Implement the client and error tree](12-implement-client-and-error-tree.md) now have their public surface fixed. [Prototype the typed question API](06-prototype-typed-question-api.md) should be re-read before it runs: its premise included the `"id" to choice(...)` infix and a separate key type, both now gone.
