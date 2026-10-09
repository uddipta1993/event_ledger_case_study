# Event Ledger

Event Ledger is a small Java 8 take-home implementation with two independently runnable Spring Boot services. The public Gateway validates and records submitted events, then calls the internal Account Service synchronously. Each process owns its own in-memory state; restart clears that service's data.

## Architecture

- **Gateway** (`:8080`) exposes event submission, event lookup/listing, health, and request metrics.
- **Account Service** (`:8081`) applies each event once, computes balances from the complete transaction set, and exposes account queries.
- `X-Trace-Id` is generated at the Gateway when absent and sent to Account Service. Both services emit JSON log records containing timestamp, level, service, and trace ID.
- `eventTimestamp` controls chronological listing only. Account balances are derived from all applied entries, so arrival order does not affect the result.

The two maps are process-local in-memory databases, which keeps the example dependency-light. They are deliberately not shared. A production deployment would replace them with durable stores and transactional outbox/reconciliation handling.

## Run with Docker Compose

Prerequisites: Docker with Compose, Java 8 or newer, and Maven (for local builds).

```sh
docker compose up --build
```

The Gateway is available at `http://localhost:8080`. The Account Service stays on Compose's private network and is not published to the host.

## Run locally

From the repository root, start two terminals:

```sh
mvn -f account/pom.xml spring-boot:run
mvn -f gateway/pom.xml spring-boot:run
```

The Gateway defaults to `http://localhost:8081` for its Account Service URL. Override with `ACCOUNT_SERVICE_URL` if needed.

## API examples

```sh
curl -i -H 'Content-Type: application/json' -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z","metadata":{"source":"batch"}}' http://localhost:8080/events
curl 'http://localhost:8080/events?account=acct-123'
curl http://localhost:8080/events/evt-001
curl http://localhost:8081/accounts/acct-123/balance
```

Duplicate event IDs return the original event with `200` and never apply an additional balance change. A successful new submission returns `201`. Invalid input returns `400`; Account Service failures return `503`. The Gateway keeps event lookup/list operations available during Account Service outages. An event retained after an outage is retried on a later duplicate submission; Account Service's event ID deduplication makes that retry safe.

## Resiliency

The Gateway uses bounded connect/read timeouts, one retry with a short backoff, and a small circuit breaker that opens after three consecutive call failures and remains open for five seconds. This limits hanging requests and prevents repeated calls to a failing dependency. `POST /events` reports `503` when application cannot be confirmed; local event reads remain available. Balance and account-detail reads return `503` with a clear message when the dependency cannot be reached.

## Tests

Run both services' test suites with:

```sh
mvn -f account/pom.xml test
mvn -f gateway/pom.xml test
```

Tests cover validation, idempotency, order-independent balance calculation, trace propagation, dependency failure handling, and an end-to-end Gateway-to-Account flow.

## Metrics and health

Both services expose `GET /health`; the response reports service status and in-memory store availability. The Gateway exposes `GET /metrics` with request counts by method and path. JSON logs go to standard output.
