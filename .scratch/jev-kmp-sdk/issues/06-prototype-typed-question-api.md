# Prototype the typed question API

Type: prototype
Status: claimed
Blocked by:

## Question

Read [research/05-established-kotlin-sdk-patterns.md](../research/05-established-kotlin-sdk-patterns.md) §2 first — the survey landed after this ticket was written and it supplies a strong precedent the sketch below does not account for: supabase-kt's `PostgrestResult` keeps the raw payload and gives **every** typed accessor an `OrNull` twin, and the rule the whole survey converges on is *an unrecognised server variant returns `null` and logs; only malformed data throws*.

**[updated by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** That ticket fixed most of the surface this prototype was meant to explore, so the scope is narrower and the sketch has changed. **Already settled — do not re-litigate:**

- **No separate key type.** The typed key *is* the wire question; `choice`/`noul`/`score` take the id as their first argument. The `"id" to …` infix is gone — it built a public `Pair` purely to glue an id to a question that can hold its own id.
- **Unknown variant:** `r[key]` throws `AnswerTypeMismatchException`, `r.answerOrNull(key)` returns `null`, and `UnknownAnswer(type, raw)` surfaces only through `r.answers`.
- **Accessor:** `operator get` plus the `answerOrNull` twin.
- **`Answer` is a `sealed interface`** with `data class` subtypes.

The updated target call site:

```kotlin
val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
val urgent   = noul("urgent", "Does this convey urgency?")

val r = client.systemOne("Help! My payouts have been failing for 3 days.", category, urgent)
val c: ChoiceAnswer = r[category]     // statically typed
val u: NoulAnswer   = r[urgent]       // statically typed
```

Build a throwaway module (or a `prototype/` source set — this is not the real build) that settles what is genuinely still open:

1. **Does `Question<T : Answer>` type-check cleanly** for all three primitives, including inside a heterogeneous `vararg Question<*>` and inside a `List<Question<*>>`? Where does variance fight back, and does the supertype need `out`? `vararg` is the locked shape — prove it compiles.
2. **Does an `operator get` on `SystemOneResponse` collide with `Map` semantics** in a way that confuses readers, given that `r.answers` is right beside it and genuinely *is* a map? If `r[key]` reads badly once there is real code and KDoc around it, propose a rename and bring it back for confirmation — whoever locked `get` had no compiling code in front of them.
3. **Builder shapes.** Top-level `noul`/`choice`/`score` functions returning `Question<T>` vs a `systemOne { }` receiver DSL. Write both, then say which you would want on a Monday morning.
4. **Overload friction.** `choice(id: String, prompt: String, options: Map<String, String?>)` beside `choice(id: String, prompt: JsonElement, options: Map<String, JsonElement>)` — does the common case resolve unambiguously, or does the compiler need help at every call site? Same for `score` with `List<String>` vs `List<JsonElement>`, and for the `systemOne(String)` / `systemOne(JsonElement)` pair.
5. **The sibling-literal form.** `mapOf("x" to choice(...))` can no longer produce statically typed access, because the id now lives inside the question. Confirm what the compiler actually does with it, and confirm the divergence still looks worth it against real code. If a Python or JS user's first instinct now yields a confusing error, say so plainly — that is a cost accepted blind, and this is the cheapest place to discover it.
6. **Ergonomics with many questions.** Show a 10-question call. Does it read well, and does the `vararg` hold up?
7. **KDoc and `explicitApi()`** — does the chosen shape produce a public API that is pleasant to document, or does it leak type parameters into every signature?

Deliverable: a compiled artifact plus a short written verdict — the exact signatures to adopt, and every place the brief's sketch had to bend. The bent parts go back to the owner for confirmation before the real API is built.

## Answer

Prototype shipped unmerged on `issue-06-prototype-typed-question-api` — [PR #2](https://github.com/snevadalabs/jev-kmp/pull/2).
Throwaway module at `prototype/`: JVM-only, unpublished, `apiValidation { ignoredProjects += "prototype" }` in
the root build, to be deleted when [Implement the question and answer model](11-implement-question-and-answer-model.md)
lands the real one. Nine tests, all green; `./gradlew checkVersion ktlintCheck jvmTest apiCheck`
→ `BUILD SUCCESSFUL`. Nothing user-visible, so `CHANGELOG.md` is untouched.

### The surface to adopt

```kotlin
public sealed interface Answer
public data class NoulAnswer(public val noul: Double) : Answer
public data class ChoiceAnswer(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : Answer
public data class ScoreAnswer(val score: String, val legend: List<String>, val probabilities: Map<String, Double>, val confidence: Double) : Answer
public data class UnknownAnswer(public val type: String, public val raw: JsonElement) : Answer

public sealed interface Question<T : Answer> {          // invariant — see 1.
    public val id: String
    public val prompt: JsonElement                        // one type holds both overload pair
}
public data class NoulQuestion(id, prompt) : Question<NoulAnswer>
public data class ChoiceQuestion(id, prompt, public val options: Map<String, JsonElement?>) : Question<ChoiceAnswer>
public data class ScoreQuestion(id, prompt, public val levels: List<JsonElement>) : Question<ScoreAnswer>

public fun noul(id: String, prompt: String): NoulQuestion
public fun noul(id: String, prompt: JsonElement): NoulQuestion
public fun choice(id: String, prompt: String, options: Map<String, String?>): ChoiceQuestion
public fun choice(id: String, prompt: JsonElement, options: Map<String, JsonElement>): ChoiceQuestion
public fun score(id: String, prompt: String, levels: List<String>): ScoreQuestion
public fun score(id: String, prompt: JsonElement, levels: List<JsonElement>): ScoreQuestion

public class SystemOneResponse(
    public val answers: Map<String, Answer>,
    public val model: String? = null,
    public val requestId: String? = null,
) {
    public operator fun <T : Answer> get(question: Question<T>): T
    public fun <T : Answer> answerOrNull(question: Question<T>): T?
}

public class AnswerTypeMismatchException(message: String) : IllegalStateException(message)   // ← 8, needs your call
```

### The load-bearing finding: `as? T` cannot implement the accessor pair

`T` is erased, so `answers[id] as? T` compiles to an unchecked cast to `Answer` and never fails. Measured by
restoring that implementation over the same tests: `answerOrNull` handed back a `ChoiceAnswer` where
`null` was promised, and `get` escaped the ticket-16 contract entirely —
`java.lang.ClassCastException: class ChoiceAnswer cannot be cast to class NoulAnswer` at the caller's
assignment, so `AnswerTypeMismatchException` was never thrown. Three tests went red for exactly that reason.

The fix is `internal fun questionAccepts(question: Question<*>, answer: Answer): Boolean` — an exhaustive
`when` over the sealed `Question` hierarchy. It is internal, so no public surface is added, and the `when`
breaks at compile time when a primitive is added, the same way consumers' `when (r.answers[id])` does.
**Ticket 11 must ship this check; the naive cast is a silent hole.**

### The seven questions

1. **`Question<T : Answer>` type-checks cleanly**, in a heterogeneous `vararg Question<*>` and in a
   `List<Question<*>>` spread. **No `out` needed.** `T` is phantom (it appears only in the bound), so
   `out T` also compiles and changes nothing observable — the probe passes either way — but nothing needs
   the subtyping and `out` would forbid a future member that consumes `T`. `List` costs
   `client.systemOne(state, *list.toTypedArray())`; a `List` convenience overload is a 3-line extension if
   you want it, and is not added here.
2. **`r[key]` reads fine; keep `get`.** Beside it, `r.answers["category"]` is the raw map and `r[category]`
   is the same shape one type safer — the mnemonic helps rather than confuses, and `attributes[key]` is the
   established Kotlin idiom for a typed key. The string form fails loudly, if not helpfully:
   `r["category"]` → `Argument type mismatch: actual type is 'String', but 'Question<Answer>' was expected.`
   No rename proposed.
3. **Top-level builders win over the DSL.** The receiver DSL cannot hand back the typed keys, because
   inside its own block `choice(...)`/`noul(...)` resolve to the receiver members — and those members cannot
   even be implemented by delegating to the top-level builders of the same name (`questions.add(noul(...))`
   in the class body resolves to the member and gives `actual type is 'Unit', but 'Question<*>' was expected`;
   the probe needs `com.sierranevadalabs.jev.sdk.noul(...)`). So the compiler-clean DSL form builds the
   questions outside and pushes them in via `questions +=`, which leaves the receivers dead code: a state
   setter and a list append for one extra public class. The DSL also fights `vararg` and adds a second way
   to do the one endpoint the brief keeps to one abstract method.
4. **Overload friction is nil, because the pair is selected by `prompt`, not by the container.** With a
   `String` prompt the `JsonElement` overload is rejected outright, so `mapOf("billing" to null)`,
   `emptyMap()` and `emptyList()` all resolve without help — the ambiguous case never arises. The cost is
   the one mixed form: a plain prompt with structured options/levels is
   `None of the following candidates is applicable:` and nothing more. Its practical shape is
   `choice(id, prompt: String, options: Map<String, String?>)` when the options are labels, and
   `choice(id, prompt: JsonElement, options: Map<String, JsonElement>)` once anything is structured.
5. **The sibling-literal form does not compile, and its error points at the wrong argument.**
   `mapOf("billing" to noul("billing", "Is this about billing?"))` into `systemOne` gives
   `Argument type mismatch: actual type is 'Map<String, NoulQuestion>', but 'Question<*>' was expected.` —
   and when the state was also written as a `String`, the compiler instead complained about *the state*:
   `actual type is 'String', but 'JsonElement' was expected`, because the member overload outlived the
   convenience extension once the vararg rejected it. Nothing in either message says "put the id first".
   **The divergence still looks worth it** — the id inside the question is what buys `val c: ChoiceAnswer =
   r[category]` at all, and the compile error on `val c: ChoiceAnswer = r[urgent]` comes free with it — but
   this is the cost accepted blind in ticket 16, and it is one confused compile for the sibling user's
   first instinct. Worth one README sentence, nothing more.
6. **Ten questions read well.** `client.systemOne(state, about, billing, technical, urgency, anger,
   churn, refund, account, quality, legal)` is one call, one line per question in the source, and each
   `val x: XAnswer = r[x]` stays statically typed. `vararg` holds up at ten; at ~30 a caller will want the
   `List` form and the spread.
7. **KDoc is pleasant; the generic leaks in exactly three signatures** — `Question<T : Answer>`, the three
   `*Question` subtypes' supertype argument, and `<T : Answer> get/answerOrNull`. Everything else is
   concrete. Two measured corrections to the brief, the first of which someone must record:
   **brief §18 is wrong — `explicitApi(Strict)` does not enforce KDoc at all** (deleting a public
   declaration's KDoc compiles clean, so "makes missing KDoc a compile error" and "coverage is enforced
   rather than hoped for" are false; KDoc coverage needs a Dokka/CI check or a review rule instead). The
   second is smaller: it requires an explicit `public` on top-level declarations but *not* on
   primary-constructor properties (removing `public` from `ChoiceQuestion.options` compiles clean; removing
   it from a top-level `fun` does not).

### Bends, and the one thing I stopped on

8. **`AnswerTypeMismatchException`'s base class is not decided anywhere I can find.** The brief §7 says it
   lives at the root, out of `.errors`, "a programming error, and nothing from the network can produce it",
   which narrows it to an unchecked exception but names no class; §11's verbatim-from-the-siblings rule
   cannot help, because neither sibling has this type (JS enforces it at compile time, Python does not have
   it). The prototype had to pick one to compile, and picked `IllegalStateException`.
   **Confirm `IllegalStateException` or name another — `IllegalArgumentException`, `ClassCastException` and
   plain `RuntimeException` are all defensible — before ticket 11 freezes the dump.** That is the only
   thing between this ticket and `resolved`; everything else above is settled.
9. Missing-key behaviour is **not** decided here: ticket 11's own brief already says to *"decide and test
   the behaviour rather than letting it fall out"*. The prototype's placeholder is `NoSuchElementException`
   from `get`, `null` from `answerOrNull`, and ticket 11 owns the real answer (including the
   response-key-absent-from-request direction).
10. Naming: the brief §7 says `Noul`/`Choice`/`Score`/`Unknown(type, raw)`; the later frozen surface in
    ticket 16 says `NoulAnswer`/`ChoiceAnswer`/`ScoreAnswer`/`UnknownAnswer`. The prototype follows ticket
    16, since it is the newer and more specific text.

Left undone on purpose: `usage`, `status`, `headers`, `ModelCard`, config resolution, retry and the error
tree (tickets 10–12); the compile-failure test that turns a typing regression into a build failure (ticket
11 asks for it and it needs the real model's source set); `CHANGELOG.md` (nothing a user would notice).
`prototype/` must be deleted, together with the `apiValidation` block it needs, when the real model lands.
