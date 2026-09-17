# Implement the client and error tree

Type: task
Status: open
Blocked by: 10, 11

## Question

Nothing to decide — assemble the public entry point and the error hierarchy on top of the two layers below it.

**Config resolution.** `explicit → env → default` with the siblings' env names and defaults, via `expect fun platformEnv`. JVM `System.getenv`, Apple `NSProcessInfo`, Android reuses the JVM actual. Blank or whitespace-only env values are ignored, not treated as set — both siblings do this and it is worth a test.

**`TypeSafeClient`.** A regular class, **not a `data class`**. Holds `apiKey` such that it appears in no generated `toString()`, no `toString()` we write, no logging path, and no exception message. Write the test that asserts this before writing the class. Implements `AutoCloseable`: closes the engine it created, never one it was handed, and closing twice is safe. Owns `systemOne(...)` and a `models` accessor.

`systemOne` takes `state` (String or JsonElement), the questions, an optional per-call `model`, an optional per-call timeout, and an optional per-call `RetryPolicy`. It returns `SystemOneResponse` carrying `model`, `answers`, `usage`, `requestId`, `status`, and `headers: Map<String, String>`.

**`Models`.** `client.models.list()` → `List<ModelCard>` from `GET /v1/models`. A response that is not `{models: [...]}` fails with a clear message naming the endpoint, not a cast error. Reached through the transport seam so it never sees the API key directly.

**Errors.** The 12-class tree in `com.sierranevadalabs.jev.sdk.errors`, names verbatim from the siblings. `RateLimitError` carries `retryAfterMs`. The status→class mapping lives in exactly one place. Message extraction handles all four body shapes the fixtures encode, including FastAPI's `detail: [{loc, msg}]` flattened into a readable message, with the raw body length-capped and non-JSON bodies passed through as text. The `Authorization` header must never appear in an exception's message or `toString()`.

**Tests.** A call-level round trip through `MockEngine` asserting the exact request body and headers, plus the exact response surface. Error mapping for every status. The API-key-leak assertions. Lifecycle: close-what-you-own, idempotent close, and that an injected engine survives the client.

Deliverable: client, models, errors, and tests.

## Answer
