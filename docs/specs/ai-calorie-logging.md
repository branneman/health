# Spec — AI Calorie Logging

**Story 16 (AI calorie estimate)**

Adds a fourth path to the Log flow: "Ask AI". The user describes a meal in text, attaches a photo,
or both, and the server returns a calorie estimate from Claude. The estimate feeds directly into
quick-add so no calorie information is lost — the user can accept it as-is, adjust it, or discard.

---

## Motivation

Users frequently cannot find exact calorie counts — restaurant meals, home-cooked dishes eaten
elsewhere, unknown portions. The existing paths (template, quick-add, build from scratch) all
require the user to already know the number or the ingredients. "Ask AI" fills the gap: a rough
estimate is always better than no log.

---

## Scope

- User connects their Anthropic API key via Settings → AI
- New "Ask AI" path in the Log flow: text description + optional photo → kcal estimate +
  explanation → quick-add pre-fill
- Server mediates all Claude calls; app never calls Anthropic directly
- API key stored encrypted server-side (same pattern as Polar token)

Out of scope:
- Multiple AI providers (Claude only)
- Macro estimation (protein/carbs/fat) — kcal only
- Automatically saving AI estimates as templates
- E2E automated tests (live Claude + real image cost not justified for CI)

---

## Architecture

```
App                        Server                      Anthropic
────────────────────       ────────────────────────    ──────────────────
Ask AI screen        →     POST /ai/estimate
  JPEG + resize             validate request
  ≤ 1024px / ~1 MB         fetch ai_config for user
                            decrypt API key
                            call Claude (Java SDK)    → claude-opus-4-8
                                                      ← structured JSON
                            validate kcal + explanation
                            build AiEstimateResponse
Result screen        ←     200 + { kcal, explanation }
```

The server holds image bytes in JVM heap only for the duration of the Ktor request handler. It
base64-encodes them inline into the Claude API request body, then they are GC'd when the handler
returns. No disk writes, no caching, no temp files.

---

## Data Model

### Postgres — `ai_config` table

New Flyway migration: `V10__add_ai_config.sql`

```sql
CREATE TABLE ai_config (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key     TEXT        NOT NULL,
    expires_at  DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id)
);
```

`api_key` is stored encrypted using `pgcrypto` (`pgp_sym_encrypt` / `pgp_sym_decrypt`) with the
server-side secret from Ansible vault — identical pattern to Polar token storage.

`expires_at` is optional. When set and in the past, the server treats the config as absent and
returns `ai_key_expired`.

### Shared DTOs

```kotlin
@Serializable
data class AiConfigStatusDto(
    val configured: Boolean,
    val expiresAt: String?   // YYYY-MM-DD or null
)

@Serializable
data class AiEstimateResponse(
    val kcal: Int,
    val explanation: String
)
```

No local Room table — AI config is server-only. The app reads `GET /ai/config` on Settings → AI
screen load.

---

## API

### `GET /ai/config`

Auth required. Returns the user's AI configuration status. Never returns the key itself.

**Response `200`:**

```json
{ "configured": true, "expiresAt": "2027-01-01" }
```

```json
{ "configured": false, "expiresAt": null }
```

`configured` is `false` when no key is stored or when `expires_at` is in the past.

---

### `PUT /ai/config`

Auth required. Upserts the user's Anthropic API key. Creates on first call, replaces on subsequent
calls.

**Request:**

```json
{ "apiKey": "sk-ant-...", "expiresAt": "2027-01-01" }
```

`expiresAt` is optional (omit to store without an expiry). `apiKey` max 300 chars.

**Response `200`:** same shape as `GET /ai/config`.

---

### `POST /ai/estimate`

Auth required. Multipart form data. Body size limit: 2 MB (per-route override — server default is
64 KB).

**Parts:**

| Name | Content-type | Required | Notes |
|------|-------------|----------|-------|
| `text` | `text/plain` | No | Meal description, ≤ 500 chars |
| `image` | `image/jpeg` or `image/png` | No | Pre-resized on app to max 1024px / ~1 MB |

At least one of `text` or `image` must be present. Both may be sent together for better accuracy.

**Response `200`:**

```json
{
  "kcal": 650,
  "explanation": "This looks like a standard tiramisu serving (approx. 150g). Mascarpone and ladyfingers account for most of the calories."
}
```

`kcal` is a positive integer in range [1, 9999]. `explanation` is a non-empty string, max 300 chars.

**Error responses:**

| Code | Body | Condition |
|------|------|-----------|
| `400` | — | Neither `text` nor `image` part present; text > 500 chars; image not JPEG/PNG |
| `401` | — | Missing or expired bearer token |
| `422` | `{ "error": "ai_not_configured" }` | No `ai_config` row for this user |
| `422` | `{ "error": "ai_key_expired" }` | `expires_at` is in the past |
| `422` | `{ "error": "ai_estimate_failed" }` | Claude API error, timeout, refusal, or invalid response |

---

## Claude Integration

**Model:** `claude-opus-4-8`
**SDK:** Anthropic Java SDK (`com.anthropic.*`) — Kotlin uses the Java SDK
**Thinking:** disabled (low-latency estimation; no complex reasoning needed)

The server enforces structured output via `output_config.format` with a JSON schema:

```json
{
  "type": "object",
  "properties": {
    "kcal":        { "type": "integer", "minimum": 1, "maximum": 9999 },
    "explanation": { "type": "string",  "maxLength": 300 }
  },
  "required": ["kcal", "explanation"]
}
```

**Server-side validation after Claude responds** — the server always validates before building its
response and never passes through raw Claude output:

1. Parse the structured JSON from Claude's response
2. Assert `kcal` is an integer in range [1, 9999]
3. Assert `explanation` is a non-empty string, max 300 chars
4. Build and return `AiEstimateResponse` from validated fields

Any failure in steps 1–3 (parse error, out-of-range value, missing field, Claude refusal, API
timeout, network error to Anthropic) → `422 { "error": "ai_estimate_failed" }`.

---

## App UX

### Entry point

"Ask AI" is the 4th path in the Log flow, listed after "Build from scratch". If the user has not
configured a key, tapping "Ask AI" navigates to Settings → AI rather than the estimate screen.

### Ask AI screen

- **Text field** — "What did you eat?" Placeholder: "e.g. tiramisu, restaurant portion". Optional.
  Max 500 chars.
- **Photo section** — "Add a photo (optional)". One button opens camera or gallery picker. Shows
  thumbnail with remove button once selected.
- **Estimate button** — disabled until at least one of (text, photo) is provided. Label:
  "Get estimate".

The app JPEG-compresses and resizes the image to max 1024px on the longest edge, capped at
approximately 1 MB, before upload.

### Loading state

Spinner with label "Asking Claude…". The server handles timeouts internally and returns `422` if
Claude does not respond in time.

### Result screen

```
Claude estimates: 650 kcal

"This looks like a standard tiramisu serving
 (approx. 150g). Mascarpone and ladyfingers
 account for most of the calories."

[Use this]   [Edit amount]   [Discard]
```

- **Use this** — pre-fills quick-add with the estimated kcal and the text description as label (if
  text was provided), then logs immediately via the quick-add flow
- **Edit amount** — opens the quick-add screen with kcal pre-filled but editable; user can correct
  before logging
- **Discard** — returns to log path selection without logging

### Error states

| Error | Message shown |
|-------|---------------|
| `ai_not_configured` | "Connect your Anthropic API key in Settings → AI" (tappable) |
| `ai_key_expired` | "Your Anthropic API key has expired. Update it in Settings → AI." |
| `ai_estimate_failed` | "Claude couldn't estimate this one. Try adding a description or a clearer photo." |
| Network error | "No connection — try again when you're online." |

### Settings → AI screen

- **API key field** — masked input, show/hide toggle. Label: "Anthropic API key".
- **Expiry date** — optional date picker. When set and within 7 days of expiry, app shows:
  "API key expires on {date}."
- **Save** / **Remove key** buttons.
- **Status badge** — "Connected" / "Expired" / "Not configured".

---

## Security

- Both `/ai/config` and `/ai/estimate` sit inside `authenticate("api") { ... }`.
- All `ai_config` queries filter by `userId eq sessionUserId` — no cross-user access possible.
- The raw API key is never logged at any log level. Log only `userId` and request outcome.
- `GET /ai/config` never returns the key value — only `configured` and `expiresAt`.
- `api_key` is stored with `pgcrypto` column-level encryption; server-side secret in Ansible vault.
- `POST /ai/estimate` has a per-route body size limit of 2 MB (overrides the server-default 64 KB).

---

## Testing

### Tier 1 — Unit

**`AiEstimateServiceTest`** — inject a fake Anthropic client (no real SDK calls):
- Valid response with in-range `kcal` and non-empty `explanation` → `AiEstimateResponse` built correctly
- `kcal = 0` → exception
- `kcal > 9999` → exception
- `explanation` empty string → exception
- Claude refusal → exception
- SDK timeout → exception
- Malformed / missing fields in response → exception

**`AiConfigServiceTest`**:
- Upsert creates row on first call
- Upsert replaces key + expiry on second call
- `getStatus()` returns `configured = true` when key present and `expires_at` is null
- `getStatus()` returns `configured = true` when key present and `expires_at` is a future date
- `getStatus()` returns `configured = false` when no row exists
- `getStatus()` returns `configured = false` when `expires_at` is a past date
- Returned `AiConfigStatusDto` never contains the key value

**DTO serialization**:
- `AiConfigStatusDto` and `AiEstimateResponse` round-trip through `Json.encodeToString` /
  `decodeFromString`, including nullable fields

### Tier 2a — Server integration

New class `AiIntegrationTest` — UUID slot **#11**
(`00000000-0000-0000-0000-000000000011`, email `ai-test@test.local`).

- `PUT /ai/config` with valid key + expiry → `200`; subsequent `GET /ai/config` returns
  `configured = true` and correct `expiresAt`
- `GET /ai/config` when no row → `configured = false`
- `GET /ai/config` when `expires_at` is in the past → `configured = false`
- `GET /ai/config` response body never contains key value
- `POST /ai/estimate` — no parts → `400`
- `POST /ai/estimate` — text part over 500 chars → `400`
- `POST /ai/estimate` — image part with non-JPEG/PNG content-type → `400`
- `POST /ai/estimate` — no `ai_config` row for user → `422 { "error": "ai_not_configured" }`
- `POST /ai/estimate` — expired key → `422 { "error": "ai_key_expired" }`
- `POST /ai/estimate` — Claude service returns valid response (faked at service boundary) →
  `200` with correct `kcal` and `explanation`
- `POST /ai/estimate` — Claude service throws → `422 { "error": "ai_estimate_failed" }`
- Multi-user isolation: user A's `ai_config` is not accessible or modifiable via user B's token
- Auth: `GET /ai/config`, `PUT /ai/config`, and `POST /ai/estimate` all return `401` without token

### Tier 2b — App component

**`AskAiViewModelTest`** (Robolectric, fake `AiRepository`):
- Initial state: estimate button disabled
- Text entered: button enabled
- Photo added without text: button enabled
- Request in flight: loading state, button disabled
- Success response: result state with `kcal` and `explanation`
- `ai_not_configured` error: config-required error state
- `ai_key_expired` error: expired-key error state
- `ai_estimate_failed` error: recoverable error state
- Network error: network error state

**`AskAiScreenTest`** (Robolectric + `ComposeTestRule`):
- Estimate button disabled with no input; enabled after typing text
- Estimate button enabled after photo selected
- Loading indicator visible during in-flight state
- Result screen shows `kcal` value and explanation text
- "Use this", "Edit amount", and "Discard" buttons present on result screen
- Each error state renders correct copy

**`AiConfigScreenTest`** (Robolectric + `ComposeTestRule`):
- Key field masked by default; show/hide toggle changes visibility
- Save button disabled when field is empty; enabled when non-empty
- Status badge displays correct string for each of the three states

### Tier 3 — API tests

`AiApiTest` in `server/src/apiTest/`. Requires `ANTHROPIC_API_KEY` env var. A pre-seeded
`ai_config` row for `test+api@bran.name` must be present in `test-api-account-seed.sql`.

- `PUT /ai/config` → `GET /ai/config` round-trip: response shows `configured = true`
- `POST /ai/estimate` with text description only ("slice of tiramisu, restaurant portion") →
  live Claude call → `200` with integer `kcal` in range [1, 9999] and non-empty `explanation`
- Teardown: delete `ai_config` row for test user

### Tier 4 — E2E

Deferred. The full path (open log → Ask AI → type description → get estimate → use it) requires
a live Anthropic key in CI and incurs per-call cost. Not suitable for automated smoke tests.
