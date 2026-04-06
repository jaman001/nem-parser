# Meter Reading Processor

Containerized Spring Boot 3 (Java 21) service to parse NEM12 meter data from CSV or ZIP, then either:
- generate SQL insert statements, or
- insert data directly into PostgreSQL.

## Why this stack
- **Spring Boot Web + JDBC**: simple, explicit, and good for streaming + batch inserts.
- **No heavy ORM**: reduces overhead for large file ingest.
- **OpenAPI (springdoc)**: easy API discovery/testing.
- **Docker**: Containerized app, run anywhere

## Run with Docker Compose (app + Postgres + Adminer)
```bash
docker compose -f docker-compose.yaml -f docker-compose.local.yml up --build -d
```

## Service URLs
- App base URL: `http://localhost:8081`
- Swagger UI: `http://localhost:8081/swagger-ui.html`
- Adminer UI: `http://localhost:8090/?pgsql=nem12-postgres&username=postgres&db=nem12db&ns=public`

## API
- `POST /api/v1/meter-readings/sql` (multipart `file`): returns SQL statements.
- `POST /api/v1/meter-readings/ingest` (multipart `file`): inserts rows into DB and returns counters.

## Build and test (optional, local Maven)
```bash
mvn clean test
mvn clean package
```

## Notes on design choices
- **Transactional + fail-fast ingest**: parsing is fail-fast for critical NEM12 issues (invalid `100/200/300` structure, invalid date, invalid interval count, non-numeric interval value). Ingest runs transactionally, so if an error occurs, processing stops immediately and previously inserted rows are rolled back.
- **Streaming parser for large files**: input is processed line-by-line with `BufferedReader` to avoid loading entire files into memory.
- **ZIP and CSV support**: for ZIP input, the first non-directory entry is processed.
- **Packaged to run anywhere with one command**: app + PostgreSQL + Adminer are containerized and orchestrated with Docker Compose (for local) for easy 
  verification.
  Swagger UI is added to test APIs from the browser, and Adminer UI is used to inspect/query PostgreSQL from the browser. For normal validation, no separate DB client or Postman is required.
- **Virtual threads per NMI block (Java 21)**: the NEM12 file is parsed sequentially line-by-line. All 300 records under a single 200 record are buffered into one `NmiBlock`. Each `NmiBlock` is immediately dispatched to its own Java 21 virtual thread. All NMIs are processed fully concurrently. Virtual threads are lightweight (managed by the JVM, not the OS), require no thread pool size tuning, and are ideal for I/O-bound work like DB inserts.
- **`/ingest` uses virtual threads per NmiBlock with NMI-level transactions**: each `NmiBlock` is inserted in its own `@Transactional` call via `NmiIngestService`. All readings for one NMI (e.g. NEM1201010 with 30 300-records × 48 intervals = 1,440 rows) are committed together in one transaction. If any insert fails, all 1,440 rows for that NMI roll back — other NMIs are unaffected. NMI id is logged on transaction open and commit.
  > **Performance benchmark**: switching from sequential single-threaded processing to virtual threads per NMI block reduced processing time from **13 min 
  > 42 sec → 2 min 52 sec** — a **~6× speedup** on the same hardware and dataset. Tested against a large NEM12 CSV file containing **1,863,680 lines** and 
  > **17,520,000 unique consumption readings**.

## Production readiness

### Secrets are never hardcoded
`application.yml` does not contain any secrets. All sensitive values are externalised as environment variables:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
```

### Local / Docker Compose
For local development, secrets are supplied via `docker-compose.yaml` environment block:

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/nem12db
  SPRING_DATASOURCE_USERNAME: postgres
  SPRING_DATASOURCE_PASSWORD: postgres
```

This is acceptable for local development only. These values should never be committed to version control for production use.

### Separating local vs production compose config
The compose setup is split into two files:

- `docker-compose.yaml` — base config, app service only. Used as the foundation in all environments. Reads DB credentials from environment variables (no hardcoded values).
- `docker-compose.local.yml` — local override. Adds a PostgreSQL container and Adminer UI, and supplies dev credentials directly. This file is **never used in production**.

Run locally with both files merged:
```bash
docker compose -f docker-compose.yaml -f docker-compose.local.yml up --build -d
```

In production (e.g. ECS/Fargate), only `docker-compose.yaml` (or the equivalent task definition) is used, with secrets injected from AWS Secrets Manager at runtime.

### AWS deployment
When deploying to AWS, secrets can be stored in **AWS Secrets Manager** and injected into the container at runtime via the ECS task definition `secrets` block.
The app code and image do not change — only the deployment config differs:

## Assignment write-up

### Q1. What is the rationale for the technologies you have decided to use?

- **Java 21**: LTS release with virtual threads, records, and modern language features. Records are used for `MeterReading` and `ParseResult` to keep domain 
  models concise and immutable. Virtual threads are used to process each NMI block concurrently with minimal overhead.
- **Spring Boot 3.x**: Industry-standard framework for building production-grade REST services quickly. Provides auto-configuration for web, JDBC, transactions, validation, and multipart file handling with minimal boilerplate.
- **Spring JDBC (`JdbcTemplate`)**: Chosen deliberately over JPA/Hibernate. For bulk ingest of potentially millions of rows, JDBC batch inserts are significantly faster and more memory-efficient than an ORM. There is no complex domain graph here — just row inserts — so an ORM adds overhead without benefit.
- **springdoc OpenAPI**: Generates Swagger UI automatically from the controller annotations. Allows testing the APIs directly in the browser without any external tool.
- **PostgreSQL**: Matches the target schema specified in the assignment.
- **Docker + Docker Compose**: Ensures the full stack (app + database + Adminer) runs identically on any machine with a single command. No Java or Maven 
  installation required.

### Q2. What would you have done differently if you had more time?

- **Async ingest endpoint**: For very large files, an async endpoint that returns a job ID and allows polling for status would be more appropriate than a synchronous HTTP response that could time out.
- **Structured error response with line numbers**: Instead of just throwing on the first bad line, collect and return the exact line number and content of the failure so callers can diagnose issues faster.
- **CI/CD pipeline**: Add a GitHub Actions workflow to run tests, build the Docker image, and push to ECR on merge to main.
- **Observability**: Add Spring Actuator with health/readiness endpoints and expose metrics (processed rows, error counts) for monitoring in production.
- **Play around with connection pool/batch size tuning**: For optimal performance, especially with virtual threads, it would be worth experimenting with 
  different settings to achieve the best performance on large files.
- **`/sql` ** : Improve the SQL generation endpoint to return a downloadable `.sql` file instead of returning the insert statements in the response body, which could be unwieldy for large files.
- Improve CSV parsing including processing 400/500 records and edge-case coverage

### Q3. What is the rationale for the design choices that you have made?

- **Fail-fast parsing**: Any malformed critical NEM12 record (bad structure, invalid date, non-numeric value) immediately throws an exception and stops processing. This is intentional — partial ingestion of corrupt data is worse than no ingestion. Combined with transactional ingest, this guarantees the database is never left in a half-written state.
- **Streaming line-by-line parsing**: Input is read with `BufferedReader` one line at a time, never fully loaded into memory. This satisfies the requirement to handle very large files safely.
- **`/ingest` uses NMI-level transactions via `NmiIngestService`**: all readings for one NMI are inserted together in one `@Transactional` call. If any insert fails, all readings for that NMI roll back — other NMIs are unaffected.
- **Upsert over plain insert**: `ON CONFLICT (nmi, timestamp) DO UPDATE` makes re-processing the same file idempotent. Files can be safely resubmitted 
  without creating duplicates. This can be switched to skip duplicates instead (`DO NOTHING`) if the requirement is to reject duplicates rather than update them.
- **Secrets externalised as environment variables**: `application.yml` contains no hardcoded credentials. Locally, values are supplied via `docker-compose.local.yml`. In production (AWS), they are injected from Secrets Manager at runtime — the app image never changes between environments.
- **Split Docker Compose files**: `docker-compose.yaml` is the environment-agnostic base (app only). `docker-compose.local.yml` is the local override that 
  adds PostgreSQL and Adminer. This keeps the production artifact clean and avoids accidentally running dev-only services in production.
