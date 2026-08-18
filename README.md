# Spring Boot + OpenTelemetry + LGTM Stack

A demo project showing how **Spring Boot 4** is instrumented with **OpenTelemetry** using [**zero-code instrumentation**](https://opentelemetry.io/docs/zero-code/java/agent/) — a Java agent attached to the JVM — with the **LGTM stack** (Loki · Grafana · Tempo · Mimir/Prometheus) as the observability backend, all running locally via Docker Compose.

The demo consists of two services:

- **order-service** — a web shop (The Arcane Emporium) where customers place orders. Exposes a Thymeleaf/htmx UI on port `8080`.
- **mystery-box-service** — called by the order service when a Mystery Box is purchased; uses Spring AI (OpenAI) to generate a random box of contents. Runs on port `8081`.

Both services emit **traces**, **metrics**, and **logs** via OTLP to the collector bundled inside the `grafana/otel-lgtm` container. A custom Grafana dashboard is provisioned automatically on startup.

Neither service has a single `io.opentelemetry` dependency — no SDK, no exporter, not even the instrumentation annotations — and no OpenTelemetry configuration in code.

Everything the **agent** reads lives in [`otel-agent.properties`](./otel-agent.properties): exporters, sampling, which instrumentations are on, and even the business spans. It is mounted read-only at `/otel` by `docker-compose.yaml` and the image's `ENTRYPOINT` points the agent at it with `-Dotel.javaagent.configuration-file`, so editing it takes effect on a restart — neither the jar nor the image is rebuilt.

The Spring-side settings that shape what the agent has to work with — actuator exposure, the Micrometer `@Timed` aspects, the log correlation pattern — sit in each service's `application.yaml`, in the jar.

This project previously used `spring-boot-starter-opentelemetry` instead. Two documents cover that:

- [`docs/starter-vs-agent.md`](./docs/starter-vs-agent.md) — the migration, step by step, with the trade-offs of each approach.
- [`docs/zero-code-instrumentation.md`](./docs/zero-code-instrumentation.md) — the reference for how the current setup works.

Both are written in Dutch.

---

## Prerequisites

- Docker + Docker Compose
- An OpenAI API key (for mystery-box-service)

No local JDK or Maven: the Dockerfiles run the build inside the container.

---

## The OpenTelemetry Java agent

There is no separate download step. The [`Dockerfile`](./order-service/Dockerfile) pulls the agent straight from Maven Central with `ADD` (version pinned by `ARG OTEL_AGENT_VERSION`) and attaches it in the `ENTRYPOINT`, together with the JVM arguments that are fixed properties of the image.

The agent is a Docker instruction, never a Maven dependency, so it stays off the compile and runtime classpath: `mvn dependency:list` reports no `io.opentelemetry` artifact. The poms build the application jar and nothing else — no agent, no JVM arguments, no observability configuration.

The split that matters: the **agent** is part of the runtime and therefore lives in the image, while its **configuration** does not and gets mounted at startup.

---

## Running the demo

Each service has a multi-stage [`Dockerfile`](./order-service/Dockerfile) that runs its own Maven build, so this works from a clean checkout:

```bash
./build-images.sh
```

Then start everything:

```bash
export OPENAI_API_KEY=<your-key>
docker compose up -d
```

All five containers start together:

| Container | What it provides | Port |
|-----------|-----------------|------|
| `order-service` | The shop (Thymeleaf/htmx UI) | `8080` |
| `mystery-box-service` | Mystery box generation via Spring AI | `8081` |
| `lgtm` | Grafana UI, Loki, Tempo, Prometheus, OTLP collector | `3000` (UI), `4317` (gRPC), `4318` (HTTP) |
| `postgres` | PostgreSQL for both services | `5432` |
| `mcp-grafana` | Grafana MCP server (for AI-assisted observability) | `8888` |

Open the shop at [http://localhost:8080](http://localhost:8080) and place some orders. The Grafana dashboard is at [http://localhost:3000](http://localhost:3000) (credentials: `admin` / `admin`).

### Changing what is collected

`otel-agent.properties` is mounted into both containers, so changes take effect on a restart rather than a rebuild:

```bash
docker compose restart order-service mystery-box-service
export OPENAI_API_KEY=<your-key>
cd mystery-box-service
./mvnw spring-boot:run
```

### 3. Start order-service

```bash
cd order-service
./mvnw spring-boot:run
```

### 4. Open the shop

Navigate to [http://localhost:8080](http://localhost:8080) and place some orders. The Grafana dashboard is available at [http://localhost:3000](http://localhost:3000) (credentials: `admin` / `admin`).

---

## What to observe

### Traces
Open **Explore → Tempo** in Grafana. Each order placement produces a distributed trace spanning both services. Everything below comes from the agent without a line of configuration:

- `POST /api/{version}/orders` — the HTTP server span, with `http.route`
- `OrderRepository.save` — Spring Data
- `HikariDataSource.getConnection`, `INSERT "orders"`, `COMMIT` — JDBC, with the full statement in `db.query.text`
- `OrderService.createOrder` → `OrderService.processItems` → `OrderService.processItem` — the business spans, declared in [`otel-agent.properties`](./otel-agent.properties) under `otel.instrumentation.methods.include`

That last group is the interesting one: `processItems` and `processItem` are **private** and called from within the same bean, which Spring AOP could never intercept. The agent weaves the bytecode, so they are instrumented anyway — and the application code that produces them contains no OpenTelemetry import, annotation or dependency whatsoever.

The trade-off is that method-level instrumentation cannot capture arguments as span attributes. Business detail such as the SKU lives in the log lines instead; use Grafana's span-to-logs link to jump straight to them.

### Metrics
The custom home dashboard shows business metrics registered through Micrometer's `MeterRegistry` and bridged to OTLP by the agent:

- `orders_created_total` — order volume by status
- `orders_value` — distribution of order values
- `orders_items_ordered_total` — units sold per SKU
- `mystery_box_generated_count_total` — mystery box generation success/error rate
- `mystery_box_items_count` — distribution of items per mystery box

Alongside those, the agent contributes HTTP server metrics (`http_server_request_duration_seconds`, `http_server_active_requests`), JVM runtime metrics and HikariCP pool metrics on its own.

### GenAI prompts
Spring AI 2.x calls OpenAI through the official `com.openai:openai-java` client, which the agent instruments — so every `ChatClient` call gets a `chat gpt-4o-mini` span with `gen_ai.request.model`, `gen_ai.response.*` and token counts, with no code and no configuration.

The prompts and completions themselves are switched on by `otel.instrumentation.genai.capture-message-content` in [`otel-agent.properties`](./otel-agent.properties). They are **not** span attributes: they arrive as log records carrying the `chat` span's id, so select that span in Tempo and follow the logs link, or query Loki:

```logql
{service_name="mystery-box-service"} | json | event_name=`gen_ai.user.message`
```

You get the prompt exactly as Spring AI sent it, JSON schema instructions and all.

### Logs
Open **Explore → Loki** and filter by `service_name`. The agent's Logback instrumentation ships every log record over OTLP and stamps `trace_id` / `span_id` into the MDC, so logs are correlated with their traces both in Loki and on the console.

---

## Load generation

[`load-test.js`](./load-test.js) is a [k6](https://github.com/grafana/k6) script that drives realistic traffic through the order-service:

- Places random orders with 1–3 regular items (Rubber Duck, Forever Pen, Alarm Clock, Umbrella)
- Occasionally lists all orders to mix read and write traffic
- Ramps from 5 → 20 virtual users, holds a spike, then ramps back down

Mystery Box orders are **opt-in** (they consume OpenAI tokens). Use `MYSTERY_BOX_REQUESTS` to control how many are sent. Each request fires two calls: a valid order (`quantity: 1`) and an invalid one (`quantity: 2`) to exercise the error path.

**Run with Docker (no install needed)**

```bash
docker run --rm -i --network host grafana/k6 run - < load-test.js
```

**Run with a local k6 install**

```bash
k6 run load-test.js
```

**Point at a non-default host**

```bash
k6 run -e BASE_URL=http://my-host:8080 load-test.js
```

**Run for a fixed duration (flat load, 5 VUs) instead of the full staged scenario**

```bash
k6 run -e DURATION=1m load-test.js
```

Accepts any k6 duration string: `30s`, `2m`, `1h`, etc.

**Include Mystery Box requests**

```bash
k6 run -e MYSTERY_BOX_REQUESTS=5 load-test.js
```

---

## Leveraging the Grafana MCP server

The `mcp-grafana` container exposes a [Model Context Protocol](https://modelcontextprotocol.io) server that gives an AI assistant direct access to your Grafana instance. This enables a natural-language workflow for observability tasks — exploring data, building dashboards, and investigating incidents — without leaving the chat.

### Connect Claude Code to the MCP server

Add the following to your Claude Code MCP configuration (`.claude/settings.json` or via `claude mcp add`):

```json
{
  "mcpServers": {
    "grafana": {
      "type": "http",
      "url": "http://localhost:8888/mcp"
    }
  }
}
```

### Example prompts

**Explore data**
```
Query Prometheus for the p99 latency of the order-service over the last hour.
```
```
Show me all error logs from mystery-box-service in the last 15 minutes.
```
```
Find the slowest traces in Tempo for the createOrder operation.
```

**Build dashboards**
```
Create a Grafana dashboard that shows orders_created_total split by status,
orders_value as a heatmap, and the top 5 SKUs by units sold.
```
```
Add a panel to the existing custom dashboard showing mystery box generation
errors over time, grouped by reason.
```

**Incident investigation**
```
I'm seeing elevated error rates in order-service. Find the relevant traces,
check the logs around the same time window, and summarise what's going wrong.
```

The MCP server has read and write access to dashboards, datasources, Prometheus, Loki, and Tempo — so the assistant can go from a question to a fully provisioned dashboard in a single conversation.
