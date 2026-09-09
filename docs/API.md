# API Reference

Every example below is a **real response captured from the running system**, with tokens
truncated and identifiers replaced.

Interactive documentation is served by the gateway at **<http://localhost:8080/docs>**
(Swagger UI, with a selector for each service). The raw OpenAPI documents are proxied at
`/api-docs/user-service` and `/api-docs/notification-service`.

## Contents

- [Conventions](#conventions)
- [Errors](#errors)
- [Authentication](#authentication)
- [Users](#users)
- [Notifications](#notifications)
- [Rate limits](#rate-limits)
- [Events emitted](#events-emitted)

---

## Conventions

**Base URL.** `http://localhost:8080` — the API Gateway. It is the only reachable entry
point; the services are not published to the host.

**Content type.** `application/json` for requests; `application/json` for successful
responses and `application/problem+json` for errors.

**Authentication.** Send the access token as a bearer credential:

```
Authorization: Bearer <accessToken>
```

**Correlation.** Send `X-Correlation-Id` to tie a request to server-side logs. Any value
matching `[A-Za-z0-9._-]{1,128}` is honoured and echoed back; anything else is replaced with
a generated one (an unvalidated value would allow header injection and log forging). Every
response carries the header, and the same id flows through both services and into the
events they publish.

**Timestamps.** ISO-8601 with UTC offset (`2026-09-09T05:15:51.016988204Z`).

**Paging.** Collection endpoints accept `page` (0-based), `size` and `sort`, and return a
fixed envelope. `size` is capped at **100** (default 20); a larger request is silently
reduced rather than rejected. There is no unbounded listing — that would be a
denial-of-service vector against the service's own memory as tables grow.

---

## Errors

All errors use **RFC 9457 Problem Details**, extended with two members: a stable `code` that
clients should branch on (rather than parsing prose or relying on status codes alone), and
the `correlationId` for support.

```http
POST /api/v1/auth/register
{"email":"nope","password":"short","fullName":""}
```
```json
{
  "type": "https://docs.trams.local/errors/validation-error",
  "title": "Bad Request",
  "status": 400,
  "detail": "The request payload is invalid.",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_ERROR",
  "correlationId": "491112a5-7e29-40e2-8c00-8c2431d20568",
  "errors": [
    { "field": "password", "message": "must be between 12 and 128 characters" },
    { "field": "email",    "message": "must be a well-formed email address" },
    { "field": "fullName", "message": "must not be blank" }
  ]
}
```

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Payload failed validation; see `errors[]` |
| `BAD_REQUEST` | 400 | Malformed JSON, an unparseable path variable, or a bad query parameter |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for the route |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` is not `application/json` |
| `UNAUTHORIZED` | 401 | Missing, malformed, expired or invalid access token |
| `INVALID_CREDENTIALS` | 401 | Wrong email **or** password — deliberately indistinguishable |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token unknown, expired, or already used |
| `GATEWAY_ONLY` | 401 | Request reached a service without going through the gateway |
| `FORBIDDEN` | 403 | Authenticated, but lacking the required role |
| `ACCOUNT_DISABLED` | 403 | Account exists but is disabled |
| `USER_NOT_FOUND` | 404 | No such user |
| `NOTIFICATION_NOT_FOUND` | 404 | No such notification **for this caller** |
| `EMAIL_ALREADY_REGISTERED` | 409 | Address is already in use |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic-lock conflict; retry |
| `UPSTREAM_UNAVAILABLE` | 503 | Circuit breaker open; `Retry-After` is set |
| `INTERNAL_ERROR` | 500 | Unexpected failure; details are logged, never returned |

> Two responses are deliberately vague. `INVALID_CREDENTIALS` is identical for an unknown
> address and a wrong password — and the unknown-address path still performs a password
> hash, so response *timing* does not distinguish them either. `NOTIFICATION_NOT_FOUND` is
> returned for a notification belonging to someone else, because a 403 would confirm the id
> is real and let an attacker enumerate identifiers.

---

## Authentication

### `POST /api/v1/auth/register`

Creates an account. Emits `user.registered`, which the Notification Service turns into a
welcome email. The account row and the event are committed in one transaction, so a welcome
email is never sent for a registration that failed.

Public. Rate limited per source IP.

**Request**

| Field | Rules |
|---|---|
| `email` | required, well-formed, ≤ 320 chars; stored lower-cased |
| `password` | required, **12–128 characters** |
| `fullName` | required, ≤ 200 chars |

```json
{ "email": "apidoc@example.com", "password": "a-sufficiently-long-password", "fullName": "API Doc" }
```

**`201 Created`**

```json
{
  "id": "df94801d-5334-44e8-9047-b75fd971b9ea",
  "email": "apidoc@example.com",
  "fullName": "API Doc",
  "status": "ACTIVE",
  "roles": ["USER"],
  "createdAt": "2026-09-09T05:15:51.016988204Z",
  "updatedAt": "2026-09-09T05:15:51.016988204Z"
}
```

`400` validation failed · `409` address already registered

> The password policy favours length over composition rules, following current NIST
> guidance. The 128-character ceiling exists to bound Argon2 work, since hashing cost scales
> with input.

---

### `POST /api/v1/auth/login`

Exchanges credentials for a token pair. Public; rate limited **aggressively** per source IP,
because this is the endpoint attacked with credential stuffing.

```json
{ "email": "apidoc@example.com", "password": "a-sufficiently-long-password" }
```

**`200 OK`**

```json
{
  "accessToken": "eyJraWQiOiJ0cmFtcy1zaWduaW5nLWtl...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "expiresAt": "2026-09-09T05:30:51.548197284Z",
  "refreshToken": "5nkj2X4QXBi6uxFs...",
  "refreshTokenExpiresAt": "2026-10-09T05:15:51.569620683Z",
  "user": {
    "id": "df94801d-5334-44e8-9047-b75fd971b9ea",
    "email": "apidoc@example.com",
    "fullName": "API Doc",
    "status": "ACTIVE",
    "roles": ["USER"],
    "createdAt": "2026-09-09T05:15:51.016988Z",
    "updatedAt": "2026-09-09T05:15:51.165892Z"
  }
}
```

`401` invalid credentials · `403` account disabled

The access token is a stateless RS256 JWT:

```json
{ "kid": "trams-signing-key-1", "alg": "RS256" }
{ "iss": "https://trams.local", "aud": "trams-api", "sub": "<user id>",
  "typ": "access", "roles": ["USER"], "email": "apidoc@example.com" }
```

Both a relative (`expiresIn`) and an absolute (`expiresAt`) expiry are returned: the former
is what OAuth clients expect, the latter lets a client refresh proactively without trusting
its own clock at the moment of receipt.

---

### `POST /api/v1/auth/refresh`

Exchanges a refresh token for a new pair. **The presented token is consumed** — refresh
tokens are single-use.

```json
{ "refreshToken": "5nkj2X4QXBi6uxFs..." }
```

**`200 OK`** — same body as login, with a *new* `refreshToken`.

`401` unknown, expired, or already used.

> **Replay revokes everything.** Because rotation consumes the token, presenting a consumed
> one means it was captured and replayed (or a client is broken). Since the attacker's copy
> is indistinguishable from the legitimate one, the entire token *family* — every token
> descended from that login — is revoked, and both parties must sign in again. A client that
> loses a rotation response should re-authenticate rather than retry.

---

### `POST /api/v1/auth/logout`

Revokes one refresh token.

```json
{ "refreshToken": "5nkj2X4QXBi6uxFs..." }
```

**`204 No Content`** — returned even for a token that does not exist, so the endpoint cannot
be used to probe which token values are valid.

Access tokens are stateless and remain valid until they expire (≤ 15 minutes); the refresh
token is the revocable half of the pair.

---

## Users

All routes require `Authorization: Bearer <accessToken>`.

The caller's identity always comes from the verified `sub` claim, never from a path or body
parameter — so there is no route on which one user could reference another's id and hope the
check was forgotten.

### `GET /api/v1/users/me`

**`200 OK`** — the `UserResponse` shape shown under register.

### `PATCH /api/v1/users/me`

Partial update; omitted fields are left unchanged. Emits `user.profile_updated` listing
exactly which fields changed — and emits **nothing** when the request changes nothing, so a
resubmitted form does not generate a notification.

```json
{ "fullName": "API Doc Renamed" }
```

**`200 OK`** — the updated user. `409` if the new address belongs to someone else.

### `POST /api/v1/users/me/change-password`

Requires the current password even though the caller is authenticated: proof of knowledge is
what stops a stolen access token from becoming permanent account takeover.

```json
{ "currentPassword": "a-sufficiently-long-password", "newPassword": "an-even-longer-replacement" }
```

**`204 No Content`** — and **every refresh token for the account is revoked**. If the
password is being changed because it was compromised, leaving the attacker's session alive
would defeat the exercise. Emits `user.password_changed` as a security alert.

`401` current password incorrect.

### `DELETE /api/v1/users/me`

**`204 No Content`**. Emits `user.deleted` *before* the row is removed, while the address and
name are still available — the Notification Service needs them to send the confirmation and,
by design, cannot call back to ask.

### Administrative routes

Require the `ADMIN` role. Enforced at the gateway **and** again in the service with
`@PreAuthorize`: the edge check is a fast rejection, the service check is authoritative.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/users?page=0&size=20` | Paged list of users |
| `GET /api/v1/users/{userId}` | Any user by id |
| `DELETE /api/v1/users/{userId}` | Delete any user (emits `user.deleted`) |

`403` for a non-admin caller.

> No endpoint grants the `ADMIN` role — registration always yields `USER` only. Promotion is
> a deliberate database operation, so no request can escalate privilege:
> ```bash
> docker compose exec postgres psql -U users_svc -d users_db \
>   -c "INSERT INTO user_roles (user_id, role) SELECT id, 'ADMIN' FROM users WHERE email = 'you@example.com';"
> ```
> The affected user must obtain a new access token for the change to take effect, since roles
> are carried in the token.

---

## Notifications

Served by the Notification Service. This API exists **for clients only** — it plays no part
in the integration between the two services, which happens entirely over NATS JetStream.

### `GET /api/v1/notifications/me`

The caller's own notification history, newest first.

| Parameter | Notes |
|---|---|
| `status` | optional filter: `PENDING`, `SENT`, `FAILED`, `DEAD` |
| `page`, `size` | paging (default size 20) |

**`200 OK`**

```json
{
  "content": [
    {
      "id": "<uuid>",
      "eventId": "<uuid>",
      "eventType": "user.registered",
      "channel": "EMAIL",
      "subject": "Welcome to TRAMS",
      "status": "SENT",
      "attempts": 1,
      "createdAt": "2026-09-09T05:15:51.803485Z",
      "sentAt": "2026-09-09T05:15:52.182843Z"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

The rendered body is omitted from the list — bodies are multi-kilobyte HTML documents, and
returning twenty per page would make a history request far heavier than it needs to be.

### `GET /api/v1/notifications/me/{notificationId}`

**`200 OK`** — the full record, adding `body`, `recipientEmail`, `lastError` and
`correlationId`.

`404` if it does not exist **or belongs to another user**.

### Delivery status

| Status | Meaning |
|---|---|
| `PENDING` | Recorded, not yet delivered. A redelivery of the event resumes from here. |
| `SENT` | Delivered. A redelivery is acknowledged and skipped. |
| `FAILED` | An attempt failed transiently; the event will be redelivered with backoff. |
| `DEAD` | Attempts exhausted. The event was dead-lettered and is replayable by an operator. |

---

## Rate limits

Enforced at the gateway with Redis-backed token buckets, so limits stay correct across
gateway replicas.

| Routes | Key | Default rate | Burst |
|---|---|---|---|
| `/api/v1/auth/**` | source IP | 3 req/s | 8 |
| everything else | authenticated user id (IP if anonymous) | 20 req/s | 40 |

Auth routes are limited far more aggressively because they are unauthenticated and are the
target for credential stuffing. Authenticated routes key on the **user id** so that one busy
client cannot exhaust the budget of everyone behind a shared NAT.

Every response carries the current state:

```
X-RateLimit-Replenish-Rate: 3
X-RateLimit-Burst-Capacity: 8
X-RateLimit-Remaining: 0
```

Exceeding the limit returns **`429 Too Many Requests`**. Tune with `RATE_LIMIT_*` and
`AUTH_RATE_LIMIT_*` in `.env`.

---

## Events emitted

Published by the User Service to JetStream and consumed by the Notification Service. Clients
never see these; they are documented because they are the actual contract between the
services.

| Event | Subject | Triggered by | Notification |
|---|---|---|---|
| `user.registered` | `user.events.registered` | `POST /auth/register` | Welcome |
| `user.profile_updated` | `user.events.profile_updated` | `PATCH /users/me` (when something changed) | Change summary |
| `user.password_changed` | `user.events.password_changed` | `POST /users/me/change-password` | Security alert |
| `user.deleted` | `user.events.deleted` | `DELETE /users/me` or `/users/{id}` | Confirmation |

All four share a CloudEvents-style envelope:

```json
{
  "id": "5b84f870-779e-45b0-a7fb-6e866991ec81",
  "specVersion": 1,
  "type": "user.registered",
  "dataVersion": 1,
  "source": "user-service",
  "subject": "user.events.registered",
  "occurredAt": "2026-09-09T05:15:51.016988204Z",
  "correlationId": "smoke-1788930950",
  "data": {
    "userId": "df94801d-5334-44e8-9047-b75fd971b9ea",
    "email": "apidoc@example.com",
    "fullName": "API Doc",
    "roles": ["USER"],
    "registeredAt": "2026-09-09T05:15:51.016988204Z"
  }
}
```

`id` doubles as the broker's deduplication key and as the consumer's idempotency key.
`correlationId` is carried from the originating HTTP request, which is what makes a single
identifier traceable from the client call through to the delivered email.

Every payload carries the recipient's identity, not just an id — so the consumer never needs
to call back to the User Service to render a message. Consumers must tolerate unknown fields
(the codec does), which is what allows the producer to add a field and be deployed first.

`specVersion` versions the envelope; `dataVersion` versions the payload independently, so
routine payload evolution does not break every consumer at once.
