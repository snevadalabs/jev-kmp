# Prototype the typed question API

Type: prototype
Status: open
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
