# Implement the client and error tree

Type: task
Status: claimed
Blocked by: 10, 11

## Question

Nothing to decide — assemble the public entry point and the error hierarchy on top of the two layers below it.

**Config resolution.** `explicit → env → default` with the siblings' env names and defaults, via `expect fun platformEnv`. JVM `System.getenv`, Apple `NSProcessInfo`, Android reuses the JVM actual. Blank or whitespace-only env values are ignored, not treated as set — both siblings do this and it is worth a test.

**`TypeSafeClient`.** A regular class, **not a `data class`**. Holds `apiKey` such that it appears in no generated `toString()`, no `toString()` we write, no logging path, and no exception message. Write the test that asserts this before writing the class. Implements `AutoCloseable`: closes the engine it created, never one it was handed, and closing twice is safe. Owns `systemOne(...)` and a `models` accessor.

`systemOne` takes `state` (String or JsonElement), the questions, an optional per-call `model`, an optional per-call timeout, and an optional per-call `RetryPolicy`. It returns `SystemOneResponse` carrying `model`, `answers`, `usage`, `requestId`, `status`, and `headers: Map<String, String>`.

**`Models`.** `client.models.list()` → `List<ModelCard>` from `GET /v1/models`. A response that is not `{models: [...]}` fails with a clear message naming the endpoint, not a cast error. Reached through the transport seam so it never sees the API key directly.

**Errors.** The 12-class tree in `com.sierranevadalabs.jev.sdk.errors`. **[amended by the parent before dispatch: the first dispatch stopped here, correctly — "names verbatim from the siblings" is not decidable as written, because JS's classes are already de-prefixed (`BadRequestError`…) while its *root* is `TypeSafeError`, and brief §11 says `TypeSafeClient` is the only `TypeSafe`-prefixed public name. The tree below is JS's 12 with the two names that cannot survive both rules resolved.]**

| class | extends |
|---|---|
| `JevError` | `Exception` — the root; JS calls this one `TypeSafeError` |
| `APIError` | `JevError` — carries `status`, the parsed body, the request id |
| `BadRequestError`, `AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `UnprocessableEntityError`, `RateLimitError`, `InternalServerError` | `APIError` |
| `APIConnectionError` | `JevError` |
| `APITimeoutError` | `APIConnectionError` |
| `APIResponseValidationError` | `APIError` |

`JevError` is the root because §11 forbids the `TypeSafe` prefix on anything but `TypeSafeClient`, and `Jev` is the project's own identifier prefix — the same convention as openai-kotlin's `OpenAIError`. If you would rather match JS literally on that one name, take the rename and say so in your `## Answer`; **do not stop on it again**, the count is 12 either way. **Not ported: JS's `APIUserAbortError`** — a Kotlin caller abort is `kotlin.coroutines.cancellation.CancellationException`, which is not ours to wrap, and the brief already treats cancellation as never-retryable and never-wrapped. All ten names ADR 0005 declares canonical for the fixtures are in this tree, so the fixture mapping is a pure name match. `RateLimitError` carries `retryAfterMs`. The status→class mapping lives in exactly one place. Message extraction handles all four body shapes the fixtures encode, including FastAPI's `detail: [{loc, msg}]` flattened into a readable message, with the raw body length-capped and non-JSON bodies passed through as text. The `Authorization` header must never appear in an exception's message or `toString()`.

**Tests.** A call-level round trip through `MockEngine` asserting the exact request body and headers, plus the exact response surface. Error mapping for every status. The API-key-leak assertions. Lifecycle: close-what-you-own, idempotent close, and that an injected engine survives the client.

Deliverable: client, models, errors, and tests.

## Answer
