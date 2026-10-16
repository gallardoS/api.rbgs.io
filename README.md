# api.rbgs.io

Backend for rbgs.io, a competitive matchmaking platform for WoW: Forever battlegrounds, featuring player ratings, match tracking, and organized wargames

## Stack and structure

Java 21, Spring Boot 4.1, Spring MVC/Security, Spring Data JPA, PostgreSQL 17, Flyway and Maven Wrapper. Caffeine caches character data; Actuator exposes health and Prometheus metrics.

- `identity`: Battle.net OIDC login, accounts and authorization (`USER`, `MODERATOR`).
- `characters`: WoW profile API, character cache and season selection.
- `emailnotifications`: signup, confirmation, unsubscribe, outbox delivery and signed feedback.
- `seasons`: season and rating-subject persistence.
- `foundation`: security, CSRF and health checks.

Matchmaking, ranked matches, ratings and groups currently have reserved packages. Evidence supports moderator-declared Darkspear tests, immutable reports and durable receipts. Flyway owns schema changes; Hibernate validates the schema and Open Session in View is disabled.

## Local development

Requires JDK 21 and Docker. Copy `.env.example` to `.env`, configure database and Battle.net credentials, then run from the repository root:

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

The API defaults to `http://localhost:8080`. Use the local frontend at `http://localhost:5173` for Battle.net login. `.env` is imported relative to the working directory; restart after changing it. Keep credentials out of Git.

```powershell
.\mvnw.cmd verify
```

Integration tests use the local PostgreSQL configured through `.env` and `RBGS_DB_*`, sharing the existing development container. They roll back test transactions or explicitly remove committed fixtures, including concurrent login accounts. CI runs `verify` with PostgreSQL; the Dockerfile builds the executable JAR without running tests.

## Configuration

| Variable | Purpose / default |
| --- | --- |
| `RBGS_DB_URL`, `RBGS_DB_USER`, `RBGS_DB_PASSWORD` | JDBC connection; defaults to `jdbc:postgresql://localhost:55432/rbgs`, user `rbgs`; password required. |
| `RBGS_DB_PORT` | Docker Compose host port; default `55432`. Changing it also requires updating the JDBC URL. |
| `RBGS_BNET_CLIENT_ID`, `RBGS_BNET_CLIENT_SECRET`, `RBGS_BNET_REDIRECT_URI` | Registered Battle.net OAuth client and callback URL. |
| `RBGS_WEB_ORIGIN` | Frontend origin for login redirects and email links. |
| `RBGS_SECURE_COOKIES` | Default `true`; use `false` for local HTTP. |
| `RBGS_API_PORT` | API port; falls back to `PORT`, then `8080`. |
| `RBGS_MANAGEMENT_PORT`, `RBGS_MANAGEMENT_ADDRESS` | Actuator listener; defaults to `8081`, `127.0.0.1`. |
| `RBGS_CONTROLLED_CAPTURE_ENABLED` | Controlled Darkspear capture tests; default `false`. Requires an active moderator to create assignments. Evidence enters review; no rating. |

## HTTP API and security

The contract is in [OpenAPI](src/main/resources/static/openapi/v1.yaml), served at `/openapi/v1.yaml`.

| Route | Purpose |
| --- | --- |
| `GET /api/v1/health`, `GET /api/v1/readiness` | Process health and database readiness. |
| `GET /api/v1/auth/me` | Current account; login uses `/oauth2/authorization/battle-net`. |
| `GET /api/v1/characters/me` | Authenticated player's characters. |
| `GET /api/v1/play/context`, `PUT /api/v1/me/selection` | Season context and character/match preferences. |
| `/api/v1/season-notifications` | Public availability, signup, confirmation and unsubscribe. |
| `/api/v1/moderation/season-notifications` | Moderator configuration and explicit launch action. |
| `POST /api/v1/email-notifications/feedback`, `POST /api/v1/email-notifications/feedback/resend` | Signed SNS and Resend feedback. |

Web authentication uses an HTTP session. Mutations require the `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie; signed webhook POSTs are exempt. Moderation requires an active `MODERATOR`. Native `/companion/*` and `/evidence*` routes ignore web cookies. Device operations require scoped bearer credentials; `/companion/connect` instead redeems a short-lived web-authorized launch ticket and native random proof. Capture permissions require explicit web consent, either through Open & connect or the existing native-initiated approval flow. Open & connect reuses and upgrades an existing same-account device; it refuses a silent switch to another account.

The companion opens browser Settings with a short-lived approval handle, separate from its native polling secret. Approval shows the PC/account/permissions and completes without code entry; the native process alone receives the device token. `POST /companion/heartbeat` records server-time presence every ~15 seconds. `/devices/me` exposes own lastSeenAt and connected (45-second TTL), independently of match evidence. Web Play/Settings shows selected-device connectivity and an open-app link; missing signals and failed queries are distinct.

For local capture testing, set `RBGS_CONTROLLED_CAPTURE_ENABLED=true` in `.env` and restart the backend. A moderator creates a test from web Settings with their declared character and an expected opponent. In the companion, select **Enable capture permissions**, approve the displayed pairing code and capture checkbox on the web, then finish pairing. Issue the own assignment code and paste its command into the addon before entering the BG. After finishing, `/reload` persists the capture; the companion sends it automatically or through **Sync saved captures**. A receipt means durable storage, never a verified result. Previous captures cannot be assigned retrospectively.

## Email providers

`EmailSender` supports Resend and AWS SES; both share templates, queue, confirmation, unsubscribe, quotas and suppression handling. Selecting Resend does not create the SES sending client. SNS remains available for feedback from earlier SES sends.

| Variable | Purpose / default |
| --- | --- |
| `RBGS_EMAIL_PROVIDER` | `resend` or `ses`; default `ses`. |
| `RBGS_EMAIL_ENABLED` | Feature and sending switch; default `false`. |
| `RBGS_EMAIL_FROM` | Sender authorized by the selected provider, e.g. `rbgs.io <notifications@rbgs.io>`. |
| `RBGS_NOTIFICATION_TOKEN_SECRET` | Stable random secret, at least 32 characters; preserve when switching providers. |
| `RBGS_EMAIL_DAILY_LIMIT` | New delivery attempts per rolling 24 hours; default `90`. Provider quotas also apply. |
| `RBGS_SEASON_LIVE` | Allows launch delivery and closes signup; default `false`. Sending the campaign also requires `POST /api/v1/moderation/season-notifications/launch`. |
| `RBGS_RESEND_API_KEY` | Required for Resend sending. |
| `RBGS_RESEND_WEBHOOK_SECRET` | Resend feedback signing secret, separate from the API key. Missing secret disables feedback processing, not sending. |
| `RBGS_SES_REGION` | Region containing the verified SES identity. |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Optional explicit SES credentials; otherwise the AWS SDK default credential chain applies. Access and secret keys must be supplied together. |
| `RBGS_SES_FEEDBACK_TOPIC_ARN` | Allowed SNS feedback topic. |

Permanent bounces and complaints suppress future sends. Ambiguous delivery failures enter `REVIEW`; changing providers does not replay them automatically. Templates and localized text live in `src/main/resources/emails`.
