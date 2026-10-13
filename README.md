# RelayForge

RelayForge delivers events from one application to other applications through HTTP webhooks. It stores events and deliveries in PostgreSQL, routes events by subscription, signs requests, retries temporary failures, and records delivery attempts.

Example: a shop publishes `order.paid`. A warehouse and a notification service subscribe to that type. RelayForge sends a separate webhook to each service. The warehouse processes the order; the notification service sends a message to the customer.

## Stack

- Java 21, Spring Boot 4.1.1, Spring MVC
- Spring Data JPA / Hibernate, PostgreSQL 17, Flyway
- Java HTTP Client, HMAC-SHA256 through `javax.crypto.Mac`
- Docker Compose, Nginx gateway, Squid egress proxy, iptables
- JUnit, Mockito, PostgreSQL Testcontainers, GitHub Actions
- Demo frontend: plain HTML, CSS, JavaScript with a separate Spring Boot service

## Test through the frontend

Requires Docker with Compose. Run from the repository root:

```sh
docker compose -f compose.demo.yaml up -d --build
```

Open [http://localhost:8090](http://localhost:8090).

- **Example** creates Shop, Warehouse, and Notifications.
- **Add client** adds a client card, up to 10 clients.
- **Role**: `sender` publishes events, `receiver` receives webhooks, `both` does both.
- **Subscriptions**: event types to receive, separated by commas, such as `order.paid, stock.updated`.
- **Response**: simulate `200`, `500`, or a timeout when receiving a webhook.
- **Send event**: choose a type, enter JSON, optionally set an idempotency key, and send from that client.
- Each card shows its own logs. The right panel shows delivery status, HTTP results, attempts, and retry countdowns. **Details** opens attempt history; **Retry** is available for failed deliveries.
- The page and log panels scroll independently. The right panel stays visible while scrolling on desktop.

Sending `order.paid` from Shop delivers it to every receiver subscribed to `order.paid`. A client with role `both` can receive its own event if its subscription matches.

This is a local test environment with its own database volume, a fixed test API key, and local webhook addresses enabled. It does not send real SMS messages or manage real stock. Client configuration and received-message logs are held in memory and reset when the demo service restarts; backend event and delivery history persists in the demo database.

Stop the demo without deleting its data:

```sh
docker compose -f compose.demo.yaml down
```

## Run RelayForge

Copy `.env.example` to `.env` and set:

```dotenv
RELAYFORGE_API_KEY=replace-with-a-long-random-secret
RELAYFORGE_WEBHOOK_HOSTS=warehouse.example.com,notifications.example.com
POSTGRES_DB=relayforge
POSTGRES_USER=relayforge
POSTGRES_PASSWORD=replace-for-a-new-database
```

The host list accepts exact hostnames, without schemes, paths, or wildcards. An empty list rejects all webhook destinations. Keep `.env` out of version control. For an existing database volume, changing `POSTGRES_PASSWORD` does not change the stored database user's password.

```sh
docker compose up -d --build
curl http://localhost:8080/health
```

The API is exposed on `127.0.0.1:8080`; PostgreSQL is exposed on `127.0.0.1:5432`. The API key is required for all API routes. `/health` is open.

```sh
docker compose down
```

Do not use `down -v` if you need to preserve the database.

## Integrate with your application

Your application publishes events to RelayForge; receivers expose HTTP POST endpoints. The examples use a POSIX shell with `API_KEY` set to your RelayForge key.

### 1. Register a receiver

Add its exact hostname to `RELAYFORGE_WEBHOOK_HOSTS`. It must resolve only to public addresses.

```sh
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -d '{"url":"https://warehouse.example.com/webhooks","subscribedEvents":["order.paid"]}'
```

### 2. Get its signing secret

Replace `1` with the returned endpoint ID. Store the returned `secret` in the receiver; rotation replaces the previous secret.

```sh
curl -X POST http://localhost:8080/api/v1/endpoints/1/rotate-secret \
  -H "X-API-Key: $API_KEY"
```

### 3. Publish an event

```sh
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-123-paid' \
  -d '{"type":"order.paid","payload":"{\"orderId\":123,\"amount\":1500}"}'
```

`payload` is a string containing JSON. Enabled receivers with a matching subscription receive it asynchronously. The optional idempotency key prevents duplicate event creation for the same type and exact payload; different content with the same key returns `409`.

### 4. Handle the webhook

Receivers get JSON with `id`, `type`, `createdAt`, and parsed `data`, plus `X-Webhook-Id`, `X-Webhook-Timestamp` (Unix seconds), and `X-Webhook-Signature` headers.

Before processing:

1. Verify `X-Webhook-Signature` equals `sha256=` followed by the lowercase hex HMAC-SHA256 of `timestamp + "." + raw_body`. Use the secret's UTF-8 bytes as the key, without hex decoding, and compare in constant time. Do not reformat the body before verification.
2. Reject missing headers and stale timestamps, for example older than five minutes.
3. Deduplicate by event `id`, process or durably queue the operation, then return `2xx`. Duplicate events should return success without repeating the operation.

## Delivery behavior

- Statuses: `PENDING`, `PROCESSING`, `SUCCEEDED`, `FAILED`.
- Any `2xx` response succeeds. Redirects are not followed.
- Network failures, timeouts, HTTP `408`, `429`, and `5xx` schedule retries, up to three total attempts, with delays of 30 and 60 seconds.
- The worker checks the queue every five seconds by default. Request timeout is ten seconds.
- Stale processing deliveries are recovered after 60 seconds. A crash can cause duplicate delivery; HTTP delivery is not exactly once.
- Event ID, type, creation time, and data remain stable across retries. The signature timestamp is generated for each send.
- Manual retry creates a new delivery and preserves the original delivery's history.

## API reference

All routes below require `X-API-Key`.

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/v1/endpoints` | Register a URL and `subscribedEvents` |
| GET | `/api/v1/endpoints`, `/api/v1/endpoints/{id}` | List or inspect endpoints |
| PUT | `/api/v1/endpoints/{id}/subscriptions` | Replace subscriptions with `{"subscribedEvents":["order.paid"]}` |
| PATCH | `/api/v1/endpoints/{id}/enable`, `/api/v1/endpoints/{id}/disable` | Enable or disable an endpoint |
| POST | `/api/v1/endpoints/{id}/rotate-secret` | Replace and return the signing secret |
| POST | `/api/v1/events` | Publish `type` and JSON-string `payload` |
| GET | `/api/v1/events`, `/api/v1/events/{id}` | List or inspect events |
| GET | `/api/v1/deliveries` | List deliveries; optional `status` and `eventId` query parameters |
| GET | `/api/v1/deliveries/{id}` | Inspect a delivery |
| GET | `/api/v1/deliveries/{id}/attempts` | Inspect attempt history |
| POST | `/api/v1/deliveries` | Send an existing event to an endpoint using `{"eventId":123,"endpointId":1}` |
| POST | `/api/v1/deliveries/{id}/retry` | Manually retry a failed delivery |

Errors use `{"status":404,"message":"Event not found","path":"/api/v1/events/123"}`. Missing or incorrect API keys return `401`.

## Security and deployment

The main Compose configuration isolates the application from direct Internet access and sends webhooks through the egress proxy. The proxy blocks private, loopback, link-local, and special IPv4 destinations at connection time. Outbound IPv6 is disabled in this deployment.

The application also validates destination URLs and DNS answers at registration and before sending. HTTP uses port 80; HTTPS uses port 443. Credentials in URLs and fragments are rejected.

Running the JAR outside Compose does not provide the container network restrictions. A DNS precheck alone does not prevent DNS rebinding; retain an equivalent egress policy or the configured proxy when deploying elsewhere. Use trusted destination hosts and HTTPS. If exposing the API remotely, configure TLS and access restrictions at your gateway. The current API key is shared; there are no per-user accounts or tenant permissions.

## Development and database migrations

Requires JDK 21 and Docker for integration tests. Maven is provided by the wrapper.

```sh
bash ./mvnw -B verify
bash ./mvnw -B -f demo/pom.xml verify
```

On Windows, use `./mvnw.cmd` instead of `bash ./mvnw`.

To run the backend locally, use a PostgreSQL instance reachable on `localhost:5432` with database/user/password `relayforge`, configure `RELAYFORGE_API_KEY` and `RELAYFORGE_WEBHOOK_HOSTS`, then run `./mvnw.cmd spring-boot:run` or `bash ./mvnw spring-boot:run`. Override `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` when using different database settings. Set `SERVER_PORT=8081` if the main Compose deployment already occupies port 8080. Local webhook destinations remain blocked in the main configuration.

Flyway migrations are in `src/main/resources/db/migration`. Hibernate validates the schema rather than modifying it. Fresh databases migrate automatically. The `legacy` profile enables baseline version 0 for the tested legacy schema; use it only after backing up and verifying your existing schema on a copy. Do not enable it for arbitrary existing databases. Flyway clean is disabled.

Useful checks on Windows:

```powershell
./scripts/check-compose.ps1 -Restart
./scripts/check-egress.ps1
```

`check-compose` verifies health, API access, and endpoint preservation across restart. `check-egress` verifies blocked connections using a temporary local receiver. GitHub Actions runs backend and demo verification on pushes and pull requests.

## Repository layout

| Path | Contents |
|---|---|
| `src/main/java` | Backend API, event routing, delivery worker, signing, URL policy |
| `src/main/resources/db/migration` | Database migrations |
| `src/test` | Backend unit and PostgreSQL integration tests |
| `demo/src/main/java` | Local test clients and frontend API |
| `demo/src/main/resources/static` | Client-card frontend |
| `compose.yaml`, `docker/` | Main deployment, gateway, egress proxy |
| `compose.demo.yaml` | Separate local test environment |
| `scripts/` | Compose and egress checks |
| `.github/workflows/build.yml` | Build and test workflow |
