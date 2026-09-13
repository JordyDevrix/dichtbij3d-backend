# Dichtbij3D — backend

Kotlin + Spring Boot microservice that powers the Dichtbij3D marketplace: adverts,
3D models, bidding, notifications, moderation and the print-cost calculator.

* Kotlin 2 / Spring Boot 3 / Java 21 bytecode, built with **Maven**
* **PostgreSQL 16** with **Flyway** migrations
* **MinIO** (S3-compatible) blob storage for images and model files
* Argon2id password hashing, JWT access tokens + rotating opaque refresh tokens,
  TOTP and passkeys (WebAuthn)

## Requirements

| Tool | Version used |
|---|---|
| JDK | 21 or newer (25 works) |
| Maven | 3.9+ |
| Docker **or** Podman | for Postgres + MinIO |

## Run it

```bash
# 1. start the infrastructure
docker compose up -d          # or: podman compose up -d

# 2. start the API
mvn spring-boot:run
```

The API listens on <http://localhost:8080>.

| Service | URL | Credentials |
|---|---|---|
| API | http://localhost:8080 | — |
| PostgreSQL | localhost:**5433** | `dichtbij3d` / `dichtbij3d` |
| MinIO S3 | http://localhost:9000 | `dichtbij3d` / `dichtbij3d-secret` |
| MinIO console | http://localhost:9001 | same |

> **Podman note** — if the Docker socket is not accessible on your machine, use rootless
> Podman instead: `systemctl --user start podman.socket && podman compose up -d`.
> Postgres is published on **5433** on purpose so it never clashes with a local Postgres.

### Seeded accounts

Demo data is inserted on first boot (`app.demo-data.enabled`, default `true`).

| Account | Password | Roles |
|---|---|---|
| `admin@dichtbij3d.nl` | `Admin123!` | ADMIN, CUSTOMER |
| `sanne@dichtbij3d.nl` | `Demo12345!` | CUSTOMER |
| `bram@dichtbij3d.nl` | `Demo12345!` | PRINTER |
| `lieke@dichtbij3d.nl` | `Demo12345!` | MODELLER, PRINTER |
| `tom@dichtbij3d.nl` | `Demo12345!` | CUSTOMER, PRINTER |

## API surface

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/{register,login,refresh,logout,logout-all,password}` |
| MFA | `POST /api/auth/mfa/verify`, `/api/auth/mfa/totp/{setup,enable,disable}` |
| Passkeys | `/api/auth/passkeys` + `register/{options,finish}` + `login/{options,finish}` |
| Users | `GET/PATCH /api/users/me`, `POST /api/users/me/avatar`, `GET /api/users/{id}` |
| Adverts | `GET/POST /api/adverts`, `GET/PATCH/DELETE /api/adverts/{id}`, `/view`, `/accept`, `/status`, `/reactions`, `/bids` |
| Models | `/api/models`, `/api/models/mine`, `/api/models/library`, `/{id}/acquire`, `/{id}/files/{fileId}/download` |
| Calculator | `GET /api/printers`, `POST /api/calculator/estimate` |
| Notifications | `/api/notifications`, `/unread-count`, `/{id}/read`, `/read-all` |
| Moderation | `POST /api/reports`, `POST /api/adverts/{id}/moderate/delete` |
| Admin | `/api/admin/{metrics,users,adverts,reports,audit-log}` |
| Public | `GET /api/public/stats`, `GET /api/files/**` (public blobs) |

### Marketplace search

`GET /api/adverts` accepts `q, type[], tag[], status[], minPrice, maxPrice, city,
authorId, postedAfter, postedBefore, biddable, sort, page, size`.
Sort keys: `newest, oldest, views|most_viewed, least_viewed, price_asc, price_desc,
popular, deadline`.

### Smart view counting

`POST /api/adverts/{id}/view` takes a `dwellMillis` value. A view is only counted when

* the visitor is **not** the author,
* dwell time is at least `app.views.min-dwell-millis` (1500 ms), and
* the same viewer fingerprint (user id or IP+user-agent hash) has not been counted for
  this advert within `app.views.dedup-window` (12 h).

Spam-clicking your own advert therefore never inflates the counter.

## Security

* **Argon2id** (`m=19456, t=2, p=1`) for passwords.
* **Access tokens**: short-lived JWT (15 min). **Refresh tokens**: opaque, SHA-256
  hashed at rest, rotated on every use, grouped in families — replaying a rotated token
  revokes the whole family (theft detection).
* **TOTP** (RFC 6238) as optional second factor, **passkeys** (WebAuthn) for passwordless
  sign-in. Neither is mandatory.
* CORS, rate limiting on auth endpoints and a full moderation audit log.

## Why no pgvector?

The requirement is keyword/tag/city filtering over adverts and models — not semantic
similarity. PostgreSQL's built-in `pg_trgm` + `tsvector` full-text search covers this
with lower operational cost, so **pgvector is intentionally not installed**. The
rationale is repeated at the top of `V1__baseline.sql`; if semantic "find similar
models" is ever added, the extension can be enabled in a new migration.

## Configuration

Everything lives in `src/main/resources/application.yml` and can be overridden with
environment variables, e.g.:

```bash
export DICHTBIJ3D_JWT_SECRET=...   # app.jwt.secret (>= 64 random chars)
export DB_URL=... DB_USER=... DB_PASSWORD=...
export MINIO_ENDPOINT=... MINIO_ACCESS_KEY=... MINIO_SECRET_KEY=...
export ADMIN_EMAIL=... ADMIN_PASSWORD=...   # bootstrap admin
export DEMO_DATA=false                      # disable the demo seed
```

If MinIO is unreachable the storage service transparently falls back to local disk
(`./.storage`), so the app keeps working without the container.

## Build

```bash
mvn clean package      # produces target/dichtbij3d-backend-*.jar
java -jar target/dichtbij3d-backend-*.jar
```

## Container image

Every push to `main` and every `v*.*.*` tag publishes a multi-arch image to GHCR via
`.github/workflows/release.yml`:

```
ghcr.io/jordydevrix/dichtbij3d-backend:latest
ghcr.io/jordydevrix/dichtbij3d-backend:1.2.3
ghcr.io/jordydevrix/dichtbij3d-backend:sha-<commit>
```

Build it yourself with `docker build -t dichtbij3d-backend .`.
The image expects `DB_URL`, `DB_USER`, `DB_PASSWORD`, `MINIO_*`,
`DICHTBIJ3D_JWT_SECRET`, `CORS_ORIGINS`, `WEBAUTHN_RP_ID`, `WEBAUTHN_ORIGINS` and
`ADMIN_PASSWORD`, and exposes `8080` with a health check on `/actuator/health`.

For a full stack (frontend + backend + Postgres + MinIO) use the deployment repository:
<https://github.com/JordyDevrix/dichtbij3d>.
