# Dual-Teacher Credential Service

A service that decides whether a teacher genuinely holds the credentials to teach a
**high-risk practical course** on a given date. It aggregates facts that today live in
different departments — enterprise-practice periods, equipment/skill certifications,
industry-project experience, and a versioned course competency matrix — and returns a
**per-item, evidence-backed verdict** rather than a naive "does a certificate exist" check.

## Why it works the way it does

- **Event-sourced.** Every upstream fact arrives as an event with a globally unique
  `event_id`. Ingest is **idempotent**: retrying the same `event_id` is a no-op.
- **Business-time projection.** Qualification is projected in the order facts *happened*
  (`occurred_at`), never in the order the server received them. A revocation that arrives
  before its issuance still lands correctly.
- **`as_of` is the yardstick.** A query names the date to judge against:
  - overlapping practice intervals are **merged** so shared days count once;
  - a certification must be **within its issuance window on `as_of`** and **not covered by a
    revocation whose effective date has already arrived** by `as_of`;
  - an industry project counts only once it has been **enterprise-accepted** on/before `as_of`;
  - the competency-matrix version in force is the latest one **effective on/before `as_of`**.
- **Immutable decisions.** Each query is saved as an immutable snapshot under a deterministic
  `decision_id` (hash of teacher + course + `as_of`). Concurrent creators race to a single
  row via a unique constraint, and **recording material later never rewrites a past decision**.

## Tech stack

Kotlin 2.0.21 · JDK 21 · Ktor 3 (Netty) · Exposed 0.56 · PostgreSQL 16+ · Flyway · HikariCP ·
kotlinx.serialization · Logback + logstash JSON encoder · JUnit 5. Built with the Gradle Wrapper.

## Prerequisites

- JDK 21 (the wrapper is pinned to it via `org.gradle.java.home` in `gradle.properties`;
  adjust that path if your JDK 21 lives elsewhere).
- A running PostgreSQL 16+ server.

## Local database setup

Create a database for local runs (migrations are applied automatically on startup by Flyway):

```bash
createdb dual_teacher
# or:  psql -d postgres -c "CREATE DATABASE dual_teacher"
```

`DATABASE_URL` accepts a libpq URL (`postgresql://user:pass@host:port/db`) or a JDBC URL
(`jdbc:postgresql://host:port/db`). For a libpq URL, credentials may be embedded
(`postgresql://user:pass@localhost:5432/dual_teacher`) or supplied via the `DATABASE_USER` /
`DATABASE_PASSWORD` environment variables.

## Run

```bash
DATABASE_URL=postgresql://localhost:5432/dual_teacher ./gradlew run
```

The server listens on `:8080` (override with `PORT`). Flyway runs `V1__init.sql` on startup.

## Test & build

```bash
./gradlew test     # unit + integration tests
./gradlew build    # compile, test, assemble
```

Integration tests provision a throwaway PostgreSQL database per run (named `dtc_test_<nanos>`),
migrate it, and drop it afterwards. They connect using `TEST_DATABASE_URL`, falling back to
`DATABASE_URL`, then to `postgresql://localhost:5432/postgres`. The account must be allowed to
`CREATE DATABASE`.

## HTTP API

| Method | Path | Purpose |
| ------ | ---- | ------- |
| `GET`  | `/health` | Liveness/readiness (checks a DB round-trip) |
| `POST` | `/events` | Ingest a source event (idempotent on `event_id`) |
| `GET`  | `/teachers/{teacherId}/qualification?course_id=&as_of=` | Evaluate & snapshot |
| `GET`  | `/decisions/{decisionId}` | Fetch a stored immutable decision |
| `GET`  | `/openapi.yaml` | OpenAPI 3 specification |

### Event types (`payload.type`)

- `TeacherRegistered` — `teacher_id`, `name`
- `PracticeRecorded` — `teacher_id`, `enterprise`, `start_date`, `end_date` (inclusive)
- `CertificationIssued` — `teacher_id`, `cert_id`, `cert_type`, `issued_on`, `expires_on`
- `CertificationRevoked` — `teacher_id`, `cert_id`, `effective_on`
- `IndustryProjectRoleRecorded` — `teacher_id`, `project_id`, `role`, `domain`, `accepted`, `accepted_on`
- `CompetencyMatrixPublished` — `course_id`, `version`, `effective_from`, `min_practice_days`,
  `required_cert_type?`, `required_project_domain?`

### Worked example — teacher T1

```bash
BASE=http://localhost:8080

# Competency matrix: 60 practice days + a valid WELDING certification.
curl -XPOST $BASE/events -H 'Content-Type: application/json' -d '{"event_id":"t1-matrix","occurred_at":"2025-12-01T00:00:00Z","payload":{"type":"CompetencyMatrixPublished","course_id":"C-RISK","version":1,"effective_from":"2026-01-01","min_practice_days":60,"required_cert_type":"WELDING"}}'

# Revocation ARRIVES FIRST (effective 2026-05-01) — projected by business time.
curl -XPOST $BASE/events -H 'Content-Type: application/json' -d '{"event_id":"t1-revoke","occurred_at":"2026-03-15T00:00:00Z","payload":{"type":"CertificationRevoked","teacher_id":"T1","cert_id":"C1","effective_on":"2026-05-01"}}'

# Certification C1, valid through 2026-06-30.
curl -XPOST $BASE/events -H 'Content-Type: application/json' -d '{"event_id":"t1-issue","occurred_at":"2026-01-01T00:00:00Z","payload":{"type":"CertificationIssued","teacher_id":"T1","cert_id":"C1","cert_type":"WELDING","issued_on":"2026-01-01","expires_on":"2026-06-30"}}'

# Two overlapping practice windows (merged span = 91 distinct days).
curl -XPOST $BASE/events -H 'Content-Type: application/json' -d '{"event_id":"t1-p1","occurred_at":"2025-03-05T00:00:00Z","payload":{"type":"PracticeRecorded","teacher_id":"T1","enterprise":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}}'
curl -XPOST $BASE/events -H 'Content-Type: application/json' -d '{"event_id":"t1-p2","occurred_at":"2025-04-05T00:00:00Z","payload":{"type":"PracticeRecorded","teacher_id":"T1","enterprise":"ACME","start_date":"2025-02-15","end_date":"2025-04-01"}}'

curl "$BASE/teachers/T1/qualification?course_id=C-RISK&as_of=2026-04-30"   # qualified=true
curl "$BASE/teachers/T1/qualification?course_id=C-RISK&as_of=2026-05-01"   # false: revocation effective
curl "$BASE/teachers/T1/qualification?course_id=C-RISK&as_of=2026-07-01"   # false: expired (and revoked)
```

Each response lists every requirement's `status` (`PASS`/`FAIL`), the `evidence_event_ids`
it relied on, and — for failures — what is still `missing`, plus a `decision_id` you can
re-fetch from `/decisions/{decisionId}`.

## Project layout

```
src/main/kotlin/com/dualteacher/
  Application.kt                 Ktor wiring: plugins, logging, routes, OpenAPI
  app/Json.kt                    Shared JSON config (type discriminator)
  api/                           HTTP DTOs and routes
  db/                            Hikari + Flyway + Exposed tables, jsonb column type
  domain/                        Events, projection, interval merge, evaluator, decision model
  repo/                          EventStore (idempotent) and DecisionRepository (immutable)
  service/QualificationService   Orchestration: replay -> project -> evaluate -> snapshot
src/main/resources/
  db/migration/V1__init.sql      Schema
  openapi.yaml                   API spec
  logback.xml                    Structured JSON logging
src/test/kotlin/com/dualteacher/
  unit/                          Interval merge, projection, evaluator (pure logic)
  integration/                   T1 timeline, idempotency, concurrency, immutability, HTTP API
```
