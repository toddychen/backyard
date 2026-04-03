# Backyard Repo

## Build Commands

Use `./mvnw` (Maven wrapper) instead of `mvn` for all Maven commands.
The wrapper is at the repo root.

Examples:
- `./mvnw clean package`
- `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- `./mvnw test`

## Services

### playground (`services/playground/`)

Spring Boot 4.0.4, Java 21. Stateless external-API aggregator + Dory
reminders CRUD.

**Conventions:**
- `@RequestMapping("/{version}/resource")` — `/api` prefix added
  automatically by `WebMvcConfig` via `addPathPrefix`
- `@GetMapping(version = "1+")` on methods — Spring Framework 7 API
  versioning
- Constructor injection, `private final` fields
- Custom exceptions in `exception/` extend `BaseException` — bubble up
  to `GlobalExceptionHandler`
- DTOs are Java records in `data/{domain}/` packages


**Java Code Style** (matches `.vscode/eclipse-java-formatter.xml`):
- Max line length: 120 characters
- Indent: 4 spaces. Continuation indent: 8 spaces (2 levels)
- Do not break a statement onto multiple lines unless it exceeds
  120 chars — the Eclipse formatter does not re-join short lines,
  so unnecessary breaks are permanent noise
- When a line must wrap, break at a natural boundary (before a
  method argument, before a chained call, before a binary
  operator) and indent the continuation by 8 spaces
- Comments and Javadoc lines: also keep under 80 chars (Eclipse
  wraps comments separately at `comment.line_length`)

**Spring Profiles:**

| Profile | Purpose | Start command |
|---------|---------|---------------|
| `dev` | Active development, DEBUG logs, tracing on | `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` |
| `home` | Long-running local jar, INFO logs, no Redis, no tracing | `java -jar playground.jar --spring.profiles.active=home` |
| none | Kind/stage/prod — config via Helm env vars | Helm deployment |
