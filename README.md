# GuildWorkman API

Spring Boot backend for **GuildWorkman**, a marketplace that connects clients
with skilled workers (electricians, plumbers, barbers, etc.) for booked
appointments and consultations, with in-app payments and reviews. Extended
with Soroban smart contracts (see
[`guildworkman-contracts`](https://github.com/workman-labs/guildworkman-contracts))
for on-chain escrow, reputation, and loyalty rewards.

## Tech stack

- **Java 17**, **Spring Boot 3.3.2**
- **Spring Web**, **Spring Data JPA**, **Spring Security**
- **PostgreSQL** (`org.postgresql:postgresql`)
- **Auth0 java-jwt** — JWT issuance/verification
- **ModelMapper** — DTO ↔ entity mapping
- **Cloudinary** (`cloudinary-http44`) — media/image uploads
- **OkHttp** + **Gson** — outbound HTTP calls (e.g. Paystack)
- Build: **Maven** (wrapper included), packaged as a runnable jar, containerized via Docker

## Architecture

Single Spring Boot module, package root `com.guildworkman.api`, in a
conventional layered structure:

```
controllers/   REST endpoints (@RestController)
services/
  ServiceUtils/        service interfaces
  implmentations/       interface implementations
  paystack/             Paystack payment integration
data/models/    JPA entities
dto/requests/   inbound request payloads
dto/responses/  outbound response payloads
exceptions/     custom exceptions + GlobalExceptionHandler
config/         Spring config (security, mail, cloud, mapper, app)
utils/          JWT + validation helpers
```

Core domain entities: `Client`, `SkilledWorker`, `Skill`, `Address`,
`Appointment`, `Consultation`, `ConsultationAvailability`, `Review`,
`Transaction` / `TransactionHistory`, `Notification`, `Admin`.

## Features

- Client and skilled-worker registration and login (JWT-based auth)
- Appointment booking, update, cancellation, and deletion
- Consultations: booking a consultation and scheduling client/worker availability
- Skilled-worker profile management, skill listing, and geo lookup (`/nearby`)
- Payment initiation/verification via **Paystack** (service layer + `PaymentServiceImpl`)
- Transactional email sending (mail service, provider-agnostic API key + URL config)
- Reviews and worker ratings (`ReviewServiceImpl`)
- Admin operations (`AdminServiceImpl`)
- Global exception handling with typed exceptions (`UserNotFoundException`,
  `AppointmentNotFoundException`, `InvalidEmailFoundException`,
  `InvalidPasswordException`, `GuildWorkmanException`)

Two controllers exist in the tree but are currently **commented out and inactive**:
`PaymentController` (`/payment`) and `mapController/MapController` (`/showMap`).
Payment logic itself is implemented in the service layer
(`services/paystack/PaymentServiceImpl`) and can be re-exposed by uncommenting
`PaymentController` once its request/response wiring is finalized.

## Prerequisites

- JDK 17
- PostgreSQL instance (local or remote)
- Maven (or just use the included `./mvnw` wrapper — no local Maven install needed)

## Configuration

All configuration lives in `src/main/resources/application.properties`, entirely
as `${ENV_VAR:default}` placeholders — there is no separate secrets file anymore.
Non-secret keys have real defaults; secret keys default to empty and must be
supplied via environment variables in every environment (local, CI, production).
See `.env.example` at the repo root for the full list with a short description
of each. Copy it to `.env` for local reference (never commit a populated one) and
export the values into your shell, IDE run configuration, or `docker run --env-file`.

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DATABASE_URL` | `jdbc:postgresql://localhost:5432/service` |
| `spring.datasource.username` | `DATABASE_USERNAME` | `postgres` |
| `spring.datasource.password` | `DATABASE_PASSWORD` | `password` |
| `spring.jpa.hibernate.ddl-auto` | `DDL_AUTO` | `update` |
| `mail.api.key` | `MAIL_API_KEY` | *(empty — required)* |
| `mail.api.url` | `MAIL_API_URL` | `https://api.brevo.com/v3/smtp/email` |
| `cloud.api.name` | `CLOUDINARY_API_NAME` | *(empty — required)* |
| `cloud.api.key` | `CLOUDINARY_API_KEY` | *(empty — required)* |
| `cloud.api.secret` | `CLOUDINARY_API_SECRET` | *(empty — required)* |
| `paystack.secret.key` | `PAYSTACK_SECRET_KEY` | *(empty — required)* |
| `paystack.verify.payment.url` | `PAYSTACK_VERIFY_URL` | `https://api.paystack.co/transaction/verify` |
| `paystack.initiate.payment` | `PAYSTACK_INITIATE_URL` | `https://api.paystack.co/transaction/initialize` |
| `spring.h2.console.enabled` | `H2_CONSOLE_ENABLED` | `false` |

> **Security note:** this repository's git history (both the old `secret.properties`
> committed file and, briefly, this repo's own earlier state) contains real
> credentials: a Postgres password for a Railway-hosted database, a Brevo/Sendinblue
> mail API key, a Cloudinary API key + secret, and a Paystack test secret key.
> **Rotate/revoke all of these** in their respective dashboards — removing them from
> the tracked file does not undo the exposure already baked into git history.
> Never commit real credentials anywhere in this repo again; supply them only as
> environment variables at deploy time.

## Running locally

A `docker-compose.yml` is provided for the database, so you don't need a local
PostgreSQL install:

```sh
docker compose up -d   # starts Postgres on localhost:5432, db=service user=postgres password=password

export MAIL_API_KEY=...
export CLOUDINARY_API_NAME=...
export CLOUDINARY_API_KEY=...
export CLOUDINARY_API_SECRET=...
export PAYSTACK_SECRET_KEY=...

./mvnw spring-boot:run
```

`DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD` already default to match
the `docker-compose.yml` credentials, so you don't need to export them unless
you're pointing at a different database.

The app starts on the default Spring Boot port (`8080`) unless overridden.

## Running with Docker

```sh
docker build -t guildworkman-api .
docker run -p 8080:8080 \
  -e DATABASE_URL=jdbc:postgresql://<host>:5432/service \
  -e DATABASE_USERNAME=postgres \
  -e DATABASE_PASSWORD=yourpassword \
  -e MAIL_API_KEY=... \
  -e CLOUDINARY_API_NAME=... -e CLOUDINARY_API_KEY=... -e CLOUDINARY_API_SECRET=... \
  -e PAYSTACK_SECRET_KEY=... \
  guildworkman-api
```

The Dockerfile is a two-stage build: Maven build stage (`maven:3.8.7`) →
runtime stage (`openjdk:17`) copying the packaged jar.

## API reference

All endpoints are prefixed `/api/v1/...`. Responses are wrapped in a common
`ApiResponse { data, success }` envelope unless noted.

### `ClientController` — `/api/v1/client`

| Method | Path | Purpose |
|---|---|---|
| POST | `/registerClient` | Register a new client |
| POST | `/login` | Client login |
| POST | `/bookAppointment` | Book an appointment with a skilled worker |
| PUT | `/updateAppointment?appointmentId=` | Update an existing appointment |
| PUT | `/cancelAppointment?appointmentId=` | Cancel an appointment |
| DELETE | `/deleteAppointment?appointmentId=` | Delete an appointment |
| GET | `/viewAllAppointment?clientId=` | List a client's appointments |
| PUT | `/updateClientProfile` | Update client profile |
| POST | `/consult?clientId=&workerId=&details=` | Book a consultation |
| POST | `/{consultationId}/availability?clientAvailability=&workerAvailability=` | Schedule consultation availability |

### `SkilledWorkerController` — `/api/v1/skilledWorker`

| Method | Path | Purpose |
|---|---|---|
| POST | `/registerSkilledWorker` | Register a new skilled worker |
| POST | `/login` | Skilled-worker login |
| POST | `/addSkill` | Add a skill to a worker's profile |
| GET | `/findById?skilledWorkerId=` | Fetch a worker by ID |
| GET | `/findByFullName?skilledWorkerFullName=` | Fetch a worker by name |
| PUT | `/updateSkilledWorkerProfile` | Update worker profile |
| GET | `/nearby?lat=&lon=&radius=` | Find workers near a location (`radius` defaults to 10) |

### `MailController` — `/api/v1/mail`

| Method | Path | Purpose |
|---|---|---|
| POST | `/sendMail` | Send a transactional email |

CORS is restricted to `https://guildworkman.vercel.app/` on `MailController` and
`SkilledWorkerController`; `ClientController` currently allows all origins (`*`).

## Testing

Tests are `@SpringBootTest`-based and need a real database — bring up the
Postgres container first:

```sh
docker compose up -d
./mvnw test
```

CI runs the same suite against a Postgres service container on every push/PR
to `development` (`.github/workflows/test.yml`).

Existing tests cover `MailServiceTest`, `ClientServiceTest`,
`SkilledWorkerServiceTest`, `AppointmentServiceTest`, `ReviewServiceTest`, and
`SkillServiceTest`.

All 14 tests pass as of this writing. Test-isolation issues (missing
`@Transactional` rollback, hardcoded IDs colliding with seed data) and a
disconnected mock in `PaymentServiceImplTest` (the code under test created
its own `OkHttpClient`/`RestTemplate` internally, so the test's mocks were
never actually wired in) have been fixed -- see git history for details.

## Branching

Default branch is **`development`**. Other active branches: `appointment`,
`fitzgerald`, `login`, `main`, `security`, `transaction` — these generally
track feature/area work and get merged back into `development` via PR.

## Contributing

Branch off `development`, keep real credentials out of your commits (only
`.env.example` placeholders, never a populated `.env`), and open a PR back
into `development`.
