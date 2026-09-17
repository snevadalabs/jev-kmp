# TypeSafe AI Python SDK — Recon

Repo: `/tmp/ts-study/typesafe-sdk-python` @ `420ef4f Release v0.6.0`. 27 source files, 14 test modules.
Verified locally: `uv sync --all-groups` + `uv run pytest -m "not integration"` → **534 passed, 17 skipped, 33 deselected** (584 collected). Only tag/workflow in the public repo is `publish.yml`.

## 1. Package / layout

```
src/typesafe_sdk/
  __init__.py          public re-exports (__all__, 39 names)
  constants.py         public env names + defaults
  _version.py          __version__ = importlib.metadata.version("typesafe-sdk")
  py.typed             empty marker (present in built wheel)
  _core/
    constants.py       paths, header names, logger name, MAX_ERROR_BODY_LENGTH=200, SECRET_HEADERS
    config.py          Config dataclass + env resolution + timeout validation
    logging.py         logger "typesafe_sdk", NullHandler, SensitiveHeadersFilter, setup_logging()
    json_types.py      JSONValue / JSONContent type aliases
    json.py            serialize()/deserialize() over msgspec
    endpoints.py       prepare_system_one() / prepare_models()
    questions.py       normalize_questions() (shallow pre-flight validation)
    question_types.py  Noul/Choice/Score structs + *Model TypedDicts + Question/Questions aliases
    response_types.py  Answer types, Usage, SystemOneResponse, ListModelsResponse, 2 decoders
    retry.py           RetryPolicy + build_tenacity/_async
    errors.py          exception tree, extract_message(), parse_retry_after(), api_error()
    transport.py       Request, RequestState, prepare(), send(), send_async()
    client/sync/{client,models}.py   TypeSafeClient, Models
    client/aio/{client,models}.py    AsyncTypeSafeClient, AsyncModels
    schemas/base.py    Schema/Response base structs, field_path(), validation_error()
  _schemas/models.py   100% codegen (datamodel-code-generator, msgspec) from api.typesafe.ai/openapi.json
```

**pyproject.toml**: name `typesafe-sdk`, version `0.6.0`, `requires-python = ">=3.10"`, classifiers list 3.10–3.14, `Typing :: Typed`. Deps: `httpx2>=2.0.0` (fork of httpx, locked 2.12.0), `msgspec>=0.21.1`, `tenacity>=9.0.0`, `typing-extensions>=4.13.0`. Build backend `uv_build>=0.12.5,<0.13`. Dev group: gitpython, pyrefly, pytest, pytest-asyncio, ruff, sybil, syrupy, tach. Docs group: mkdocstrings, zensical. `.python-version` = `3.14`.

**pytest config** (`pyproject.toml`): `addopts = "-vv -ra --capture=tee-sys -p no:tach"`, `log_cli=true`/INFO, `asyncio_mode="auto"`, `testpaths=["tests"]`, marker `integration: live API tests requiring TYPESAFE_API_KEY`. No `[tool.ruff]` section despite many `# noqa: S101/S311/S603/SLF001/PT012/INP001` comments installed as dev dep. No tach.toml (plugin disabled anyway). **No coverage tooling.**

**pyrefly.toml**: `preset="strict"`, includes `src` + `tests`, `project-excludes=["tests/typing/negative"]` ("checked by pytest with --expectations"), `python-version="3.10"`, `search-path=["src","."]`.

**Public API** (`src/typesafe_sdk/__init__.py:49-88`, snapshot-locked): `TypeSafeClient, AsyncTypeSafeClient, Models, AsyncModels, RetryPolicy, SystemOneResponse, ListModelsResponse, ModelMetadata, Usage, Answer, NoulAnswer, ChoiceAnswer, ScoreAnswer, Question, Questions, QuestionModel, Noul, NoulModel, NoulCriteria, Choice, ChoiceModel, Score, ScoreModel, JSONValue, JSONContent, 12 error types, constants`.

## 2. Design

**Sync vs async**: near-verbatim duplication, not shared code.
- `_core/client/sync/client.py:20-186` vs `_core/client/aio/client.py:20-196`: identical constructor signature/snapshot, identical `system_one` docstring/args; differ only in `await`, `close`/`aclose`, `__enter__`/`__aenter__`, `build_tenacity` vs `build_tenacity_async`, `send` vs `send_async`.
- `sync/models.py:15-54` vs `aio/models.py:15-56`: same, `list()` 30-line docstring duplicated.
- Constructor: `transport`/`http_client` mutually exclusive (`ValueError`), `http_client.timeout` inherited when `timeout is None`, `Config.resolve(...)`, `build_tenacity(retry)`, `httpx2.Client(timeout=..., transport=...)` if no http_client. `models` is a `cached_property`.

**Transport** (`_core/transport.py`): immutable frozen `Request[ResponseT]` dataclass (`method,url,headers,content,timeout,response_type`); `RequestState.attempt()` contextmanager copies headers, adds `X-TypeSafe-Retry-Count: <n>` only on retries, times with `time.monotonic()`, logs `-> / <-` wire at DEBUG, converts `httpx2.TimeoutException`→`TypeSafeAPITimeoutError(request.timeout)`, other `httpx2.RequestError`→`TypeSafeAPIConnectionError` (both `from error`). `prepare()` merges default headers then caller headers, pops caller-supplied retry-count header, then force-sets `Authorization: Bearer`, `Accept`, `User-Agent`/`X-TypeSafe-SDK` = `typesafe-sdk/<version>`, `X-TypeSafe-Runtime` = `python/<ver> (<platform>; <machine>)`; URL = `config.base_url + path` (base_urlrstripped "/"). `send`/`send_async` build a fresh `RequestState`, `policy.copy()(attempt)`, per-call `RetryPolicy` override rebuilt via `build_tenacity*`. `http_client.request(..., auth=None)` (defeats httpx-level auth). Encoding errors → `TypeSafeError("The request body could not be encoded as JSON")`.

**Config** (`_core/config.py`): `Config.resolve(api_key, base_url, default_model, timeout, default_headers)`; `_resolve_env` = explicit value else `os.environ.get(env,"").strip() or default`; missing key → `TypeSafeError("...TYPESAFE_API_KEY...")`; base_url `rstrip("/")`; two `assert`-based narrowing noqa S101; `timeout` validated by `resolve_timeout` (positive finite, or `httpx2.Timeout` passthrough). `api_key`/`default_headers` are `field(repr=False)`. Defaults from `constants.py`: base `https://api.typesafe.ai`, model `jev-latest`, timeout `10.0`.

**Retry** (`_core/retry.py`): frozen dataclass `RetryPolicy` with `max_retries=2, backoff_initial=0.5, backoff_max=5.0, backoff_jitter=0.25, http_statuses={408,429,500..599}, respect_retry_after=True, api_connection_error=True, api_timeout_error=True, exceptions=set(), predicate=None, timeout=30.0`. `__post_init__` validates everything. `_retryable` = builtin rule OR `isinstance(error, tuple(self.exceptions))` OR predicate. `_wait` prefers `parse_retry_after` (no cap — server delay always honored), else `_backoff(attempt, initial, max, jitter)` = `ldexp`-based cap then `*(1 - random()*jitter)`, rounded to 3dp. `_stop` = `stop_after_attempt(max_retries+1) | stop_before_delay(timeout)`. Tenacity configured with `reraise=True`. Build seams `build_tenacity/build_tenacity_async` are `# noqa: SLF001` and excluded from docs (`__all__ = ["RetryPolicy"]`).

**Errors** (`_core/errors.py`): `TypeSafeError(Exception)` → `TypeSafeAPIError` (status/body/headers/endpoint/message override; `request_id` property; `__repr__` excludes body) → 400/401/403/404/422/429/5xx subclasses; `TypeSafeRateLimitError.retry_after_ms`; separate `TypeSafeAPIConnectionError(TypeSafeError, ConnectionError)` → `TypeSafeAPITimeoutError(..., TimeoutError)`; `TypeSafeAPIResponseValidationError` carries `field_path` and rewrites `self.args`. `STATUS_ERROR_TYPES` map + `api_error()` defaults to `TypeSafeInternalServerError` for >=500 else `TypeSafeAPIError`. `extract_message()` walks `error{str|dict.message}`, `message`, `detail{str|dict.message|list[{loc,msg}]}`. `parse_retry_after` tries `retry-after-ms` (ms) then `retry-after` (s, or HTTP-date via `parsedate_to_datetime`), rejects non-finite/negative.

**Logging** (`_core/logging.py`): single logger `typesafe_sdk`, `NullHandler`, never configures handlers; `SensitiveHeadersFilter` rewrites `record.args["headers"]` (secret = name in `SECRET_HEADERS` or contains "token"/"secret"); `setup_logging()` applies `TYPESAFE_LOG_LEVEL` at **import time** only.

**JSON** (`_core/json.py`): `serialize` = `msgspec.json.encode(value, enc_hook=_enc_hook)` where `_enc_hook` materializes any abstract `Mapping`→dict / `Sequence`→list (so `MappingProxyType`, tuples work). `deserialize`: `msgspec.json.decode`, falling back to `content.decode("utf-8", errors="replace")` on non-JSON, `None` on empty.

**Questions builder API**: no functional helpers — *structs + TypedDicts*.
- `Noul`/`Choice`/`Score` subclass generated wire structs (`_core/question_types.py:63-96`) with `kw_only=True, omit_defaults=True`, `instructions`/`criteria` widened to abstract `JSONContent`/`Mapping`/`Sequence` and marked `# pyrefly: ignore[bad-override-mutable-attribute]`. Discriminator `type` is auto (`tag_field="type"`), not a constructor arg.
- Raw dict form: `NoulModel/ChoiceModel/ScoreModel` TypedDicts with `extra_items=JSONValue | None` (open extra keys), `QuestionModel`/`Question`/`Questions` aliases.
- `normalize_questions` (`_core/questions.py:10-23`): rejects empty mapping ("At least one question is required."), empty score criteria, and raw dicts lacking a nonempty string `type` or missing `criteria` for choice/score. Anything else is forwarded verbatim to the API.

**Response types** (`_core/response_types.py`): answers subclass wire structs `frozen=True, kw_only=True` (mutable→immutable override, `# pyrefly: ignore[bad-override]`); score maps coerced `dict[int, ...]` at decode. `Usage` is **redefined** publicly because the OpenAPI schema requires `billing_units` that the API never returns (comment lines 70-71). `SystemOneResponse(Response, frozen, kw_only, dict=True)`: `_decode_native` one-shot `msgspec.json.Decoder(_SystemOneBody)` fast path; on `ValidationError` falls back to `_decode_by_dispatch` (per-answer `msgspec.Raw` + `_AnswerTag`) which *skips unknown tags with a warning* and raises `validation_error(response, field_path(...))` for malformed known ones. `cached_property` views `nouls`/`choices`/`scores`; `Response.request_id`/`raw_http_response` read `__dict__["_request_id"/"_raw"]` set post-decode, raise `TypeSafeError` when absent; `__copy__`/`__reduce__` preserve them (pickle round-trip).

**Models resource**: `Models.list()` / `AsyncModels.list()` → `GET /v1/models` → `ListModelsResponse(models: tuple[ModelMetadata, ...])` decoded via `wire.ModelMetadataList`.

**Constants**: public `constants.py` (4 env names, base URL, model, timeout); internal `_core/constants.py` (paths, 10 header names, `SECRET_HEADERS` frozenset).

## 3. Wire protocol

`POST https://api.typesafe.ai/v1/systemone` (base_url configurable).
Request headers: `Authorization: Bearer <key>`, `Accept: application/json`, `Content-Type: application/json`, `User-Agent`/`X-TypeSafe-SDK`: `typesafe-sdk/0.6.0`, `X-TypeSafe-Runtime`: `python/3.x (<platform>; <machine>)`; `X-TypeSafe-Retry-Count: n` (n≥1, retries only); caller `headers`/`extra_headers` merged, retry-count caller value stripped.
Request body (`_core/endpoints.py:26-33`): `{"state": <str|object|array>, "model": <call model or client default>, "questions": {name: {type:"noul"|"choice"|"score", instructions?, criteria?}}}` then shallow `body.update(extra_body)` (last-write-wins, no deep merge).
Response 200: `{"model": str, "usage": {"input_tokens": int|null, "output_tokens": int|null}, "answers": {name: {"type":"noul","noul":float} | {"type":"choice","choice":str,"confidence":float,"probabilities":{label:float}} | {"type":"score","score":float,"confidence":float,"legend":{int_score:str|obj|list},"probabilities":{int_score:float}}}}`; response header `x-typesafe-request-id` surfaced as `response.request_id`.
`GET /v1/models` (no body) → `{"models":[{"name","description","release_date"}]}`. Unknown fields ignored; unknown answer `type` silently skipped (warn log).

## 4. Tests — file by file

Wiring: **`tests/conftest.py`** is the core harness. `ClientFactory` (lines 13-64) closes over `async_mode` and builds either client with a **`httpx2.MockTransport`** handler by default, defaulting `retry=RetryPolicy(max_retries=0)` unless `retries=True`, and records instances for teardown:

```python
if transport is None and http_client is None:
    transport = httpx2.MockTransport(handler)
if retry is None and not retries:
    retry = RetryPolicy(max_retries=0)
```

```python
@pytest.fixture(params=[False, True], ids=["sync", "async"])
async def clients(request: pytest.FixtureRequest) -> AsyncIterator[ClientFactory]:
    factory = ClientFactory(request.param)
    yield factory
    for client in factory.instances: ...close/aclose...

@pytest.fixture(autouse=True)
def clean_env(monkeypatch, request) -> None:
    if request.node.get_closest_marker("integration") is None:
        for name in (API_KEY_ENV, BASE_URL_ENV, DEFAULT_MODEL_ENV, LOG_LEVEL_ENV):
            monkeypatch.delenv(name, raising=False)

@pytest.fixture
def live_api_key() -> str:
    key = os.environ.get(API_KEY_ENV, "").strip()
    if not key: pytest.skip(f"{API_KEY_ENV} is not set")
    return key

@pytest.fixture(params=["sync", "async"])
async def live_client(request, live_api_key) -> AsyncIterator[Client]: ...timeout=120...
```
Every non-integration test therefore runs **twice** (sync+async) via one code path; `asyncio_mode="auto"` means `async def test_*` need no marker.
**`tests/helpers.py`** adds `TrackingTransport(httpx2.MockTransport)` overriding `close`/`aclose` to count calls without delegating, plus `async def models(...)`/`system_one(...)` that `isinstance(client, AsyncTypeSafeClient)`-dispatch so one call body serves both clients:
```python
if isinstance(client, AsyncTypeSafeClient):
    return await client.system_one(state, questions, model=model, extra_body=extra_body, ...)
else:
    return client.system_one(state, questions, model=model, extra_body=extra_body, ...)
```

- **tests/test_clients.py** (21 tests, the workhorse; MockTransport with in-handler assertions on `msgspec.json.decode(request.content)`):
  - `test_round_trip[dataclass|raw|mixed]` — full request-body equality (`state`, `model`, `questions` incl. emoji `"Hello 🌍"`), full response surface: `nouls/choices/scores`, `cached_property` identity + absence from `__dict__` before access, int-keyed `legend`/`probabilities`, answer↔group identity, frozen (AttributeError on set), `assert_type` checks inline.
  - `test_extra_body_shallow_override`, `test_unserializable_request_body_raises` (handler `pytest.fail` proves no network), `test_raw_question_passthrough` (arbitrary extra keys `weight`/`nested` forwarded), `test_question_schema_validation_is_left_to_api` (invalid shapes forwarded then 422), `test_rich_descriptions`.
  - `test_models_shape` (GET, empty body), `test_models_ignore_unknown_fields` (+ raw response still has them), `test_invalid_models_response[4 bodies]`.
  - `test_validation_before_network` (empty questions / empty score criteria; handler fails if hit).
  - `test_error_mapping[11 statuses]` asserts exact `str(error)` incl. endpoint + request_id, `retry_after_ms`.
  - `test_error_messages[8 body shapes]`, `test_transport_errors[5 httpx errors]` (asserts `__cause__` and `timeout`), `test_system_one_timeout_override[4]` (inspects `request.extensions["timeout"]`).
  - `test_headers_timeout_and_logging` — protected headers cannot be overridden, `x-typesafe-retry-count` stripped, secrets absent from `caplog.text`, request id present.
  - `test_http_client_settings` — supplied `http_client` (`event_hooks`, `auth=("wrong","credentials")`, wrong `base_url`) is not mutated; hooks fire.
  - Lifecycle: `test_supplied_network_resources_closed[use_context×supply_http_client]` (idempotent close, `is_closed`, RuntimeError afterwards), `test_owned_http_client_closed`, `test_exceptional_context_closes_http_client[ValueError|CancelledError]`, `test_task_cancellation_closes_context` (asyncio task cancel), `test_cancellation_propagates`.
- **tests/test_retry.py** (25 tests, white-box on Tenacity). Techniques: monkeypatch `"tenacity.time"` to a fake monotonic clock + injected `sleep` callables (`_retry.copy(sleep=...)`), exhaustive `RetryCallState` construction from `RetryPolicy()._build_tenacity()`:
  - Validity: `test_retry_policy_invalid_timeout/backoff/jitter/max_retries` (NaN/inf/negatives).
  - `test_retry_policy_timeout_budget[resource×6]` — deterministic fake clock advances by response `duration` and mock sleep; asserts attempt count and `delays == [delay]*(attempts-1)`; "Each SDK call gets a fresh budget." two loops.
  - `test_retry_policy_timeout_override`, `test_default_retry_statuses[12 statuses]` (asserts `x-typesafe-retry-count` sequence `[None,"1","2"]`), `test_retry_policy_max_retries[3]`, `test_retry_policy_custom_statuses`, `test_retry_policy_exceptions_and_predicate`.
  - `test_connection_retry_recovers` (bounded jitter ranges `0.375<=d<=0.5`), `test_server_delay_through_tenacity[4 header combos]` (real delays observed via injected sleep), `test_parse_retry_after[9]`, `test_backoff_dates_cap_and_jitter` (monkeypatch `time.time`, HTTP-date headers, `random.random` pinned 0.0/1.0, cap at 5.0, untruncated server delay `Retry-After: 61 → 61`), `test_backoff_extreme_values[4]` (1e-300/1e308).
  - Concurrency/cancel: `test_async_concurrent_retry_state`, `test_concurrent_system_one_overrides` (asyncio.gather of 3 calls with different retry/timeout overrides proving per-call state isolation), `test_cancel_pending_retry`.
  - `test_exhausted_transport_retry[ReadTimeout|ConnectError]` asserts final `__cause__` is the last transport error and `TypeSafeAPITimeoutError.timeout is timeout`; `test_exhausted_retry_preserves_final_http_error` asserts final 503/body/request_id.
- **tests/test_responses.py** (14): targeted decode-failure paths `test_malformed_response_raises_validation_error[7]` asserting exact `field_path` and message; `test_nested_missing_field_path` → `models[1].name`; request_id/raw response; `test_response_serialization_excludes_http_metadata` (`set(msgspec.to_builtins(result)) == set(body)` after populating cached views, re-decode equality); pickle + `copy.copy`/`deepcopy` metadata preservation; `test_unknown_answer_type_ignored` ("aurora" skipped, still in raw); `test_unknown_extra_fields_tolerated`; `test_response_preserves_nested_json` (to_builtins deep-copy independence); frozen/slotted + non-reassignable `cached_property` groups.
- **tests/test_errors.py** (6): `test_exception_reconstruction[14 errors]` — `type(e)(*e.args)`, copy, deepcopy, pickle all preserve `str`/`repr`/`args`/`vars`; `test_api_error_from_process_pool` (spawn `ProcessPoolExecutor` pickling across a real process boundary); request-context/endpoint assertions incl. credentials stripped from URL (`userinfo`, `query`, `fragment`) and no key in `repr`; `test_message_override`; `test_error_body_edge_cases[9 raw bytes]` (empty/`null`/`[]`/`42`/invalid UTF-8/long-body truncation with `…` at 200 chars).
- **tests/test_config.py** (8): mutually-exclusive transport/http_client; model override precedence; `test_resolution[default|env|constructor]` incl. whitespace-padded env values and `///`-suffixed base URLs; missing/blank key; blank env ignored; invalid timeout (constructor + per-call); `httpx2.Timeout` object mapping into `extensions["timeout"]`; `test_http_client_timeout_precedence[2×4]`.
- **tests/test_questions.py** (8): `normalize_questions` preserves object identity and raw dicts (deep-copy comparison), structural key rejection `[10 invalid shapes]`, default-omission encoding (`omit_defaults`, explicit `""`/`[]`/`{}` kept), discriminator/auto-`type`/`kw_only`/mutability via `inspect.signature` + `__struct_config__`, `test_optional_noul_criteria[2×6]`, empty score criteria rejection, `test_covariant_question_mappings` (9 call shapes incl. `MappingProxyType`, dict-typed/`Questions`-typed/nested-literal, asserting no mutation).
- **tests/test_types.py** (5): runtime annotation introspection — `test_json_value_and_state_exclude_top_level_none` uses `get_args`/`get_type_hints` to assert `JSONValue` has no top-level `None` and `state` is `str | Mapping[str, JSONValue|None] | Sequence[JSONValue|None]`; array-valued state/instructions/criteria; explicit JSON `null` preservation; `test_abstract_input_containers_encode` (MappingProxyType + tuple criteria encoded as dict/list).
- **tests/test_logging.py** (3): `test_secret_headers_redacted[3 statuses × 9 header names]` asserts `***` appears and every credential (request, response, api key) is absent from `caplog.text` for both 200/400/429(+retry logs); logger level gates DEBUG wire lines vs one INFO summary; `test_setup_logging_from_env[5]`.
- **tests/test_typing.py** (1 test, 6 params): runs **pyrefly in a subprocess** per fixture, `--config pyrefly.toml --expectations <file>`, `returncode == 0`:
```python
[sys.executable, "-m", "pyrefly", "check", "--config", str(ROOT / "pyrefly.toml"), "--expectations", str(path)]
```
  Fixtures: `tests/typing/valid.py` (`assert_type` on positive calls incl. `Mapping`/`Sequence`/nested-literal inputs), `tests/typing/transport.py` (`assert_type` on `prepare_*` → `Request[T]`, `RequestState.parse`, `send`/`send_async`, `client._request`), and 4 negative files whose `# E: ...` comments are the expectations: `negative/sync_client.py` & `negative/async_client.py` (wrong `state`, `Retrying` vs `AsyncRetrying`, sync vs async `http_client`), `negative/questions.py` (13 cases: wrong/missing `type`, missing `criteria`, non-JSON extra items, wrong criteria containers), `negative/transport.py` (wrong response type from `send`/`_request`).
- **tests/test_public_api_surface.py** (3, **syrupy snapshot**): `test_public_members[typesafe_sdk|constants]` snapshot-sorted public names; `test_package_exports` (no `_` in `__all__`); `test_constructor_kwargs[2]` snapshot of `inspect.signature` order + all KW_ONLY + all defaults `None`. Snapshot: `tests/__snapshots__/test_public_api_surface.ambr` (4 entries).
- **tests/test_public_sync.py** (10 tests) — **skipped in this repo**: `pytestmark = skipif(not (SCRIPTS/"sync_public.py").is_file(), reason="Public sync tooling is only available in the dev repository")` (17 skips). Fixture `repos` builds 2 real git repos + bare remote in `tmp_path`; `signer` fixture `monkeypatch.syspath_prepend(SCRIPTS)` + `importlib.import_module("sync_public")` and replaces the GitHub Git-objects API with a local-git emulation (`git hash-object -w`, `read-tree`, `update-index --cacheinfo`, `write-tree`, `commit-tree`) asserting the payload only ever contains `{"message","tree","parents"}` (unsigned → GitHub signs). Covers: snapshot+sign+push with/without prior history, file modes/binary content/private-file exclusion/deletions, signing & tree-mismatch failures keep refs, dry-run never calls GitHub, retry/idempotency, immutable tags/refusing older versions, invalid `.releaseinclude` entries (`../private`, `.git/config`, globs, absolute, missing), symlink/"destination must be clean", version/tag mismatch, atomic push rejecting concurrent main updates, `Co-authored-by` aggregation.
- **tests/test_release_notes.py** (2, subprocess): runs `.github/scripts/release_notes.py` in `tmp_path` with `GITHUB_OUTPUT` set; asserts `title=v1.2.3 (2026-09-09)` output + `release-notes.md` body, and 9 failure modes (0/>1 matches, non-latest, empty body, invalid date/heading, version mismatch, bad tag format).
- **tests/test_integration.py** (2, `pytestmark = pytest.mark.integration`, live API): `test_live_models` (nonempty cards), `test_live_questions` (noul/choice/score on a billing ticket; asserts ranges, `legend == {0:...,1:...,2:...}`, probabilities sum ≈1 with `abs=0.1`).
- **tests/test_docs.py** (29 params, integration): **Sybil** executes every ```` ```python ````/```` ```py ```` fenced block in `README.md` + `docs/**/*.md` (`CodeBlockParser`, `PythonCodeBlockParser`), and every fenced block in source docstrings via `PythonDocStringDocument`, injecting `typesafe_sdk.__all__` into the docstring namespace. All examples are live `TypeSafeClient()` calls, so doc correctness is a live-API test. Final `doctest.testmod(importlib.import_module(name))` is effectively a no-op given no `>>>` examples.

## 5. Test gaps / brittleness

- **No CI runs the tests.** `.github/workflows/publish.yml` is the only workflow: it runs `release_notes.py`, `uv build`, `check_distributions.py`, uploads artifacts, publishes. `pytest` is never invoked in CI, and **no lint/type-check job exists** (ruff/pyrefly only run if a human runs them locally / test_typing runs pyrefly). Public CI also has no Python-version matrix despite claiming 3.10–3.14 support and `pyrefly python-version = "3.10"` while `.python-version = 3.14` (only one interpreter ever exercised; the `ly`/`Sequence`/`extra_items=` TypedDict features and `typing_extensions` fallbacks are not verified on 3.10).
- **Live-API testing is gated only by `TYPESAFE_API_KEY`** (`conftest.live_api_key` skips) + the `integration` marker (no `--strict-markers`/manual opt-in, so a dev with the key in `.env`-loaded shell gets live calls including ~29 doc examples that hit the real API; token cost + non-determinism in `test_docs.py`). Nothing pins a model, base URL, or retries for live runs, and `test_live_questions` uses loose numeric assertions (`abs=0.1`).
- **`test_public_sync.py` is dead weight here** (17 skips) — the sync tooling (`sync_public.py`, `push_public.py`) is absent from the public repo, so release-automation coverage only exists in the private repo.
- **White-box coupling to Tenacity internals**: `client._retry`, `client.models._retry`, `RetryCallState(...)` built with `policy._build_tenacity()`, `policy._wait`, `policy._stop`, module-level `monkeypatch.setattr("tenacity.time", ...)`. A Tenacity upgrade breaks these without any behavior change; the design forces it because retry policy is only reachable through private client attributes (no public seam/injectable `Retrying`).
- **Global monkeypatching**: `test_backoff_dates_cap_and_jitter` patches `time.time` and `random.random` process-wide; backoff assertions in `test_connection_retry_recovers` are range-based (real `random`) — mildly flaky by construction.
- **`tests/test_types.py` introspects annotations at runtime** (`get_type_hints`, `get_args`) — duplicates what `assert_type` fixtures already cover and is brittle to any refactor of aliases.
- **Client-factory duplication hides asymmetries**: because almost everything goes through `clients`/`helpers`, sync- and async-specific behavior (e.g. `Timeout` extension handling, `event_hooks`) is only exercised where a test branches on `clients.async_mode`; async-only cancellation tests (`test_cancel_pending_retry`, `test_task_cancellation_closes_context`, `test_cancellation_propagates`) are hardcoded to `AsyncTypeSafeClient`, and there is no test that a **sync** call cannot be cancelled / no sync equivalent of concurrent override isolation other than through the parametrized factory.
- **Untested**: `_core/logging.setup_logging()` at import time (only the function is called directly); the "Ignoring answer ... unrecognized type" `logger.warning` (forward-compat skip verified, the log is not); `deserialize`'s non-UTF-8 replace path is only reached indirectly via error bodies; `json._enc_hook` unsupported-type branch (`TypeError: Encoding objects of type ...`) — the request-body test uses msgspec's failure path, not the hook's; `RUNTIME` header content beyond `.startswith("python/")`; process/thread-safety of the shared `RetryPolicy`-built `Retrying` under heavy concurrency; no `hypothesis`/property tests; no coverage measurement; no test asserts `py.typed` (or the absence of `__pycache__`/private modules) in the built wheel.
- **Distribution checks are shallow** (`.github/scripts/check_installed_distribution.py`): asserts `importlib.metadata.version("typesafe-sdk") == argv[1]`, `typesafe_sdk.__version__ == argv[1]`, and that both clients construct with a dummy key. It does **not** verify `py.typed` presence, package data, import of the private tree, or any request round-trip. `check_distributions.py` re-builds a wheel from the sdist and re-runs that check in `uv run --isolated --no-project --with <wheel>`.

## 6. CI

`.github/workflows/publish.yml` (only workflow; `permissions: {}` at top, actions SHA-pinned, `if: github.repository == 'typesafe-ai/typesafe-sdk-python'`):
- `build` (on `v[0-9]*` tag push or `workflow_dispatch` with `target: dry-run|testpypi|pypi`): validates target, derives `TAG="v$(uv version --short)"`, refuses non-public repo or non-merged `HEAD` for real targets (requires `REPOSITORY_PRIVATE=false` and `git merge-base --is-ancestor HEAD origin/main`), requires `GITHUB_REF_TYPE=tag` for PyPI, runs `release_notes.py` (fails unless changelog's latest `## vX.Y.Z (date)` matches the tag and has non-empty notes), then `uv build` + `check_distributions.py dist <version>` (exactly one wheel + one sdist; rebuild wheel from sdist; install+smoke-test each in isolated envs), uploads `dist/` + `release-notes.md`.
- `publish` (`target != dry-run`): `id-token: write`, `pypa/gh-action-pypi-publish` to testpypi/PyPI trusted publishing.
- `github-release` (`target == pypi`): `gh release create --verify-tag`/`edit` with the extracted notes + `gh release upload --clobber`.
- Checkout uses `persist-credentials: false`, `fetch-depth: 0`; `setup-uv` pinned to `0.12.5` with `enable-cache: false`; concurrency group `publish-<target>`, `cancel-in-progress: false`.
- No test/lint/typecheck job anywhere (see gaps).

## Start here

`src/typesafe_sdk/_core/transport.py` (1-169) — `prepare`/`RequestState`/`send`/`send_async` is where every request header, timeout, retry header, and error translation is decided, and it is the single shared surface both clients call into. Then `tests/conftest.py` (13-100) to understand how every test is wired through `ClientFactory` (sync+async parametrization, `MockTransport`, retry defaults) — without it the test suite reads as if each test covers only one client.
